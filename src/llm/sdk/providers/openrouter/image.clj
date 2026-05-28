(ns llm.sdk.providers.openrouter.image
  "OpenRouter image generation transport.

   OpenRouter image models generate images through chat completions, not
   OpenAI's /images/generations endpoint. This adapter mirrors that wire
   shape and extracts images from choices[].message.images[].image_url.url."
  (:require [clojure.string :as str]
            [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.image :as it]
            [llm.sdk.usage :as usage]))

(defn- openrouter-headers []
  {"HTTP-Referer" (or (System/getenv "OPENROUTER_HTTP_REFERER")
                      "https://github.com/DeadMeme5441/clojure-llm-sdk")
   "X-Title" (or (System/getenv "OPENROUTER_APP_NAME")
                 "clojure-llm-sdk")})

(defn- aspect-ratio [size]
  (case size
    ("256x256" "512x512" "1024x1024" "auto" nil) "1:1"
    "1536x1024" "3:2"
    "1792x1024" "16:9"
    "1024x1536" "2:3"
    "1024x1792" "9:16"
    "1:1"))

(defn- image-size [quality]
  (case quality
    (:low "low" :standard "standard" :auto "auto" nil) "1K"
    (:medium "medium") "2K"
    (:high "high" :hd "hd") "4K"
    nil))

(defn build-image-request-openrouter
  [profile request]
  (let [image-config (cond-> {}
                       (:image/size request)
                       (assoc :aspect_ratio (aspect-ratio (:image/size request)))
                       (:image/quality request)
                       (assoc :image_size (image-size (:image/quality request))))
        body (cond-> {:model (:image/model request)
                      :messages [{:role "user"
                                  :content (:image/prompt request)}]}
               (:image/n request)
               (assoc :n (:image/n request))
               (seq image-config)
               (assoc :image_config image-config))
        extra (get-in request [:image/provider-options :extra_body])
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (str (:profile/base-url profile) "/chat/completions")
     :headers (merge (provider/default-headers profile
                                               (provider/resolve-auth-token profile))
                     (openrouter-headers))
     :body body}))

(defn- data-url->image [url]
  (let [[prefix b64] (str/split (str url) #"," 2)
        mime (second (re-find #"^data:([^;]+);base64$" prefix))]
    (cond-> {}
      b64 (assoc :image/b64 b64)
      mime (assoc :image/mime-type mime))))

(defn- image-url->image [url]
  (if (str/starts-with? (str url) "data:")
    (data-url->image url)
    {:image/url url}))

(defn- choice-images [choice]
  (->> (get-in choice [:message :images])
       (keep (fn [img]
               (some-> (get-in img [:image_url :url])
                       image-url->image)))))

(defn parse-image-response-openrouter
  [_profile raw]
  (let [images (->> (:choices raw)
                    (mapcat choice-images)
                    vec)]
    (cond-> {:image/provider :openrouter
             :image/model (:model raw)
             :image/images images
             :image/raw raw}
      (:usage raw) (assoc :response/usage
                          (usage/normalize-openai-usage (:usage raw))))))

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
