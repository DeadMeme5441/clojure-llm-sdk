(ns llm.sdk.providers.bedrock-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.bedrock :as bedrock]))

(deftest test-resolve-model-id-known-short
  (is (= "anthropic.claude-sonnet-4-5-20250929-v1:0"
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

(deftest test-inference-config-stop-sequence-is-not-split
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "nova-pro"
                :request/messages [{:message/role :user
                                    :message/content "hi"}]
                :request/temperature 0.2
                :request/stop "END"})]
    (is (= 0.2 (get-in built [:body :inferenceConfig :temperature])))
    (is (= ["END"] (get-in built [:body :inferenceConfig :stopSequences])))))

(deftest test-build-request-document-attachment
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages
                [{:message/role :user
                  :message/content [{:part/type :text
                                     :text "Summarize."}
                                    {:part/type :file
                                     :file/name "Q1 Report!!.pdf"
                                     :file/data "JVBERi0x"
                                     :file/mime-type "application/pdf"
                                     :file/citations true}]}]})
        content (get-in built [:body :messages 0 :content])]
    (is (= {:text "Summarize."} (first content)))
    (is (= {:document {:format "pdf"
                       :name "Q1 Report pdf"
                       :source {:bytes "JVBERi0x"}
                       :citations {:enabled true}}}
           (second content)))))

(deftest test-build-request-document-s3-source
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages
                [{:message/role :user
                  :message/content [{:part/type :file
                                     :file/name "brief.pdf"
                                     :file/url "s3://bucket/brief.pdf"
                                     :file/mime-type "application/pdf"}]}]})]
    (is (= {:document {:format "pdf"
                       :name "brief pdf"
                       :source {:s3Location {:uri "s3://bucket/brief.pdf"}}}}
           (get-in built [:body :messages 0 :content 0])))))

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

(deftest test-assistant-tool-call-replay-keeps-text
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages [{:message/role :assistant
                                    :message/content "I'll check."
                                    :message/tool-calls [{:part/type :tool-call
                                                          :tool-call/id "tool_1"
                                                          :tool-call/name "fetch_weather"
                                                          :tool-call/arguments "{\"city\":\"NYC\"}"}]}]})
        content (get-in built [:body :messages 0 :content])]
    (is (= "I'll check." (get-in content [0 :text])))
    (is (= "tool_1" (get-in content [1 :toolUse :toolUseId])))
    (is (= {"city" "NYC"} (get-in content [1 :toolUse :input])))))

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
    (is (= 9 (get-in ev [:usage :usage/output-tokens])))
    (is (not (contains? (:usage ev) :usage/cached-input-tokens)))))

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
    (is (= 10 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 8 (get-in parsed [:response/usage :usage/cached-input-tokens])))
    (is (= 2 (get-in parsed [:response/usage :usage/cache-write-tokens])))))

(deftest test-build-current-converse-fields
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-sonnet-4-6"
                :request/messages [{:message/role :user
                                    :message/content "Answer as JSON"}]
                :request/tools [{:type :function
                                 :function {:name "lookup"
                                            :parameters {:type "object"}}}]
                :request/reasoning {:enabled true :effort :xhigh}
                :request/cache {:ttl "1h" :tools-cache? true}
                :request/response-format {:type :json_schema
                                          :name "answer"
                                          :json-schema {:type "object"}}
                :request/metadata {:trace-id 42}
                :request/provider-options
                {:bedrock
                 {:additional-model-response-field-paths ["/stop_sequence"]
                  :guardrail-config {:guardrailIdentifier "guardrail-id"
                                     :guardrailVersion "1"
                                     :trace "enabled"}
                  :service-tier {:type "priority"}}}})
        body (:body built)]
    (is (= {:type "adaptive"}
           (get-in body [:additionalModelRequestFields :thinking])))
    (is (= "max"
           (get-in body [:additionalModelRequestFields :output_config :effort])))
    (is (= "json_schema" (get-in body [:outputConfig :textFormat :type])))
    (is (= "{\"type\":\"object\"}"
           (get-in body [:outputConfig :textFormat :structure :jsonSchema :schema])))
    (is (= {"trace-id" "42"} (:requestMetadata body)))
    (is (= "guardrail-id"
           (get-in body [:guardrailConfig :guardrailIdentifier])))
    (is (= ["/stop_sequence"] (:additionalModelResponseFieldPaths body)))
    (is (= {:type "default" :ttl "1h"}
           (get-in body [:messages 0 :content 1 :cachePoint])))
    (is (= {:type "default" :ttl "1h"}
           (get-in body [:toolConfig :tools 1 :cachePoint])))))

