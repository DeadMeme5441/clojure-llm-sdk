(ns llm.sdk.providers.openai-embed
  "OpenAI embeddings adapter — POST {base}/embeddings.

   Same auth and base-url plumbing as the chat adapter; we share the
   profile, just register an additional :profile/embed-transport-
   constructor on it. Other OpenAI-compat hosts that offer embeddings
   (DeepSeek, Together, NVIDIA NIM, etc.) can reuse this transport by
   attaching the same constructor — that's the T2-07 scope. For T2-01
   we ship the OpenAI registration only."
  (:require [llm.sdk.transport.embed :as et]
            [llm.sdk.provider :as provider]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-embed-request-openai
  [profile request]
  (let [model (:embed/model request)
        inputs (:embed/inputs request)
        ;; OpenAI accepts string OR vector of strings. We canonicalize
        ;; to vector on input, but if the caller passed a single input,
        ;; unwrap to a string for compatibility with strict providers
        ;; (and to keep tokens accounted as one).
        input-payload (if (= 1 (count inputs)) (first inputs) inputs)
        body (cond-> {:model model :input input-payload}
               (:embed/dimensions request)
               (assoc :dimensions (:embed/dimensions request))
               (:embed/encoding-format request)
               (assoc :encoding_format (name (:embed/encoding-format request)))
               (:embed/user request)
               (assoc :user (:embed/user request)))
        extra (get-in request [:embed/provider-options :extra_body])
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (str (:profile/base-url profile) "/embeddings")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn parse-embed-response-openai
  [profile raw]
  (let [;; OpenAI returns data already in :index order, but a couple of
        ;; compat providers do not — sort to be safe.
        data (->> (:data raw)
                  (sort-by #(or (:index %) 0))
                  vec)
        vectors (mapv :embedding data)
        first-vec (first vectors)
        usage-raw (:usage raw)]
    (cond-> {:embed/provider (:profile/id profile)
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      first-vec
      (assoc :embed/dimensions (count first-vec))
      usage-raw
      (assoc :response/usage (usage/normalize-embedding-usage usage-raw)))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-embed-error-openai
  [profile status body]
  (errors/classify-error (Exception. "OpenAI embed API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenAIEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-openai profile request))
  (parse-embed-response [_ profile raw]
    (parse-embed-response-openai profile raw))
  (parse-embed-error [_ profile status body]
    (parse-embed-error-openai profile status body))
  (normalize-embed-usage [_ _ raw]
    (usage/normalize-embedding-usage raw)))

(defn make-transport [] (->OpenAIEmbedTransport))

;; ---------------------------------------------------------------------------
;; Attach to providers that ship the OpenAI /embeddings wire shape
;;
;; Mistral and Together register chat profiles too (under :openai-chat
;; protocol family) — adding the embed transport is purely additive.
;; Voyage and Jina are embedding-first; the protocol-family on their
;; profile is :openai-embed.
;;
;; Cohere has its own embed shape and lives in providers/cohere-embed.
;; ---------------------------------------------------------------------------

(doseq [pid [:openai :mistral :together :voyage :jina]]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (-> p
         (assoc :profile/embed-transport-constructor make-transport)
         (update :profile/capabilities (fnil conj #{}) :embedding)))))
