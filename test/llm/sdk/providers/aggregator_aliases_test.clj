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
    :env "SAMBANOVA_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :deepinfra   :base "https://api.deepinfra.com/v1/openai"
    :env "DEEPINFRA_TOKEN"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :lambda      :base "https://api.lambda.ai/v1"
    :env "LAMBDA_API_KEY"
    :capabilities #{:chat :streaming :tools}}
   {:id :nebius      :base "https://api.tokenfactory.nebius.com/v1"
    :env "NEBIUS_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :hyperbolic  :base "https://api.hyperbolic.xyz/v1"
    :env "HYPERBOLIC_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :novita      :base "https://api.novita.ai/openai"
    :env "NOVITA_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :friendliai  :base "https://api.friendli.ai/serverless/v1"
    :env "FRIENDLI_TOKEN"
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :featherless :base "https://api.featherless.ai/v1"
    :env "FEATHERLESS_API_KEY"
    :capabilities #{:chat :streaming :tools}}
   {:id :dashscope   :base "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    :env "DASHSCOPE_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :volcengine  :base "https://ark.cn-beijing.volces.com/api/v3"
    :env "ARK_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema}}])

(deftest test-aggregator-profiles-registered
  (doseq [{:keys [id base env capabilities]} aggregators]
    (let [p (provider/get-provider id)]
      (is (some? p) (str id))
      (is (= base (:profile/base-url p)) (str id " base-url"))
      (is (= [env] (:profile/env-var-names p)) (str id " env-var"))
      (is (fn? (:profile/transport-constructor p)) (str id " transport"))
      (is (every? (:profile/capabilities p) capabilities)
          (str id " required capabilities")))))

(deftest test-cloudflare-profile-has-account-placeholder
  (testing "Cloudflare base-url is account-scoped and has no global model list"
    (let [p (provider/get-provider :cloudflare)]
      (is (some? p))
      (is (re-find #"REPLACE-WITH-ACCOUNT-ID" (:profile/base-url p))
          "users have to substitute their account id before use")
      (is (= #{:chat :streaming :tools :json-schema}
             (:profile/capabilities p)))
      (is (false? (:profile/supports-model-listing p)))
      (is (fn? (:profile/transport-constructor p))))))

(deftest test-aggregator-requests-use-provider-base-url
  (doseq [{:keys [id base]} aggregators]
    (let [t (openai/make-transport)
          profile (provider/get-provider id)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (transport/build-request
                   t profile
                   {:request/model "test-model"
                    :request/messages [{:message/role :user
                                        :message/content "Hi"}]}))]
      (is (= (str base "/chat/completions") (:url built)) (str id " URL"))
      (is (= "Bearer stub" (get-in built [:headers "Authorization"]))
          (str id " auth")))))

(deftest test-models-fetch-multimethods-registered
  (doseq [{:keys [id]} aggregators]
    (is (true? (models/supports-models-listing? id))
        (str id " registered for /models fetch"))))

(deftest test-list-providers-includes-aggregators
  (let [ids (set (sdk/list-providers))]
    (doseq [{:keys [id]} aggregators]
      (is (contains? ids id) (str id " in list-providers")))
    (is (contains? ids :cloudflare))))
