(ns llm.sdk.providers.vertex-imagen
  "Vertex AI Imagen 3 / 4 image-generation adapter.

   Endpoint:
     POST {host}/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:predict
   Body:
     {:instances [{:prompt \"...\"}]
      :parameters {:sampleCount N :aspectRatio \"1:1\" :seed ...}}
   Response:
     {:predictions [{:bytesBase64Encoded \"...\" :mimeType \"image/png\"}]}

   Auth: same GCP OAuth as vertex-gemini — token from
   :request provider-options.vertex.access-token or
   GOOGLE_OAUTH_ACCESS_TOKEN.

   Models surfaced under :vertex-imagen include imagen-3.0-generate-002,
   imagen-3.0-fast-generate-001, imagen-4.0-generate-001."
  (:require [clojure.string :as str]
            [llm.sdk.transport.image :as it]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]))

(defn- env [k] (System/getenv k))

(defn- vertex-project [profile request]
  (or (get-in request [:image/provider-options :vertex :project])
      (get-in profile [:profile/quirks :vertex-project])
      (env "GOOGLE_CLOUD_PROJECT") ""))

(defn- vertex-location [profile request]
  (or (get-in request [:image/provider-options :vertex :location])
      (get-in profile [:profile/quirks :vertex-location])
      (env "GOOGLE_CLOUD_LOCATION") "us-central1"))

(defn- vertex-host [location]
  (if (= "global" (str location))
    "https://aiplatform.googleapis.com"
    (str "https://" location "-aiplatform.googleapis.com")))

(defn- access-token [request]
  (or (get-in request [:image/provider-options :vertex :access-token])
      (env "GOOGLE_OAUTH_ACCESS_TOKEN")))

(defn- size->aspect-ratio
  "Translate canonical :image/size (e.g. \"1024x1024\") into Imagen's
   :aspectRatio. Imagen only accepts a discrete set of ratios."
  [size]
  (case size
    "1024x1024" "1:1"
    "1024x1792" "9:16"
    "1792x1024" "16:9"
    "768x1408" "9:16"
    "1408x768" "16:9"
    (when (and (string? size)
               (let [parts (str/split size #"x")]
                 (= 2 (count parts))))
      (let [[w h] (mapv #(Integer/parseInt %) (str/split size #"x"))]
        (cond
          (= w h) "1:1"
          (< w h) "9:16"
          :else "16:9")))))

(defn build-image-request-vertex-imagen
  [profile request]
  (let [model (or (:image/model request) "imagen-3.0-generate-002")
        project (vertex-project profile request)
        location (vertex-location profile request)
        host (vertex-host location)
        token (access-token request)
        ratio (size->aspect-ratio (:image/size request))
        params (cond-> {}
                 (:image/n request) (assoc :sampleCount (:image/n request))
                 (not (:image/n request)) (assoc :sampleCount 1)
                 ratio (assoc :aspectRatio ratio)
                 (get-in request [:image/provider-options :vertex :seed])
                 (assoc :seed (get-in request [:image/provider-options :vertex :seed]))
                 (get-in request [:image/provider-options :vertex :negative-prompt])
                 (assoc :negativePrompt (get-in request [:image/provider-options :vertex :negative-prompt])))
        body {:instances [{:prompt (:image/prompt request)}]
              :parameters params}]
    {:method :post
     :url (str host
               "/v1/projects/" project
               "/locations/" location
               "/publishers/google/models/" model
               ":predict")
     :headers (merge {"Content-Type" "application/json"}
                     (when token {"Authorization" (str "Bearer " token)}))
     :body body}))

(defn parse-image-response-vertex-imagen
  [_profile raw]
  (let [images (mapv (fn [p]
                       (cond-> {}
                         (:bytesBase64Encoded p)
                         (assoc :image/b64 (:bytesBase64Encoded p))
                         (:mimeType p) (assoc :image/mime-type (:mimeType p))))
                     (:predictions raw))]
    {:image/provider :vertex-imagen
     :image/model nil
     :image/images images
     :image/raw raw}))

(defn parse-image-error-vertex-imagen
  [_profile status body]
  (errors/classify-error (Exception. "Vertex Imagen API error")
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
  :profile/protocol-family :vertex-imagen
  :profile/base-url "https://us-central1-aiplatform.googleapis.com"
  :profile/auth-strategy :gcp-oauth
  :profile/supports-model-listing false
  :profile/capabilities #{:image-generation}
  :profile/env-var-names ["GOOGLE_APPLICATION_CREDENTIALS"
                          "GOOGLE_OAUTH_ACCESS_TOKEN"
                          "GOOGLE_CLOUD_PROJECT"
                          "GOOGLE_CLOUD_LOCATION"]
  :profile/image-transport-constructor make-transport})
