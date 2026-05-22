(ns llm.sdk.providers.bedrock-image
  "Bedrock image-generation adapter (Titan Image Generator + Stability
   SD3 / SDXL). All use bedrock-runtime /model/{id}/invoke with SigV4.
   Each model has a different body shape:

     amazon.titan-image-generator-v1 / -v2:0
       {:taskType \"TEXT_IMAGE\"
        :textToImageParams {:text \"...\"}
        :imageGenerationConfig {:numberOfImages N :width W :height H :cfgScale 8 :seed 0}}
       response {:images [\"b64\", ...]}

     stability.stable-diffusion-xl-v1
       {:text_prompts [{:text \"...\" :weight 1.0}]
        :cfg_scale N :seed N :steps 30}
       response {:artifacts [{:base64 \"...\"}]}

   We dispatch on a substring match against the model id and route to
   the matching builder/parser pair."
  (:require [clojure.string :as str]
            [llm.sdk.transport.image :as it]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]
            [llm.sdk.providers.bedrock :as bedrock]))

(defn- aws-region []
  (or (System/getenv "AWS_REGION")
      (System/getenv "AWS_DEFAULT_REGION")
      "us-east-1"))

(defn- bedrock-base-url [] (str "https://bedrock-runtime." (aws-region) ".amazonaws.com"))

(defn- titan? [model] (str/starts-with? (str model) "amazon.titan-image"))
(defn- stability? [model] (str/starts-with? (str model) "stability."))

;; ---------------------------------------------------------------------------
;; Body shapes per model family
;; ---------------------------------------------------------------------------

(defn- parse-size [size]
  (when (string? size)
    (let [parts (str/split size #"x")]
      (when (= 2 (count parts))
        (mapv #(Integer/parseInt %) parts)))))

(defn- titan-body [request]
  (let [[w h] (or (parse-size (:image/size request)) [1024 1024])
        opts (get-in request [:image/provider-options :bedrock] {})]
    {:taskType "TEXT_IMAGE"
     :textToImageParams {:text (:image/prompt request)}
     :imageGenerationConfig
     (cond-> {:numberOfImages (or (:image/n request) 1)
              :width w
              :height h
              :cfgScale (or (:cfg-scale opts) 8.0)}
       (:seed opts) (assoc :seed (:seed opts))
       (:image/quality request) (assoc :quality (name (:image/quality request))))}))

(defn- stability-body [request]
  (let [[w h] (or (parse-size (:image/size request)) [1024 1024])
        opts (get-in request [:image/provider-options :bedrock] {})]
    (cond-> {:text_prompts [{:text (:image/prompt request) :weight 1.0}]
             :width w
             :height h
             :cfg_scale (or (:cfg-scale opts) 7.0)
             :steps (or (:steps opts) 30)}
      (:seed opts) (assoc :seed (:seed opts))
      (:image/n request) (assoc :samples (:image/n request)))))

(defn build-image-request-bedrock
  [_profile request]
  (let [canonical (or (:image/model request) "amazon.titan-image-generator-v2:0")
        model (bedrock/resolve-model-id canonical)
        body (cond
               (titan? model) (titan-body request)
               (stability? model) (stability-body request)
               :else (titan-body request))]
    {:method :post
     :url (str (bedrock-base-url) "/model/" model "/invoke")
     :headers {"Content-Type" "application/json"
               "Accept" "application/json"}
     :llm.sdk.providers.bedrock/aws-service "bedrock"
     :llm.sdk.providers.bedrock/aws-region (aws-region)
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- titan-parse [raw]
  (mapv (fn [b64] {:image/b64 b64
                   :image/mime-type "image/png"})
        (:images raw)))

(defn- stability-parse [raw]
  (mapv (fn [a] (cond-> {}
                  (:base64 a) (assoc :image/b64 (:base64 a))
                  (:finishReason a) (assoc :image/finish-reason (:finishReason a))))
        (:artifacts raw)))

(defn parse-image-response-bedrock
  [_profile raw]
  {:image/provider :bedrock
   :image/model nil
   :image/images (cond
                   (:artifacts raw) (stability-parse raw)
                   (:images raw) (titan-parse raw)
                   :else [])
   :image/raw raw})

(defn parse-image-error-bedrock
  [_profile status body]
  (errors/classify-error (Exception. "Bedrock image API error")
                         :status status
                         :body body
                         :provider :bedrock))

(defrecord BedrockImageTransport []
  it/ImageTransport
  (build-image-request [_ profile request]
    (build-image-request-bedrock profile request))
  (parse-image-response [_ profile raw]
    (parse-image-response-bedrock profile raw))
  (parse-image-error [_ profile status body]
    (parse-image-error-bedrock profile status body)))

(defn make-transport [] (->BedrockImageTransport))

;; Attach to :bedrock alongside its existing chat transport so callers
;; can do (sdk/complete :bedrock ...) AND (sdk/generate-image :bedrock ...).
(when-let [p (provider/get-provider :bedrock)]
  (provider/register-provider
   (-> p
       (assoc :profile/image-transport-constructor make-transport)
       (update :profile/capabilities (fnil conj #{}) :image-generation))))
