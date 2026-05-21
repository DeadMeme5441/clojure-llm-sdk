(ns llm.sdk.models
  "Per-provider /models endpoint fetchers.

   Each supported provider has a fetcher that hits its public /models
   endpoint and returns a vector of normalized ModelEntry maps with
   :model/source :live-models-api.

   The registry layer (llm.sdk.registry) merges these entries with the
   models.dev breadth registry and a bundled offline snapshot to produce
   one unified view per (provider, model).

   Providers without a public /models endpoint (Codex, Codex-backend,
   Bedrock, Fake) throw :error :unsupported on fetch — callers should
   route those through models.dev / snapshot layers only."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [malli.core :as m]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]))

;; ---------------------------------------------------------------------------
;; ModelEntry schema
;; ---------------------------------------------------------------------------

(def ModelCost
  [:map
   [:input-per-million {:optional true} number?]
   [:output-per-million {:optional true} number?]
   [:cache-read-per-million {:optional true} number?]
   [:cache-write-per-million {:optional true} number?]
   [:request-cost {:optional true} number?]])

(def ModelEntry
  "Canonical model registry entry. Every layer (live /models, models.dev,
   bundled snapshot) emits maps of this shape; :model/source tags the
   producing layer."
  [:map
   [:model/id string?]
   [:model/provider keyword?]
   [:model/family {:optional true} string?]
   [:model/display-name {:optional true} string?]
   [:model/context-length {:optional true} int?]
   [:model/max-output-tokens {:optional true} int?]
   [:model/capabilities {:optional true} [:set keyword?]]
   [:model/cost {:optional true} ModelCost]
   [:model/source [:enum :live-models-api :models-dev :bundled-snapshot :override]]
   [:model/source-url {:optional true} string?]
   [:model/fetched-at {:optional true} inst?]])

(def validate-model-entry (m/validator ModelEntry))

(defn explain-model-entry [x] (m/explain ModelEntry x))

;; ---------------------------------------------------------------------------
;; HTTP fetch helper
;; ---------------------------------------------------------------------------

(defn- now ^java.util.Date [] (java.util.Date.))

(defn- get-json
  "GET a URL, return the JSON-parsed body. Throws ex-info on non-2xx."
  [url headers]
  (let [resp (http/request {:method :get :url url :headers headers})
        status (:status resp)]
    (if (and (>= status 200) (< status 300))
      (:body resp)
      (throw (ex-info "Provider /models fetch failed"
                      {:status status :url url :body (:body resp)})))))

;; ---------------------------------------------------------------------------
;; Parsers (per wire shape) — public so tests can drive them with fixtures
;; ---------------------------------------------------------------------------

(defn parse-openai-style
  "Shape: {object 'list', data [{id, object, created, owned_by}]}.
   Used by OpenAI, DeepSeek, Kimi — none surface context length or
   pricing via /models, so entries are id-only."
  [body provider-id source-url]
  (let [ts (now)]
    (mapv (fn [m]
            {:model/id (:id m)
             :model/provider provider-id
             :model/source :live-models-api
             :model/source-url source-url
             :model/fetched-at ts})
          (:data body))))

(defn parse-anthropic-models
  "Shape: {data [{type, id, display_name, created_at}], has_more, ...}."
  [body provider-id source-url]
  (let [ts (now)]
    (mapv (fn [m]
            (cond-> {:model/id (:id m)
                     :model/provider provider-id
                     :model/source :live-models-api
                     :model/source-url source-url
                     :model/fetched-at ts}
              (:display_name m) (assoc :model/display-name (:display_name m))))
          (:data body))))

(defn- gemini-method->capability [method]
  (case method
    "generateContent" :chat
    "streamGenerateContent" :streaming
    "embedContent" :embedding
    "countTokens" :count-tokens
    "batchEmbedContents" :embedding
    "countMessageTokens" :count-tokens
    nil))

(defn parse-gemini-models
  "Shape (Gemini Native + Vertex):
   {models [{name 'models/gemini-2.5-pro', version, displayName,
             description, inputTokenLimit, outputTokenLimit,
             supportedGenerationMethods, temperature, ...}]}"
  [body provider-id source-url]
  (let [ts (now)]
    (mapv (fn [m]
            (let [raw-name (:name m)
                  model-id (if (and (string? raw-name)
                                    (str/starts-with? raw-name "models/"))
                             (subs raw-name 7)
                             raw-name)
                  caps (->> (:supportedGenerationMethods m)
                            (keep gemini-method->capability)
                            (into #{}))]
              (cond-> {:model/id model-id
                       :model/provider provider-id
                       :model/source :live-models-api
                       :model/source-url source-url
                       :model/fetched-at ts}
                (:displayName m) (assoc :model/display-name (:displayName m))
                (:inputTokenLimit m) (assoc :model/context-length (:inputTokenLimit m))
                (:outputTokenLimit m) (assoc :model/max-output-tokens (:outputTokenLimit m))
                (seq caps) (assoc :model/capabilities caps))))
          (:models body))))

(defn- parse-decimal-string
  "OpenRouter encodes per-token pricing as decimal strings. Convert to
   per-million (USD) — returns nil for nil/empty/malformed input."
  [s]
  (when (and s (string? s) (seq s))
    (try (* (Double/parseDouble s) 1000000.0)
         (catch Exception _ nil))))

(defn parse-openrouter-models
  "Shape: {data [{id, name, context_length,
                  pricing {prompt, completion, request, image, cache_read,
                           cache_write, ...},
                  architecture {modality, tokenizer, instruct_type},
                  top_provider {context_length, max_completion_tokens,
                                is_moderated},
                  per_request_limits}]}.
   Per-token pricing strings are converted to per-million USD numbers."
  [body provider-id source-url]
  (let [ts (now)]
    (mapv (fn [m]
            (let [pricing (:pricing m)
                  top (:top_provider m)
                  cost (cond-> {}
                         (parse-decimal-string (:prompt pricing))
                         (assoc :input-per-million
                                (parse-decimal-string (:prompt pricing)))
                         (parse-decimal-string (:completion pricing))
                         (assoc :output-per-million
                                (parse-decimal-string (:completion pricing)))
                         (parse-decimal-string (:cache_read pricing))
                         (assoc :cache-read-per-million
                                (parse-decimal-string (:cache_read pricing)))
                         (parse-decimal-string (:cache_write pricing))
                         (assoc :cache-write-per-million
                                (parse-decimal-string (:cache_write pricing))))
                  base {:model/id (:id m)
                        :model/provider provider-id
                        :model/source :live-models-api
                        :model/source-url source-url
                        :model/fetched-at ts}]
              (cond-> base
                (:name m) (assoc :model/display-name (:name m))
                (:context_length m) (assoc :model/context-length (:context_length m))
                (:max_completion_tokens top)
                (assoc :model/max-output-tokens (:max_completion_tokens top))
                (seq cost) (assoc :model/cost cost))))
          (:data body))))

