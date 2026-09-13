(ns llm.sdk.provider-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai.chat :as openai]))

(deftest runtime-headers-reach-native-requests-with-case-insensitive-precedence
  (let [sent (atom nil)]
    (with-redefs [http/request
                  (fn [request]
                    (reset! sent request)
                    {:status 200
                     :body {:model "test-model" :done true
                            :message {:role "assistant" :content "ok"}}})]
      (is (= "ok"
             (get-in (sdk/complete
                      :ollama-native
                      {:request/model "test-model"
                       :request/messages [{:message/role :user :message/content "hello"}]}
                      :config {:headers {"content-type" "application/custom"
                                         "X-App" "example"}})
                     [:response/parts 0 :text])))
      (is (= "application/custom" (get-in @sent [:headers "content-type"])))
      (is (not (contains? (:headers @sent) "Content-Type")))
      (is (= "example" (get-in @sent [:headers "X-App"])))
      (is (nil? (get-in (provider/get-provider :ollama-native)
                        [:profile/default-headers "X-App"]))))))

(deftest query-authentication-is-applied-at-the-common-request-boundary
  (let [profile (assoc (openai/build-alias-profile
                        {:id ::query-auth :base-url "https://example.invalid/v1"})
                       :profile/auth-strategy :api-key-query
                       :profile/auth-query-param "key")
        sent (atom nil)]
    (provider/register-provider profile)
    (with-redefs [http/request
                  (fn [request]
                    (reset! sent request)
                    {:status 200 :body {:choices [{:message {:content "ok"}
                                                   :finish_reason "stop"}]}})]
      (sdk/complete ::query-auth
                    {:request/model "test-model"
                     :request/messages [{:message/role :user :message/content "hello"}]}
                    :config {:api-key "synthetic-token"})
      (is (= "synthetic-token" (get-in @sent [:query-params "key"])))
      (is (nil? (get-in @sent [:headers "Authorization"]))))))

(deftest invalid-registration-cannot-replace-an-existing-provider
  (let [original (provider/get-provider :openai)]
    (is (= :provider/invalid-profile
           (try (provider/register-provider {:profile/id :openai})
                nil
                (catch clojure.lang.ExceptionInfo e (:error/type (ex-data e))))))
    (is (= original (provider/get-provider :openai)))))

(deftest unsupported-chat-fails-at-the-sdk-boundary
  (let [failure (try
                  (sdk/complete :elevenlabs
                                {:request/model "test-model"
                                 :request/messages [{:message/role :user
                                                     :message/content "hello"}]})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= {:provider :elevenlabs :capability :chat}
           (select-keys failure [:provider :capability])))))
