(ns llm.sdk.providers.openai.image
  "OpenAI image generation adapter.

   POST {base}/images/generations. Covers DALL-E 3, DALL-E 2, and
   the gpt-image-1 family. The wire body differs subtly across them
   (gpt-image-1 takes :quality :low|:medium|:high|:auto and returns
   b64_json only; DALL-E 3 takes :quality :standard|:hd and :style
   :vivid|:natural). The adapter passes canonical fields straight
   through — provider-specific values are the caller's responsibility,
   and the same provider-options :extra_body hatch as elsewhere
   covers anything we haven't surfaced."
  (:require [llm.sdk.transport.image :as it]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]
            [llm.sdk.usage :as usage]))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-image-request-openai
  [profile request]
  (let [model (or (:image/model request) "dall-e-3")
        body (cond-> {:model model
                      :prompt (:image/prompt request)}
               (:image/n request)
               (assoc :n (:image/n request))
               (:image/size request)
               (assoc :size (:image/size request))
               (:image/quality request)
               (assoc :quality (name (:image/quality request)))
               (:image/style request)
               (assoc :style (name (:image/style request)))
               (:image/response-format request)
               (assoc :response_format (name (:image/response-format request)))
               (:image/user request)
               (assoc :user (:image/user request)))
        extra (get-in request [:image/provider-options :extra_body])
        body (if (seq extra) (merge body extra) body)]
    {:method :post
     :url (str (:profile/base-url profile) "/images/generations")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- ->image [d]
  (cond-> {}
    (:url d) (assoc :image/url (:url d))
    (:b64_json d) (assoc :image/b64 (:b64_json d))
    (:revised_prompt d) (assoc :image/revised-prompt (:revised_prompt d))))

(defn parse-image-response-openai
  [profile raw]
  (let [images (mapv ->image (:data raw))]
    (cond-> {:image/provider (:profile/id profile)
             :image/model (:model raw)
             :image/images images
             :image/raw raw}
      (:created raw) (assoc :image/created (:created raw))
      (:usage raw) (assoc :response/usage
                          (usage/normalize-openai-usage (:usage raw))))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-image-error-openai
  [profile status body]
  (errors/classify-error (Exception. "OpenAI image API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenAIImageTransport []
  it/ImageTransport
  (build-image-request [_ profile request]
    (build-image-request-openai profile request))
  (parse-image-response [_ profile raw]
    (parse-image-response-openai profile raw))
  (parse-image-error [_ profile status body]
    (parse-image-error-openai profile status body)))

(defn make-transport [] (->OpenAIImageTransport))

;; Attach
(when-let [p (provider/get-provider :openai)]
  (provider/register-provider
   (-> p
       (assoc :profile/image-transport-constructor make-transport)
       (update :profile/capabilities (fnil conj #{}) :image-generation))))
