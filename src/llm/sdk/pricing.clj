(ns llm.sdk.pricing
  "Pricing lookup + cost estimation, layered on llm.sdk.registry.

   Data flow:
     (sdk/estimate-cost ...) ─► estimate-cost-for-model
                                       │
                                       ▼
                                 registry/lookup        ─► merged ModelEntry
                                  - override tier       (pricing from cost map)
                                  - live /models tier
                                  - models.dev tier (including bundled snapshot)

   The hardcoded pricing snapshot that previously lived here folded into
   the bundled models.dev snapshot at resources/models-dev-snapshot.json
   (every former entry verified present with current pricing). Callers
   who need to inject custom pricing should use
   llm.sdk.registry/register-entry!, which bypasses the public registries.

   The PricingEntry record shape is preserved for callers who already
   consume it — internally we convert ModelEntry's :model/cost map back
   to this shape at lookup time."
  (:require [clojure.string :as str]
            [llm.sdk.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Legacy entry shapes — preserved for callers that already depend on them
;; ---------------------------------------------------------------------------

(defn pricing-entry
  "Construct a pricing entry. Costs are per-million-tokens in USD."
  [& {:keys [input output cache-read cache-write request-cost
             source source-url pricing-version]}]
  {:input-cost-per-million input
   :output-cost-per-million output
   :cache-read-cost-per-million cache-read
   :cache-write-cost-per-million cache-write
   :request-cost request-cost
   :source (or source :registry)
   :source-url source-url
   :pricing-version pricing-version})

(defn cost-result
  [amount status source label & {:keys [notes]}]
  {:cost/amount-usd amount
   :cost/status status
   :cost/source source
   :cost/label label
   :cost/notes (or notes [])})

;; ---------------------------------------------------------------------------
;; ModelEntry → pricing-entry adapter
;; ---------------------------------------------------------------------------

(defn- model-entry->pricing-entry
  "Convert the :model/cost map on a ModelEntry back into a legacy
   pricing-entry. Returns nil when the model entry has no cost data."
  [model-entry]
  (when-let [c (:model/cost model-entry)]
    (when (or (:input-per-million c) (:output-per-million c))
      (pricing-entry
       :input (:input-per-million c)
       :output (:output-per-million c)
       :cache-read (:cache-read-per-million c)
       :cache-write (:cache-write-per-million c)
       :request-cost (:request-cost c)
       :source (:model/source model-entry)
       :source-url (:model/source-url model-entry)))))

;; ---------------------------------------------------------------------------
;; Registry-backed lookup
;; ---------------------------------------------------------------------------

(defn get-pricing
  "Get the pricing-entry for (provider, model), or nil when no tier of
   the registry has cost data for the pair."
  [provider model]
  (some-> (registry/lookup provider model)
          model-entry->pricing-entry))

(defn register-pricing
  "Register a caller-provided pricing entry as a registry override.
   Internally converts to ModelEntry shape and pushes into the override
   tier. The entry argument can be a legacy pricing-entry map, OR a map
   with :input/:output/:cache-read/:cache-write/:request-cost shorthand."
  [provider model entry]
  (let [norm (cond
               (:input-cost-per-million entry) entry
               :else
               (pricing-entry :input (:input entry)
                              :output (:output entry)
                              :cache-read (:cache-read entry)
                              :cache-write (:cache-write entry)
                              :request-cost (:request-cost entry)
                              :source (:source entry :user-override)
                              :source-url (:source-url entry)))
        cost (cond-> {}
               (:input-cost-per-million norm)
               (assoc :input-per-million (:input-cost-per-million norm))
               (:output-cost-per-million norm)
               (assoc :output-per-million (:output-cost-per-million norm))
               (:cache-read-cost-per-million norm)
               (assoc :cache-read-per-million (:cache-read-cost-per-million norm))
               (:cache-write-cost-per-million norm)
               (assoc :cache-write-per-million (:cache-write-cost-per-million norm))
               (:request-cost norm) (assoc :request-cost (:request-cost norm)))]
    (registry/register-entry! provider model
                              (cond-> {}
                                (seq cost) (assoc :model/cost cost)
                                (:source-url norm) (assoc :model/source-url (:source-url norm))))))

;; ---------------------------------------------------------------------------
;; Billing route — informational only
;; ---------------------------------------------------------------------------

(defn resolve-billing-route
  "Determine billing route metadata from model name, provider, and an
   optional base-url. Pure data — not a pricing lookup."
  [model & {:keys [provider base-url]}]
  (let [p (or provider "unknown")
        url (or base-url "")]
    {:billing-route/provider p
     :billing-route/model model
     :billing-route/base-url url
     :billing-route/mode (cond
                           (str/includes? url "openrouter") :openrouter
                           (str/includes? url "localhost") :local
                           :else :direct)}))

;; ---------------------------------------------------------------------------
;; Live refresh — delegate to registry
;; ---------------------------------------------------------------------------

(defn fetch-pricing!
  "Refresh pricing for a billing-route by hitting the provider's live
   /models endpoint via registry/refresh!. Returns the number of
   entries fetched, or 0 when the provider lacks /models support."
  [billing-route & {:keys [api-key]}]
  ;; api-key is forwarded only via the provider profile's env var path —
  ;; the SDK doesn't accept per-call key injection for /models fetches.
  ;; Reserved for future use.
  (let [pid (when-let [p (:billing-route/provider billing-route)]
              (cond
                (keyword? p) p
                (string? p) (keyword p)))]
    (if pid
      (try
        (count (registry/refresh! pid))
        (catch Exception _ 0))
      0)))

;; ---------------------------------------------------------------------------
;; Cost estimation
;; ---------------------------------------------------------------------------

(defn- per-million [n cost-per-m]
  (if (and n cost-per-m)
    (-> (bigdec n)
        (.multiply (bigdec cost-per-m))
        (.movePointLeft 6))
    0M))

(defn- safe-bigdec [x]
  (cond
    (nil? x) 0M
    (number? x) (try (bigdec x) (catch Exception _ 0M))
    :else 0M))

(defn estimate-cost
  "Compute cost from canonical Usage and a pricing-entry. Returns a
   cost-result map. Pure — no registry lookup."
  [usage pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [{:keys [input-cost-per-million
                  output-cost-per-million
                  cache-read-cost-per-million
                  cache-write-cost-per-million
                  request-cost]} pricing
          input-tokens (:usage/input-tokens usage 0)
          output-tokens (:usage/output-tokens usage 0)
          cache-read (:usage/cached-input-tokens usage 0)
          cache-write (:usage/cache-write-tokens usage 0)]
      (if (and input-cost-per-million output-cost-per-million)
        (let [amount (reduce + 0M
                             [(per-million input-tokens input-cost-per-million)
                              (per-million output-tokens output-cost-per-million)
                              (per-million cache-read cache-read-cost-per-million)
                              (per-million cache-write cache-write-cost-per-million)
                              (safe-bigdec request-cost)])]
          (cost-result amount :actual (:source pricing)
                       (str "Tokens: " input-tokens " in / " output-tokens " out")))
        (cost-result nil :estimated (:source pricing)
                     "Incomplete pricing data")))))

(defn estimate-cost-for-model
  "Look up pricing through the registry and estimate cost for the given
   usage. Returns a cost-result. Works for any provider+model the
   merged registry knows — no manual registration required."
  [provider model usage]
  (estimate-cost usage (get-pricing provider model)))
