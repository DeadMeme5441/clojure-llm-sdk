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
            [llm.sdk.errors :as errors]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.transport.image :as it]))

(defn- parse-size [s]
  (when-let [[_ w h] (and (string? s) (re-matches #"(\d+)x(\d+)" s))]
    {:width (Long/parseLong w)
     :height (Long/parseLong h)}))

(defn- stamp-image-cost [provider-id request parsed]
  (let [model (:image/model parsed)
        usage (:response/usage parsed)
        pricing (pricing/get-pricing provider-id model)
        dims (parse-size (:image/size request))
        n-images (or (some-> (:image/images parsed) count)
                     (:image/n request)
                     1)
        cost (if usage
               (pricing/canonical-cost provider-id model usage)
               (let [result (pricing/image-cost (merge {:n-images n-images} dims)
                                                pricing)]
                 (pricing/cost-result->canonical
                  result
                  pricing
                  (cond-> {:images n-images}
                    (:width dims) (assoc :width (:width dims))
                    (:height dims) (assoc :height (:height dims))))))]
    (assoc parsed :response/cost cost)))

(defn generate-image
  "Send a canonical ImageGenRequest, return an ImageGenResponse.

   Required: :image/prompt. Optional: :image/model, :image/n,
   :image/size, :image/quality, :image/style,
   :image/response-format (:url or :b64_json), :image/user,
   :image/provider-options.

   The canonical response includes :image/images — a vector of
   {:image/url? :image/b64? :image/revised-prompt?}."
  [provider-id request & {:keys [config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
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
        req (provider/apply-http-options profile req)
        req (aws-sigv4/maybe-sign profile req)
        resp (try
               (http/request req)
               (catch Exception e
                 (throw (ex-info "Provider image transport error"
                                 {:error (errors/classify-error e :provider provider-id)
                                  :provider provider-id}
                                 e))))
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (it/parse-image-error transport profile status body)]
        (throw (ex-info "Provider image API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (let [parsed (it/parse-image-response transport profile body)
            parsed (assoc parsed :image/model
                          (or (:image/model parsed)
                              (:image/model request)
                              "openai-image"))]
        (stamp-image-cost provider-id request parsed)))))
