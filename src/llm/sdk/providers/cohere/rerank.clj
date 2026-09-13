(ns llm.sdk.providers.cohere.rerank
  "Cohere /rerank transport. Jina uses the same core wire shape while also
   supporting structured returned documents and optional returned embeddings.

   Cohere additionally returns :meta.billed_units.search_units for
   usage; Jina returns :usage {total_tokens}. Both are surfaced
   through the canonical :response/usage where possible."
  (:require [clojure.string :as str]
            [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.rerank :as rt]))

;; ---------------------------------------------------------------------------
;; Usage normalization
;; ---------------------------------------------------------------------------

(defn- ->int [value]
  (when (and (number? value)
             (not (neg? value))
             (Double/isFinite (double value)))
    (int value)))

(defn- present-int [m k]
  (->int (get m k)))

(defn- normalize-cohere-rerank-usage [raw]
  (when-let [meta (:meta raw)]
    (let [tokens (:tokens meta)
          billed (:billed_units meta)
          input (present-int tokens :input_tokens)
          output (present-int tokens :output_tokens)
          search-units (present-int billed :search_units)]
      (when (or (some? input) (some? output) (some? search-units))
        (cond-> {:usage/request-count 1
                 :usage/provider-raw meta}
          (some? input) (assoc :usage/input-tokens input)
          (some? output) (assoc :usage/output-tokens output)
          (and (some? input) (some? output))
          (assoc :usage/total-tokens (+ input output))
          (some? search-units)
          (assoc :usage/search-units search-units))))))

(defn- normalize-jina-rerank-usage [raw]
  (when-some [total (->int (get-in raw [:usage :total_tokens]))]
    {:usage/input-tokens total
     :usage/output-tokens 0
     :usage/total-tokens total
     :usage/request-count 1
     :usage/provider-raw (:usage raw)}))

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
(defn- validate-documents! [provider-id documents]
  (when (= :cohere provider-id)
    (doseq [[index document] (map-indexed vector documents)]
      (when-not (string? document)
        (throw
         (ex-info
          "Cohere rerank documents must be strings"
          {:error/type :request/invalid-rerank-document
           :provider provider-id
           :document/index index
           :document/value document}))))))

(defn build-rerank-request-cohere-shape
  [profile request]
  (let [provider-id (:profile/id profile)
        documents (:rerank/documents request)
        opts (:rerank/provider-options request)
        body (cond-> {:model (:rerank/model request)
                      :query (:rerank/query request)
                      :documents documents}
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
               (and (= :jina provider-id) (:max-doc-length opts))
               (assoc :max_doc_length (:max-doc-length opts))
               (and (= :jina provider-id) (contains? opts :return-embeddings))
               (assoc :return_embeddings (:return-embeddings opts)))
        extra (:extra_body opts)
        body (transport/merge-extra-body provider-id body extra)
        _ (validate-documents! provider-id (:documents body))]
    {:method :post
     :url (rerank-url profile)
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- result-document [provider-id doc]
  (cond
    (string? doc) doc
    (and (= :cohere provider-id) (map? doc)) (or (:text doc) (str doc))
    (and (= :jina provider-id) (map? doc)) doc
    :else nil))

(defn- numeric-embedding [embedding]
  (when (and (sequential? embedding)
             (every? number? embedding))
    (vec embedding)))
(defn- required-score [provider-id result]
  (let [score (:relevance_score result)]
    (when-not (number? score)
      (throw
       (ex-info
        "Rerank response result is missing a numeric relevance score"
        {:error/type :response/missing-rerank-score
         :provider provider-id
         :result/index (:index result)})))
    (double score)))

(defn- result->canonical [provider-id r]
  (let [document (result-document provider-id (:document r))
        embedding (when (= :jina provider-id)
                    (numeric-embedding (:embedding r)))]
    (cond-> {:rerank/index (:index r)
             :rerank/score (required-score provider-id r)}
      document (assoc :rerank/document document)
      embedding (assoc :rerank/embedding embedding))))

(defn parse-rerank-response-cohere-shape
  [profile raw]
  (let [provider-id (:profile/id profile)
        results (mapv #(result->canonical provider-id %) (:results raw))
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

