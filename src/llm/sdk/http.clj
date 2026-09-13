(ns llm.sdk.http
  "Thin, mockable HTTP layer built on hato."
  (:require [hato.client :as hc]
            [cheshire.core :as json]
            [llm.sdk.provider.auth :as auth])
  (:import [java.io BufferedReader InputStream InputStreamReader]
           [java.nio.charset StandardCharsets]
           [java.net.http HttpTimeoutException]
           [java.util.concurrent ScheduledThreadPoolExecutor ScheduledFuture ThreadFactory TimeUnit]
           [java.util.concurrent.atomic AtomicLong]))

(def ^:dynamic *http-client*
  "Dynamic var for the HTTP client. Bind to a mock for tests."
  nil)

(defonce ^:private default-client
  (delay (hc/build-http-client {:connect-timeout 30000})))

(defn client
  "Resolve an injected client, dynamic client, or shared default connection pool.
   A custom connect timeout requires a separate client; request deadlines do not."
  ([] (client nil))
  ([{:keys [http-client connect-timeout-ms]}]
   (or http-client
       *http-client*
       (if connect-timeout-ms
         (hc/build-http-client {:connect-timeout connect-timeout-ms})
         @default-client))))

(defn request-options
  "Common HTTP client, request deadline, and query options for every modality."
  [req]
  (let [timeout (long (or (:timeout-ms req) 120000))]
    (when-not (pos? timeout)
      (throw (ex-info "HTTP timeout must be positive"
                      {:error/type :request/invalid-timeout})))
    (cond-> {:http-client (client req) :timeout timeout}
      (:query-params req) (assoc :query-params (:query-params req)))))

(defonce ^:private stream-timer
  (delay
    (doto (ScheduledThreadPoolExecutor.
           1
           (reify ThreadFactory
             (newThread [_ runnable]
               (doto (Thread. runnable "llm-sdk-stream-timeouts")
                 (.setDaemon true)))))
      (.setRemoveOnCancelPolicy true))))

(defn- bounded-stream
  "JDK request deadlines stop at response headers for InputStream bodies.
   Close stalled bodies independently so a blocked stream read also times out."
  [input timeout-ms]
  (if-not (instance? InputStream input)
    input
    (let [^InputStream input input
          last-activity (AtomicLong. (System/nanoTime))
          expired? (atom false)
          closed? (atom false)
          scheduled (atom nil)
          close! (fn []
                   (when (compare-and-set! closed? false true)
                     (when-let [^ScheduledFuture task @scheduled]
                       (.cancel task false))
                     (.close input)))
          check! (fn []
                   (when @expired?
                     (throw (HttpTimeoutException. "Provider stream inactivity timeout"))))
          read! (fn [^bytes target offset length]
                  (java.util.Objects/checkFromIndexSize (int offset) (int length)
                                                        (alength target))
                  (if (zero? length)
                    0
                    (do
                      (check!)
                      (try
                        (let [n (.read input target (int offset) (int length))]
                          (check!)
                          (if (neg? n) (close!) (.set last-activity (System/nanoTime)))
                          n)
                        (catch Throwable error
                          (try (close!) (catch Throwable _))
                          (check!)
                          (throw error))))))
          expire! (fn []
                    (when (and (not @closed?)
                               (>= (- (System/nanoTime) (.get last-activity))
                                   (* (long timeout-ms) 1000000)))
                      (reset! expired? true)
                      (try (close!) (catch Throwable _))))
          task (.scheduleWithFixedDelay
                ^ScheduledThreadPoolExecutor @stream-timer
                ^Runnable expire! (long timeout-ms)
                (long (min timeout-ms 1000)) TimeUnit/MILLISECONDS)
          one-byte (byte-array 1)]
      (reset! scheduled task)
      (when @closed? (.cancel ^ScheduledFuture task false))
      (proxy [InputStream] []
        (read
          ([] (let [n (read! one-byte 0 1)]
                (if (neg? n) -1 (bit-and 255 (aget one-byte 0)))))
          ([target] (read! target 0 (alength ^bytes target)))
          ([target offset length] (read! target offset length)))
        (close [] (close!))))))

