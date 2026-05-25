(ns llm.sdk.http
  "Thin, mockable HTTP layer built on hato."
  (:require [hato.client :as hc]
            [cheshire.core :as json])
  (:import [java.io BufferedReader InputStream InputStreamReader]))

(def ^:dynamic *http-client*
  "Dynamic var for the HTTP client. Bind to a mock for tests."
  nil)

(defn- client
  ([] (client nil))
  ([{:keys [http-client connect-timeout-ms timeout-ms]}]
   (or http-client
       *http-client*
       (hc/build-http-client {:connect-timeout (or connect-timeout-ms 30000)
                              :timeout (or timeout-ms 120000)}))))

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
            (instance? InputStream body) (with-open [r (InputStreamReader. body)]
                                           (slurp r))
            :else (str body))]
    (try
      (json/parse-string s true)
      (catch Exception _ s))))

(defn request
  "Make an HTTP request. Returns a map with :status, :body, :headers
   for every status code (including 4xx/5xx) — callers branch on
   :status. Body is parsed as JSON if Content-Type is application/json."
  [{:keys [method url headers body query-params] :as req}]
  (let [opts {:method method
              :url url
              :headers (merge {"Content-Type" "application/json"
                               "Accept" "application/json"}
                              headers)
              :http-client (client req)
              ;; Always return a response map — callers (sdk/complete,
              ;; llm.sdk.models/get-json) branch on :status >= 400
              ;; themselves and throw ex-info with provider-specific
              ;; context.
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        opts (if query-params
               (assoc opts :query-params query-params)
               opts)
        resp (hc/request opts)]
    {:status (:status resp)
     :headers (:headers resp)
     :body (decode-body (:body resp))}))

(defn line-seq-closeable
  "Return a lazy seq of lines and close the underlying stream at EOF
   or on read failure. If callers abandon the lazy seq before EOF, they
   still own the usual lazy-resource caveat; sdk/complete's :on-event
   path consumes it fully."
  [^InputStream is]
  (let [reader (BufferedReader. (InputStreamReader. is))]
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
              :headers (merge {"Content-Type" "application/json"
                               "Accept" "text/event-stream"}
                              headers)
              :as :stream
              :http-client (client req)
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        resp (hc/request opts)
        status (:status resp)
        body (:body resp)]
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
              :http-client (client req)
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        resp (hc/request opts)]
    {:status (:status resp)
     :headers (:headers resp)
     :body (:body resp)}))
