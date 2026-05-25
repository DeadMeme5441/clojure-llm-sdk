(ns llm.sdk.models-dev
  "models.dev breadth registry loader.

   Three-tier cache hierarchy mirroring hermes-agent/agent/models_dev.py:
     1. In-memory atom (1h TTL by default)
     2. Disk cache at ~/.clojure-llm-sdk/models-dev-cache.json (1h by mtime)
     3. Network fetch from https://models.dev/api.json
     4. Stale disk cache fallback (network failed, disk exists but old)
     5. Bundled snapshot at resources/models-dev-snapshot.json (last resort,
        ships with the SDK so offline use works)

   Normalizes models.dev's per-provider tree into ModelEntry maps with
   :model/source :models-dev so the registry merge layer can compare
   against live /models fetches and the bundled snapshot uniformly."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [llm.sdk.http :as http]))

;; ---------------------------------------------------------------------------
;; SDK provider keyword ↔ models.dev provider id
;; ---------------------------------------------------------------------------

(def provider-id->models-dev-id
  "Map our SDK provider keywords to models.dev's provider ids.
   :codex / :codex-backend point at OpenAI's catalog (their wire endpoint
   serves the same model set). :gemini-native uses 'google', :vertex-gemini
   uses 'google-vertex' (separate catalog including Anthropic-on-Vertex)."
  {:openai "openai"
   :anthropic "anthropic"
   :gemini-native "google"
   :vertex-gemini "google-vertex"
   :openrouter "openrouter"
   :deepseek "deepseek"
   :kimi "kimi-for-coding"
   :kimi-code "kimi-for-coding"
   :mistral "mistral"
   :groq "groq"
   :cerebras "cerebras"
   :together "togetherai"
   :xai "xai"
   :perplexity "perplexity"
   :huggingface "huggingface"
   :cohere "cohere"
   :voyage "voyage"
   :jina "jina"
   :codex "openai"
   :codex-backend "openai"
   :bedrock "amazon-bedrock"})

;; ---------------------------------------------------------------------------
;; Normalization
;; ---------------------------------------------------------------------------

(defn- str-contains-ci? [^String s ^String sub]
  (and (string? s) (str/includes? (str/lower-case s) sub)))