;; ---------------------------------------------------------------------------
;; Vertex auth — gcloud OAuth or env override
;; ---------------------------------------------------------------------------

(defn- vertex-auth-token
  "Resolve a Vertex OAuth token. Honours GOOGLE_OAUTH_ACCESS_TOKEN first,
   then falls back to `gcloud auth print-access-token --project=$GCP`.
   Throws ex-info if neither yields a token."
  []
  (or (System/getenv "GOOGLE_OAUTH_ACCESS_TOKEN")
      (let [project (System/getenv "GOOGLE_CLOUD_PROJECT")
            args (cond-> ["gcloud" "auth" "print-access-token"]
                   (seq project) (conj (str "--project=" project)))
            {:keys [exit out err]} (apply shell/sh args)]
        (if (and (zero? exit) (seq (str/trim (or out ""))))
          (str/trim out)
          (throw (ex-info "Vertex auth failed — set GOOGLE_OAUTH_ACCESS_TOKEN or install gcloud"
                          {:provider :vertex-gemini
                           :gcloud-exit exit
                           :gcloud-err (str/trim (or err ""))}))))))

;; ---------------------------------------------------------------------------
;; Per-provider fetch (URL + auth + parse)
;; ---------------------------------------------------------------------------

(defn- profile! [provider-id]
  (or (provider/get-provider provider-id)
      (throw (ex-info "Unknown provider"
                      {:provider provider-id :error :unknown-provider}))))

(defn- openai-style-fetch [provider-id]
  (let [p (profile! provider-id)
        base (:profile/base-url p)
        url (str base "/models")
        token (provider/resolve-auth-token p)
        headers (provider/default-headers p token)]
    (parse-openai-style (get-json url headers) provider-id url)))

(defmulti fetch-models
  "Fetch the live model catalog for a provider. Returns a vector of
   ModelEntry maps tagged with :model/source :live-models-api. Throws
   ex-info on auth / HTTP / unsupported."
  identity)

(defmethod fetch-models :openai [_] (openai-style-fetch :openai))
(defmethod fetch-models :deepseek [_] (openai-style-fetch :deepseek))
(defmethod fetch-models :kimi [_] (openai-style-fetch :kimi))

(defmethod fetch-models :anthropic [_]
  (let [p (profile! :anthropic)
        base (:profile/base-url p)
        url (str base "/models")
        token (provider/resolve-auth-token p)
        headers (provider/default-headers p token)]
    (parse-anthropic-models (get-json url headers) :anthropic url)))

(defmethod fetch-models :gemini-native [_]
  (let [p (profile! :gemini-native)
        base (:profile/base-url p)
        url (str base "/models")
        token (provider/resolve-auth-token p)
        headers (provider/default-headers p token)]
    (parse-gemini-models (get-json url headers) :gemini-native url)))

(defmethod fetch-models :vertex-gemini [_]
  (let [project (System/getenv "GOOGLE_CLOUD_PROJECT")
        location (or (System/getenv "GOOGLE_CLOUD_LOCATION") "us-central1")]
    (when-not project
      (throw (ex-info "GOOGLE_CLOUD_PROJECT required for Vertex /models"
                      {:provider :vertex-gemini :error :missing-config})))
    (let [url (str "https://" location "-aiplatform.googleapis.com/v1/projects/"
                   project "/locations/" location
                   "/publishers/google/models")
          token (vertex-auth-token)
          headers {"Authorization" (str "Bearer " token)
                   "Accept" "application/json"}]
      (parse-gemini-models (get-json url headers) :vertex-gemini url))))

(defmethod fetch-models :openrouter [_]
  (let [p (profile! :openrouter)
        base (:profile/base-url p)
        url (str base "/models")
        token (provider/resolve-auth-token p)
        headers (cond-> {"Accept" "application/json"}
                  token (assoc "Authorization" (str "Bearer " token)))]
    (parse-openrouter-models (get-json url headers) :openrouter url)))

(defmethod fetch-models :default [pid]
  (throw (ex-info (str "Provider does not support live /models: " pid)
                  {:provider pid :error :unsupported})))

;; ---------------------------------------------------------------------------
;; Supported set
;; ---------------------------------------------------------------------------

(def supported-providers
  "Providers with a usable live /models endpoint."
  #{:openai :anthropic :gemini-native :vertex-gemini
    :openrouter :deepseek :kimi})

(defn supports-models-listing?
  "Does this provider expose a /models endpoint we can call?"
  [provider-id]
  (contains? supported-providers provider-id))