(deftest test-build-converse-multimodal-and-replay-content
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-7-sonnet"
                :request/messages
                [{:message/role :user
                  :message/content
                  [{:part/type :image
                    :image/url "s3://bucket/photo.webp"
                    :image/mime-type "image/webp"}
                   {:part/type :image
                    :image/url "data:image/png;base64,aW1hZ2U="
                    :image/mime-type "image/png"}]}
                 {:message/role :assistant
                  :message/content
                  [{:part/type :reasoning
                    :reasoning/text "checked"
                    :reasoning/signature "signed"}]}]})
        messages (get-in built [:body :messages])]
    (is (= {:s3Location {:uri "s3://bucket/photo.webp"}}
           (get-in messages [0 :content 0 :image :source])))
    (is (= {:bytes "aW1hZ2U="}
           (get-in messages [0 :content 1 :image :source])))
    (is (= {:text "checked" :signature "signed"}
           (get-in messages [1 :content 0 :reasoningContent :reasoningText])))))

(deftest test-build-tool-result-and-guard-content
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "nova-pro"
                :request/messages
                [{:message/role :user
                  :message/content
                  [{:part/type :tool-result
                    :tool-result/id "tool-1"
                    :tool-result/name "lookup"
                    :tool-result/content "failed"
                    :tool-result/is-error true}
                   {:part/type :unknown/provider-native
                    :unknown/provider :bedrock
                    :unknown/data
                    {:guardContent {:text {:text "guard only this"}}}}]}]})
        content (get-in built [:body :messages 0 :content])]
    (is (= "error" (get-in content [0 :toolResult :status])))
    (is (= "failed" (get-in content [0 :toolResult :content 0 :text])))
    (is (= "guard only this"
           (get-in content [1 :guardContent :text :text])))))

(deftest test-parse-current-converse-content
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        parsed (transport/parse-response
                t profile
                {:stopReason "malformed_tool_use"
                 :output
                 {:message
                  {:content
                   [{:reasoningContent
                     {:reasoningText {:text "analysis" :signature "sig"}}}
                    {:reasoningContent {:redactedContent "cmVkYWN0ZWQ="}}
                    {:citationsContent
                     {:content [{:text "cited answer"}]
                      :citations [{:source "doc-1" :title "Document"}]}}]}}})]
    (is (= :incomplete (:response/finish-reason parsed)))
    (is (= {:part/type :reasoning
            :reasoning/text "analysis"
            :reasoning/signature "sig"}
           (first (:response/parts parsed))))
    (is (= {:part/type :reasoning
            :reasoning/text "cmVkYWN0ZWQ="
            :reasoning/encrypted true}
           (second (:response/parts parsed))))
    (is (= {:part/type :text :text "cited answer"}
           (nth (:response/parts parsed) 2)))))

(deftest test-parse-current-stream-events
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        signature (transport/parse-stream-event
                   t profile
                   {:event-type "contentBlockDelta"
                    :data {:contentBlockIndex 2
                           :delta {:reasoningContent {:signature "sig"}}}})
        redacted (transport/parse-stream-event
                  t profile
                  {:event-type "contentBlockDelta"
                   :data {:contentBlockIndex 2
                          :delta {:reasoningContent
                                  {:redactedContent "cmVkYWN0ZWQ="}}}})
        exception (transport/parse-stream-event
                   t profile
                   {:event-type nil
                    :headers {":message-type" "exception"
                              ":exception-type" "validationException"}
                    :data {:message "bad request"}})]
    (is (= :stream/provider-state (:event/type signature)))
    (is (= "sig"
           (get-in signature [:provider-state/data :reasoning/signature])))
    (is (= :stream/reasoning-delta (:event/type redacted)))
    (is (true? (:event/encrypted redacted)))
    (is (= :stream/error (:event/type exception)))
    (is (= "validationException" (get-in exception [:error/error :type])))))

(deftest test-parse-converse-preserves-provider-response-metadata
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        metadata {:additionalModelResponseFields {:reasoning "enabled"}
                  :metrics {:latencyMs 123}
                  :performanceConfig {:latency "optimized"}
                  :serviceTier {:type "priority"}
                  :trace {:guardrail {:action "NONE"}}}
        parsed (transport/parse-response
                t profile
                (merge {:modelId "anthropic.claude-sonnet-4-20250514-v1:0"
                        :stopReason "end_turn"
                        :output {:message {:content [{:text "hello"}]}}
                        :usage {:inputTokens 1
                                :outputTokens 1
                                :totalTokens 2}}
                       metadata))]
    (is (= metadata (:response/provider-data parsed)))))
