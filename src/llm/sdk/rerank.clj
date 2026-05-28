(ns llm.sdk.rerank
  "Driver for rerank requests — the rerank counterpart to sdk/complete,
   sdk/embed, and sdk/moderate.

   Resolves the profile, picks up its
   :profile/rerank-transport-constructor, builds and sends the request,
   returns a canonical RerankResponse. Providers without rerank
   support throw a clear ex-info."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]
            [llm.sdk.errors :as errors]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.transport.rerank :as rt]))

(defn rerank
  "Send a canonical RerankRequest, return a RerankResponse.

   Required keys: :rerank/model, :rerank/query, :rerank/documents
   (vector of strings).
   Optional: :rerank/top-n, :rerank/return-documents,
   :rerank/provider-options."
  [provider-id request & {:keys [config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (schema/validate-rerank-request request)
            (throw (ex-info "Invalid llm.sdk rerank request"
                            {:error/type :schema/invalid-rerank-request
                             :schema/explain (schema/explain-rerank-request request)})))
        ctor (:profile/rerank-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Rerank not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (rt/build-rerank-request transport profile request)
        req (provider/apply-http-options profile req)
        req (aws-sigv4/maybe-sign profile req)
        resp (try
               (http/request req)
               (catch Exception e
                 (throw (ex-info "Provider rerank transport error"
                                 {:error (errors/classify-error e :provider provider-id)
                                  :provider provider-id}
                                 e))))
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (rt/parse-rerank-error transport profile status body)]
        (throw (ex-info "Provider rerank API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      ;; Adapters whose servers don't echo the model leave
      ;; :rerank/model nil — fall back to the caller-supplied id so
      ;; the surface always carries the model that was actually used.
      (let [parsed (rt/parse-rerank-response transport profile body)
            parsed (update parsed :rerank/model #(or % (:rerank/model request)))
            usage (:response/usage parsed)
            cost (pricing/canonical-cost provider-id (:rerank/model parsed) usage)]
        (cond-> parsed
          cost (assoc :response/cost cost))))))
