(ns llm.sdk.catalog
  "Model catalog — registry-backed lookups for model metadata.

   Every fn here delegates to llm.sdk.registry; the hardcoded catalog
   atom that previously lived here is gone (all former entries are
   present in the bundled models.dev snapshot at
   resources/models-dev-snapshot.json).

   Single-arg metadata lookups scan providers in the documented preference
   order for compatibility. resolve-model is stricter: it accepts only exact
   ids or an explicit provider/id pair and reports ambiguous exact ids instead
   of choosing pricing from an arbitrary provider."
  (:require [llm.sdk.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Lookup — provider-aware preferred, single-arg falls back to scan
;; ---------------------------------------------------------------------------

(def ^:private provider-preference-order
  "Native providers come before aliases (:codex/:codex-backend reuse the
   OpenAI catalog; :vertex-gemini reuses Google's via models.dev's
   `google-vertex` entry). Single-arg lookups walk this order so
   ambiguous ids resolve to the native provider first."
  [:openai :anthropic :gemini-native :openrouter :deepseek :kimi :kimi-code
   :mistral :groq :cerebras :together :xai :perplexity :huggingface
   :cohere :voyage :jina
   :sambanova :deepinfra :nebius :hyperbolic :novita
   :friendliai :featherless :cloudflare :dashscope :volcengine
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
;; Conservative resolution
;; ---------------------------------------------------------------------------

(defn- exact-matches [model-id]
  (->> (registry/known-providers)
       sort
       (keep #(registry/lookup % model-id))
       vec))

(defn- unique-exact-match [model-id]
  (let [matches (exact-matches model-id)]
    (case (count matches)
      0 nil
      1 (first matches)
      (throw
       (ex-info "Model id is ambiguous; specify its provider"
                {:error :catalog/ambiguous-model
                 :model/id model-id
                 :providers (mapv :model/provider matches)})))))

(defn resolve-model
  "Resolve an exact model id. A leading known provider segment is treated as
   an explicit provider selection (for example, openai/gpt-4o). Otherwise the
   id must have exactly one provider match; ambiguity throws ex-info with
   :error :catalog/ambiguous-model. No substring guessing is performed."
  ([model-name]
   (let [[_ prefix without-prefix] (re-find #"^([^/]+)/(.+)$" model-name)
         provider (some-> prefix keyword)
         explicit (when (and provider
                             (contains? (registry/known-providers) provider))
                    (registry/lookup provider without-prefix))]
     (or explicit (unique-exact-match model-name))))
  ([provider model-id]
   (registry/lookup provider model-id)))
