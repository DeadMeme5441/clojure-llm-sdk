(ns llm.sdk.providers.bedrock-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.stream :as stream]
            [llm.sdk.providers.bedrock :as bedrock]))

(deftest test-resolve-model-id-known-short
  (is (= "anthropic.claude-sonnet-4-5-20250101-v1:0"
         (bedrock/resolve-model-id "claude-sonnet-4-5")))
  (is (= "amazon.nova-pro-v1:0"
         (bedrock/resolve-model-id "nova-pro")))
  (is (= "cohere.command-r-plus-v1:0"
         (bedrock/resolve-model-id "command-r-plus"))))

(deftest test-resolve-model-id-passthrough
  (testing "ids that look like full Bedrock ARNs are forwarded verbatim"
    (is (= "anthropic.claude-3-5-sonnet-20241022-v2:0"
           (bedrock/resolve-model-id "anthropic.claude-3-5-sonnet-20241022-v2:0"))))
  (testing "ARNs are forwarded"
    (let [arn "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0"]
      (is (= arn (bedrock/resolve-model-id arn))))))

(deftest test-build-request-basic
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (.endsWith ^String (:url built) "/model/anthropic.claude-3-5-sonnet-20241022-v2:0/converse"))
    (is (= "Sys" (get-in built [:body :system 0 :text])))
    (is (= 1 (count (get-in built [:body :messages]))))
    (is (= "bedrock" (get built :llm.sdk.providers.bedrock/aws-service)))))

(deftest test-build-request-stream-url
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request t profile
                                       {:request/model "nova-pro"
                                        :request/stream? true
                                        :request/messages [{:message/role :user
                                                            :message/content "hi"}]})]
    (is (.endsWith ^String (:url built) "/model/amazon.nova-pro-v1:0/converse-stream"))
    (is (= "application/vnd.amazon.eventstream" (get-in built [:headers "Accept"])))))

(deftest test-tool-call-shape
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages [{:message/role :user :message/content "use it"}]
                :request/tools [{:type "function"
                                 :function {:name "fetch_weather"
                                            :description "Get weather"
                                            :parameters {:type "object"
                                                         :properties {:city {:type "string"}}}}}]
                :request/tool-choice :auto})]
    (let [tools (get-in built [:body :toolConfig :tools])]
      (is (= 1 (count tools)))
      (is (= "fetch_weather" (get-in tools [0 :toolSpec :name])))
      (is (= "object" (get-in tools [0 :toolSpec :inputSchema :json :type]))))
    (is (= {:auto {}} (get-in built [:body :toolConfig :toolChoice])))))

(deftest test-cache-point-default-on-when-cache-enabled
  (testing "Bedrock injects cachePoint sentinel after system and final user content"
    (let [t (bedrock/make-transport)
          profile (provider/get-provider :bedrock)
          req {:request/model "claude-3-5-sonnet"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "Hi"}]
               :request/cache {}}
          built (transport/build-request t profile req)
          sys (get-in built [:body :system])
          last-msg-content (get-in built [:body :messages 0 :content])]
      (is (= {:type "default"} (:cachePoint (last sys)))
          "trailing system cachePoint should be present")
      (is (= {:type "default"} (:cachePoint (last last-msg-content)))
          "trailing user-message cachePoint should be present"))))

(deftest test-cache-point-suppressed-when-strategy-none
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hi"}]
             :request/cache {:strategy :none}}
        built (transport/build-request t profile req)
        sys (get-in built [:body :system])
        last-msg-content (get-in built [:body :messages 0 :content])]
    (is (nil? (:cachePoint (last sys))))
    (is (nil? (:cachePoint (last last-msg-content))))))

(deftest test-cache-point-default-off-when-cache-omitted
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)
        sys (get-in built [:body :system])
        last-msg-content (get-in built [:body :messages 0 :content])]
    (is (nil? (:cachePoint (last sys))))
    (is (nil? (:cachePoint (last last-msg-content))))))

;; ---------------------------------------------------------------------------
;; Stream parsing — frame shape produced by aws-eventstream/frame->json
;; ---------------------------------------------------------------------------

(deftest test-parse-frame-content-delta
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frame {:event-type "contentBlockDelta"
               :data {:contentBlockIndex 0
                      :delta {:text "Hello"}}}
        ev (transport/parse-stream-event t profile frame)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-parse-frame-message-stop
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frame {:event-type "messageStop"
               :data {:stopReason "end_turn"}}
        ev (transport/parse-stream-event t profile frame)]
    (is (= :stream/end (:event/type ev)))
    (is (= :stop (:event/finish-reason ev)))))

(deftest test-parse-frame-metadata
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frame {:event-type "metadata"
               :data {:usage {:inputTokens 17 :outputTokens 9 :totalTokens 26}}}
        ev (transport/parse-stream-event t profile frame)]
    (is (= :stream/usage (:event/type ev)))
    (is (= 17 (get-in ev [:usage :usage/input-tokens])))
    (is (= 9 (get-in ev [:usage :usage/output-tokens])))))

(deftest test-parse-response-cache-tokens
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        raw {:modelId "anthropic.claude-3-5-sonnet-20241022-v2:0"
             :stopReason "end_turn"
             :output {:message {:content [{:text "hi back"}]}}
             :usage {:inputTokens 10
                     :outputTokens 5
                     :totalTokens 15
                     :cacheReadInputTokens 8
                     :cacheWriteInputTokens 2}}
        parsed (transport/parse-response t profile raw)]
    (is (= [{:part/type :text :text "hi back"}] (:response/parts parsed)))
    (is (= 8 (get-in parsed [:response/usage :usage/cached-input-tokens])))
    (is (= 2 (get-in parsed [:response/usage :usage/cache-write-tokens])))))
