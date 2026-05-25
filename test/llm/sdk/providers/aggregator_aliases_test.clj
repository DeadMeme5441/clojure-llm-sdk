(ns llm.sdk.providers.aggregator-aliases-test
  "Coverage for aggregator OpenAI-compatible alias profiles.

   These are pure config additions — base-url + env-var + the openai-
   chat transport. Each profile is verified to register with the right
   URL, env-var, and a working transport-constructor; the actual chat
   wire shape is already covered by openai_chat_test."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai-chat :as openai]
            [llm.sdk.models :as models]))

(def ^:private aggregators
  [{:id :sambanova   :base "https://api.sambanova.ai/v1"
    :env "SAMBANOVA_API_KEY"}
   {:id :deepinfra   :base "https://api.deepinfra.com/v1/openai"
    :env "DEEPINFRA_API_KEY"}
   {:id :lambda      :base "https://api.lambda.ai/v1"
    :env "LAMBDA_API_KEY"}
   {:id :nebius      :base "https://api.studio.nebius.com/v1"
    :env "NEBIUS_API_KEY"}
   {:id :hyperbolic  :base "https://api.hyperbolic.xyz/v1"
    :env "HYPERBOLIC_API_KEY"}
   {:id :novita      :base "https://api.novita.ai/v3/openai"
    :env "NOVITA_API_KEY"}
   {:id :friendliai  :base "https://api.friendli.ai/serverless/v1"
    :env "FRIENDLI_TOKEN"}
   {:id :featherless :base "https://api.featherless.ai/v1"
    :env "FEATHERLESS_API_KEY"}
   {:id :dashscope   :base "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    :env "DASHSCOPE_API_KEY"}
   {:id :volcengine  :base "https://ark.cn-beijing.volces.com/api/v3"
    :env "ARK_API_KEY"}
   ;; :cloudflare is intentionally tested separately — its base-url
   ;; contains an account-id placeholder users must replace.
   ])

(deftest test-aggregator-profiles-registered
  (doseq [{:keys [id base env]} aggregators]
    (let [p (provider/get-provider id)]
      (is (some? p) (str id))
      (is (= base (:profile/base-url p)) (str id " base-url"))
      (is (= [env] (:profile/env-var-names p)) (str id " env-var"))
      (is (fn? (:profile/transport-constructor p)) (str id " transport"))
      (is (contains? (:profile/capabilities p) :chat) (str id " chat")))))

(deftest test-cloudflare-profile-has-account-placeholder
  (testing "Cloudflare base-url ships with REPLACE-WITH-ACCOUNT-ID"
    (let [p (provider/get-provider :cloudflare)]
      (is (some? p))
      (is (re-find #"REPLACE-WITH-ACCOUNT-ID" (:profile/base-url p))
          "users have to substitute their account id before use")
      (is (fn? (:profile/transport-constructor p))))))

(deftest test-aggregator-request-uses-its-own-base-url
  (testing "build-request for SambaNova goes to api.sambanova.ai"
    (let [t (openai/make-transport)
          profile (provider/get-provider :sambanova)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (transport/build-request
                   t profile
                   {:request/model "Meta-Llama-3.1-8B-Instruct"
                    :request/messages [{:message/role :user
                                        :message/content "Hi"}]}))]
      (is (= "https://api.sambanova.ai/v1/chat/completions" (:url built)))
      (is (= "Bearer stub" (get-in built [:headers "Authorization"]))))))

(deftest test-models-fetch-multimethods-registered
  (doseq [{:keys [id]} aggregators]
    (is (true? (models/supports-models-listing? id))
        (str id " registered for /models fetch"))))

(deftest test-list-providers-includes-aggregators
  (let [ids (set (sdk/list-providers))]
    (doseq [{:keys [id]} aggregators]
      (is (contains? ids id) (str id " in list-providers")))
    (is (contains? ids :cloudflare))))
