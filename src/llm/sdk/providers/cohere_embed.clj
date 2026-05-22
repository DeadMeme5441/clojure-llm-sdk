(ns llm.sdk.providers.cohere-embed
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
  (:require [llm.sdk.transport.embed :as et]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Usage normalization (Cohere-specific)
;; ---------------------------------------------------------------------------

(defn- ->int [x] (cond (int? x) x (number? x) (int x) :else 0))

(defn normalize-cohere-embedding-usage
  "Cohere /embed returns
     {:meta {:billed_units {:input_tokens N} :api_version {...}}}"
  [raw]
  (let [input (->int (get-in raw [:meta :billed_units :input_tokens]))]
    {:usage/input-tokens input
     :usage/output-tokens 0
     :usage/total-tokens input
     :usage/request-count 1
     :usage/provider-raw (:meta raw)}))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-embed-request-cohere
  [profile request]
  (let [model (:embed/model request)
        inputs (:embed/inputs request)
        opts (:embed/provider-options request)
        input-type (or (:input-type opts) "search_document")
        body (cond-> {:model model
                      :texts inputs
                      :input_type input-type}
               (:embed/encoding-format request)
               (assoc :embedding_types
                      [(name (:embed/encoding-format request))])
               (:truncate opts)
               (assoc :truncate (:truncate opts)))
        extra (get-in request [:embed/provider-options :extra_body])
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (str (:profile/base-url profile) "/embed")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- extract-vectors
  "Cohere /embed returns embeddings under :embeddings.float (multi-
   format API) or directly under :embeddings (legacy / single format).
   Always normalise to [[v1] [v2] ...]."
  [raw]
  (let [emb (:embeddings raw)]
    (cond
      ;; Newer API: {:embeddings {:float [[...] [...]]}}
      (and (map? emb) (seq (:float emb)))
      (vec (:float emb))

      ;; Newer API with a different requested encoding (e.g. :int8)
      ;; — take whichever key holds the vector data
      (map? emb)
      (->> emb
           vals
           (some #(when (sequential? %) (vec %))))

      ;; Legacy: {:embeddings [[...] [...]]}
      (sequential? emb)
      (vec emb)

      :else
      [])))

(defn parse-embed-response-cohere
  [profile raw]
  (let [vectors (extract-vectors raw)
        first-vec (first vectors)]
    (cond-> {:embed/provider (:profile/id profile)
             ;; Cohere doesn't echo the model in the response — leave
             ;; this nil and let llm.sdk.embed/embed fill it in from
             ;; the request. (:response_type is "embeddings_floats",
             ;; not a model id, so it isn't a useful fallback.)
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      first-vec
      (assoc :embed/dimensions (count first-vec))
      (get-in raw [:meta :billed_units])
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
