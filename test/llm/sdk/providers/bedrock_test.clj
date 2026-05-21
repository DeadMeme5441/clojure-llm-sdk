(ns llm.sdk.providers.bedrock-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.bedrock :as bedrock]))

(deftest test-build-request-basic
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "anthropic.claude-3-5-sonnet-20241022-v2:0"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (= "anthropic.claude-3-5-sonnet-20241022-v2:0" (get-in built [:body :modelId])))
    (is (= "Sys" (get-in built [:body :system 0 :text])))
    (is (= 1 (count (get-in built [:body :messages]))))))

(deftest test-cache-point-default-on-when-cache-enabled
  (testing "Bedrock injects cachePoint sentinel after system and final user content"
    (let [t (bedrock/make-transport)
          profile (provider/get-provider :bedrock)
          req {:request/model "anthropic.claude-3-5-sonnet"
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
        req {:request/model "anthropic.claude-3-5-sonnet"
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
        req {:request/model "anthropic.claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)
        sys (get-in built [:body :system])
        last-msg-content (get-in built [:body :messages 0 :content])]
    (is (nil? (:cachePoint (last sys))))
    (is (nil? (:cachePoint (last last-msg-content))))))
