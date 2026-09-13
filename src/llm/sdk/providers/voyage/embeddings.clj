(ns llm.sdk.providers.voyage.embeddings
  "Voyage text embeddings transport for POST /v1/embeddings."
  (:require [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.embed :as et]))

(defn- ->int [value]
  (when (and (number? value)
             (not (neg? value))
             (Double/isFinite (double value)))
    (int value)))

(defn build-embed-request-voyage
  [profile request]
  (let [inputs (:embed/inputs request)
        input (if (= 1 (count inputs)) (first inputs) inputs)
        opts (:embed/provider-options request)
        encoding (:embed/encoding-format request)
        output-dtype (:output-dtype opts)
        body (cond-> {:model (:embed/model request)
                      :input input}
               (:input-type opts) (assoc :input_type (:input-type opts))
               (contains? opts :truncation)
               (assoc :truncation (:truncation opts))
               (:embed/dimensions request)
               (assoc :output_dimension (:embed/dimensions request))
               (= :float encoding) (assoc :output_dtype "float")
               (= :base64 encoding) (assoc :encoding_format "base64")
               output-dtype
               (assoc :output_dtype
                      (if (keyword? output-dtype)
                        (name output-dtype)
                        output-dtype)))
        body (transport/merge-extra-body (:profile/id profile)
                                         body
                                         (:extra_body opts))
        encoding-format (or (:encoding_format body)
                            (get body "encoding_format"))
        encoding-format (if (keyword? encoding-format)
                          (name encoding-format)
                          encoding-format)
        output-dtype (or (:output_dtype body)
                         (get body "output_dtype"))
        output-dtype (if (keyword? output-dtype)
                       (name output-dtype)
                       output-dtype)]
    (when (and (= "base64" encoding-format)
               output-dtype
               (not= "float" output-dtype))
      (throw (ex-info
              "Voyage base64 embeddings require float output_dtype"
              {:provider :voyage
               :error/type :request/unsupported-embedding-encoding
               :encoding-format encoding-format
               :output-dtype output-dtype})))
    {:method :post
     :url (str (:profile/base-url profile) "/embeddings")
     :headers (provider/default-headers
               profile
               (provider/resolve-auth-token profile))
     :body body}))

(defn normalize-voyage-embedding-usage [raw]
  (let [usage (or (:usage raw) raw)
        input (or (->int (:prompt_tokens usage))
                  (->int (:total_tokens usage)))
        total (or (->int (:total_tokens usage)) input)]
    (cond-> {:usage/output-tokens 0
             :usage/request-count 1
             :usage/provider-raw usage}
      (some? input) (assoc :usage/input-tokens input)
      (some? total) (assoc :usage/total-tokens total))))

(defn- dense-vector [embedding]
  (cond
    (string? embedding)
    (et/decode-float32-base64 embedding)

    (and (sequential? embedding)
         (every? number? embedding))
    (vec embedding)))

(defn parse-embed-response-voyage
  [profile raw]
  (let [data (sort-by #(or (:index %) 0) (:data raw))
        [vectors opaque]
        (reduce (fn [[vectors opaque] item]
                  (if-let [vector (dense-vector (:embedding item))]
                    [(conj vectors vector) opaque]
                    [vectors (conj opaque item)]))
                [[] []]
                data)
        first-vector (first vectors)]
    (cond-> {:embed/provider (:profile/id profile)
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      first-vector (assoc :embed/dimensions (count first-vector))
      (seq opaque) (assoc :embed/provider-data {:raw opaque})
      (:usage raw) (assoc :response/usage
                          (normalize-voyage-embedding-usage raw)))))

(defn parse-embed-error-voyage
  [_profile status body]
  (errors/classify-error (Exception. "Voyage embeddings API error")
                         :status status
                         :body body
                         :provider :voyage))

(defrecord VoyageEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-voyage profile request))
  (parse-embed-response [_ profile raw]
    (parse-embed-response-voyage profile raw))
  (parse-embed-error [_ profile status body]
    (parse-embed-error-voyage profile status body))
  (normalize-embed-usage [_ _ raw]
    (normalize-voyage-embedding-usage raw)))

(defn make-transport [] (->VoyageEmbedTransport))

