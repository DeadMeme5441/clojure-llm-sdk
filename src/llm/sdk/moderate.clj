(ns llm.sdk.moderate
  "Driver for moderation requests — the moderate counterpart to
   llm.sdk/complete and llm.sdk/embed.

   Resolves the provider profile, picks up its
   :profile/moderation-transport-constructor, builds the request, and
   returns a canonical ModerationResponse.

   Providers without a moderation transport throw ex-info rather
   than NullPointer."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]
            [llm.sdk.errors :as errors]
            [llm.sdk.transport.moderate :as mt]))

(defn moderate
  "Send a canonical ModerationRequest and return a ModerationResponse.

   :moderation/inputs is a vector of either plain strings (for text-
   only moderation) or {:type :text :text \"...\"} / {:type :image_url
   :image_url \"https://...\"} maps. The OpenAI omni-moderation models
   accept the multi-modal shape."
  [provider-id request & {:keys [config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (schema/validate-moderation-request request)
            (throw (ex-info "Invalid llm.sdk moderation request"
                            {:error/type :schema/invalid-moderation-request
                             :schema/explain (schema/explain-moderation-request request)})))
        ctor (:profile/moderation-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Moderation not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (mt/build-moderation-request transport profile request)
        req (provider/apply-http-options profile req)
        resp (try
               (http/request req)
               (catch Exception e
                 (throw (ex-info "Provider moderation transport error"
                                 {:error (errors/classify-error e :provider provider-id)
                                  :provider provider-id}
                                 e))))
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (mt/parse-moderation-error transport profile status body)]
        (throw (ex-info "Provider moderation API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (mt/parse-moderation-response transport profile body))))
