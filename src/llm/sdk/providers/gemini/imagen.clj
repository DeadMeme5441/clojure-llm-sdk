(ns llm.sdk.providers.gemini.imagen
  "Vertex AI image-generation adapter.

   Google discontinued the Imagen prediction endpoints on 2026-06-30.
   The :vertex-imagen SDK identity now targets the documented replacement,
   Gemini image generation over Vertex generateContent."
  (:require [clojure.string :as str]
            [llm.sdk.transport.image :as it]
            [llm.sdk.provider :as provider]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

(defn- auth-request [request]
  {:request/provider-options
   {:vertex (get-in request [:image/provider-options :vertex])}})

(defn- vertex-project [profile request]
  (or (gcp-auth/resolve-project (auth-request request) profile)
      (throw (ex-info "Vertex project id not set for image generation."
                      {:error/type :vertex/missing-project
                       :provider :vertex-imagen}))))

(defn- vertex-location [profile request]
  (or (get-in request [:image/provider-options :vertex :location])
      (get-in profile [:profile/quirks :vertex-location])
      (System/getenv "GOOGLE_CLOUD_LOCATION")
      "global"))

(defn- vertex-host [location]
  (if (= "global" (str location))
    "https://aiplatform.googleapis.com"
    (str "https://" location "-aiplatform.googleapis.com")))

(defn- access-token [profile request]
  (gcp-auth/resolve-access-token (auth-request request) profile))

(def ^:private common-aspect-ratios
  [["1:8" 0.125]
   ["1:4" 0.25]
   ["9:21" (/ 9.0 21.0)]
   ["9:16" 0.5625]
   ["2:3" (/ 2.0 3.0)]
   ["3:4" 0.75]
   ["4:5" 0.8]
   ["1:1" 1.0]
   ["5:4" 1.25]
   ["4:3" (/ 4.0 3.0)]
   ["3:2" 1.5]
   ["16:9" (/ 16.0 9.0)]
   ["21:9" (/ 21.0 9.0)]
   ["4:1" 4.0]
   ["8:1" 8.0]])

(def ^:private model-options
  {"gemini-2.5-flash-image"
   {:aspect-ratios
    (remove (comp #{"1:8" "1:4" "9:21" "4:1" "8:1"} first)
            common-aspect-ratios)
    :image-sizes #{}}
   "gemini-3.1-flash-lite-image"
   {:aspect-ratios
    (remove (comp #{"9:21"} first) common-aspect-ratios)
    :image-sizes #{"1K"}}
   "gemini-3.1-flash-image"
   {:aspect-ratios common-aspect-ratios
    :image-sizes #{"512" "1K" "2K" "4K"}}
   "gemini-3-pro-image"
   {:aspect-ratios common-aspect-ratios
    :image-sizes #{"1K" "2K" "4K"}}})

(defn- invalid-option! [model option value message]
  (throw (ex-info message
                  {:provider :vertex-imagen
                   :model model
                   :option option
                   :value value
                   :error/type :provider/unsupported-option})))

(defn- discontinued-model? [model]
  (boolean
   (re-find #"(?i)^(?:imagen-|imagegeneration@|imagetext@)" model)))

(defn- gemini-image-model? [model]
  (and (str/starts-with? model "gemini-")
       (str/ends-with? model "-image")))

(defn- normalize-image-size [image-size]
  (cond
    (keyword? image-size) (str/upper-case (name image-size))
    (string? image-size) (str/upper-case image-size)
    :else image-size))

(defn- size->aspect-ratio [model size supported]
  (when size
    (let [[_ width height] (and (string? size)
                                (re-matches #"([1-9]\d*)x([1-9]\d*)" size))]
      (when-not width
        (invalid-option! model :image/size size
                         "Vertex Gemini image size must be WIDTHxHEIGHT"))
      (let [ratio (/ (Double/parseDouble width)
                     (Double/parseDouble height))]
        (first
         (apply min-key
                (fn [[_ supported-ratio]]
                  (Math/abs (- ratio supported-ratio)))
                supported))))))

(defn- validate-request-options! [model request]
  (doseq [option [:image/quality :image/style
                  :image/response-format :image/user]
          :when (contains? request option)]
    (invalid-option! model option (get request option)
                     (str "Vertex Gemini image generation does not support "
                          option)))
  (when-let [n (:image/n request)]
    (when-not (and (int? n) (pos? n))
      (invalid-option! model :image/n n
                       "Vertex Gemini image n must be a positive integer"))))


(defn build-image-request-vertex-imagen
  [profile request]
  (let [model (or (:image/model request) "gemini-2.5-flash-image")
        _ (when (discontinued-model? model)
            (throw
             (ex-info
              "Vertex Imagen prediction endpoints are discontinued; use a Gemini image model."
              {:error/type :vertex-imagen/discontinued-model
               :provider :vertex-imagen
               :model model
               :replacement "gemini-2.5-flash-image"})))
        _ (when-not (gemini-image-model? model)
            (throw
             (ex-info "Vertex image generation requires a Gemini image model"
                      {:error/type :request/unsupported-model
                       :provider :vertex-imagen
                       :model model})))
        _ (validate-request-options! model request)
        {:keys [aspect-ratios image-sizes]}
        (get model-options model
             {:aspect-ratios common-aspect-ratios
              :image-sizes #{"512" "1K" "2K" "4K"}})
        extra-body (or (get-in request [:image/provider-options :extra_body]) {})
        native-generation-config (:generationConfig extra-body)
        native-image-config (:imageConfig native-generation-config)
        ratio (or (size->aspect-ratio model (:image/size request)
                                      aspect-ratios)
                  (:aspectRatio native-image-config))
        image-size (normalize-image-size
                    (or (get-in request
                                [:image/provider-options :gemini :image-size])
                        (:imageSize native-image-config)))
        supported-ratios (set (map first aspect-ratios))
        _ (when (and ratio (not (contains? supported-ratios ratio)))
            (invalid-option! model :aspectRatio ratio
                             (str "Unsupported aspect ratio for " model)))
        _ (when (and image-size
                     (not (contains? image-sizes image-size)))
            (invalid-option! model :imageSize image-size
                             (str "Unsupported image size for " model)))
        project (vertex-project profile request)
        location (vertex-location profile request)
        host (vertex-host location)
        token (access-token profile request)
        image-config (cond-> (or native-image-config {})
                       ratio (assoc :aspectRatio ratio)
                       image-size (assoc :imageSize image-size))
        generation-config
        (-> (or native-generation-config {})
            (assoc :responseModalities ["IMAGE"]
                   :candidateCount (or (:image/n request) 1)
                   :imageConfig image-config))
        body (assoc (merge {:contents [{:role "user"
                                        :parts [{:text (:image/prompt request)}]}]}
                           (dissoc extra-body :generationConfig))
                    :generationConfig generation-config)]
    {:method :post
     :url (str host
               "/v1/projects/" project
               "/locations/" location
               "/publishers/google/models/" model
               ":generateContent")
     :headers {"Content-Type" "application/json"
               "Authorization" (str "Bearer " token)}
     :body body}))

(defn parse-image-response-vertex-imagen
  [_profile raw]
  (let [images
        (->> (:candidates raw)
             (mapcat #(get-in % [:content :parts]))
             (keep (fn [part]
                     (cond
                       (get-in part [:inlineData :data])
                       (cond-> {:image/b64 (get-in part [:inlineData :data])}
                         (get-in part [:inlineData :mimeType])
                         (assoc :image/mime-type
                                (get-in part [:inlineData :mimeType])))

                       (get-in part [:fileData :fileUri])
                       (cond-> {:image/url (get-in part [:fileData :fileUri])}
                         (get-in part [:fileData :mimeType])
                         (assoc :image/mime-type
                                (get-in part [:fileData :mimeType])))

                       :else nil)))
             vec)
        usage-raw (:usageMetadata raw)
        parsed
        (cond-> {:image/provider :vertex-imagen
                 :image/model (:modelVersion raw)
                 :image/images images
                 :image/raw raw}
          usage-raw
          (assoc :response/usage
                 (cond-> (usage/normalize-usage :gemini-native usage-raw)
                   (some? (:thoughtsTokenCount usage-raw))
                   (assoc :usage/reasoning-tokens
                          (:thoughtsTokenCount usage-raw)))))]
    (when-not (seq images)
      (throw
       (ex-info "Vertex Gemini returned no usable generated images"
                {:provider :vertex-imagen
                 :error/type :provider/invalid-image-response
                 :prompt-feedback (:promptFeedback raw)
                 :finish-reasons (mapv :finishReason (:candidates raw))
                 :response parsed
                 :body raw})))
    parsed))

(defn parse-image-error-vertex-imagen
  [_profile status body]
  (errors/classify-error (Exception. "Vertex Gemini image API error")
                         :status status
                         :body body
                         :provider :vertex-imagen))

(defrecord VertexImagenTransport []
  it/ImageTransport
  (build-image-request [_ profile request]
    (build-image-request-vertex-imagen profile request))
  (parse-image-response [_ profile raw]
    (parse-image-response-vertex-imagen profile raw))
  (parse-image-error [_ profile status body]
    (parse-image-error-vertex-imagen profile status body)))

(defn make-transport [] (->VertexImagenTransport))

(provider/register-provider
 {:profile/id :vertex-imagen
  :profile/protocol-family :gemini-native
  :profile/base-url "https://aiplatform.googleapis.com"
  :profile/auth-strategy :gcp-oauth
  :profile/supports-model-listing false
  :profile/capabilities #{:image-generation}
  :profile/env-var-names ["GOOGLE_APPLICATION_CREDENTIALS"
                          "GOOGLE_OAUTH_ACCESS_TOKEN"
                          "GOOGLE_CLOUD_PROJECT"
                          "GOOGLE_CLOUD_LOCATION"]
  :profile/image-transport-constructor make-transport})
