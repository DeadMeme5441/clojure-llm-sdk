(ns llm.sdk.providers.jina.embeddings
  "Jina embeddings transport for the model-discriminated /v1/embeddings API."
  (:require [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.embed :as et])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.util Base64)))

(defn- ->int [x]
  (cond
    (int? x) x
    (number? x) (int x)
    :else 0))

(defn- decode-base64-floats [encoded]
  (let [bytes (.decode (Base64/getDecoder) ^String encoded)
        buffer (doto (ByteBuffer/wrap bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (loop [values (transient [])]
      (if (>= (.remaining buffer) Float/BYTES)
        (recur (conj! values (double (.getFloat buffer))))
        (persistent! values)))))

(defn build-embed-request-jina
  [profile request]
  (let [inputs (:embed/inputs request)
        input (if (= 1 (count inputs)) (first inputs) inputs)
        opts (:embed/provider-options request)
        body (cond-> {:model (:embed/model request)
                      :input input}
               (:embed/dimensions request)
               (assoc :dimensions (:embed/dimensions request))
               (:embed/encoding-format request)
               (assoc :embedding_type
                      (name (:embed/encoding-format request)))
               (:task opts) (assoc :task (:task opts))
               (contains? opts :normalized)
               (assoc :normalized (:normalized opts))
               (contains? opts :truncate)
               (assoc :truncate (:truncate opts))
               (contains? opts :late-chunking)
               (assoc :late_chunking (:late-chunking opts))
               (contains? opts :return-multivector)
               (assoc :return_multivector (:return-multivector opts))
               (contains? opts :return-tokenized-input)
               (assoc :return_tokenized_input
                      (:return-tokenized-input opts)))
        body (if-let [extra (:extra_body opts)]
               (merge body extra)
               body)]
    {:method :post
     :url (str (:profile/base-url profile) "/embeddings")
     :headers (provider/default-headers
               profile
               (provider/resolve-auth-token profile))
     :body body}))

(defn normalize-jina-embedding-usage [raw]
  (let [usage (or (:usage raw) raw)
        input (->int (or (:prompt_tokens usage) (:total_tokens usage)))
        total (->int (or (:total_tokens usage) input))
        image (->int (:image_tokens usage))
        audio (->int (:audio_tokens usage))]
    (cond-> {:usage/input-tokens input
             :usage/output-tokens 0
             :usage/total-tokens total
             :usage/request-count 1
             :usage/provider-raw usage}
      (pos? image) (assoc :usage/image-tokens image)
      (pos? audio) (assoc :usage/audio-tokens audio))))

(defn- embedding-vector [{:keys [embedding]}]
  (cond
    (string? embedding) (decode-base64-floats embedding)
    (sequential? embedding) (vec embedding)
    :else nil))

(defn parse-embed-response-jina
  [profile raw]
  (let [vectors (->> (:data raw)
                     (sort-by #(or (:index %) 0))
                     (keep embedding-vector)
                     vec)
        first-vector (first vectors)]
    (cond-> {:embed/provider (:profile/id profile)
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      first-vector (assoc :embed/dimensions (count first-vector))
      (:usage raw) (assoc :response/usage
                          (normalize-jina-embedding-usage raw)))))

(defn parse-embed-error-jina
  [_profile status body]
  (errors/classify-error (Exception. "Jina embeddings API error")
                         :status status
                         :body body
                         :provider :jina))

(defrecord JinaEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-jina profile request))
  (parse-embed-response [_ profile raw]
    (parse-embed-response-jina profile raw))
  (parse-embed-error [_ profile status body]
    (parse-embed-error-jina profile status body))
  (normalize-embed-usage [_ _ raw]
    (normalize-jina-embedding-usage raw)))

(defn make-transport [] (->JinaEmbedTransport))

(when-let [p (provider/get-provider :jina)]
  (provider/register-provider
   (assoc p :profile/embed-transport-constructor make-transport)))
