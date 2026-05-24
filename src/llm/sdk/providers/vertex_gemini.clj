(ns llm.sdk.providers.vertex-gemini
  "Vertex AI Gemini transport adapter.

   Builds on Gemini native with different auth (GCP OAuth) and endpoint
   structure. Auth resolution follows the standard GCP ADC chain via
   llm.sdk.gcp-auth: request opts → GOOGLE_OAUTH_ACCESS_TOKEN env →
   `gcloud auth print-access-token` → GOOGLE_APPLICATION_CREDENTIALS
   service-account JSON (RS256-signed JWT exchanged at
   oauth2.googleapis.com/token).

   Project resolution: request opts → profile quirks →
   GOOGLE_CLOUD_PROJECT env → SA JSON project_id."
  (:require [clojure.string :as str]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.gemini-native :as gemini]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]
            [llm.sdk.gcp-auth :as gcp-auth]))

;; ---------------------------------------------------------------------------
;; Vertex endpoints use:
;;   POST /v1/projects/{project}/locations/{location}/publishers/google/models/{model}:generateContent
;; Auth: Authorization: Bearer {oauth_token}
;; ---------------------------------------------------------------------------

(defn- vertex-project
  "Resolve the GCP project id. Throws when no source provides one."
  [profile request]
  (or (gcp-auth/resolve-project request profile)
      (throw (ex-info
              (str "Vertex project id not set. Provide it via request "
                   "provider-options [:vertex :project], profile quirks "
                   ":vertex-project, GOOGLE_CLOUD_PROJECT env, or in the "
                   "service-account JSON pointed to by "
                   "GOOGLE_APPLICATION_CREDENTIALS.")
              {:error/type :vertex/missing-project
               :provider :vertex-gemini}))))

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
        token (gcp-auth/resolve-access-token request profile)]
    (assoc base-req
           :url (str host
                     "/v1/projects/" project
                     "/locations/" location
                     "/publishers/google/models/" model-norm
                     ":generateContent")
           :headers (merge (:headers base-req)
                           {"Authorization" (str "Bearer " token)}))))

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
  :profile/env-var-names ["GOOGLE_APPLICATION_CREDENTIALS"
                          "GOOGLE_OAUTH_ACCESS_TOKEN"]
  :profile/transport-constructor make-transport})
