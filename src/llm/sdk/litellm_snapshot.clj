(ns llm.sdk.litellm-snapshot
  "Read-only loader for the LiteLLM-derived pricing/capability snapshot
   bundled at resources/litellm-snapshot.json.

   LiteLLM maintains an actively-curated model catalog keyed by each
   provider's native model id. The bundled snapshot is a filtered subset —
   only providers we have SDK adapters for, with each entry stripped to the
   fields llm.sdk.registry uses (context limits, capability flags, lifecycle
   metadata, and available text/modality-specific pricing). To refresh, re-run
   scripts/build_litellm_snapshot.py.

   This tier is a sibling to llm.sdk.models-dev — both contribute
   to llm.sdk.registry's field-merge. Where they overlap, the merge
   layer (registry/merge-pair) takes a key-level union and rightmost-
   wins per field; the registry orders the tiers via its lookup fn."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; Loading — keep keys as strings since Bedrock model ids contain ':'
;; and 'foo:bar' is an awkward Clojure keyword. Provider keys are
;; clean strings ("openai", "anthropic", "vertex-anthropic"…); we
;; convert to keywords at the API boundary.
;; ---------------------------------------------------------------------------

(def ^:private snapshot-resource "litellm-snapshot.json")

(defn- load-snapshot []
  (try
    (when-let [r (io/resource snapshot-resource)]
      (with-open [rdr (io/reader r)]
        ;; false = don't keywordize keys.
        (json/parse-stream rdr false)))
    (catch Exception _ nil)))

(def ^:private cache
  "Memoized parsed snapshot:
     {provider-string {model-id-string raw-entry}}
   where raw-entry has string keys (cost, capabilities, mode, …)."
  (delay (load-snapshot)))

(defn- snapshot-providers [snapshot]
  (or (get snapshot "providers") snapshot))

(defn- snapshot-meta [snapshot]
  (get snapshot "_meta"))

(def ^:private provider-aliases
  "SDK provider ids whose model catalog and pricing come from another
   provider in the LiteLLM snapshot."
  {:codex :openai
   :codex-backend :openai})

(defn- canonical-provider-key [k]
  (cond
    (keyword? k) (get provider-aliases k k)
    (string? k) (get provider-aliases (keyword k) k)
    :else k))

(defn- provider->string [k]
  (let [canonical (canonical-provider-key k)]
    (cond
      (keyword? canonical) (name canonical)
      (string? canonical) canonical
      :else (str canonical))))

(defn- by-provider [provider-key]
  (some-> @cache snapshot-providers (get (provider->string provider-key))))

;; ---------------------------------------------------------------------------
;; Normalization — snapshot entry → ModelEntry shape
;; ---------------------------------------------------------------------------

(defn- ->entry [provider-id model-id raw]
  (let [cost-raw (get raw "cost")
        cost-keys [["input_per_million" :input-per-million]
                   ["output_per_million" :output-per-million]
                   ["cache_read_per_million" :cache-read-per-million]
                   ["cache_write_per_million" :cache-write-per-million]
                   ["image_input_per_million" :image-input-per-million]
                   ["image_output_per_million" :image-output-per-million]
                   ["audio_input_per_million" :audio-input-per-million]
                   ["audio_output_per_million" :audio-output-per-million]
                   ["image_cache_read_per_million" :image-cache-read-per-million]
                   ["audio_cache_read_per_million" :audio-cache-read-per-million]
                   ["rerank_per_search_unit" :rerank-per-search-unit]
                   ["request_cost" :request-cost]
                   ["image_per_image" :image-per-image]
                   ["image_per_megapixel" :image-per-megapixel]
                   ["transcription_per_minute" :transcription-per-minute]
                   ["tts_per_million_chars" :tts-per-million-chars]
                   ["search_per_call" :search-per-call]]
        cost (when (map? cost-raw)
               (reduce (fn [out [json-key model-key]]
                         (if (contains? cost-raw json-key)
                           (assoc out model-key
                                  (double (get cost-raw json-key)))
                           out))
                       {}
                       cost-keys))
        caps-raw (get raw "capabilities")
        caps (when (sequential? caps-raw)
               (into #{} (map keyword caps-raw)))
        meta (snapshot-meta @cache)
        source-url (or (get meta "source_url")
                       "https://github.com/BerriAI/litellm/blob/b1a61f510c90ce7e4533e89247c941fa201ada4f/model_prices_and_context_window.json")
        source-revision (get meta "source_revision")]
    (cond-> {:model/id model-id
             :model/provider provider-id
             :model/source :litellm-snapshot
             :model/source-url source-url}
      source-revision
      (assoc :model/source-revision source-revision)
      (get raw "context_length")
      (assoc :model/context-length (get raw "context_length"))
      (get raw "max_output_tokens")
      (assoc :model/max-output-tokens (get raw "max_output_tokens"))
      (get raw "deprecation_date")
      (assoc :model/deprecation-date (get raw "deprecation_date"))
      (seq caps) (assoc :model/capabilities caps)
      (seq cost) (assoc :model/cost cost))))

;; ---------------------------------------------------------------------------
;; Lookup + listing — public API matches llm.sdk.models-dev's shape
;; ---------------------------------------------------------------------------

(defn lookup
  "Return a normalized ModelEntry for (provider-keyword, model-id), or
   nil when the snapshot doesn't carry that entry."
  [provider-id model-id]
  (when-let [raw (some-> (by-provider provider-id) (get model-id))]
    (->entry provider-id model-id raw)))

(defn list-models
  "Return normalized ModelEntry maps for every model the snapshot has
   under provider-id."
  [provider-id]
  (let [m (by-provider provider-id)]
    (mapv (fn [[mid raw]] (->entry provider-id mid raw))
          (or m {}))))

(defn known-providers
  "Set of SDK provider keywords the snapshot has entries for."
  []
  (->> (snapshot-providers (or @cache {}))
       keys
       (map keyword)
       set))

(defn loaded? []
  (some? @cache))
