(ns llm.sdk.providers.aggregator-aliases-test
  "Coverage for aggregator OpenAI-compatible alias profiles and their shared
   raw-HTTP request contract."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai.chat :as openai]
            [llm.sdk.models :as models]))

(def ^:private aggregators
  [{:id :sambanova   :base "https://api.sambanova.ai/v1"
    :env "SAMBANOVA_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :deepinfra   :base "https://api.deepinfra.com/v1/openai"
    :env "DEEPINFRA_TOKEN"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
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
    :capabilities #{:chat :streaming :tools :json-schema}
    :model-listing? false}])

(def ^:private aggregator-request-cases
  [{:id :sambanova
    :native-options {:seed 23}
    :wire {:seed 23 :max_tokens 80}}
   {:id :deepinfra
    :native-options {:top_k 40}
    :wire {:top_k 40 :max_tokens 80}}
   {:id :nebius
    :native-options {:store false}
    :wire {:store false :max_tokens 80}}
   {:id :hyperbolic
    :native-options {:top_p 0.9}
    :wire {:top_p 0.9 :max_tokens 80}}
   {:id :novita
    :native-options {:temperature 0.2}
    :wire {:temperature 0.2 :max_tokens 80}}
   {:id :friendliai
    :native-options {:temperature 0.7}
    :wire {:temperature 0.7 :max_tokens 80}}
   {:id :featherless
    :native-options {:repetition_penalty 1.1}
    :wire {:repetition_penalty 1.1 :max_tokens 80}}
   {:id :cloudflare
    :native-options {}
    :wire {:max_tokens 80}}
   {:id :dashscope
    :native-options {:enable_thinking true}
    :wire {:enable_thinking true :max_tokens 80}}
   {:id :volcengine
    :native-options {:seed 23}
    :wire {:seed 23 :max_tokens 80}}])

(deftest test-aggregator-profiles-registered
  (doseq [{:keys [id base env capabilities model-listing?]} aggregators]
    (let [p (provider/get-provider id)]
      (is (some? p) (str id))
      (is (= base (:profile/base-url p)) (str id " base-url"))
      (is (= [env] (:profile/env-var-names p)) (str id " env-var"))
      (is (fn? (:profile/transport-constructor p)) (str id " transport"))
      (is (every? (:profile/capabilities p) capabilities)
          (str id " required capabilities"))
      (is (= (not (false? model-listing?))
             (:profile/supports-model-listing p))
          (str id " model listing")))))

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

(deftest test-aggregator-documented-request-contracts
  (is (= (conj (set (map :id aggregators)) :cloudflare)
         (set (map :id aggregator-request-cases))))
  (doseq [{:keys [id native-options wire]} aggregator-request-cases]
    (let [profile (provider/get-provider id)
          json? (contains? (:profile/capabilities profile) :json-schema)
          request
          (cond-> {:request/model "test-model"
                   :request/messages
                   [{:message/role :user :message/content "Hi"}]
                   :request/max-tokens 80
                   :request/tools
                   [{:type :function
                     :function {:name "lookup"
                                :parameters {:type "object"}}}]
                   :request/provider-options {:extra_body native-options}}
            json?
            (assoc :request/response-format {:type :json_object}))
          body
          (-> (transport/build-request
               (openai/make-transport) profile request)
              :body
              json/generate-string
              (json/parse-string true))]
      (is (= "test-model" (:model body)) (str id " model"))
      (is (= "Hi" (get-in body [:messages 0 :content]))
          (str id " text"))
      (is (= "function" (get-in body [:tools 0 :type]))
          (str id " function tools"))
      (is (not (contains? body :extra_body))
          (str id " no SDK wrapper on wire"))
      (doseq [[field expected] wire]
        (is (= expected (get body field))
            (str id " documented top-level " field)))
      (when json?
        (is (= "json_object" (get-in body [:response_format :type]))
            (str id " JSON mode"))))))

(deftest test-models-fetch-multimethods-registered
  (doseq [{:keys [id model-listing?]} aggregators]
    (is (= (not (false? model-listing?))
           (models/supports-models-listing? id))
        (str id " /models support"))))

(deftest test-list-providers-includes-aggregators
  (let [ids (set (sdk/list-providers))]
    (doseq [{:keys [id]} aggregators]
      (is (contains? ids id) (str id " in list-providers")))
    (is (contains? ids :cloudflare))
    (is (not (contains? ids :lambda)))
    (is (nil? (provider/get-provider :lambda)))))
