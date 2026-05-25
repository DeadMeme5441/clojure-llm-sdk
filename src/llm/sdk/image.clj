(ns llm.sdk.image
  "Driver for image generation requests — the image counterpart to
   sdk/complete, sdk/embed, sdk/moderate, and sdk/rerank.

   Resolves the profile, picks up its
   :profile/image-transport-constructor, builds and sends the request,
   returns a canonical ImageGenResponse. Providers without image
   support throw a clear ex-info."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.transport.image :as it]))

(defn generate-image
  "Send a canonical ImageGenRequest, return an ImageGenResponse.

   Required: :image/prompt. Optional: :image/model, :image/n,
   :image/size, :image/quality, :image/style,
   :image/response-format (:url or :b64_json), :image/user,
   :image/provider-options.

   The canonical response includes :image/images — a vector of
   {:image/url? :image/b64? :image/revised-prompt?}."
  [provider-id request]
  (let [profile (or (provider/get-provider provider-id)
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (schema/validate-image-gen-request request)
            (throw (ex-info "Invalid llm.sdk image generation request"
                            {:error/type :schema/invalid-image-request
                             :schema/explain (schema/explain-image-gen-request request)})))
        ctor (:profile/image-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Image generation not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (it/build-image-request transport profile request)
        req (aws-sigv4/maybe-sign profile req)
        resp (http/request req)
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (it/parse-image-error transport profile status body)]
        (throw (ex-info "Provider image API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (it/parse-image-response transport profile body))))