(defn- entry->capabilities
  "Derive a capability set from a models.dev model entry. Defaults to
   #{:chat :streaming} for chat models; flips to #{:embedding} when the
   model id reads as an embedding model. Augments with :tools,
   :reasoning, :json-schema, :vision, :pdf based on the entry's flags
   and modalities."
  [model-id m]
  (let [in-mods (set (get-in m [:modalities :input]))
        embedding-model? (or (str-contains-ci? model-id "embed")
                             (str-contains-ci? model-id "voyage"))
        base (if embedding-model? #{:embedding} #{:chat :streaming})]
    (cond-> base
      (:tool_call m) (conj :tools)
      (:reasoning m) (conj :reasoning)
      (:structured_output m) (conj :json-schema)
      (or (:attachment m) (contains? in-mods "image")) (conj :vision)
      (contains? in-mods "pdf") (conj :pdf)
      (contains? in-mods "audio") (conj :audio-input))))

(defn- entry->cost
  "Convert a models.dev cost map (already per-million USD floats) into
   our :model/cost shape. Returns nil when the entry has no cost data."
  [m]
  (let [c (:cost m)]
    (when (map? c)
      (let [out (cond-> {}
                  (:input c) (assoc :input-per-million (double (:input c)))
                  (:output c) (assoc :output-per-million (double (:output c)))
                  (:cache_read c) (assoc :cache-read-per-million (double (:cache_read c)))
                  (:cache_write c) (assoc :cache-write-per-million (double (:cache_write c))))]
        (when (seq out) out)))))

(defn normalize-entry
  "Convert a models.dev model entry into our canonical ModelEntry. The
   returned entry's :model/provider is the SDK provider keyword we were
   queried with, NOT the models.dev provider id — that way callers who
   look up by (:codex \"gpt-5\") get :codex back, not :openai."
  [provider-keyword model-id raw-entry ts]
  (let [caps (entry->capabilities model-id raw-entry)
        cost (entry->cost raw-entry)
        limit (:limit raw-entry)
        base {:model/id model-id
              :model/provider provider-keyword
              :model/source :models-dev
              :model/source-url "https://models.dev/api.json"}]
    (cond-> base
      ts (assoc :model/fetched-at ts)
      (:family raw-entry) (assoc :model/family (:family raw-entry))
      (:name raw-entry) (assoc :model/display-name (:name raw-entry))
      (:context limit) (assoc :model/context-length (:context limit))
      (:output limit) (assoc :model/max-output-tokens (:output limit))
      (seq caps) (assoc :model/capabilities caps)
      (seq cost) (assoc :model/cost cost))))

;; ---------------------------------------------------------------------------
;; Cache
;; ---------------------------------------------------------------------------

(def ^:dynamic *ttl-ms*
  "Cache freshness threshold for in-mem + disk tiers."
  (* 60 60 1000))

(def ^:dynamic *cache-dir*
  "Directory holding the on-disk models.dev cache. Tests may rebind."
  (str (System/getProperty "user.home") "/.clojure-llm-sdk"))

(def ^:dynamic *api-url*
  "models.dev API endpoint. Tests may rebind."
  "https://models.dev/api.json")

(def ^:private cache
  "In-memory cache: {:data <parsed-tree> :fetched-at-ms <epoch-ms>
                     :source :network|:disk|:bundled}"
  (atom {:data nil :fetched-at-ms nil :source nil}))

(defn- ms-now [] (System/currentTimeMillis))

(defn- cache-file ^java.io.File []
  (io/file *cache-dir* "models-dev-cache.json"))

(defn- mem-fresh? [{:keys [fetched-at-ms]}]
  (and fetched-at-ms (< (- (ms-now) fetched-at-ms) *ttl-ms*)))

(defn- disk-fresh? []
  (let [f (cache-file)]
    (and (.exists f)
         (< (- (ms-now) (.lastModified f)) *ttl-ms*))))

(defn- read-disk-cache []
  (try
    (let [f (cache-file)]
      (when (.exists f)
        {:data (json/parse-string (slurp f) true)
         :fetched-at-ms (.lastModified f)
         :source :disk}))
    (catch Exception _ nil)))

(defn- write-disk-cache! [data]
  (try
    (let [dir (io/file *cache-dir*)
          f (cache-file)
          tmp (io/file (str (.getPath f) ".tmp"))]
      (when-not (.exists dir) (.mkdirs dir))
      (spit tmp (json/generate-string data))
      (.renameTo tmp f))
    (catch Exception _ nil)))

(defn- load-bundled-snapshot []
  (try
    (when-let [r (io/resource "models-dev-snapshot.json")]
      {:data (json/parse-string (slurp r) true)
       :fetched-at-ms 0
       :source :bundled})
    (catch Exception _ nil)))

(defn- fetch-network []
  (try
    (let [resp (http/request {:method :get
                              :url *api-url*
                              :headers {"Accept" "application/json"}})
          status (:status resp)]
      (when (and (>= status 200) (< status 300) (map? (:body resp)))
        {:data (:body resp)
         :fetched-at-ms (ms-now)
         :source :network}))
    (catch Exception _ nil)))

(defn fetch-all
  "Return {:data tree :fetched-at-ms long :source keyword}, walking the
   cache hierarchy: fresh in-mem → fresh disk → network → stale disk →
   bundled snapshot. Returns nil only if every tier fails (no network,
   no disk cache, no bundled snapshot)."
  ([] (fetch-all {}))
  ([{:keys [force-refresh?]}]
   (cond
     (and (not force-refresh?) (mem-fresh? @cache))
     @cache

     (and (not force-refresh?) (disk-fresh?))
     (let [d (read-disk-cache)]
       (when d (reset! cache d))
       d)

     :else
     (or (let [n (fetch-network)]
           (when n
             (write-disk-cache! (:data n))
             (reset! cache n)
             n))
         (let [d (read-disk-cache)]
           (when d (reset! cache d) d))
         (let [b (load-bundled-snapshot)]
           (when b (reset! cache b) b))))))

(defn refresh!
  "Force a network fetch, bypassing in-mem and disk caches. Returns the
   new cache map, or nil if the network call failed."
  []
  (fetch-all {:force-refresh? true}))

(defn reset-cache!
  "Clear the in-memory cache. Tests use this to isolate runs."
  []
  (reset! cache {:data nil :fetched-at-ms nil :source nil}))

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn- full-key-name
  "Convert a possibly-namespaced keyword back to its full string form so
   model ids like 'openai/gpt-4o' round-trip correctly."
  [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    (string? k) k
    :else (str k)))

(defn- find-model-entry
  "Find a model entry in a (keyword-keyed) models map. Tries exact
   match, then case-insensitive scan over the full key name."
  [models-map model-id]
  (let [direct (get models-map (keyword model-id))]
    (or direct
        (let [lc (str/lower-case model-id)]
          (some (fn [[k v]]
                  (when (= lc (str/lower-case (full-key-name k)))
                    v))
                models-map)))))

(defn- provider-models-map
  "Return the models.dev :models sub-map for a provider keyword, or nil."
  [data provider-id]
  (when-let [mdev (provider-id->models-dev-id provider-id)]
    (some-> data (get (keyword mdev)) :models)))

(defn- ms->inst [ms]
  (when (and ms (pos? ms))
    (java.util.Date. ^long ms)))

(defn lookup
  "Look up (provider, model) and return a normalized ModelEntry tagged
   :model/source :models-dev. Returns nil when the provider has no
   mapping or models.dev doesn't know the model."
  [provider-id model-id]
  (let [{:keys [data fetched-at-ms]} (or (fetch-all) {})]
    (when-let [models-map (provider-models-map data provider-id)]
      (when-let [raw (find-model-entry models-map model-id)]
        (normalize-entry provider-id model-id raw (ms->inst fetched-at-ms))))))

(defn list-models
  "Return normalized ModelEntry maps for every model models.dev knows
   under our SDK provider keyword. Empty vector when the provider has
   no mapping or the registry is empty."
  [provider-id]
  (let [{:keys [data fetched-at-ms]} (or (fetch-all) {})
        ts (ms->inst fetched-at-ms)]
    (->> (provider-models-map data provider-id)
         (mapv (fn [[k v]]
                 (normalize-entry provider-id (full-key-name k) v ts))))))

(defn known-providers
  "Return the set of SDK provider keywords for which models.dev has
   data, intersected with the current cache tier."
  []
  (let [{:keys [data]} (or (fetch-all) {})
        mdev-ids (set (keys data))]
    (->> provider-id->models-dev-id
         (filter (fn [[_ v]] (contains? mdev-ids (keyword v))))
         (map first)
         set)))
