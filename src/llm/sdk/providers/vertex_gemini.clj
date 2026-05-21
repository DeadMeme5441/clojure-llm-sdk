(ns llm.sdk.providers.vertex-gemini
  "Vertex AI Gemini transport adapter.
   Builds on Gemini native with different auth (GCP OAuth) and endpoint structure.
   Uses project/region routing."
  (:require [clojure.string :as str]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.gemini-native :as gemini]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Vertex endpoints use:
;;   POST /v1/projects/{project}/locations/{location}/publishers/google/models/{model}:generateContent
;; Auth: Authorization: Bearer {oauth_token}
;; ---------------------------------------------------------------------------

(defn- vertex-project
  "Resolve the GCP project id. Caller > profile quirks > env."
  [profile request]
  (or (get-in request [:request/provider-options :vertex :project])
      (get-in profile [:profile/quirks :vertex-project])
      (System/getenv "GOOGLE_CLOUD_PROJECT")
      ""))

(defn- vertex-location
  "Resolve the GCP location. Caller > profile quirks > env > default."
  [profile request]
  (or (get-in request [:request/provider-options :vertex :location])
      (get-in profile [:profile/quirks :vertex-location])
      (System/getenv "GOOGLE_CLOUD_LOCATION")
      "us-central1"))

(defn- vertex-base-url
  "Choose the Vertex AI host for a given location. `global` uses the
   region-less endpoint; everything else uses the regional host that
   matches the location."
  [location]
  (if (= "global" (str location))
    "https://aiplatform.googleapis.com"
    (str "https://" location "-aiplatform.googleapis.com")))

(defn- vertex-access-token
  "Resolve a GCP OAuth2 access token.
   Order: request provider-options > GOOGLE_OAUTH_ACCESS_TOKEN env var.
   Note: GOOGLE_APPLICATION_CREDENTIALS is a file *path* (a service-
   account JSON), not an access token — exchanging it for an access
   token requires a JWT signer, which is out of scope for this SDK.
   Callers must materialize a token themselves (e.g. via
   `gcloud auth print-access-token`)."
  [request]
  (or (get-in request [:request/provider-options :vertex :access-token])
      (System/getenv "GOOGLE_OAUTH_ACCESS_TOKEN")))

(defn build-request-vertex
  [profile request]
  (let [base-req (gemini/build-request-gemini profile request)
        project (vertex-project profile request)
        location (vertex-location profile request)
        host (vertex-base-url location)
        model (:request/model request)
        model-norm (cond-> model
                     (str/starts-with? (str/lower-case model) "models/")
                     (subs 7))
        token (vertex-access-token request)]
    (assoc base-req
           :url (str host
                     "/v1/projects/" project
                     "/locations/" location
                     "/publishers/google/models/" model-norm
                     ":generateContent")
           :headers (merge (:headers base-req)
                           (when token {"Authorization" (str "Bearer " token)})))))

(defn parse-response-vertex
  [profile raw]
  (gemini/parse-response-gemini profile raw))

(defn parse-stream-event-vertex
  [profile line]
  (gemini/parse-stream-event-gemini profile line))

(defn parse-error-vertex
  [profile status body]
  (errors/classify-error (Exception. "Vertex Gemini API error")
                         :status status
                         :body body
                         :provider :vertex-gemini))

(defrecord VertexGeminiTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-vertex profile request))

  (parse-response [this profile raw]
    (parse-response-vertex profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-vertex profile line))

  (parse-error [this profile status body]
    (parse-error-vertex profile status body))

  (normalize-usage [this profile raw]
    (usage/normalize-usage :gemini-native raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :multimodal :reasoning}))

(defn make-transport []
  (->VertexGeminiTransport))

;; Register
(provider/register-provider
 {:profile/id :vertex-gemini
  :profile/protocol-family :gemini-native
  :profile/base-url "https://us-central1-aiplatform.googleapis.com"
  :profile/auth-strategy :gcp-oauth
  :profile/supports-model-listing true
  :profile/capabilities #{:chat :streaming :tools :multimodal :reasoning}
  :profile/env-var-names ["GOOGLE_APPLICATION_CREDENTIALS"]
  :profile/transport-constructor make-transport})
