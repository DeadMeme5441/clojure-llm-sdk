(ns llm.sdk.http
  "Thin, mockable HTTP layer built on hato."
  (:require [hato.client :as hc]
            [cheshire.core :as json]))

(def ^:dynamic *http-client*
  "Dynamic var for the HTTP client. Bind to a mock for tests."
  nil)

(defn- client []
  (or *http-client* (hc/build-http-client {:connect-timeout 30000
                                            :timeout 120000})))

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

(defn request
  "Make an HTTP request. Returns a map with :status, :body, :headers
   for every status code (including 4xx/5xx) — callers branch on
   :status. Body is parsed as JSON if Content-Type is application/json."
  [{:keys [method url headers body query-params]}]
  (let [opts {:method method
              :url url
              :headers (merge {"Content-Type" "application/json"
                               "Accept" "application/json"}
                              headers)
              :http-client (client)
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
     :body (try
             (json/parse-string (:body resp) true)
             (catch Exception _ (:body resp)))}))

(defn sse-request
  "Make an SSE streaming request. Returns a lazy seq of raw SSE line strings.
   Caller should parse lines with parse-stream-event."
  [{:keys [method url headers body]}]
  (let [opts {:method method
              :url url
              :headers (merge {"Content-Type" "application/json"
                               "Accept" "text/event-stream"}
                              headers)
              :as :stream
              :http-client (client)}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        resp (hc/request opts)
        reader (java.io.BufferedReader.
                (java.io.InputStreamReader. (:body resp)))]
    (line-seq reader)))

(defn binary-stream-request
  "Make a streaming HTTP request and return the raw java.io.InputStream
   in :body. Used by adapters that speak a binary framing protocol
   (e.g. AWS event-stream for Bedrock /converse-stream) and need to
   decode frames themselves."
  [{:keys [method url headers body]}]
  (let [opts {:method method
              :url url
              :headers headers
              :as :stream
              :http-client (client)
              :throw-exceptions? false}
        opts (if body
               (assoc opts :body (encode-body body))
               opts)
        resp (hc/request opts)]
    {:status (:status resp)
     :headers (:headers resp)
     :body (:body resp)}))
