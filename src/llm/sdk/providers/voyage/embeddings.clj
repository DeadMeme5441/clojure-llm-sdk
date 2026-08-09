(ns llm.sdk.providers.voyage.embeddings
  "Voyage text embeddings transport for POST /v1/embeddings."
  (:require [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.embed :as et]))

(defn- ->int [x]
  (cond
    (int? x) x
    (number? x) (int x)
    :else 0))


(defn build-embed-request-voyage
  [profile request]
  (let [inputs (:embed/inputs request)
        input (if (= 1 (count inputs)) (first inputs) inputs)
        opts (:embed/provider-options request)
        encoding (:embed/encoding-format request)
        body (cond-> {:model (:embed/model request)
                      :input input}
               (:input-type opts) (assoc :input_type (:input-type opts))
               (contains? opts :truncation)
               (assoc :truncation (:truncation opts))
               (:embed/dimensions request)
               (assoc :output_dimension (:embed/dimensions request))
               (= :float encoding) (assoc :output_dtype "float")
               (= :base64 encoding) (assoc :encoding_format "base64")
               (:output-dtype opts) (assoc :output_dtype (:output-dtype opts)))
        body (if-let [extra (:extra_body opts)]
               (merge body extra)
               body)]
    {:method :post
     :url (str (:profile/base-url profile) "/embeddings")
     :headers (provider/default-headers
               profile
               (provider/resolve-auth-token profile))
     :body body}))

(defn normalize-voyage-embedding-usage [raw]
  (let [usage (or (:usage raw) raw)
        input (->int (or (:prompt_tokens usage) (:total_tokens usage)))
        total (->int (or (:total_tokens usage) input))]
    {:usage/input-tokens input
     :usage/output-tokens 0
     :usage/total-tokens total
     :usage/request-count 1
     :usage/provider-raw usage}))

(defn- dense-vector [embedding]
  (when (and (sequential? embedding)
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

(when-let [p (provider/get-provider :voyage)]
  (provider/register-provider
   (assoc p :profile/embed-transport-constructor make-transport)))
