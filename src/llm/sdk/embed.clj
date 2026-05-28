(ns llm.sdk.embed
  "Driver for embedding requests — the embed counterpart to
   llm.sdk/complete. Resolves the provider profile, picks up its
   :profile/embed-transport-constructor, builds the request, sends it,
   and returns a canonical EmbedResponse.

   Providers without an embed transport throw ex-info on call rather
   than returning nil — surfacing missing capability at the call site
   is friendlier than letting a downstream NullPointer explode."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]
            [llm.sdk.errors :as errors]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.transport.embed :as et]))

(defn embed
  "Send a canonical EmbedRequest and return a canonical EmbedResponse.

   The request map must contain :embed/model and :embed/inputs (a
   vector of strings). Optional fields: :embed/dimensions,
   :embed/encoding-format (:float or :base64), :embed/user,
   :embed/provider-options."
  [provider-id request & {:keys [config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (schema/validate-embed-request request)
            (throw (ex-info "Invalid llm.sdk embed request"
                            {:error/type :schema/invalid-embed-request
                             :schema/explain (schema/explain-embed-request request)})))
        ctor (:profile/embed-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Embedding not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (et/build-embed-request transport profile request)
        req (provider/apply-http-options profile req)
        resp (try
               (http/request req)
               (catch Exception e
                 (throw (ex-info "Provider embed transport error"
                                 {:error (errors/classify-error e :provider provider-id)
                                  :provider provider-id}
                                 e))))
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (et/parse-embed-error transport profile status body)]
        (throw (ex-info "Provider embed API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      ;; Adapters that don't echo the model in the response leave
      ;; :embed/model nil — fall back to what the caller asked for so
      ;; the surface always carries a useful model id.
      (let [parsed (et/parse-embed-response transport profile body)
            parsed (update parsed :embed/model #(or % (:embed/model request)))
            usage (:response/usage parsed)
            cost (pricing/canonical-cost provider-id (:embed/model parsed) usage)]
        (cond-> parsed
          cost (assoc :response/cost cost))))))
