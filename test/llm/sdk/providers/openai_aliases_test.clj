(ns llm.sdk.providers.openai-aliases-test
  "Observable wire, replay, error, and provider metadata contracts for the
   OpenAI-compatible alias mechanism."
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai-chat :as openai]
            [llm.sdk.providers.openai-compat.aliases :as aliases]))

(def ^:private new-aliases
  [{:id :mistral     :base "https://api.mistral.ai/v1"      :env "MISTRAL_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :groq        :base "https://api.groq.com/openai/v1" :env "GROQ_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :cerebras    :base "https://api.cerebras.ai/v1"     :env "CEREBRAS_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :together    :base "https://api.together.ai/v1"     :env "TOGETHER_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :xai         :base "https://api.x.ai/v1"            :env "XAI_API_KEY"
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :huggingface :base "https://router.huggingface.co/v1" :env "HF_TOKEN"
    :capabilities #{:chat :streaming :tools :json-schema}}])

(deftest test-new-alias-metadata-contracts
  (testing "each new alias retains its documented connection metadata"
    (doseq [{:keys [id base env capabilities]} new-aliases]
      (let [p (provider/get-provider id)]
        (is (= base (:profile/base-url p)) (str id " base-url"))
        (is (= [env] (:profile/env-var-names p)) (str id " env-var"))
        (is (= :bearer (:profile/auth-strategy p)) (str id " auth-strategy"))
        (is (every? (:profile/capabilities p) capabilities)
            (str id " required capabilities"))))))

(deftest test-existing-alias-metadata-contracts
  (testing "deepseek, kimi, and kimi-code retain their connection metadata"
    (let [ds (provider/get-provider :deepseek)
          k (provider/get-provider :kimi)
          kc (provider/get-provider :kimi-code)]
      (is (= "https://api.deepseek.com/v1" (:profile/base-url ds)))
      (is (= #{:chat :streaming :tools :reasoning}
             (:profile/capabilities ds)))
      (is (= "https://api.moonshot.cn/v1" (:profile/base-url k)))
      (is (= ["MOONSHOT_API_KEY"] (:profile/env-var-names k)))
      (is (= #{:chat :streaming :tools :json-schema :reasoning}
             (:profile/capabilities k)))
      (is (= "https://api.kimi.com/coding/v1" (:profile/base-url kc)))
      (is (= ["KIMI_API_KEY"] (:profile/env-var-names kc)))
      (is (false? (:profile/supports-model-listing kc)))
      (is (= #{:chat :streaming :tools :reasoning}
             (:profile/capabilities kc))))))

(deftest test-kimi-code-build-request-url-auth-and-client-identity
  (testing "Kimi Code uses the coding endpoint without impersonating Kimi CLI"
    (let [t (openai/make-transport)
          profile (provider/get-provider :kimi-code)
          built (with-redefs [provider/resolve-auth-token (constantly "stub-token")]
                  (transport/build-request
                   t profile
                   {:request/model "kimi-for-coding"
                    :request/messages [{:message/role :user
                                        :message/content "Hi"}]}))
          headers (:headers built)]
      (is (= "https://api.kimi.com/coding/v1/chat/completions" (:url built)))
      (is (= "Bearer stub-token" (get headers "Authorization")))
      (is (nil? (get headers "X-Msh-Platform")))
      (is (nil? (get headers "X-Msh-Version")))
      (is (nil? (get headers "X-Msh-Device-Id")))
      (is (nil? (get headers "User-Agent")))
      (is (= "kimi-for-coding" (get-in built [:body :model]))))))

;; ---------------------------------------------------------------------------
;; build-request goes to the right URL with the right header
;; ---------------------------------------------------------------------------

(deftest test-alias-build-request-url-and-auth
  (testing "build-request emits provider-specific URL and bearer auth"
    (doseq [{:keys [id base]} new-aliases]
      (let [t (openai/make-transport)
            profile (provider/get-provider id)
            built (with-redefs [provider/resolve-auth-token
                                (constantly "stub-token")]
                    (transport/build-request
                     t profile
                     {:request/model "test-model"
                      :request/messages [{:message/role :user
                                          :message/content "Hi"}]}))]
        (is (= (str base "/chat/completions") (:url built))
            (str id " URL"))
        (is (= "Bearer stub-token" (get-in built [:headers "Authorization"]))
            (str id " bearer auth"))
        (is (= "test-model" (get-in built [:body :model]))
            (str id " body model"))))))

(deftest test-openai-compatible-aliases-reject-file-parts
  (testing "aliases do not inherit OpenAI-only file content parts"
    (doseq [id aliases/chat-alias-ids]
      (let [t (openai/make-transport)
            profile (provider/get-provider id)
            req {:request/model "test-model"
                 :request/messages
                 [{:message/role :user
                   :message/content [{:part/type :file
                                      :file/name "brief.pdf"
                                      :file/data "JVBERi0x"}]}]}]
        (is (not (contains? (:profile/capabilities profile) :file-attachments))
            (str id " must not claim file attachments"))
        (try
          (transport/build-request t profile req)
          (is false (str id " should reject file parts before network"))
          (catch clojure.lang.ExceptionInfo e
            (is (= :provider/unsupported-file-attachment
                   (:error/type (ex-data e)))
                (str id " error type"))
            (is (= id (:provider (ex-data e)))
                (str id " error provider"))))))))

;; ---------------------------------------------------------------------------
;; Current Mistral parameters and reasoning profile
;; ---------------------------------------------------------------------------

(deftest test-mistral-keeps-current-penalty-fields
  (testing "Mistral accepts frequency_penalty and presence_penalty"
    (let [t (openai/make-transport)
          profile (provider/get-provider :mistral)
          built (transport/build-request
                 t profile
                 {:request/model "mistral-small-latest"
                  :request/messages [{:message/role :user :message/content "Hi"}]
                  :request/provider-options
                  {:extra_body {:frequency_penalty 0.5
                                :presence_penalty 0.4
                                :random_seed 42}}})]
      (is (= 0.5 (get-in built [:body :frequency_penalty])))
      (is (= 0.4 (get-in built [:body :presence_penalty])))
      (is (= 42 (get-in built [:body :random_seed])))
      (is (not (contains? (:body built) :extra_body)))
      (is (contains? (:profile/capabilities profile) :reasoning))
      (is (= :mistral
             (get-in profile [:profile/quirks :reasoning-mode]))))))

(deftest test-non-mistral-keeps-penalty-fields
  (testing "providers without :drops keep native fields at wire top level"
    (let [t (openai/make-transport)
          profile (provider/get-provider :together)
          built (transport/build-request
                 t profile
                 {:request/model "meta-llama/Llama-3.3-70B-Instruct-Turbo"
                  :request/messages
                  [{:message/role :user :message/content "Hi"}]
                  :request/provider-options
                  {:extra_body {:frequency_penalty 0.5}}})]
      (is (= 0.5 (get-in built [:body :frequency_penalty])))
      (is (not (contains? (:body built) :extra_body))))))

(def ^:private direct-request-cases
  [{:id :deepseek
    :native-options {:top_p 0.9}
    :wire {:top_p 0.9 :max_tokens 96}}
   {:id :kimi
    :native-options {:prompt_cache_key "session-1"}
    :wire {:prompt_cache_key "session-1" :max_tokens 96}}
   {:id :kimi-code
    :native-options {:prompt_cache_key "session-1"}
    :wire {:prompt_cache_key "session-1" :max_tokens 96}}
   {:id :mistral
    :native-options {:random_seed 17}
    :wire {:random_seed 17 :max_tokens 96}}
   {:id :groq
    :native-options {:seed 17}
    :wire {:seed 17 :max_completion_tokens 96}}
   {:id :cerebras
    :native-options {:seed 17}
    :wire {:seed 17 :max_tokens 96}}
   {:id :together
    :native-options {:repetition_penalty 1.05}
    :wire {:repetition_penalty 1.05 :max_tokens 96}}
   {:id :xai
    :native-options {:service_tier "priority"}
    :wire {:service_tier "priority" :max_completion_tokens 96}}
   {:id :huggingface
    :native-options {:logprobs true}
    :wire {:logprobs true :max_tokens 96}}])

(def ^:private alias-quirk-contract
  {:deepseek {:reasoning-mode :deepseek
              :reasoning-replay-field :reasoning_content}
   :kimi {:reasoning-mode :kimi
          :reasoning-replay-field :reasoning_content}
   :kimi-code {:reasoning-mode :kimi-code
               :reasoning-replay-field :reasoning_content}
   :mistral {:reasoning-mode :mistral}
   :groq {:reasoning-mode :groq
          :stream-usage true
          :max-completion-tokens true
          :drops #{:logprobs :logit_bias :top_logprobs}}
   :cerebras {:reasoning-mode :cerebras
              :reasoning-replay-field :reasoning}
   :together {:reasoning-mode :together
              :reasoning-replay-field :model-specific}
   :xai {:reasoning-mode :xai
         :reasoning-replay-field :reasoning_content
         :stream-usage true
         :max-completion-tokens true}
   :huggingface {:stream-usage true}
   :sambanova {:reasoning-mode :sambanova
               :stream-usage true}
   :deepinfra {:reasoning-mode :deepinfra
               :stream-usage true}
   :nebius {:stream-usage true}
   :hyperbolic {}
   :novita {}
   :friendliai {}
   :featherless {}
   :cloudflare {}
   :dashscope {:stream-usage true}
   :volcengine {:stream-usage true}})

(def ^:private function-tool
  {:type :function
   :function {:name "lookup"
              :description "Look up a value"
              :parameters {:type "object"
                           :properties {:key {:type "string"}}}}})

(deftest test-alias-quirk-contracts
  (doseq [[id expected] alias-quirk-contract]
    (let [profile (provider/get-provider id)]
      (is (= expected (:profile/quirks profile))
          (str id " exact shared mapper quirks"))
      (is (nil? (:profile/supported-params profile))
          (str id " does not infer parameter drops from capabilities")))))

(deftest test-direct-alias-documented-request-contracts
  (doseq [{:keys [id native-options wire]} direct-request-cases]
    (let [profile (provider/get-provider id)
          json? (contains? (:profile/capabilities profile) :json-schema)
          request
          (cond-> {:request/model "test-model"
                   :request/messages
                   [{:message/role :user :message/content "Hi"}]
                   :request/max-tokens 96
                   :request/tools [function-tool]
                   :request/provider-options {:extra_body native-options}}
            json?
            (assoc :request/response-format {:type :json_object}))
          body (-> (transport/build-request
                    (openai/make-transport) profile request)
                   :body
                   json/generate-string
                   (json/parse-string true))]
      (is (= "test-model" (:model body)) (str id " model"))
      (is (= "Hi" (get-in body [:messages 0 :content]))
          (str id " messages"))
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

(deftest test-reasoning-replay-fields-follow-alias-contract
  (doseq [[id model expected-field]
          [[:deepseek "deepseek-v4-pro" :reasoning_content]
           [:kimi "kimi-k2.7-code" :reasoning_content]
           [:kimi-code "kimi-for-coding" :reasoning_content]
           [:cerebras "qwen-3.8-27b" :reasoning]
           [:together "openai/gpt-oss-120b" :reasoning]
           [:together "kimi-k2.7-code" :reasoning_content]
           [:xai "grok-4" :reasoning_content]]]
    (let [body
          (:body
           (transport/build-request
            (openai/make-transport)
            (provider/get-provider id)
            {:request/model model
             :request/messages
             [{:message/role :assistant
               :message/content
               [{:part/type :reasoning :reasoning/text "preserve me"}
                {:part/type :text :text "answer"}]}]}))
          assistant-message (first (:messages body))]
      (is (= "preserve me" (get assistant-message expected-field))
          (str id " " model " reasoning replay field")))))

(deftest test-all-aliases-reject-openai-custom-tools
  (doseq [id aliases/chat-alias-ids]
    (try
      (transport/build-request
       (openai/make-transport)
       (provider/get-provider id)
       {:request/model "test-model"
        :request/messages [{:message/role :user :message/content "Hi"}]
        :request/tools [{:type :custom
                         :custom {:name "shell"
                                  :format {:type :text}}}]})
      (is false (str id " should reject custom tools"))
      (catch clojure.lang.ExceptionInfo e
        (is (= :provider/unsupported-custom-tool
               (:error/type (ex-data e)))
            (str id " custom tool error"))
        (is (= id (:provider (ex-data e)))
            (str id " custom tool provider"))))))

(deftest test-all-aliases-reject-protected-extra-body-overrides
  (doseq [id aliases/chat-alias-ids
          [provided-key expected-field provided-value]
          [[:model :model "other-model"]
           ["model" :model "other-model"]
           [:messages :messages []]
           ["messages" :messages []]
           [:stream :stream false]
           ["stream" :stream false]]]
    (try
      (transport/build-request
       (openai/make-transport)
       (provider/get-provider id)
       {:request/model "test-model"
        :request/messages [{:message/role :user :message/content "Hi"}]
        :request/stream? true
        :request/provider-options {:extra_body {provided-key provided-value}}})
      (is false (str id " should reject native override " provided-key))
      (catch clojure.lang.ExceptionInfo e
        (is (= :request/protected-extra-body-override
               (:error/type (ex-data e)))
            (str id " protected override error"))
        (is (= expected-field (:field (ex-data e)))
            (str id " protected override field"))
        (is (= id (:provider (ex-data e)))
            (str id " protected override provider"))))))

(deftest test-stream-usage-only-on-documented-aliases
  (let [documented
        #{:groq :xai :huggingface :sambanova :deepinfra
          :nebius :dashscope :volcengine}]
    (doseq [id aliases/chat-alias-ids]
      (let [body
            (:body
             (transport/build-request
              (openai/make-transport)
              (provider/get-provider id)
              {:request/model "test-model"
               :request/messages
               [{:message/role :user :message/content "Hi"}]
               :request/stream? true}))]
        (is (= (contains? documented id)
               (= {:include_usage true} (:stream_options body)))
            (str id " stream usage contract"))))))

(deftest test-max-completion-token-aliases-do-not-also-send-max-tokens
  (doseq [id [:groq :xai]]
    (let [body
          (:body
           (transport/build-request
            (openai/make-transport)
            (provider/get-provider id)
            {:request/model "test-model"
             :request/messages
             [{:message/role :user :message/content "Hi"}]
             :request/max-tokens 96}))]
      (is (= 96 (:max_completion_tokens body)) (str id))
      (is (not (contains? body :max_tokens)) (str id " legacy max_tokens")))))

(defn- reasoning-body [provider-id model reasoning]
  (-> (transport/build-request
       (openai/make-transport)
       (provider/get-provider provider-id)
       {:request/model model
        :request/messages [{:message/role :user :message/content "Think"}]
        :request/reasoning reasoning})
      :body
      json/generate-string
      (json/parse-string true)))

(deftest test-model-specific-alias-reasoning-controls
  (testing "DeepSeek emits both current thinking controls"
    (is (= {:type "enabled"}
           (:thinking
            (reasoning-body :deepseek "deepseek-v4-pro"
                            {:enabled true :effort :minimal}))))
    (is (= "low"
           (:reasoning_effort
            (reasoning-body :deepseek "deepseek-v4-pro"
                            {:enabled true :effort :minimal}))))
    (let [disabled (reasoning-body :deepseek "deepseek-v4-pro"
                                   {:enabled false :effort :max})]
      (is (= {:type "disabled"} (:thinking disabled)))
      (is (not (contains? disabled :reasoning_effort)))))

  (testing "Kimi separates K3 effort, K2.6 toggle, and K2.7 preserved thinking"
    (is (= "max"
           (:reasoning_effort
            (reasoning-body :kimi "kimi-k3"
                            {:enabled true :effort :xhigh}))))
    (is (= {:type "disabled"}
           (:thinking
            (reasoning-body :kimi "kimi-k2.6" {:enabled false}))))
    (is (= {:type "enabled" :keep "all"}
           (:thinking
            (reasoning-body :kimi "kimi-k2.7-code"
                            {:enabled true :effort :high}))))
    (is (= "max"
           (:reasoning_effort
            (reasoning-body :kimi-code "kimi-for-coding"
                            {:enabled true :effort :max}))))
    (let [disabled
          (reasoning-body :kimi-code "kimi-for-coding-highspeed"
                          {:enabled false})]
      (is (not (contains? disabled :thinking)))
      (is (not (contains? disabled :reasoning_effort)))))

  (testing "provider-specific effort ranges are normalized at top level"
    (is (= "high"
           (:reasoning_effort
            (reasoning-body :mistral "mistral-small-latest"
                            {:enabled true :effort :low}))))
    (is (= "default"
           (:reasoning_effort
            (reasoning-body :groq "qwen/qwen3.6-27b"
                            {:enabled true :effort :high}))))
    (is (= "none"
           (:reasoning_effort
            (reasoning-body :cerebras "qwen-3.8-27b"
                            {:enabled false}))))
    (is (= "high"
           (:reasoning_effort
            (reasoning-body :sambanova "DeepSeek-V3.1"
                            {:enabled true :effort :max}))))
    (is (= "none"
           (:reasoning_effort
            (reasoning-body :deepinfra "deepseek-ai/DeepSeek-V4-Pro-0813"
                            {:enabled false}))))
    (is (= "xhigh"
           (:reasoning_effort
            (reasoning-body :xai "grok-4"
                            {:enabled true :effort :max})))))

  (testing "Together distinguishes GPT-OSS effort from hybrid toggles"
    (is (= "high"
           (:reasoning_effort
            (reasoning-body :together "openai/gpt-oss-120b"
                            {:enabled true :effort :max}))))
    (let [hybrid
          (reasoning-body :together
                          "deepseek-ai/DeepSeek-V4-Pro-0813"
                          {:enabled true :effort :medium})]
      (is (= {:enabled true} (:reasoning hybrid)))
      (is (= "high" (:reasoning_effort hybrid))))))
