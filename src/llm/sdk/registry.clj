(ns llm.sdk.registry
  "Unified merged model + pricing registry.

   Layered precedence (highest first):
     1. Caller overrides — register-entry! lets the SDK consumer inject
        custom data for endpoints the public registries don't know.
     2. Live per-provider /models fetch — populated lazily by refresh!.
        Authoritative for what the provider currently advertises.
     3. LiteLLM snapshot — bundled at resources/litellm-snapshot.json
        from llm.sdk.litellm-snapshot. Refreshable via
        scripts/build_litellm_snapshot.py. Wide coverage of pricing +
        capabilities, especially strong on Bedrock variants and
        less-mainstream providers.
     4. models.dev — breadth source via llm.sdk.models-dev. Includes
        the bundled offline snapshot as its own innermost fallback.

   Lookups field-merge across all tiers: higher tiers fill in missing
   fields (like context-length and pricing) from lower tiers. The
   :model/source fields describe the highest-precedence contributor,
   :model/sources retains all contributors, and :model/cost-source records
   the highest tier that supplied pricing.

   All operations are by [provider-keyword, model-id]."
  (:require [clojure.set :as set]
            [llm.sdk.models :as models]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.litellm-snapshot :as lsnap]))

;; ---------------------------------------------------------------------------
;; State — atoms isolating each mutable tier
;; ---------------------------------------------------------------------------

(def ^:private live-store
  "{[provider-id model-id] entry}. Populated by refresh!."
  (atom {}))

(def ^:private override-store
  "{[provider-id model-id] entry}. Populated by register-entry!."
  (atom {}))

;; ---------------------------------------------------------------------------
;; Merge — higher tiers fill in missing fields from lower tiers
;; ---------------------------------------------------------------------------

(def ^:private source-fields
  [:model/source :model/source-url :model/source-revision
   :model/source-freshness :model/availability :model/fetched-at])

(def ^:private merge-metadata-fields
  (into source-fields [:model/sources :model/cost-source]))

(defn- source-descriptor [entry]
  (when-let [source (:model/source entry)]
    (cond-> {:source source}
      (:model/source-url entry)
      (assoc :source-url (:model/source-url entry))
      (:model/source-revision entry)
      (assoc :source-revision (:model/source-revision entry))
      (:model/source-freshness entry)
      (assoc :freshness (:model/source-freshness entry))
      (:model/fetched-at entry)
      (assoc :fetched-at (:model/fetched-at entry)))))

(defn- entry-sources [entry]
  (or (seq (:model/sources entry))
      (some-> (source-descriptor entry) vector)
      []))

(defn- deep-merge-cost
  "Cost map needs key-level merge so live + mdev can each contribute
   different cost dimensions."
  [a b]
  (merge a b))

(defn- merge-pair
  "Merge entry b on top of entry a. Provenance is reconstructed after all
   tiers merge so a missing higher-tier URL or revision never inherits a
   lower-tier value and becomes misleading."
  [a b]
  (let [merged (merge (apply dissoc a merge-metadata-fields)
                      (apply dissoc b merge-metadata-fields))
        cost (deep-merge-cost (:model/cost a) (:model/cost b))]
    (cond-> merged
      (seq cost) (assoc :model/cost cost))))

