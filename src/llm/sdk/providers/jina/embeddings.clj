(ns llm.sdk.providers.jina.embeddings
  "Jina embeddings transport for the model-discriminated /v1/embeddings API."
  (:require [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.embed :as et]))

(defn- ->int [value]
  (when (and (number? value)
             (not (neg? value))
             (Double/isFinite (double value)))
    (int value)))

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
        body (transport/merge-extra-body (:profile/id profile)
                                         body
                                         (:extra_body opts))
        multivector? (true? (:return_multivector body))
        tokenized? (true? (:return_tokenized_input body))
        dimensions (:dimensions body)
        invalid-data {:provider :jina
                      :error/type :request/invalid-embedding-options
                      :return-tokenized-input tokenized?
                      :return-multivector multivector?
                      :dimensions dimensions}]
    (when (and tokenized? (not multivector?))
      (throw (ex-info
              "Jina return_tokenized_input requires return_multivector"
              invalid-data)))
    (when (and multivector? dimensions)
      (throw (ex-info
              "Jina return_multivector cannot be combined with dimensions"
              invalid-data)))
    {:method :post
     :url (str (:profile/base-url profile) "/embeddings")
     :headers (provider/default-headers
               profile
               (provider/resolve-auth-token profile))
     :body body}))

(defn normalize-jina-embedding-usage [raw]
  (let [usage (or (:usage raw) raw)
        input (or (->int (:prompt_tokens usage))
                  (->int (:total_tokens usage)))
        total (or (->int (:total_tokens usage)) input)
        image (->int (:image_tokens usage))
        audio (->int (:audio_tokens usage))
        video (->int (:video_tokens usage))]
    (cond-> {:usage/output-tokens 0
             :usage/request-count 1
             :usage/provider-raw usage}
      (some? input) (assoc :usage/input-tokens input)
      (some? total) (assoc :usage/total-tokens total)
      (some? image) (assoc :usage/image-tokens image)
      (some? audio) (assoc :usage/audio-tokens audio)
      (some? video) (assoc :usage/video-tokens video))))

(defn- dense-vector [embedding]
  (cond
    (string? embedding)
    (et/decode-float32-base64 embedding)

    (and (sequential? embedding)
         (every? number? embedding))
    (vec embedding)))

(defn- opaque-embedding? [item dense]
  (or (and (contains? item :embedding) (nil? dense))
      (contains? item :embeddings)
      (contains? item :tokenized_input)))

(defn parse-embed-response-jina
  [profile raw]
  (let [data (sort-by #(or (:index %) 0) (:data raw))
        [vectors opaque]
        (reduce (fn [[vectors opaque] item]
                  (let [dense (dense-vector (:embedding item))]
                    [(cond-> vectors dense (conj dense))
                     (cond-> opaque
                       (opaque-embedding? item dense) (conj item))]))
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

