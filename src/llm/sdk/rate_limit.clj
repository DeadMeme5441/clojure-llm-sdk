(ns llm.sdk.rate_limit
  "Rate-limit header parsing and tracking."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Header parsing
;; ---------------------------------------------------------------------------

(defn parse-header-int [headers key]
  (when-let [v (get headers key)]
    (try (Integer/parseInt (str v)) (catch Exception _ nil))))

(defn parse-header-float [headers key]
  (when-let [v (get headers key)]
    (try (Float/parseFloat (str v)) (catch Exception _ nil))))

(defn parse-rate-limit-headers
  "Parse standard x-ratelimit-* headers from a response headers map.
   Returns a map with request and token bucket info."
  [headers]
  (let [h (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])) headers)]
    {:rate-limit/requests-limit
     (or (parse-header-int h "x-ratelimit-limit-requests")
         (parse-header-int h "x-ratelimit-limit"))
     :rate-limit/requests-remaining
     (or (parse-header-int h "x-ratelimit-remaining-requests")
         (parse-header-int h "x-ratelimit-remaining"))
     :rate-limit/requests-reset-seconds
     (or (parse-header-float h "x-ratelimit-reset-requests")
         (parse-header-float h "x-ratelimit-reset"))
     :rate-limit/tokens-limit
     (parse-header-int h "x-ratelimit-limit-tokens")
     :rate-limit/tokens-remaining
     (parse-header-int h "x-ratelimit-remaining-tokens")
     :rate-limit/tokens-reset-seconds
     (parse-header-float h "x-ratelimit-reset-tokens")}))

;; ---------------------------------------------------------------------------
;; State tracking
;; ---------------------------------------------------------------------------

(def ^:private state-atom (atom {}))

(defn track!
  "Track rate-limit state for a provider."
  [provider headers]
  (let [parsed (parse-rate-limit-headers headers)]
    (swap! state-atom assoc provider parsed)))

(defn get-state
  "Get last known rate-limit state for a provider."
  [provider]
  (get @state-atom provider))

(defn throttled?
  "Check if a provider appears throttled based on last known state.
   Conservative: returns true if remaining requests is 0 or very low."
  [provider]
  (when-let [s (get-state provider)]
    (or (= 0 (:rate-limit/requests-remaining s))
        (and (:rate-limit/requests-remaining s)
             (< (:rate-limit/requests-remaining s) 3)))))
