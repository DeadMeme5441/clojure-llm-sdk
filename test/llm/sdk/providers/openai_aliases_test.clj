(ns llm.sdk.providers.openai-aliases-test
  "Coverage for the OpenAI-compat alias mechanism added under T2-03.

   build-alias-profile and register-alias! let us register a new
   OpenAI-compat host in one map literal. The tests below verify:
     - every shipped alias resolves to the right base-url, env-vars,
       capabilities, and transport constructor
     - the :drops quirk strips body keys for Mistral
     - existing aliases (:deepseek :kimi :kimi-code) still work after the move out
       of provider.clj's built-ins
     - models/supported-providers + catalog provider-preference-order
       know about the new ids
     - sdk/list-providers reports them

   Live smokes for /models and chat live under
   test/llm/sdk/live_models_test.clj and live_alias_chat_test.clj
   respectively (project convention: live-prefixed namespaces gated by
   the :live-test alias)."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai-chat :as openai]
            [llm.sdk.models :as models]))

;; ---------------------------------------------------------------------------
;; build-alias-profile shape
;; ---------------------------------------------------------------------------

(deftest test-build-alias-profile-defaults
  (testing "minimal spec yields a usable OpenAI-compat profile"
    (let [p (openai/build-alias-profile
             {:id :test-alias
              :base-url "https://api.example.com/v1"
              :env-var-names ["TEST_API_KEY"]})]
      (is (= :test-alias (:profile/id p)))
      (is (= :openai-chat (:profile/protocol-family p)))
      (is (= :bearer (:profile/auth-strategy p)))
      (is (= ["TEST_API_KEY"] (:profile/env-var-names p)))
      (is (= #{:chat :streaming :tools} (:profile/capabilities p)))
      (is (true? (:profile/supports-model-listing p)))
      (is (fn? (:profile/transport-constructor p))))))

(deftest test-build-alias-profile-overrides
  (testing "spec keys override defaults"
    (let [p (openai/build-alias-profile
             {:id :custom
              :base-url "https://api.custom.example/v1"
              :auth-strategy :api-key-header
              :auth-header-name "X-Custom-Key"
              :capabilities #{:chat :embedding}
              :default-headers {"X-App" "test"}
              :quirks {:drops #{:frequency_penalty}}
              :supports-model-listing? false
              :supported-params #{:model :messages :temperature}})]
      (is (= :api-key-header (:profile/auth-strategy p)))
      (is (= "X-Custom-Key" (:profile/auth-header-name p)))
      (is (= #{:chat :embedding} (:profile/capabilities p)))
      (is (= {"X-App" "test"} (:profile/default-headers p)))
      (is (= #{:frequency_penalty} (get-in p [:profile/quirks :drops])))
      (is (false? (:profile/supports-model-listing p)))
      (is (= #{:model :messages :temperature} (:profile/supported-params p))))))

;; ---------------------------------------------------------------------------
;; Each new alias resolves to a usable profile
;; ---------------------------------------------------------------------------

(def ^:private new-aliases
  [{:id :mistral     :base "https://api.mistral.ai/v1"           :env "MISTRAL_API_KEY"}
   {:id :groq        :base "https://api.groq.com/openai/v1"      :env "GROQ_API_KEY"}
   {:id :cerebras    :base "https://api.cerebras.ai/v1"          :env "CEREBRAS_API_KEY"}
   {:id :together    :base "https://api.together.xyz/v1"         :env "TOGETHER_API_KEY"}
   {:id :xai         :base "https://api.x.ai/v1"                 :env "XAI_API_KEY"}
   ;; T2-06 — HuggingFace Inference Router is a plain OpenAI-compat alias.
   ;; TGI / self-hosted endpoints register their own profile.
   {:id :huggingface :base "https://router.huggingface.co/v1"    :env "HF_TOKEN"}])

(deftest test-new-aliases-registered
  (testing "each new alias has a profile with the expected URL and env-var"
    (doseq [{:keys [id base env]} new-aliases]
      (let [p (provider/get-provider id)]
        (is (some? p) (str id " profile is registered"))
        (is (= base (:profile/base-url p)) (str id " base-url"))
        (is (= [env] (:profile/env-var-names p)) (str id " env-var"))
        (is (= :bearer (:profile/auth-strategy p)) (str id " auth-strategy"))
        (is (fn? (:profile/transport-constructor p)) (str id " has transport"))
        (is (contains? (:profile/capabilities p) :chat) (str id " can chat"))))))

(deftest test-deepseek-kimi-carry-transport-constructor
  (testing "deepseek, kimi, and kimi-code get a constructor"
    (let [ds (provider/get-provider :deepseek)
          k (provider/get-provider :kimi)
          kc (provider/get-provider :kimi-code)]
      (is (= "https://api.deepseek.com/v1" (:profile/base-url ds)))
      (is (fn? (:profile/transport-constructor ds)))
      (is (= "https://api.moonshot.cn/v1" (:profile/base-url k)))
      (is (= ["MOONSHOT_API_KEY"] (:profile/env-var-names k)))
      ;; This was a latent bug pre-T2-03 — the doseq attaching
      ;; constructors only covered [:openai :openrouter :deepseek] and
      ;; silently skipped :kimi. T2-03's compat-provider-ids list
      ;; closes that gap.
      (is (fn? (:profile/transport-constructor k))
          ":kimi finally carries a transport-constructor")
      (is (= "https://api.kimi.com/coding/v1" (:profile/base-url kc)))
      (is (= ["KIMI_API_KEY"] (:profile/env-var-names kc)))
      (is (false? (:profile/supports-model-listing kc)))
      (is (fn? (:profile/transport-constructor kc))
          ":kimi-code carries a transport-constructor"))))

(deftest test-kimi-code-build-request-url-auth-and-client-headers
  (testing "Kimi Code uses the coding endpoint plus KimiCLI identity headers"
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
      (is (= "kimi_cli" (get headers "X-Msh-Platform")))
      (is (string? (get headers "X-Msh-Version")))
      (is (string? (get headers "X-Msh-Device-Id")))
      (is (string? (get headers "User-Agent")))
      (is (= "kimi-for-coding" (get-in built [:body :model]))))))

;; ---------------------------------------------------------------------------
;; build-request goes to the right URL with the right header
;; ---------------------------------------------------------------------------

(deftest test-alias-build-request-url-and-auth
  (testing "build-request emits provider-specific URL and bearer auth"
    (doseq [{:keys [id base]} new-aliases]
      (let [t (openai/make-transport)
            profile (provider/get-provider id)
            ;; Stub the env-var so the auth header is populated.
            env (first (:profile/env-var-names profile))
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
            (str id " body model"))
        (is (some? env))))))

;; ---------------------------------------------------------------------------
;; :drops quirk strips body keys (Mistral)
;; ---------------------------------------------------------------------------

(deftest test-mistral-drops-penalty-fields
  (testing "Mistral profile strips frequency_penalty/presence_penalty"
    (let [t (openai/make-transport)
          profile (provider/get-provider :mistral)
          ;; Penalties land in body via :extra_body; verify both that
          ;; path and a hypothetical top-level path are stripped.
          built (transport/build-request
                 t profile
                 {:request/model "mistral-small-latest"
                  :request/messages [{:message/role :user :message/content "Hi"}]
                  :request/provider-options
                  {:extra_body {:frequency_penalty 0.5
                                :presence_penalty 0.5
                                :random_seed 42}}})
          body (:body built)]
      (is (nil? (:frequency_penalty body)))
      (is (nil? (:presence_penalty body)))
      (is (nil? (get-in body [:extra_body :frequency_penalty])))
      (is (nil? (get-in body [:extra_body :presence_penalty])))
      ;; :random_seed survives — it's not in the drop list and Mistral
      ;; accepts it.
      (is (= 42 (get-in body [:extra_body :random_seed]))))))

(deftest test-drops-clears-empty-extra-body
  (testing "if every :extra_body key gets dropped, :extra_body itself goes"
    (let [t (openai/make-transport)
          profile (provider/get-provider :mistral)
          built (transport/build-request
                 t profile
                 {:request/model "mistral-small-latest"
                  :request/messages [{:message/role :user :message/content "Hi"}]
                  :request/provider-options
                  {:extra_body {:frequency_penalty 0.5
                                :presence_penalty 0.5}}})]
      (is (nil? (get-in built [:body :extra_body]))))))

(deftest test-non-mistral-keeps-penalty-fields
  (testing "providers without :drops keep extra_body intact"
    (let [t (openai/make-transport)
          profile (provider/get-provider :together)
          built (transport/build-request
                 t profile
                 {:request/model "meta-llama/Llama-3.3-70B-Instruct-Turbo"
                  :request/messages [{:message/role :user :message/content "Hi"}]
                  :request/provider-options
                  {:extra_body {:frequency_penalty 0.5}}})]
      (is (= 0.5 (get-in built [:body :extra_body :frequency_penalty]))))))

;; ---------------------------------------------------------------------------
;; Catalog + /models registries know about the new ids
;; ---------------------------------------------------------------------------

(deftest test-models-supports-listing
  (doseq [{:keys [id]} new-aliases]
    (is (true? (models/supports-models-listing? id))
        (str id " is in models/supported-providers"))))

(deftest test-list-providers-includes-new-aliases
  (let [ids (set (sdk/list-providers))]
    (is (contains? ids :mistral))
    (is (contains? ids :groq))
    (is (contains? ids :cerebras))
    (is (contains? ids :together))
    (is (contains? ids :xai))
    (is (contains? ids :huggingface))
    (is (contains? ids :kimi-code))))
