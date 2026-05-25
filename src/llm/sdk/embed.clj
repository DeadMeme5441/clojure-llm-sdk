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
            [llm.sdk.transport.embed :as et]))

(defn embed
  "Send a canonical EmbedRequest and return a canonical EmbedResponse.

   The request map must contain :embed/model and :embed/inputs (a
   vector of strings). Optional fields: :embed/dimensions,
   :embed/encoding-format (:float or :base64), :embed/user,
   :embed/provider-options."
  [provider-id request]
  (let [profile (or (provider/get-provider provider-id)
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
        resp (http/request req)
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
      (let [parsed (et/parse-embed-response transport profile body)]
        (update parsed :embed/model #(or % (:embed/model request)))))))
