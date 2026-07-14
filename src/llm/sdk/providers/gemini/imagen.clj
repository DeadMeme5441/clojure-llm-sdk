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

(def ^:private supported-aspect-ratios
  [["1:8" 0.125]
   ["1:4" 0.25]
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

(defn- size->aspect-ratio [size]
  (when-let [[_ width height] (and (string? size)
                                   (re-matches #"(\d+)x(\d+)" size))]
    (let [ratio (/ (Double/parseDouble width)
                   (Double/parseDouble height))]
      (first
       (apply min-key
              (fn [[_ supported]]
                (Math/abs (- ratio supported)))
              supported-aspect-ratios)))))

(defn build-image-request-vertex-imagen
  [profile request]
  (let [model (or (:image/model request) "gemini-2.5-flash-image")
        _ (when (str/starts-with? (str/lower-case model) "imagen-")
            (throw
             (ex-info
              "Vertex Imagen models are discontinued; use a Gemini image model."
              {:error/type :vertex-imagen/discontinued-model
               :provider :vertex-imagen
               :model model
               :replacement "gemini-2.5-flash-image"})))
        project (vertex-project profile request)
        location (vertex-location profile request)
        host (vertex-host location)
        token (access-token profile request)
        ratio (size->aspect-ratio (:image/size request))
        image-size (get-in request [:image/provider-options :gemini :image-size])
        extra-body (or (get-in request [:image/provider-options :extra_body]) {})
        canonical-generation-config
        {:responseModalities ["IMAGE"]
         :candidateCount (or (:image/n request) 1)
         :imageConfig (cond-> {}
                        ratio (assoc :aspectRatio ratio)
                        image-size (assoc :imageSize image-size))}
        generation-config (merge canonical-generation-config
                                 (:generationConfig extra-body))
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
                       {:image/b64 (get-in part [:inlineData :data])}

                       (get-in part [:fileData :fileUri])
                       {:image/url (get-in part [:fileData :fileUri])}

                       :else nil)))
             vec)
        usage-raw (:usageMetadata raw)]
    (cond-> {:image/provider :vertex-imagen
             :image/model (:modelVersion raw)
             :image/images images
             :image/raw raw}
      usage-raw
      (assoc :response/usage
             (cond-> (usage/normalize-usage :gemini-native usage-raw)
               (some? (:thoughtsTokenCount usage-raw))
               (assoc :usage/reasoning-tokens
                      (:thoughtsTokenCount usage-raw)))))))

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
