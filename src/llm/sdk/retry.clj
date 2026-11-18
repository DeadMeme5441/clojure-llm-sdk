(ns llm.sdk.retry
  "Data-driven retry policy with jittered backoff."
  (:require [clojure.string :as str]
            [llm.sdk.errors :as errors])
  (:import [java.time Duration Instant ZonedDateTime]
           [java.time.format DateTimeFormatter]))

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
        delay (min (* (double base-delay-ms) (Math/pow 2 (min 62 exponent))) max-delay-ms)
        jitter (int (* jitter-ratio delay (rand)))]
    (long (min max-delay-ms (+ delay jitter)))))

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
   :retry/budget-ms 120000
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
  "Parse a Retry-After delay-seconds or RFC 1123 HTTP-date value.
   Returns a nonnegative delay in milliseconds, or nil if unparseable.
   The two-argument arity accepts the current Instant for deterministic use."
  ([value]
   (parse-retry-after value (Instant/now)))
  ([value now]
   (when (and value (instance? Instant now))
     (let [v (str/trim (str value))]
       (if (re-matches #"[0-9]+" v)
         (try
           (Math/multiplyExact (Long/parseLong v) 1000)
           (catch NumberFormatException _ nil)
           (catch ArithmeticException _ nil))
         (try
           (let [date (-> (ZonedDateTime/parse
                           v DateTimeFormatter/RFC_1123_DATE_TIME)
                          (.toInstant))]
             (max 0 (.toMillis (Duration/between now date))))
           (catch Exception _ nil)))))))

(def ^:dynamic *clock-ms* #(quot (System/nanoTime) 1000000))
(def ^:dynamic *sleep-fn* #(Thread/sleep (long %)))

(defn normalize-policy [policy]
  (when-not (or (nil? policy) (false? policy))
    (when-not (or (true? policy) (map? policy))
      (throw (ex-info "Invalid retry policy" {:error/type :request/invalid-retry})))
    (let [p (merge (default-policy) (when (map? policy) policy))]
      (when-not (and (pos-int? (:retry/max-attempts p))
                     (pos-int? (:retry/budget-ms p))
                     (nat-int? (:retry/base-delay-ms p))
                     (nat-int? (:retry/max-delay-ms p))
                     (number? (:retry/jitter-ratio p))
                     (<= 0 (:retry/jitter-ratio p) 1)
                     (ifn? (:retry/retryable? p)))
        (throw (ex-info "Invalid retry policy" {:error/type :request/invalid-retry})))
      p)))

(defn- header [response wanted]
  (some (fn [[k v]] (when (= wanted (str/lower-case (name k)))
                     (if (sequential? v) (first v) v)))
        (:headers response)))

(defn- close-body! [response]
  (when (instance? java.io.Closeable (:body response))
    (.close ^java.io.Closeable (:body response))))

(defn- cancellation-cause [error]
  (some #(when (or (instance? InterruptedException %)
                    (instance? java.util.concurrent.CancellationException %)) %)
        (take-while some? (iterate ex-cause error))))

(defn request!
  "Run only the HTTP effect with bounded opt-in retries. Parsing and response
   validation belong outside this loop: malformed successes must not be replayed.
   Request effects must reopen multipart files on each attempt."
  [send-request request parse-error provider-id policy
   & {:keys [transport-error-message api-error-message]
      :or {transport-error-message "Provider transport error"
           api-error-message "Provider API error"}}]
  (let [policy (normalize-policy policy)
        deadline (when policy (+ (*clock-ms*) (:retry/budget-ms policy)))]
    (when (and policy (> (:retry/max-attempts policy) 1)
               (some #(instance? java.io.InputStream %)
                     (tree-seq coll? seq (select-keys request [:body :multipart]))))
      (throw (ex-info "Retries require replayable request bodies"
                      {:error/type :request/non-replayable-body :provider provider-id})))
    (loop [attempt 1]
      (when (.isInterrupted (Thread/currentThread))
        (throw (InterruptedException. "Provider request interrupted")))
      (let [remaining (when deadline (- deadline (*clock-ms*)))]
        (when (and remaining (not (pos? remaining)))
          (throw (ex-info "Provider retry deadline exceeded"
                          {:error/type :retry/deadline :provider provider-id
                           :attempts (dec attempt)})))
        (let [req (cond-> request remaining
                    (assoc :timeout-ms (min (or (:timeout-ms request) 120000) remaining)))
              outcome (try {:response (send-request req)}
                           (catch InterruptedException e
                             (.interrupt (Thread/currentThread)) (throw e))
                           (catch java.util.concurrent.CancellationException e (throw e))
                           (catch Exception e
                             (if-let [cancel (cancellation-cause e)]
                               (do (when (instance? InterruptedException cancel)
                                     (.interrupt (Thread/currentThread)))
                                   (throw cancel))
                               {:exception e})))
              response (:response outcome)
              status (:status response)]
          (if (and (number? status) (<= 200 status 299))
            (if (and deadline (>= (*clock-ms*) deadline))
              (do (close-body! response)
                  (throw (ex-info "Provider retry deadline exceeded"
                                  {:error/type :retry/deadline :provider provider-id
                                   :attempts attempt})))
              response)
            (let [cause (:exception outcome)
                  error (try
                          (cond
                            cause (errors/classify-error cause :provider provider-id)
                            (number? status) (parse-error status (:body response))
                            :else {:error/reason :provider-bug :error/retryable false})
                          (finally (close-body! response)))
                  retry-after (parse-retry-after (header response "retry-after"))
                  data (cond-> {:error error :provider provider-id :attempts attempt}
                         response (assoc :status status :headers (:headers response)
                                         :body (:body response))
                         retry-after (assoc :retry-after-ms retry-after)
                         (header response "x-request-id")
                         (assoc :request-id (header response "x-request-id"))
                         (header response "request-id")
                         (assoc :request-id (header response "request-id")))
                  transient? (and (not= false (:error/retryable error))
                                  (or cause (#{408 429 500 502 503 504 524 529} status)))
                  decision (when (and policy transient?)
                             (should-retry? error policy attempt))
                  wait-ms (when (:retry? decision)
                            (max (or retry-after 0) (:delay-ms decision)))
                  exhausted? (and wait-ms (>= wait-ms (- deadline (*clock-ms*))))]
              (if (and wait-ms (not exhausted?))
                (do
                  (try (*sleep-fn* wait-ms)
                       (catch InterruptedException e
                         (.interrupt (Thread/currentThread)) (throw e)))
                  (recur (inc attempt)))
                (throw (ex-info (if cause transport-error-message api-error-message)
                                (cond-> data exhausted? (assoc :error/type :retry/deadline))
                                cause))))))))))
