(ns llm.sdk.rerank
  "Driver for rerank requests — the rerank counterpart to sdk/complete,
   sdk/embed, and sdk/moderate.

   Resolves the profile, picks up its
   :profile/rerank-transport-constructor, builds and sends the request,
   returns a canonical RerankResponse. Providers without rerank
   support throw a clear ex-info."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.http :as http]
            [llm.sdk.transport.rerank :as rt]))

(defn rerank
  "Send a canonical RerankRequest, return a RerankResponse.

   Required keys: :rerank/model, :rerank/query, :rerank/documents
   (vector of strings).
   Optional: :rerank/top-n, :rerank/return-documents,
   :rerank/provider-options."
  [provider-id request]
  (let [profile (or (provider/get-provider provider-id)
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        ctor (:profile/rerank-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Rerank not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (rt/build-rerank-request transport profile request)
        resp (http/request req)
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (rt/parse-rerank-error transport profile status body)]
        (throw (ex-info "Provider rerank API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (rt/parse-rerank-response transport profile body))))
