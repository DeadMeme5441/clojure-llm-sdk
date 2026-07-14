(ns llm.sdk.providers.bedrock.image
  "Bedrock Runtime image-generation adapter.

   Amazon Titan Image Generator and Nova Canvas use the taskType /
   imageGenerationConfig request family. Stability SDXL uses the legacy
   text_prompts/artifacts contract, while Stable Image Core, Ultra, and
   SD3.5 use the prompt/images contract. All models are invoked through
   /model/{modelId}/invoke with SigV4."
  (:require [clojure.string :as str]
            [llm.sdk.transport.image :as it]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]
            [llm.sdk.providers.bedrock.converse :as bedrock]))

(defn- aws-region []
  (or (System/getenv "AWS_REGION")
      (System/getenv "AWS_DEFAULT_REGION")
      "us-east-1"))

(defn- bedrock-base-url [] (str "https://bedrock-runtime." (aws-region) ".amazonaws.com"))

(defn- titan? [model] (str/starts-with? (str model) "amazon.titan-image"))
(defn- nova-canvas? [model] (str/starts-with? (str model) "amazon.nova-canvas"))
(defn- legacy-stability? [model]
  (str/starts-with? (str model) "stability.stable-diffusion-xl"))
(defn- modern-stability? [model]
  (and (str/starts-with? (str model) "stability.")
       (not (legacy-stability? model))))

;; ---------------------------------------------------------------------------
;; Body shapes per model family
;; ---------------------------------------------------------------------------

(defn- parse-size [size]
  (when (string? size)
    (let [parts (str/split size #"x")]
      (when (= 2 (count parts))
        (mapv #(Integer/parseInt %) parts)))))

(def ^:private stability-aspect-ratios
  #{"16:9" "1:1" "21:9" "2:3" "3:2" "4:5" "5:4" "9:16" "9:21"})

(defn- gcd [a b]
  (if (zero? b) a (recur b (mod a b))))

(defn- size->aspect-ratio [size]
  (when-let [[w h] (parse-size size)]
    (let [divisor (gcd w h)
          ratio (str (quot w divisor) ":" (quot h divisor))]
      (when (contains? stability-aspect-ratios ratio)
        ratio))))

(defn- bedrock-options [request]
  (get-in request [:image/provider-options :bedrock] {}))

(defn- quality-value [quality]
  (case quality
    (:hd :high) "premium"
    (:standard :low :medium :auto) "standard"
    nil))

(defn- task-image-body [request cfg-default]
  (let [[w h] (or (parse-size (:image/size request)) [1024 1024])
        opts (bedrock-options request)
        quality (or (:quality opts) (quality-value (:image/quality request)))]
    {:taskType "TEXT_IMAGE"
     :textToImageParams
     (cond-> {:text (:image/prompt request)}
       (:negative-prompt opts) (assoc :negativeText (:negative-prompt opts))
       (:style opts) (assoc :style (:style opts)))
     :imageGenerationConfig
     (cond-> {:numberOfImages (or (:image/n request) 1)
              :width w
              :height h
              :cfgScale (or (:cfg-scale opts) cfg-default)}
       (:seed opts) (assoc :seed (:seed opts))
       quality (assoc :quality quality))}))

(defn- titan-body [request]
  (task-image-body request 8.0))

(defn- nova-canvas-body [request]
  (task-image-body request 6.5))

(defn- legacy-stability-body [request]
  (let [[w h] (or (parse-size (:image/size request)) [1024 1024])
        opts (bedrock-options request)]
    (cond-> {:text_prompts [{:text (:image/prompt request) :weight 1.0}]
             :width w
             :height h
             :cfg_scale (or (:cfg-scale opts) 7.0)
             :steps (or (:steps opts) 30)}
      (:seed opts) (assoc :seed (:seed opts))
      (:image/n request) (assoc :samples (:image/n request)))))

(defn- modern-stability-body [request]
  (let [opts (bedrock-options request)
        aspect-ratio (or (:aspect-ratio opts)
                         (size->aspect-ratio (:image/size request)))
        output-format (some-> (:output-format opts) name)
        image (:image opts)
        mode (or (:mode opts) (when image "image-to-image"))]
    (cond-> {:prompt (:image/prompt request)}
      aspect-ratio (assoc :aspect_ratio aspect-ratio)
      output-format (assoc :output_format output-format)
      (:seed opts) (assoc :seed (:seed opts))
      (:negative-prompt opts) (assoc :negative_prompt (:negative-prompt opts))
      image (assoc :image image)
      (:strength opts) (assoc :strength (:strength opts))
      mode (assoc :mode mode))))

(defn build-image-request-bedrock
  [_profile request]
  (let [canonical (or (:image/model request) "amazon.titan-image-generator-v2:0")
        model (bedrock/resolve-model-id canonical)
        body (cond
               (titan? model) (titan-body request)
               (nova-canvas? model) (nova-canvas-body request)
               (legacy-stability? model) (legacy-stability-body request)
               (modern-stability? model) (modern-stability-body request)
               :else
               (throw (ex-info "Unsupported Bedrock image model family"
                               {:provider :bedrock
                                :model model
                                :error/type :request/unsupported-model})))]
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

(defn- images-parse [raw]
  (mapv (fn [b64] {:image/b64 b64}) (:images raw)))

(defn- artifacts-parse [raw]
  (into []
        (keep (fn [artifact]
                (when-let [b64 (:base64 artifact)]
                  {:image/b64 b64})))
        (:artifacts raw)))

(defn parse-image-response-bedrock
  [_profile raw]
  {:image/provider :bedrock
   :image/model nil
   :image/images (cond
                   (:artifacts raw) (artifacts-parse raw)
                   (:images raw) (images-parse raw)
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
