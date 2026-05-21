(ns llm.sdk.retry
  "Data-driven retry policy with jittered backoff."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Backoff
;; ---------------------------------------------------------------------------

(def ^:private jitter-counter (atom 0))

(defn jittered-backoff
  "Compute a jittered exponential backoff delay in milliseconds.
   attempt is 1-based."
  [attempt & {:keys [base-delay-ms max-delay-ms jitter-ratio]
              :or {base-delay-ms 5000
                   max-delay-ms 120000
                   jitter-ratio 0.5}}]
  (swap! jitter-counter inc)
  (let [exponent (max 0 (dec attempt))
        delay (min (* base-delay-ms (bit-shift-left 1 exponent)) max-delay-ms)
        jitter (int (* jitter-ratio delay (rand)))]
    (+ delay jitter)))

;; ---------------------------------------------------------------------------
;; Policy
;; ---------------------------------------------------------------------------

(defn default-policy
  "Return a default retry policy map."
  []
  {:retry/max-attempts 3
   :retry/base-delay-ms 2000
   :retry/max-delay-ms 60000
   :retry/jitter-ratio 0.5
   :retry/retryable? #(#{:rate-limit :timeout :server :overloaded :unknown
                         :network :provider-bug}
                       (:error/reason %))})

(defn should-retry?
  "Given a classified error and a retry policy, decide whether to retry.
   Also returns the delay if retrying."
  [error policy attempt]
  (let [retryable? (:retry/retryable? policy)
        max-attempts (:retry/max-attempts policy 3)]
    (if (and (< attempt max-attempts)
             (retryable? error))
      {:retry? true
       :delay-ms (jittered-backoff (inc attempt)
                                   :base-delay-ms (:retry/base-delay-ms policy 2000)
                                   :max-delay-ms (:retry/max-delay-ms policy 60000)
                                   :jitter-ratio (:retry/jitter-ratio policy 0.5))}
      {:retry? false})))

;; ---------------------------------------------------------------------------
;; Retry-After header parsing
;; ---------------------------------------------------------------------------

(defn parse-retry-after
  "Parse a Retry-After header value. Returns delay in milliseconds,
   or nil if unparseable."
  [value]
  (when value
    (let [v (str/trim (str value))]
      (if-let [seconds (try (Integer/parseInt v) (catch Exception _ nil))]
        (* seconds 1000)
        (try
          (let [date (java.time.Instant/parse v)
                now (java.time.Instant/now)]
            (max 0 (.toMillis (java.time.Duration/between now date))))
          (catch Exception _ nil))))))
