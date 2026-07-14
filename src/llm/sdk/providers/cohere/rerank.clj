(ns llm.sdk.providers.cohere.rerank
  "Cohere /rerank transport. The wire shape is also used by Jina —
   both accept {model, query, documents, top_n, return_documents}
   and return {results [{index, relevance_score, document {text}}]}.

   Cohere additionally returns :meta.billed_units.search_units for
   usage; Jina returns :usage {total_tokens}. Both are surfaced
   through the canonical :response/usage where possible."
  (:require [clojure.string :as str]
            [llm.sdk.transport.rerank :as rt]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Usage normalization
;; ---------------------------------------------------------------------------

(defn- ->int [x] (cond (int? x) x (number? x) (int x) :else 0))

(defn- normalize-cohere-rerank-usage [raw]
  (when-let [meta (:meta raw)]
    (let [tokens (:tokens meta)
          input (->int (:input_tokens tokens))
          output (->int (:output_tokens tokens))]
      {:usage/input-tokens input
       :usage/output-tokens output
       :usage/total-tokens (+ input output)
       :usage/request-count 1
       :usage/provider-raw meta})))

(defn- normalize-jina-rerank-usage [raw]
  (let [total (->int (get-in raw [:usage :total_tokens]))]
    (when (pos? total)
      {:usage/input-tokens total
       :usage/output-tokens 0
       :usage/total-tokens total
       :usage/request-count 1
       :usage/provider-raw (:usage raw)})))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- rerank-url [profile]
  (let [base (:profile/base-url profile)]
    (if (= :cohere (:profile/id profile))
      (or (:profile/rerank-url profile)
          (cond
            (str/ends-with? base "/v1")
            (str (subs base 0 (- (count base) 3)) "/v2/rerank")

            (str/ends-with? base "/v2") (str base "/rerank")
            :else (str base "/rerank")))
      (str base "/rerank"))))

(defn build-rerank-request-cohere-shape
  [profile request]
  (let [provider-id (:profile/id profile)
        opts (:rerank/provider-options request)
        body (cond-> {:model (:rerank/model request)
                      :query (:rerank/query request)
                      :documents (:rerank/documents request)}
               (:rerank/top-n request)
               (assoc :top_n (:rerank/top-n request))
               (and (= :jina provider-id)
                    (some? (:rerank/return-documents request)))
               (assoc :return_documents
                      (boolean (:rerank/return-documents request)))
               (and (= :cohere provider-id) (:max-tokens-per-doc opts))
               (assoc :max_tokens_per_doc (:max-tokens-per-doc opts))
               (and (= :cohere provider-id) (contains? opts :priority))
               (assoc :priority (:priority opts))
               (and (= :jina provider-id) (contains? opts :truncation))
               (assoc :truncation (:truncation opts))
               (and (= :jina provider-id) (:max-doc-length opts))
               (assoc :max_doc_length (:max-doc-length opts))
               (and (= :jina provider-id) (contains? opts :return-embeddings))
               (assoc :return_embeddings (:return-embeddings opts)))
        extra (:extra_body opts)
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (rerank-url profile)
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- document->text [doc]
  (cond
    (string? doc) doc
    (map? doc) (or (:text doc) (str doc))
    :else nil))

(defn- result->canonical [r]
  (let [document (document->text (:document r))]
    (cond-> {:rerank/index (:index r)
             :rerank/score (double (or (:relevance_score r) 0.0))}
      document (assoc :rerank/document document))))

(defn parse-rerank-response-cohere-shape
  [profile raw]
  (let [provider-id (:profile/id profile)
        results (mapv result->canonical (:results raw))
        usage (case provider-id
                :cohere (normalize-cohere-rerank-usage raw)
                :jina (normalize-jina-rerank-usage raw)
                nil)]
    (cond-> {:rerank/provider provider-id
             ;; Cohere /rerank doesn't echo the model in the response;
             ;; leave nil here and let llm.sdk.rerank/rerank fill it
             ;; in from the request. Jina does echo :model, so the
             ;; raw value is forwarded when present.
             :rerank/model (:model raw)
             :rerank/results results
             :rerank/raw raw}
      (:id raw) (assoc :rerank/id (:id raw))
      usage (assoc :response/usage usage))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-rerank-error-cohere-shape
  [profile status body]
  (errors/classify-error (Exception. (str (:profile/id profile)
                                          " rerank API error"))
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record — used by both :cohere and :jina
;; ---------------------------------------------------------------------------

(defrecord CohereShapeRerankTransport []
  rt/RerankTransport
  (build-rerank-request [_ profile request]
    (build-rerank-request-cohere-shape profile request))
  (parse-rerank-response [_ profile raw]
    (parse-rerank-response-cohere-shape profile raw))
  (parse-rerank-error [_ profile status body]
    (parse-rerank-error-cohere-shape profile status body)))

(defn make-transport [] (->CohereShapeRerankTransport))

;; Attach to :cohere and :jina (both share the wire shape).
(doseq [pid [:cohere :jina]]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (assoc p :profile/rerank-transport-constructor make-transport))))
