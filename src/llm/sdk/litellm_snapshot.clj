(ns llm.sdk.litellm-snapshot
  "Read-only loader for the LiteLLM-derived pricing/capability snapshot
   bundled at resources/litellm-snapshot.json (T2-17).

   LiteLLM maintains an actively-curated catalog of ~2.7k model entries
   keyed by their provider's model id. The bundled snapshot is a
   filtered subset — only providers we have SDK adapters for, with
   each entry stripped to the fields llm.sdk.registry uses
   (context-length, max-output-tokens, capability flags, per-million
   pricing). To refresh, re-run scripts/build_litellm_snapshot.py.

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

(defn- provider->string [k]
  (cond
    (keyword? k) (name k)
    (string? k) k
    :else (str k)))

(defn- by-provider [provider-key]
  (some-> @cache (get (provider->string provider-key))))

;; ---------------------------------------------------------------------------
;; Normalization — snapshot entry → ModelEntry shape
;; ---------------------------------------------------------------------------

(defn- ->entry [provider-id model-id raw]
  (let [cost-raw (get raw "cost")
        cost (when cost-raw
               (cond-> {}
                 (get cost-raw "input_per_million")
                 (assoc :input-per-million
                        (double (get cost-raw "input_per_million")))
                 (get cost-raw "output_per_million")
                 (assoc :output-per-million
                        (double (get cost-raw "output_per_million")))
                 (get cost-raw "cache_read_per_million")
                 (assoc :cache-read-per-million
                        (double (get cost-raw "cache_read_per_million")))
                 (get cost-raw "cache_write_per_million")
                 (assoc :cache-write-per-million
                        (double (get cost-raw "cache_write_per_million")))))
        caps-raw (get raw "capabilities")
        caps (when (sequential? caps-raw)
               (into #{} (map keyword caps-raw)))]
    (cond-> {:model/id model-id
             :model/provider provider-id
             :model/source :litellm-snapshot
             :model/source-url
             "https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json"}
      (get raw "context_length")
      (assoc :model/context-length (get raw "context_length"))
      (get raw "max_output_tokens")
      (assoc :model/max-output-tokens (get raw "max_output_tokens"))
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
  (->> (or @cache {})
       keys
       (map keyword)
       set))

(defn loaded? []
  (some? @cache))
