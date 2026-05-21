(ns llm.sdk.catalog
  "Model catalog — registry-backed lookups for model metadata.

   Every fn here delegates to llm.sdk.registry; the hardcoded catalog
   atom that previously lived here is gone (all former entries are
   present in the bundled models.dev snapshot at
   resources/models-dev-snapshot.json).

   Single-arg lookups (get-model, context-length, model-capable?) scan
   across providers and return the first match — stable for globally
   unique model ids (gpt-4o, claude-opus-4-7), non-deterministic for
   ambiguous ids that exist under multiple providers (e.g. a model
   served by both :openrouter and :openai). Prefer the
   provider-aware overloads when the id is ambiguous."
  (:require [clojure.string :as str]
            [llm.sdk.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Lookup — provider-aware preferred, single-arg falls back to scan
;; ---------------------------------------------------------------------------

(def ^:private provider-preference-order
  "Native providers come before aliases (:codex/:codex-backend reuse the
   OpenAI catalog; :vertex-gemini reuses Google's via models.dev's
   `google-vertex` entry). Single-arg lookups walk this order so
   ambiguous ids resolve to the native provider first."
  [:openai :anthropic :gemini-native :openrouter :deepseek :kimi
   :mistral :groq :cerebras :together :xai :perplexity :huggingface
   :vertex-gemini :bedrock :codex :codex-backend :fake])

(defn- find-by-id
  "Scan provider-preference-order plus any other known provider for
   the first entry whose :model/id matches. nil when no provider has it."
  [model-id]
  (let [known (registry/known-providers)
        ordered (concat (filter known provider-preference-order)
                        (sort (remove (set provider-preference-order) known)))]
    (some #(registry/lookup % model-id) ordered)))

(defn get-model
  "Look up a model entry by id. With one arg, scans across providers
   (first match wins). With two args, queries the registry directly."
  ([model-id]
   (find-by-id model-id))
  ([provider model-id]
   (registry/lookup provider model-id)))

(defn register-model
  "Register a model entry. Pushes into the registry's override tier.
   Accepts either a (model-id, entry) pair or a (provider, model-id,
   entry) triple. With a single id, derives the provider from the
   entry's :model/provider key."
  ([model-id entry]
   (let [provider (or (:model/provider entry)
                      (throw (ex-info "register-model needs :model/provider on the entry"
                                      {:model-id model-id :entry entry})))]
     (register-model provider model-id entry)))
  ([provider model-id entry]
   (registry/register-entry! provider model-id entry)))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn list-models
  "Return a sorted seq of unique model ids the registry knows about."
  []
  (->> (registry/list-all)
       (map :model/id)
       distinct
       sort))

(defn models-by-provider
  "Return every model entry the registry has under the given SDK
   provider keyword."
  [provider]
  (registry/list-by-provider provider))

;; ---------------------------------------------------------------------------
;; Capability + context-length
;; ---------------------------------------------------------------------------

(defn model-capable?
  "Check if a model supports a capability keyword (e.g. :tools,
   :vision, :cache). Returns false when the model is unknown or has no
   :model/capabilities set on its registry entry."
  ([model-id capability]
   (boolean (some-> (find-by-id model-id)
                    :model/capabilities
                    (contains? capability))))
  ([provider model-id capability]
   (boolean (some-> (registry/lookup provider model-id)
                    :model/capabilities
                    (contains? capability)))))

(defn context-length
  "Return the context length for a model in tokens, or nil when
   unknown."
  ([model-id]
   (:model/context-length (find-by-id model-id)))
  ([provider model-id]
   (:model/context-length (registry/lookup provider model-id))))

(defn max-output-tokens
  "Return the maximum output tokens for a model, or nil when unknown."
  ([model-id]
   (:model/max-output-tokens (find-by-id model-id)))
  ([provider model-id]
   (:model/max-output-tokens (registry/lookup provider model-id))))

;; ---------------------------------------------------------------------------
;; Fuzzy match
;; ---------------------------------------------------------------------------

(defn resolve-model
  "Fuzzy-match a model name against the registry. Tries: exact id,
   provider-prefixed id (anthropic/claude → claude), then substring
   over every known model id. Returns a registry entry or nil."
  [model-name]
  (or (find-by-id model-name)
      (let [without-prefix (second (re-find #"^[^/]+/(.+)$" model-name))]
        (when without-prefix (find-by-id without-prefix)))
      (let [all (registry/list-all)]
        (some (fn [e]
                (when (and (:model/id e)
                           (str/includes? model-name (:model/id e)))
                  e))
              all))))
