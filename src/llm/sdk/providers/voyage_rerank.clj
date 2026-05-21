(ns llm.sdk.providers.voyage-rerank
  "Voyage /rerank transport. Differs from Cohere/Jina on field names
   only:
     request : top_k  (not top_n)
     response: data   (not results)
   Document representation is also slightly different — Voyage returns
   :document as a plain string when :return_documents=true.

   Voyage usage shape: {:usage {:total_tokens N}}."
  (:require [llm.sdk.transport.rerank :as rt]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]))

(defn- ->int [x] (cond (int? x) x (number? x) (int x) :else 0))

(defn build-rerank-request-voyage
  [profile request]
  (let [body (cond-> {:model (:rerank/model request)
                      :query (:rerank/query request)
                      :documents (:rerank/documents request)}
               (:rerank/top-n request)
               (assoc :top_k (:rerank/top-n request))
               (some? (:rerank/return-documents request))
               (assoc :return_documents
                      (boolean (:rerank/return-documents request))))
        extra (get-in request [:rerank/provider-options :extra_body])
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (str (:profile/base-url profile) "/rerank")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

(defn- result->canonical [r]
  (let [doc (:document r)]
    (cond-> {:rerank/index (:index r)
             :rerank/score (double (or (:relevance_score r) 0.0))}
      (some? doc) (assoc :rerank/document
                         (cond
                           (string? doc) doc
                           (map? doc) (or (:text doc) (str doc))
                           :else nil)))))

(defn parse-rerank-response-voyage
  [profile raw]
  (let [results (mapv result->canonical (:data raw))
        total (->int (get-in raw [:usage :total_tokens]))]
    (cond-> {:rerank/provider :voyage
             :rerank/model (or (:model raw) "voyage-rerank")
             :rerank/results results
             :rerank/raw raw}
      (:id raw) (assoc :rerank/id (:id raw))
      (pos? total) (assoc :response/usage
                          {:usage/input-tokens total
                           :usage/output-tokens 0
                           :usage/total-tokens total
                           :usage/request-count 1
                           :usage/provider-raw (:usage raw)}))))

(defn parse-rerank-error-voyage
  [profile status body]
  (errors/classify-error (Exception. "Voyage rerank API error")
                         :status status
                         :body body
                         :provider :voyage))

(defrecord VoyageRerankTransport []
  rt/RerankTransport
  (build-rerank-request [_ profile request]
    (build-rerank-request-voyage profile request))
  (parse-rerank-response [_ profile raw]
    (parse-rerank-response-voyage profile raw))
  (parse-rerank-error [_ profile status body]
    (parse-rerank-error-voyage profile status body)))

(defn make-transport [] (->VoyageRerankTransport))

(when-let [p (provider/get-provider :voyage)]
  (provider/register-provider
   (assoc p :profile/rerank-transport-constructor make-transport)))
