(ns llm.sdk.registry
  "Unified merged model + pricing registry.

   Layered precedence (highest first):
     1. Caller overrides — register-entry! lets the SDK consumer inject
        custom data for endpoints the public registries don't know.
     2. Live per-provider /models fetch — populated lazily by refresh!.
        Authoritative for what the provider currently advertises.
     3. models.dev — breadth source via llm.sdk.models-dev. Includes the
        bundled offline snapshot as its own innermost fallback.

   Lookups field-merge across all tiers: higher tiers fill in missing
   fields (like context-length and pricing) from lower tiers. The
   :model/source of the returned entry is the highest-precedence tier
   that contributed.

   All operations are by [provider-keyword, model-id]."
  (:require [clojure.set :as set]
            [llm.sdk.models :as models]
            [llm.sdk.models-dev :as mdev]))

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

(defn- deep-merge-cost
  "Cost map needs key-level merge so live + mdev can each contribute
   different cost dimensions (e.g. live has cache fields, mdev has
   per-million base rates)."
  [a b]
  (merge a b))

(defn- merge-pair
  "Merge entry b on top of entry a. Cost is merged at the inner-map
   level. Other fields use rightmost-wins."
  [a b]
  (let [merged (merge a b)
        cost (deep-merge-cost (:model/cost a) (:model/cost b))]
    (cond-> merged
      (seq cost) (assoc :model/cost cost))))

(defn merge-entries
  "Merge any number of ModelEntry maps in increasing-precedence order.
   nil entries skipped. Returns nil when no input has a value. The
   highest-precedence non-nil contributor's :model/source tag wins."
  [& entries]
  (let [non-nil (vec (keep identity entries))]
    (when (seq non-nil)
      (let [combined (reduce merge-pair (first non-nil) (rest non-nil))
            source (:model/source (peek non-nil))]
        (assoc combined :model/source source)))))

;; ---------------------------------------------------------------------------
;; Lookup
;; ---------------------------------------------------------------------------

(defn lookup
  "Return the merged ModelEntry for (provider, model), or nil if no
   layer knows the model. Field-merge order: models.dev (lowest) →
   live → override."
  [provider-id model-id]
  (merge-entries (mdev/lookup provider-id model-id)
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
             models/supported-providers
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
        live-entries (store-entries-for live-store provider-id)
        over-entries (store-entries-for override-store provider-id)
        ;; Build {model-id entry} maps per tier
        mdev-by-id (into {} (map (juxt :model/id identity)) mdev-entries)
        live-by-id (into {} (map (juxt :model/id identity)) live-entries)
        over-by-id (into {} (map (juxt :model/id identity)) over-entries)
        all-ids (set (concat (keys mdev-by-id) (keys live-by-id) (keys over-by-id)))]
    (mapv (fn [mid]
            (merge-entries (get mdev-by-id mid)
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

(defn refresh!
  "Hit the provider's live /models endpoint and merge results into the
   live tier. Returns the vector of fetched entries on success, throws
   ex-info on failure. No-op (returns empty vector) for providers that
   don't expose /models."
  [provider-id]
  (if-not (models/supports-models-listing? provider-id)
    []
    (let [entries (models/fetch-models provider-id)]
      (swap! live-store
             (fn [s]
               (reduce (fn [acc e]
                         (assoc acc [(:model/provider e) (:model/id e)] e))
                       s
                       entries)))
      entries)))

(defn refresh-all!
  "Refresh every supported provider's live /models. Returns a map of
   provider → number of entries fetched, or {:error ...} on failure
   per provider. Failures do not abort other providers."
  []
  (into {}
        (for [pid (sort models/supported-providers)]
          [pid (try {:count (count (refresh! pid))}
                    (catch Exception e
                      {:error (ex-message e)
                       :data (ex-data e)}))])))

(defn register-entry!
  "Insert a caller-provided entry into the override tier. The supplied
   map can omit :model/source / :model/provider / :model/id — they will
   be set to (provider-id, model-id, :override). Useful for custom
   endpoints models.dev doesn't know about."
  [provider-id model-id entry]
  (let [tagged (assoc entry
                      :model/source :override
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
   :models-dev-source (:source (or (mdev/fetch-all) {}))})
