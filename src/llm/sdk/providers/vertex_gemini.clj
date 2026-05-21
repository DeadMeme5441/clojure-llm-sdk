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

(defn build-request-vertex
  [profile request]
  (let [base-req (gemini/build-request-gemini profile request)
        project (or (System/getenv "GOOGLE_CLOUD_PROJECT") "")
        location (or (System/getenv "GOOGLE_CLOUD_LOCATION") "us-central1")
        model (:request/model request)
        model-norm (if (str/starts-with? (str/lower-case model) "models/")
                     (subs model 7)
                     model)]
    (assoc base-req
           :url (str (:profile/base-url profile)
                     "/v1/projects/" project
                     "/locations/" location
                     "/publishers/google/models/" model-norm
                     ":generateContent")
           :headers (merge (:headers base-req)
                           {"Authorization" (str "Bearer " (provider/resolve-auth-token profile))}))))

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
