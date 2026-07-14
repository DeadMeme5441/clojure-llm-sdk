(ns llm.sdk.providers.openrouter.image
  "OpenRouter image generation transport for the native POST /images API."
  (:require [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.image :as it]
            [llm.sdk.usage :as usage]))

(defn- openrouter-headers []
  {"HTTP-Referer" (or (System/getenv "OPENROUTER_HTTP_REFERER")
                      "https://github.com/DeadMeme5441/clojure-llm-sdk")
   "X-OpenRouter-Title" (or (System/getenv "OPENROUTER_APP_NAME")
                            "clojure-llm-sdk")})

(defn build-image-request-openrouter
  [profile request]
  (let [quality (case (:image/quality request)
                  :standard "auto"
                  "standard" "auto"
                  :hd "high"
                  "hd" "high"
                  :low "low"
                  "low" "low"
                  :medium "medium"
                  "medium" "medium"
                  :high "high"
                  "high" "high"
                  :auto "auto"
                  "auto" "auto"
                  nil)
        body (cond-> {:model (:image/model request)
                      :prompt (:image/prompt request)}
               (:image/n request)
               (assoc :n (:image/n request))
               (:image/size request)
               (assoc :size (:image/size request))
               quality
               (assoc :quality quality))
        extra (get-in request [:image/provider-options :extra_body])
        body (merge body extra)]
    {:method :post
     :url (str (:profile/base-url profile) "/images")
     :headers (merge (provider/default-headers
                      profile (provider/resolve-auth-token profile))
                     (openrouter-headers))
     :body body}))

(defn- response-image [image]
  (when-let [b64 (:b64_json image)]
    {:image/b64 b64}))

(defn parse-image-response-openrouter
  [_profile raw]
  (let [images (into [] (keep response-image) (:data raw))
        usage-raw (:usage raw)
        usage-cost (:cost usage-raw)]
    (cond-> {:image/provider :openrouter
             :image/images images
             :image/raw raw}
      (:model raw) (assoc :image/model (:model raw))
      (:created raw) (assoc :image/created (:created raw))
      usage-raw
      (assoc :response/usage (usage/normalize-openai-usage usage-raw))
      (number? usage-cost)
      (assoc :response/cost
             {:cost/usd usage-cost
              :cost/estimated? false
              :cost/pricing-source :openrouter-reported
              :cost/source-url
              "https://openrouter.ai/docs/api/api-reference/images/generate-an-image"
              :cost/breakdown
              (select-keys usage-raw
                           [:cost :cost_details :is_byok
                            :server_tool_use])}))))

(defn parse-image-error-openrouter
  [_profile status body]
  (errors/classify-error (Exception. "OpenRouter image API error")
                         :status status
                         :body body
                         :provider :openrouter))

(defrecord OpenRouterImageTransport []
  it/ImageTransport
  (build-image-request [_ profile request]
    (build-image-request-openrouter profile request))
  (parse-image-response [_ profile raw]
    (parse-image-response-openrouter profile raw))
  (parse-image-error [_ profile status body]
    (parse-image-error-openrouter profile status body)))

(defn make-transport [] (->OpenRouterImageTransport))

(when-let [p (provider/get-provider :openrouter)]
  (provider/register-provider
   (-> p
       (assoc :profile/image-transport-constructor make-transport)
       (update :profile/capabilities (fnil conj #{}) :image-generation))))
