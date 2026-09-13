(ns llm.sdk.providers.cohere.embeddings
  "Cohere embed adapter — POST {base}/embed.

   Cohere's wire shape diverges from OpenAI's in three places:
  - Request uses :texts (vector) instead of :input.
  - Request carries a required :input_type
       (search_document / search_query / classification / clustering)
       which lives in canonical request as
       :embed/provider-options :input-type. Defaults to
       \"search_document\" when omitted — that's the safest fallback
       for general-purpose retrieval.
  - Response embeddings live under :embeddings.float (newer API
       with multi-format support) or :embeddings (legacy single
       format). Usage is in :meta.billed_units.input_tokens.

   Live smoke is env-gated under COHERE_API_KEY."
  (:require [clojure.string :as str]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Usage normalization (Cohere-specific)
;; ---------------------------------------------------------------------------

(defn- ->int [value]
  (when (and (number? value)
             (not (neg? value))
             (Double/isFinite (double value)))
    (int value)))

(defn normalize-cohere-embedding-usage
  "Normalize Cohere v2 meta tokens, retaining billed units in provider raw."
  [raw]
  (let [meta (or (:meta raw) raw)
        actual (:tokens meta)
        billed (:billed_units meta)
        input (or (->int (:input_tokens actual))
                  (->int (:input_tokens billed)))
        output (or (->int (:output_tokens actual))
                   (->int (:output_tokens billed))
                   0)
        image-tokens (->int (:image_tokens billed))]
    (cond-> {:usage/output-tokens output
             :usage/request-count 1
             :usage/provider-raw meta}
      (some? input) (assoc :usage/input-tokens input
                           :usage/total-tokens (+ input output))
      (some? image-tokens) (assoc :usage/image-tokens image-tokens))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- embed-url [profile]
  (or (:profile/embed-url profile)
      (let [base (:profile/base-url profile)]
        (cond
          (str/ends-with? base "/v1")
          (str (subs base 0 (- (count base) 3)) "/v2/embed")

          (str/ends-with? base "/v2") (str base "/embed")
          :else (str base "/embed")))))

(defn build-embed-request-cohere
  [profile request]
  (let [model (:embed/model request)
        inputs (:embed/inputs request)
        opts (:embed/provider-options request)
        input-type (or (:input-type opts) "search_document")
        encoding (:embed/encoding-format request)
        extra (:extra_body opts)
        body (cond-> {:model model
                      :texts inputs
                      :input_type input-type}
               (:embed/dimensions request)
               (assoc :output_dimension (:embed/dimensions request))
               encoding
               (assoc :embedding_types [(name encoding)])
               (contains? opts :truncate)
               (assoc :truncate (:truncate opts))
               (:max-tokens opts) (assoc :max_tokens (:max-tokens opts))
               (contains? opts :priority) (assoc :priority (:priority opts)))
        body (transport/merge-extra-body (:profile/id profile) body extra)]
    {:method :post
     :url (embed-url profile)
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- dense-vector [embedding]
  (when (and (sequential? embedding)
             (every? number? embedding))
    (vec embedding)))

(defn- embedding-rows [embeddings]
  (cond
    (nil? embeddings) nil
    (sequential? embeddings) (vec embeddings)
    :else [embeddings]))

(defn- dense-vectors [embeddings]
  (let [rows (embedding-rows embeddings)
        vectors (when (some? rows) (mapv dense-vector rows))]
    (when (and vectors (every? some? vectors))
      vectors)))

(defn- extract-embedding-map
  "Select exactly one canonical dense representation. Cohere returns every
   requested embedding type as a complete input-aligned row set, so combining
   types would turn N inputs into N times the number of types. Float is already
   canonical and takes precedence; base64 is decoded only when float is absent
   or unusable. Every unselected native representation remains identifiable."
  [embeddings]
  (let [float-vectors (dense-vectors (:float embeddings))
        base64-rows (embedding-rows (:base64 embeddings))
        [selected vectors]
        (cond
          (seq float-vectors) [:float float-vectors]
          (some? base64-rows)
          [:base64 (mapv et/decode-float32-base64 base64-rows)]
          :else [nil []])
        native (cond-> embeddings selected (dissoc selected))]
    {:vectors vectors
     :raw (not-empty native)}))

(defn- extract-embeddings
  "Return one input-aligned canonical representation and retain all other
   native encodings in provider data."
  [raw]
  (let [embeddings (:embeddings raw)]
    (if (map? embeddings)
      (extract-embedding-map embeddings)
      (let [vectors (dense-vectors embeddings)]
        {:vectors (or vectors [])
         :raw (when-not vectors
                (some-> embeddings embedding-rows not-empty))}))))

(defn parse-embed-response-cohere
  [profile raw]
  (let [embedding-data (extract-embeddings raw)
        vectors (:vectors embedding-data)
        opaque (:raw embedding-data)
        first-vec (first vectors)]
    (cond-> {:embed/provider (:profile/id profile)
             ;; Cohere does not echo the model in the v2 response.
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      (:id raw) (assoc :embed/id (:id raw))
      first-vec (assoc :embed/dimensions (count first-vec))
      opaque (assoc :embed/provider-data {:raw opaque})
      (:meta raw)
      (assoc :response/usage (normalize-cohere-embedding-usage raw)))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-embed-error-cohere
  [profile status body]
  (errors/classify-error (Exception. "Cohere embed API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord CohereEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-cohere profile request))
  (parse-embed-response [_ profile raw]
    (parse-embed-response-cohere profile raw))
  (parse-embed-error [_ profile status body]
    (parse-embed-error-cohere profile status body))
  (normalize-embed-usage [_ _ raw]
    (normalize-cohere-embedding-usage raw)))

(defn make-transport [] (->CohereEmbedTransport))

