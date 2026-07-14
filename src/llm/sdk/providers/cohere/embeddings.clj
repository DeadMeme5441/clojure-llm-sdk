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
            [llm.sdk.errors :as errors])
  (:import (java.nio ByteBuffer ByteOrder)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Usage normalization (Cohere-specific)
;; ---------------------------------------------------------------------------

(defn- ->int [x] (cond (int? x) x (number? x) (int x) :else 0))

(defn normalize-cohere-embedding-usage
  "Normalize Cohere v2 meta tokens, retaining billed units in provider raw."
  [raw]
  (let [meta (or (:meta raw) raw)
        actual (:tokens meta)
        billed (:billed_units meta)
        input (->int (or (:input_tokens actual)
                         (:input_tokens billed)))
        output (->int (or (:output_tokens actual)
                          (:output_tokens billed)))
        image-tokens (->int (:image_tokens billed))]
    (cond-> {:usage/input-tokens input
             :usage/output-tokens output
             :usage/total-tokens (+ input output)
             :usage/request-count 1
             :usage/provider-raw meta}
      (pos? image-tokens) (assoc :usage/image-tokens image-tokens))))

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
        body (cond-> {:model model
                      :texts inputs
                      :input_type input-type}
               (:embed/dimensions request)
               (assoc :output_dimension (:embed/dimensions request))
               (:embed/encoding-format request)
               (assoc :embedding_types
                      [(name (:embed/encoding-format request))])
               (contains? opts :truncate)
               (assoc :truncate (:truncate opts))
               (:max-tokens opts) (assoc :max_tokens (:max-tokens opts))
               (contains? opts :priority) (assoc :priority (:priority opts)))
        extra (:extra_body opts)
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (embed-url profile)
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- decode-base64-floats [encoded]
  (let [bytes (.decode (Base64/getDecoder) ^String encoded)
        buffer (doto (ByteBuffer/wrap bytes)
                 (.order ByteOrder/LITTLE_ENDIAN))]
    (loop [values (transient [])]
      (if (>= (.remaining buffer) Float/BYTES)
        (recur (conj! values (double (.getFloat buffer))))
        (persistent! values)))))

(defn- extract-vectors
  "Normalize all current Cohere v2 embedding encodings."
  [raw]
  (let [emb (:embeddings raw)]
    (cond
      (sequential? emb) (vec emb)
      (seq (:float emb)) (vec (:float emb))
      (seq (:int8 emb)) (vec (:int8 emb))
      (seq (:uint8 emb)) (vec (:uint8 emb))
      (seq (:binary emb)) (vec (:binary emb))
      (seq (:ubinary emb)) (vec (:ubinary emb))
      (seq (:base64 emb)) (mapv decode-base64-floats (:base64 emb))
      :else [])))

(defn parse-embed-response-cohere
  [profile raw]
  (let [vectors (extract-vectors raw)
        first-vec (first vectors)]
    (cond-> {:embed/provider (:profile/id profile)
             ;; Cohere does not echo the model in the v2 response.
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      (:id raw) (assoc :embed/id (:id raw))
      first-vec (assoc :embed/dimensions (count first-vec))
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

;; Attach
(when-let [p (provider/get-provider :cohere)]
  (provider/register-provider
   (assoc p :profile/embed-transport-constructor make-transport)))
