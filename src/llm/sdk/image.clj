(ns llm.sdk.image
  "Driver for image generation requests — the image counterpart to
   sdk/complete, sdk/embed, sdk/moderate, and sdk/rerank.

   Resolves the profile, picks up its
   :profile/image-transport-constructor, builds and sends the request,
   returns a canonical ImageGenResponse. Providers without image
   support throw a clear ex-info."
  (:require [clojure.string :as str]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.operation :as operation]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.image :as it]))

(defn- parse-size [s]
  (when-let [[_ w h] (and (string? s) (re-matches #"(\d+)x(\d+)" s))]
    {:width (Long/parseLong w)
     :height (Long/parseLong h)}))

(defn- stamp-image-cost [provider-id request parsed]
  (if (contains? parsed :response/cost)
    parsed
    (let [model (:image/model parsed)
          usage (:response/usage parsed)
          pricing (pricing/get-pricing provider-id model)
          dims (parse-size (:image/size request))
          n-images (or (some-> (:image/images parsed) count)
                       (:image/n request)
                       1)
          cost
          (if usage
            (pricing/canonical-cost
             provider-id model usage
             {:input-modality :text :output-modality :image})
            (let [result (pricing/image-cost
                          (merge {:n-images n-images} dims)
                          pricing)]
              (pricing/cost-result->canonical
               result
               pricing
               (cond-> {:images n-images}
                 (:width dims) (assoc :width (:width dims))
                 (:height dims) (assoc :height (:height dims))))))]
      (assoc parsed :response/cost cost))))

(defn- usable-image? [image]
  (and (map? image)
       (or (and (string? (:image/url image))
                (not (str/blank? (:image/url image))))
           (and (string? (:image/b64 image))
                (not (str/blank? (:image/b64 image)))))))

(defn- validate-provider-response! [provider-id status body parsed]
  (when-not (and (map? parsed)
                 (seq (:image/images parsed))
                 (every? usable-image? (:image/images parsed)))
    (throw
     (ex-info "Provider returned an invalid or empty image response"
              {:provider provider-id
               :status status
               :error/type :provider/invalid-image-response
               :response parsed
               :body body}))))

(defn generate-image
  "Send a canonical ImageGenRequest, return an ImageGenResponse.

   Required: :image/prompt. Optional: :image/model, :image/n,
   :image/size, :image/quality, :image/style,
   :image/response-format (:url or :b64_json), :image/user,
   :image/provider-options.

   The canonical response includes :image/images — a vector of
   {:image/url? :image/b64? :image/revised-prompt?}."
  [provider-id request & {:keys [config]}]
  (let [parsed
        (operation/run
         {:provider-id provider-id
          :request request
          :config config
          :validate-request schema/validate-image-gen-request
          :explain-request schema/explain-image-gen-request
          :invalid-error-type :schema/invalid-image-request
          :invalid-message "Invalid llm.sdk image generation request"
          :constructor-key :profile/image-transport-constructor
          :unsupported-message "Image generation not supported by provider"
          :build-request it/build-image-request
          :sign-request aws-sigv4/maybe-sign
          :parse-response
          (fn [transport profile response]
            (let [parsed (it/parse-image-response
                          transport profile (:body response))
                  model (or (:image/model parsed) (:image/model request))
                  parsed (cond-> parsed model (assoc :image/model model))]
              (validate-provider-response!
               provider-id (:status response) (:body response) parsed)
              parsed))
          :parse-error it/parse-image-error
          :transport-error-message "Provider image transport error"
          :api-error-message "Provider image API error"})]
    (stamp-image-cost provider-id request parsed)))
