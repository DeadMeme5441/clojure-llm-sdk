(ns llm.sdk.providers.openai.image
  "OpenAI image generation adapter.

   POST {base}/images/generations. A model is required because the former
   implicit DALL-E models were retired on 2026-05-12. Explicit caller-selected
   DALL-E 2/3 requests retain their documented wire contracts; GPT Image
   requests use the current GPT-specific contract."
  (:require [clojure.string :as str]
            [llm.sdk.transport.image :as it]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]
            [llm.sdk.sse :as sse]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------


(defn- gpt-image-2-model? [model]
  (str/starts-with? model "gpt-image-2"))

(defn- gpt-image-2-5-model? [model]
  (str/starts-with? model "gpt-image-2.5-"))

(def ^:private gpt-only-options
  [:background :moderation :output_format :output_compression
   :partial_images :stream])

(defn- invalid-option!
  [model option value message]
  (throw (ex-info message
                  {:provider :openai
                   :model model
                   :option option
                   :value value
                   :error/type :provider/unsupported-option})))

(defn- wire-enum [value]
  (if (keyword? value) (name value) value))

(defn- normalize-enums [body]
  (reduce (fn [result option]
            (if (contains? result option)
              (update result option wire-enum)
              result))
          body
          [:quality :style :response_format
           :background :moderation :output_format]))

(defn- validate-choice!
  [model body option choices]
  (when-let [value (get body option)]
    (when-not (contains? choices value)
      (invalid-option!
       model option value
       (str "OpenAI image " (name option) " is incompatible with model "
            model)))))

(defn- valid-flexible-gpt-size? [size]
  (or (= "auto" size)
      (when-let [[_ width-text height-text]
                 (and (string? size)
                      (re-matches #"(\d+)x(\d+)" size))]
        (let [width (Long/parseLong width-text)
              height (Long/parseLong height-text)
              ratio (/ (double width) height)]
          (and (pos? width)
               (pos? height)
               (zero? (mod width 16))
               (zero? (mod height 16))
               (<= (/ 1.0 3.0) ratio 3.0)
               (<= (max width height) 3840)
               (<= (* width height) (* 3840 2160)))))))

(defn- validate-image-body!
  [model family body]
  (when-let [n (:n body)]
    (when-not (and (int? n) (<= 1 n 10))
      (invalid-option! model :n n
                       "OpenAI image n must be an integer from 1 through 10"))
    (when (and (= :dall-e-3 family) (not= 1 n))
      (invalid-option! model :n n "DALL-E 3 only supports n=1")))
  (case family
    :dall-e-2
    (do
      (validate-choice! model body :size
                        #{"256x256" "512x512" "1024x1024"})
      (validate-choice! model body :quality #{"standard"})
      (validate-choice! model body :response_format #{"url" "b64_json"})
      (when (contains? body :style)
        (invalid-option! model :style (:style body)
                         "DALL-E 2 does not support style"))
      (doseq [option gpt-only-options
              :when (contains? body option)]
        (invalid-option! model option (get body option)
                         (str "DALL-E 2 does not support " (name option)))))

    :dall-e-3
    (do
      (validate-choice! model body :size
                        #{"1024x1024" "1792x1024" "1024x1792"})
      (validate-choice! model body :quality #{"standard" "hd"})
      (validate-choice! model body :style #{"vivid" "natural"})
      (validate-choice! model body :response_format #{"url" "b64_json"})
      (doseq [option gpt-only-options
              :when (contains? body option)]
        (invalid-option! model option (get body option)
                         (str "DALL-E 3 does not support " (name option)))))

    :gpt-image
    (do
      (when (contains? body :style)
        (invalid-option! model :style (:style body)
                         "GPT Image models do not support style"))
      (when (contains? body :response_format)
        (invalid-option! model :response_format (:response_format body)
                         "GPT Image models always return base64 images"))
      (when-let [size (:size body)]
        (let [valid? (if (gpt-image-2-model? model)
                       (valid-flexible-gpt-size? size)
                       (contains? #{"auto" "1024x1024"
                                    "1536x1024" "1024x1536"}
                                  size))]
          (when-not valid?
            (invalid-option! model :size size
                             (str "OpenAI image size is incompatible with model "
                                  model)))))
      (validate-choice!
       model body :quality
       (if (gpt-image-2-5-model? model)
         #{"auto" "low" "medium" "high" "xhigh" "max"}
         #{"auto" "low" "medium" "high"}))
      (validate-choice! model body :background
                        #{"auto" "opaque" "transparent"})
      (validate-choice! model body :moderation #{"auto" "low"})
      (validate-choice! model body :output_format #{"png" "jpeg" "webp"})
      (when-let [compression (:output_compression body)]
        (when-not (and (int? compression) (<= 0 compression 100))
          (invalid-option!
           model :output_compression compression
           "OpenAI image output_compression must be an integer from 0 through 100"))
        (when-not (contains? #{"jpeg" "webp"} (:output_format body))
          (invalid-option!
           model :output_compression compression
           "OpenAI image output_compression requires jpeg or webp output_format")))
      (when (= "transparent" (:background body))
        (when-not (contains? #{nil "png" "webp"} (:output_format body))
          (invalid-option!
           model :background "transparent"
           "OpenAI transparent images require png or webp output_format")))
      (when-let [partial-images (:partial_images body)]
        (when-not (and (int? partial-images) (<= 0 partial-images 3))
          (invalid-option!
           model :partial_images partial-images
           "OpenAI image partial_images must be an integer from 0 through 3"))
        (when-not (true? (:stream body))
          (invalid-option!
           model :partial_images partial-images
           "OpenAI image partial_images requires stream=true")))
      (when (and (contains? body :stream)
                 (not (boolean? (:stream body))))
        (invalid-option! model :stream (:stream body)
                         "OpenAI image stream must be boolean")))))

(defn- model-family [model]
  (cond
    (= "dall-e-2" model) :dall-e-2
    (= "dall-e-3" model) :dall-e-3
    (str/starts-with? model "gpt-image-") :gpt-image
    :else nil))

(defn build-image-request-openai
  [profile request]
  (let [model (:image/model request)
        _ (when (str/blank? model)
            (throw
             (ex-info
              (str "OpenAI image generation requires an explicit :image/model; "
                   "DALL-E 2 and DALL-E 3 were retired on May 12, 2026")
              {:provider :openai
               :error/type :request/missing-model})))
        family (model-family model)
        _ (when-not family
            (throw
             (ex-info "Unsupported OpenAI image model family"
                      {:provider :openai
                       :model model
                       :supported-model-families
                       ["dall-e-2" "dall-e-3" "gpt-image-*"]
                       :error/type :request/unsupported-model})))
        canonical-body
        (cond-> {:model model
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
        body (-> (if (seq extra)
                   (merge canonical-body (dissoc extra :model :prompt))
                   canonical-body)
                 normalize-enums)
        _ (validate-image-body! model family body)]
    {:method :post
     :url (str (:profile/base-url profile) "/images/generations")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(def ^:private output-format->mime
  {"png" "image/png"
   "jpeg" "image/jpeg"
   "webp" "image/webp"})

(defn- ->image [d mime-type]
  (cond-> {}
    (:url d) (assoc :image/url (:url d))
    (:b64_json d) (assoc :image/b64 (:b64_json d))
    (and (:b64_json d) mime-type) (assoc :image/mime-type mime-type)
    (:revised_prompt d) (assoc :image/revised-prompt (:revised_prompt d))))

(defn parse-image-response-openai
  [profile raw]
  (let [mime-type (get output-format->mime
                       (wire-enum (:output_format raw)))
        images (->> (:data raw)
                    (map #(->image % mime-type))
                    (filter #(or (seq (:image/url %))
                                 (seq (:image/b64 %))))
                    vec)
        parsed
        (cond-> {:image/provider (:profile/id profile)
                 :image/model (:model raw)
                 :image/images images
                 :image/raw raw}
          (:created raw) (assoc :image/created (:created raw))
          (:usage raw) (assoc :response/usage
                              (usage/normalize-openai-usage (:usage raw))))]
    (when-not (seq images)
      (throw
       (ex-info "OpenAI returned no usable generated images"
                {:provider (:profile/id profile)
                 :error/type :provider/invalid-image-response
                 :provider/error (:error raw)
                 :response parsed
                 :body raw})))
    parsed))

(defn parse-image-stream-event-openai
  "Normalize current image_generation.partial_image/completed SSE events.
   ImageTransport is request/response-only, so this function is also exposed
   directly for callers opting into `:stream true` via provider-options."
  [profile line]
  (when-let [data (sse/parse-json-data line)]
    (case (:type data)
      "image_generation.partial_image"
      (stream/provider-state-event
       (:profile/id profile)
       {:image-generation/partial data})

      "image_generation.completed"
      (cond-> [(stream/provider-state-event
                (:profile/id profile)
                {:image-generation/completed
                 (dissoc data :usage)})]
        (:usage data)
        (conj (stream/usage-event
               (usage/normalize-openai-usage (:usage data))))
        true
        (conj (stream/end-event :finish-reason :stop)))

      nil)))

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
