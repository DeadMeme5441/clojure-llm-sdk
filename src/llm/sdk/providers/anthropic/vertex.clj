(ns llm.sdk.providers.anthropic.vertex
  "Vertex AI Anthropic (Claude) transport adapter.

   Claude models served through Google Vertex AI speak the same Messages
   API as the native Anthropic endpoint, with three differences:

     1. Endpoint — POST to the regional aiplatform host under
        /publishers/anthropic/models/{model}:rawPredict (unary) or
        :streamRawPredict (SSE), with the model in the URL path.
     2. Auth — GCP OAuth bearer token (ADC chain via llm.sdk.gcp-auth)
        instead of an x-api-key header.
     3. Body — the model is dropped from the JSON body and replaced by
        \"anthropic_version\": \"vertex-2023-10-16\"; streaming calls add
        \"stream\": true.

   Everything else (message/tool/cache/thinking shaping, response and
   stream parsing) is reused verbatim from llm.sdk.providers.anthropic.chat
   so the two backends stay in lockstep.

   Project resolution: request provider-options [:vertex :project] >
   profile quirks :vertex-project > GOOGLE_CLOUD_PROJECT env > SA JSON
   project_id. Location: provider-options [:vertex :location] > profile
   quirks :vertex-location > GOOGLE_CLOUD_LOCATION env > us-central1."
  (:require [clojure.string :as str]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.anthropic.chat :as anthropic]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]
            [llm.sdk.gcp-auth :as gcp-auth]))

;; ---------------------------------------------------------------------------
;; Vertex endpoint plumbing (mirrors llm.sdk.providers.gemini.vertex)
;; ---------------------------------------------------------------------------

(def ^:private anthropic-version-on-vertex "vertex-2023-10-16")

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
               :provider :vertex-anthropic}))))

(defn- vertex-location
  "Resolve the GCP location. Caller > profile quirks > env > default."
  [profile request]
  (or (get-in request [:request/provider-options :vertex :location])
      (get-in profile [:profile/quirks :vertex-location])
      (System/getenv "GOOGLE_CLOUD_LOCATION")
      "us-central1"))

(defn- vertex-base-url
  "Choose the Vertex AI host for a given location. `global` uses the
   region-less endpoint; everything else uses the regional host."
  [location]
  (if (= "global" (str location))
    "https://aiplatform.googleapis.com"
    (str "https://" location "-aiplatform.googleapis.com")))

(defn- vertex-model-id
  "Vertex publishes Claude under ids like `claude-opus-4-6@20250101`.
   Strip any leading `anthropic/` provider prefix; otherwise pass through
   so the caller-supplied `@version` suffix survives into the URL path."
  [model]
  (-> (str model)
      str/trim
      (str/replace #"(?i)^anthropic/" "")))

;; ---------------------------------------------------------------------------
;; Request building — reuse the native Anthropic body, re-target at Vertex
;; ---------------------------------------------------------------------------

(defn build-request-vertex-anthropic
  [profile request]
  (let [base (anthropic/build-request-anthropic profile request)
        project (vertex-project profile request)
        location (vertex-location profile request)
        host (vertex-base-url location)
        model (vertex-model-id (:request/model request))
        token (gcp-auth/resolve-access-token request profile)
        stream? (boolean (:request/stream? request))
        suffix (if stream? ":streamRawPredict" ":rawPredict")
        body (cond-> (-> (:body base)
                         (dissoc :model)
                         (assoc :anthropic_version anthropic-version-on-vertex))
               stream? (assoc :stream true))
        ;; Drop native-Anthropic-only auth/version headers; Vertex carries
        ;; the version in the body and authenticates with a GCP bearer token.
        headers (-> (:headers base)
                    (dissoc "x-api-key"
                            "anthropic-version"
                            "anthropic-dangerous-direct-browser-access")
                    (assoc "Authorization" (str "Bearer " token)))]
    (assoc base
           :url (str host
                     "/v1/projects/" project
                     "/locations/" location
                     "/publishers/anthropic/models/" model
                     suffix)
           :headers headers
           :body body)))

;; ---------------------------------------------------------------------------
;; Response / stream / error parsing — identical to native Anthropic
;; ---------------------------------------------------------------------------

(defn parse-response-vertex-anthropic
  [profile raw]
  (assoc (anthropic/parse-response-anthropic profile raw)
         :response/provider :vertex-anthropic))

(defn parse-stream-event-vertex-anthropic
  [profile line]
  (anthropic/parse-stream-event-anthropic profile line))

(defn parse-error-vertex-anthropic
  [_profile status body]
  (errors/classify-api-error :vertex-anthropic "Vertex Anthropic" status body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord VertexAnthropicTransport []
  t/Transport
  (build-request [_ profile request]
    (build-request-vertex-anthropic profile request))

  (parse-response [_ profile raw]
    (parse-response-vertex-anthropic profile raw))

  (parse-stream-event [_ profile line]
    (parse-stream-event-vertex-anthropic profile line))

  (parse-error [_ profile status body]
    (parse-error-vertex-anthropic profile status body))

  (normalize-usage [_ _profile raw]
    (usage/normalize-usage :anthropic raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :cache :thinking-blocks
      :file-attachments}))

(defn make-transport []
  (->VertexAnthropicTransport))

;; ---------------------------------------------------------------------------
;; Register
;; ---------------------------------------------------------------------------

(provider/register-provider
 {:profile/id :vertex-anthropic
  :profile/protocol-family :anthropic-messages
  :profile/base-url "https://us-central1-aiplatform.googleapis.com"
  :profile/auth-strategy :gcp-oauth
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning
                          :cache :thinking-blocks :file-attachments}
  :profile/default-headers {}
  :profile/env-var-names ["GOOGLE_APPLICATION_CREDENTIALS"
                          "GOOGLE_OAUTH_ACCESS_TOKEN"]
  :profile/transport-constructor make-transport})