(defn- encode-body
  "Serialize body to a JSON string unless already a string or byte array
   (callers like the SigV4 path pre-serialize so signing covers the exact
   bytes we send)."
  [body]
  (cond
    (nil? body) nil
    (string? body) body
    (bytes? body) body
    :else (json/generate-string body)))

(defn decode-body
  "Parse an HTTP body as JSON when possible. Accepts strings and
   InputStreams; closes streams it consumes."
  [body]
  (let [s (cond
            (nil? body) nil
            (string? body) body
            (instance? InputStream body)
            (with-open [r (InputStreamReader. body StandardCharsets/UTF_8)]
              (slurp r))
            :else (str body))]
    (try
      (json/parse-string s true)
      (catch Exception _ s))))

(defn request
  "Make an HTTP request. Returns a map with :status, :body, :headers
   for every status code (including 4xx/5xx) — callers branch on
   :status. Body is parsed as JSON if Content-Type is application/json."
  [{:keys [method url headers body] :as req}]
  (let [opts {:method method
              :url url
              :headers (auth/merge-headers {"Content-Type" "application/json"
                                            "Accept" "application/json"}
                                           headers)
              :as :stream
              ;; Always return a response map — callers (sdk/complete,
              ;; llm.sdk.models/get-json) branch on :status >= 400
              ;; themselves and throw ex-info with provider-specific
              ;; context.
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        opts (merge opts (request-options req))
        resp (hc/request opts)]
    {:status (:status resp)
     :headers (:headers resp)
     :body (decode-body (bounded-stream (:body resp) (:timeout opts)))}))

(defn line-seq-closeable
  "Return a lazy seq of lines and close the underlying stream at EOF
   or on read failure. If callers abandon the lazy seq before EOF, they
   still own the usual lazy-resource caveat; sdk/complete's :on-event
   path consumes it fully."
  [^InputStream is]
  (let [reader (BufferedReader. (InputStreamReader. is StandardCharsets/UTF_8))]
    (letfn [(step []
              (lazy-seq
               (try
                 (if-let [line (.readLine reader)]
                   (cons line (step))
                   (do (.close reader) nil))
                 (catch Throwable t
                   (try (.close reader) (catch Throwable _))
                   (throw t)))))]
      (step))))

(defn sse-response
  "Make an SSE streaming request. Returns a response map. Successful
   responses carry an open InputStream in :body; non-2xx responses
   carry a parsed body and have already closed the stream."
  [{:keys [method url headers body] :as req}]
  (let [opts {:method method
              :url url
              :headers (auth/merge-headers {"Content-Type" "application/json"
                                            "Accept" "text/event-stream"}
                                           headers)
              :as :stream
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        opts (merge opts (request-options req))
        resp (hc/request opts)
        status (:status resp)
        body (bounded-stream (:body resp) (:timeout opts))]
    {:status status
     :headers (:headers resp)
     :body (if (and (number? status) (>= status 400))
             (decode-body body)
             body)}))

(defn sse-request
  "Make an SSE streaming request. Returns a lazy seq of raw SSE line strings.
   Caller should parse lines with parse-stream-event."
  [req]
  (let [{:keys [status body]} (sse-response req)]
    (if (and (number? status) (>= status 400))
      (throw (ex-info "SSE request failed" {:status status :body body}))
      (line-seq-closeable body))))

(defn binary-stream-request
  "Make a streaming HTTP request and return the raw java.io.InputStream
   in :body. Used by adapters that speak a binary framing protocol
   (e.g. AWS event-stream for Bedrock /converse-stream) and need to
   decode frames themselves."
  [{:keys [method url headers body] :as req}]
  (let [opts {:method method
              :url url
              :headers headers
              :as :stream
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        opts (merge opts (request-options req))
        resp (hc/request opts)]
    {:status (:status resp)
     :headers (:headers resp)
     :body (bounded-stream (:body resp) (:timeout opts))}))
