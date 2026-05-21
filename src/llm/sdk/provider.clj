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
                                 :pareto-router true})))

;; :deepseek, :kimi, and the other OpenAI-compat aliases (:mistral :groq
;; :cerebras :together :xai) are registered by
;; llm.sdk.providers.openai-chat via register-alias!. Keeping them out of
;; the built-in block here means one file owns OpenAI-compat profile
;; data, and the transport-constructor wiring happens by construction
;; (which fixed a latent bug where :kimi had a profile but no
;; transport-constructor attached).

;; Auto-register on load
(register-built-in-providers)