(defn merge-entries
  "Merge ModelEntry maps in increasing-precedence order. nil entries are
   skipped. The winning tier owns the scalar source fields; all contributors
   remain visible in :model/sources. :model/cost-source is the highest tier
   that supplied any pricing."
  [& entries]
  (let [non-nil (vec (keep identity entries))]
    (when (seq non-nil)
      (let [winner (peek non-nil)
            combined (reduce merge-pair (first non-nil) (rest non-nil))
            cost-entry (last (filter #(seq (:model/cost %)) non-nil))
            sources (vec (distinct (mapcat entry-sources non-nil)))]
        (cond-> (merge combined (select-keys winner source-fields))
          (seq sources) (assoc :model/sources sources)
          cost-entry
          (assoc :model/cost-source
                 (or (:model/cost-source cost-entry)
                     (source-descriptor cost-entry))))))))

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn lookup
  "Return the merged ModelEntry for (provider, model), or nil if no
   layer knows the model. Field-merge order:
     models.dev (lowest) → litellm-snapshot → live → override."
  [provider-id model-id]
  (merge-entries (mdev/lookup provider-id model-id)
                 (lsnap/lookup provider-id model-id)
                 (get @live-store [provider-id model-id])
                 (get @override-store [provider-id model-id])))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(defn- store-providers [store]
  (set (map first (keys @store))))

(defn known-providers
  "Set of all provider keywords any tier knows about."
  []
  (set/union (mdev/known-providers)
             (lsnap/known-providers)
             (models/listing-provider-ids)
             (store-providers live-store)
             (store-providers override-store)))

(defn- store-entries-for [store provider-id]
  (->> @store
       (filter (fn [[[p _] _]] (= p provider-id)))
       (mapv second)))

(defn list-by-provider
  "Every model the registry knows under provider-id. Each entry is the
   merged result across tiers. Models present in only one tier come
   through with that tier's data only."
  [provider-id]
  (let [mdev-entries (mdev/list-models provider-id)
        lsnap-entries (lsnap/list-models provider-id)
        live-entries (store-entries-for live-store provider-id)
        over-entries (store-entries-for override-store provider-id)
        ;; Build {model-id entry} maps per tier
        mdev-by-id (into {} (map (juxt :model/id identity)) mdev-entries)
        lsnap-by-id (into {} (map (juxt :model/id identity)) lsnap-entries)
        live-by-id (into {} (map (juxt :model/id identity)) live-entries)
        over-by-id (into {} (map (juxt :model/id identity)) over-entries)
        all-ids (set (concat (keys mdev-by-id)
                             (keys lsnap-by-id)
                             (keys live-by-id)
                             (keys over-by-id)))]
    (mapv (fn [mid]
            (merge-entries (get mdev-by-id mid)
                           (get lsnap-by-id mid)
                           (get live-by-id mid)
                           (get over-by-id mid)))
          (sort all-ids))))

(defn list-all
  "Every (provider, model) entry the registry can produce, across every
   known provider."
  []
  (vec (mapcat list-by-provider (sort (known-providers)))))

;; ---------------------------------------------------------------------------
;; Mutation — refresh! and register-entry!
;; ---------------------------------------------------------------------------

(defn- provider-entry? [provider-id [[entry-provider _] _]]
  (= provider-id entry-provider))

(defn- replace-live-provider [store provider-id entries]
  (let [without-provider (into {} (remove (partial provider-entry? provider-id))
                               store)]
    (reduce (fn [result entry]
              (assoc result
                     [provider-id (:model/id entry)]
                     (cond-> (assoc entry
                                    :model/provider provider-id
                                    :model/source :live-models-api)
                       (nil? (:model/source-freshness entry))
                       (assoc :model/source-freshness :current)
                       (nil? (:model/availability entry))
                       (assoc :model/availability :listed))))
            without-provider
            entries)))

(defn- mark-live-provider-stale [store provider-id]
  (reduce-kv (fn [result key entry]
               (assoc result key
                      (if (= provider-id (first key))
                        (assoc entry :model/source-freshness :stale)
                        entry)))
             {}
             store))

(defn refresh!
  "Replace one provider's live /models slice with the fetched snapshot.
   Fetch and entry validation complete before the single atomic store update.
   A failed fetch preserves the prior slice and marks it stale before rethrowing.
   Unsupported providers return an empty vector."
  [provider-id]
  (if-not (models/supports-models-listing? provider-id)
    []
    (try
      (let [entries (models/fetch-models provider-id)]
        (when-let [invalid
                   (some #(when-not (and (string? (:model/id %))
                                         (or (nil? (:model/provider %))
                                             (= provider-id (:model/provider %))))
                            %)
                         entries)]
          (throw (ex-info "Provider /models returned an invalid entry"
                          {:provider provider-id :entry invalid})))
        (swap! live-store replace-live-provider provider-id entries)
        entries)
      (catch Exception e
        (swap! live-store mark-live-provider-stale provider-id)
        (throw e)))))

(defn refresh-all!
  "Refresh every provider currently advertising live model listing. Returns a
   map of provider → count, or an error per provider. Failures do not abort
   other providers."
  []
  (into {}
        (for [pid (sort (models/listing-provider-ids))]
          [pid (try {:count (count (refresh! pid))}
                    (catch Exception e
                      {:error (ex-message e)
                       :data (ex-data e)}))])))

(defn register-entry!
  "Insert a caller-provided entry into the override tier. The supplied
   map can omit source/provider/id fields; they are set to the caller-owned
   override tier."
  [provider-id model-id entry]
  (let [tagged (assoc entry
                      :model/source :override
                      :model/source-freshness :caller
                      :model/availability :configured
                      :model/provider provider-id
                      :model/id model-id)]
    (swap! override-store assoc [provider-id model-id] tagged)
    tagged))

(defn unregister-entry!
  "Remove a caller-registered override."
  [provider-id model-id]
  (swap! override-store dissoc [provider-id model-id])
  nil)

(defn clear-live!
  "Empty the live tier — useful in tests."
  []
  (reset! live-store {}))

(defn clear-overrides!
  "Empty the override tier — useful in tests."
  []
  (reset! override-store {}))

(defn snapshot
  "Inspect current tier sizes (for debugging / introspection)."
  []
  {:live-entries (count @live-store)
   :override-entries (count @override-store)
   :litellm-snapshot-loaded (lsnap/loaded?)
   :models-dev-source (:source (or (mdev/fetch-all) {}))})
