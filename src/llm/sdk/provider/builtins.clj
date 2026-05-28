(ns llm.sdk.provider.builtins
  "Built-in provider profile definitions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [llm.sdk.provider.registry :as registry]
            [llm.sdk.providers.openai-compat.aliases :as openai-aliases])
  (:import [java.net InetAddress]))

(defn mk-provider
  [id protocol-family base-url auth-strategy & {:as opts}]
  (merge
   {:profile/id id
    :profile/protocol-family protocol-family
    :profile/base-url base-url
    :profile/auth-strategy auth-strategy
    :profile/supports-model-listing true
    :profile/capabilities #{:chat :streaming}
    :profile/env-var-names []
    :profile/default-headers {}
    :profile/quirks {}}
   opts))

(defn- trimmed-file-content [path]
  (try
    (let [f (io/file path)]
      (when (.isFile f)
        (not-empty (str/trim (slurp f)))))
    (catch Throwable _ nil)))

(defn- local-host-name []
  (try
    (not-empty (.getHostName (InetAddress/getLocalHost)))
    (catch Throwable _ nil)))

(defn kimi-code-headers
  "Non-secret client identity headers required by Kimi Code's coding
   endpoint. Auth still comes from KIMI_API_KEY."
  []
  (let [home (System/getProperty "user.home")
        version (or (not-empty (System/getenv "KIMI_CLI_VERSION"))
                    (trimmed-file-content (str home "/.kimi/latest_version.txt"))
                    "1.37.0")
        device-id (or (not-empty (System/getenv "KIMI_DEVICE_ID"))
                      (trimmed-file-content (str home "/.kimi/device_id"))
                      "clojure-llm-sdk")]
    {"User-Agent" (or (not-empty (System/getenv "KIMI_USER_AGENT"))
                      (str "KimiCLI/" version))
     "X-Msh-Platform" "kimi_cli"
     "X-Msh-Version" version
     "X-Msh-Device-Id" device-id
     "X-Msh-Device-Name" (or (not-empty (System/getenv "KIMI_DEVICE_NAME"))
                             (local-host-name)
                             "clojure-llm-sdk")
     "X-Msh-Device-Model" (or (not-empty (System/getenv "KIMI_DEVICE_MODEL"))
                              (str (System/getProperty "os.name") " "
                                   (System/getProperty "os.arch")))
     "X-Msh-Os-Version" (or (not-empty (System/getenv "KIMI_OS_VERSION"))
                            (System/getProperty "os.version"))}))

(defn- register-openai-aliases! []
  (doseq [spec openai-aliases/chat-alias-specs]
    (let [headers (case (:default-headers spec)
                    :kimi-code (kimi-code-headers)
                    (:default-headers spec {}))
          profile (mk-provider (:id spec) :openai-chat (:base-url spec) :bearer
                               :profile/env-var-names (vec (:env-var-names spec []))
                               :profile/capabilities (:capabilities spec #{:chat :streaming :tools})
                               :profile/default-headers headers
                               :profile/supports-model-listing
                               (boolean (get spec :supports-model-listing? true))
                               :profile/quirks (:quirks spec {}))
          profile (cond-> profile
                    (:supported-params spec)
                    (assoc :profile/supported-params (:supported-params spec)))]
      (registry/register-provider profile))))

(defn register-built-in-providers
  "Register the built-in provider profiles."
  []
  (registry/register-provider
   (mk-provider :openai :openai-chat "https://api.openai.com/v1" :bearer
                :profile/env-var-names ["OPENAI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema
                                        :reasoning :cache :file-attachments}))
  (registry/register-provider
   (mk-provider :anthropic :anthropic-messages "https://api.anthropic.com/v1" :api-key-header
                :profile/auth-header-name "x-api-key"
                :profile/env-var-names ["ANTHROPIC_API_KEY" "CLAUDE_OAT_TOKEN"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning :cache :thinking-blocks :file-attachments}
                :profile/default-headers {"anthropic-version" "2023-06-01"}))
  (registry/register-provider
   (mk-provider :gemini-native :gemini-native "https://generativelanguage.googleapis.com/v1beta" :api-key-header
                :profile/auth-header-name "x-goog-api-key"
                :profile/env-var-names ["GEMINI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :multimodal :reasoning :file-attachments}))
  (registry/register-provider
   (mk-provider :openrouter :openrouter "https://openrouter.ai/api/v1" :bearer
                :profile/env-var-names ["OPENROUTER_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning :provider-routing}
                :profile/quirks {:provider-preferences true
                                 :pareto-router true}))
  (register-openai-aliases!)
  (registry/register-provider
   (mk-provider :perplexity :perplexity-chat "https://api.perplexity.ai" :bearer
                :profile/env-var-names ["PERPLEXITY_API_KEY"]
                :profile/capabilities #{:chat :streaming :json-schema :web-search}
                :profile/supports-model-listing false
                :profile/supported-params #{:request/temperature :request/top-p
                                            :request/max-tokens :request/stop
                                            :request/response-format}))
  (registry/register-provider
   (mk-provider :cohere :cohere-chat "https://api.cohere.com/v1" :bearer
                :profile/env-var-names ["COHERE_API_KEY"]
                :profile/capabilities #{:embedding :rerank}
                :profile/supports-model-listing false))
  (registry/register-provider
   (mk-provider :voyage :openai-embed "https://api.voyageai.com/v1" :bearer
                :profile/env-var-names ["VOYAGE_API_KEY"]
                :profile/capabilities #{:embedding :rerank}
                :profile/supports-model-listing false))
  (registry/register-provider
   (mk-provider :jina :openai-embed "https://api.jina.ai/v1" :bearer
                :profile/env-var-names ["JINA_API_KEY"]
                :profile/capabilities #{:embedding :rerank}
                :profile/supports-model-listing false))
  nil)
