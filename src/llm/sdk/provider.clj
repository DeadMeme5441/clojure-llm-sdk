(ns llm.sdk.provider
  "Provider profile registry and lookup."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Registry
;; ---------------------------------------------------------------------------

(def ^:private registry (atom {}))

(defn register-provider
  "Register a provider profile. Profile must be a map with :profile/id."
  [profile]
  (swap! registry assoc (:profile/id profile) profile))

(defn get-provider
  "Look up a provider profile by id. Returns nil if not found."
  [provider-id]
  (get @registry provider-id))

(defn list-providers
  "Return a seq of all registered provider ids."
  []
  (keys @registry))

(defn provider-ids
  "Return set of registered provider ids."
  []
  (set (keys @registry)))

;; ---------------------------------------------------------------------------
;; Auth resolution
;; ---------------------------------------------------------------------------

(defn resolve-auth-token
  "Resolve an auth token for a provider profile.
   Checks env vars in order, returning first non-nil value."
  [profile]
  (let [env-vars (:profile/env-var-names profile)]
    (some #(System/getenv %) env-vars)))

(defn auth-headers
  "Build auth headers for a provider profile given a token."
  [profile token]
  (when token
    (case (:profile/auth-strategy profile)
      :bearer {"Authorization" (str "Bearer " token)}
      :api-key-header {(:profile/auth-header-name profile "x-api-key") token}
      :api-key-query {} ;; caller must add to URL
      :gcp-oauth {"Authorization" (str "Bearer " token)}
      {})))

(defn default-headers
  "Merge provider default headers with auth headers."
  [profile token]
  (merge (:profile/default-headers profile {})
         (auth-headers profile token)))

;; ---------------------------------------------------------------------------
;; Built-in providers
;; ---------------------------------------------------------------------------

(defn- mk-provider
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

(defn register-built-in-providers
  "Register the built-in provider profiles."
  []
  (register-provider
   (mk-provider :openai :openai-chat "https://api.openai.com/v1" :bearer
                :profile/env-var-names ["OPENAI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning :cache}))
  (register-provider
   (mk-provider :anthropic :anthropic-messages "https://api.anthropic.com/v1" :api-key-header
                :profile/auth-header-name "x-api-key"
                :profile/env-var-names ["ANTHROPIC_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning :cache :thinking-blocks}
                :profile/default-headers {"anthropic-version" "2023-06-01"}))
  (register-provider
   (mk-provider :gemini-native :gemini-native "https://generativelanguage.googleapis.com/v1beta" :api-key-header
                :profile/auth-header-name "x-goog-api-key"
                :profile/env-var-names ["GEMINI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :multimodal :reasoning}))
  (register-provider
   (mk-provider :openrouter :openrouter "https://openrouter.ai/api/v1" :bearer
                :profile/env-var-names ["OPENROUTER_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning :provider-routing}
                :profile/quirks {:provider-preferences true
                                 :pareto-router true}))
  ;; OpenAI-compat aliases — same wire shape as :openai with different
  ;; base-urls, auth env-vars, and small per-provider quirks. They live
  ;; here (not in providers/openai-chat) so they're registered the
  ;; moment anyone loads llm.sdk.provider — that's how
  ;; llm.sdk.models and other lookups see them without first dragging
  ;; in the chat adapter ns. The transport-constructor gets attached
  ;; later by providers/openai-chat.
  ;;
  ;; User-defined OpenAI-compat aliases can be added via
  ;; llm.sdk.providers.openai-chat/register-alias!, which builds a
  ;; profile of the same shape and wires the constructor in one call.
  (register-provider
   (mk-provider :deepseek :openai-chat "https://api.deepseek.com/v1" :bearer
                :profile/env-var-names ["DEEPSEEK_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :reasoning}
                :profile/quirks {:thinking-explicit true
                                 :reasoning-content-echo true}))
  (register-provider
   (mk-provider :kimi :openai-chat "https://api.moonshot.cn/v1" :bearer
                :profile/env-var-names ["KIMI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :reasoning}
                :profile/quirks {:thinking-explicit true}))
  (register-provider
   (mk-provider :mistral :openai-chat "https://api.mistral.ai/v1" :bearer
                :profile/env-var-names ["MISTRAL_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema}
                ;; Mistral 400s on penalty fields. The :drops quirk is
                ;; honoured by providers/openai-chat's apply-drops.
                :profile/quirks {:drops #{:frequency_penalty :presence_penalty}}))
  (register-provider
   (mk-provider :groq :openai-chat "https://api.groq.com/openai/v1" :bearer
                :profile/env-var-names ["GROQ_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning}
                :profile/quirks {:reasoning-format :raw}))
  (register-provider
   (mk-provider :cerebras :openai-chat "https://api.cerebras.ai/v1" :bearer
                :profile/env-var-names ["CEREBRAS_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :reasoning}
                :profile/quirks {:reasoning-effort true}))
  (register-provider
   (mk-provider :together :openai-chat "https://api.together.xyz/v1" :bearer
                :profile/env-var-names ["TOGETHER_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema}))
  (register-provider
   (mk-provider :xai :openai-chat "https://api.x.ai/v1" :bearer
                :profile/env-var-names ["XAI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning}))
  ;; HuggingFace Inference Router — OpenAI-compat chat completions at
  ;; https://router.huggingface.co/v1/chat/completions with the model
  ;; in the request body (e.g. "meta-llama/Llama-3.3-70B-Instruct").
  ;; The router handles cross-provider dispatch to its inference
  ;; partners internally — the SDK just talks one wire shape.
  ;;
  ;; TGI / self-hosted users register their own profile with a custom
  ;; base-url; the :profile/url-builder hook from T2-05 is available
  ;; for fancier URL shapes.
  (register-provider
   (mk-provider :huggingface :openai-chat "https://router.huggingface.co/v1" :bearer
                :profile/env-var-names ["HF_TOKEN"]
                :profile/capabilities #{:chat :streaming :tools :json-schema}))
  ;; Perplexity wires up like an OpenAI-compat alias (bearer + OpenAI-
  ;; shape body) but has its own transport (see providers/perplexity)
  ;; so it can extract citation parts and Perplexity-specific usage
  ;; fields. NOT in openai-chat's compat-provider-ids list — the
  ;; perplexity transport attaches its own constructor.
  (register-provider
   (mk-provider :perplexity :perplexity-chat "https://api.perplexity.ai" :bearer
                :profile/env-var-names ["PERPLEXITY_API_KEY"]
                :profile/capabilities #{:chat :streaming :json-schema :web-search}))
  ;; --- Embedding-first providers (T2-07) ---
  ;; Cohere has its own embed wire shape (texts, input_type,
  ;; embedding_types) and its own embed transport — chat support
  ;; lands separately under T2-02.
  (register-provider
   (mk-provider :cohere :cohere-chat "https://api.cohere.com/v1" :bearer
                :profile/env-var-names ["COHERE_API_KEY"]
                :profile/capabilities #{:embedding :rerank}
                :profile/supports-model-listing false))
  ;; Voyage AI — OpenAI-compat /embeddings shape with optional
  ;; input_type ("query" / "document") via :embed/provider-options.
  (register-provider
   (mk-provider :voyage :openai-embed "https://api.voyageai.com/v1" :bearer
                :profile/env-var-names ["VOYAGE_API_KEY"]
                :profile/capabilities #{:embedding :rerank}
                :profile/supports-model-listing false))
  ;; Jina AI — OpenAI-compat /embeddings shape.
  (register-provider
   (mk-provider :jina :openai-embed "https://api.jina.ai/v1" :bearer
                :profile/env-var-names ["JINA_API_KEY"]
                :profile/capabilities #{:embedding :rerank}
                :profile/supports-model-listing false)))

;; Auto-register on load
(register-built-in-providers)
