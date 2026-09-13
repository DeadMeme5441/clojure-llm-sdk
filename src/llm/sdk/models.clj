(ns llm.sdk.models
  "Per-provider /models endpoint fetchers.

   Each supported provider has a fetcher that hits its public /models
   endpoint and returns a vector of normalized ModelEntry maps with
   :model/source :live-models-api.

   The registry layer (llm.sdk.registry) merges these entries with the
   models.dev breadth registry and a bundled offline snapshot to produce
   one unified view per (provider, model).

   Profiles with :profile/supports-model-listing true and
   :profile/protocol-family :openai-chat use the generic OpenAI-compatible
   listing path. Other providers without a native listing method are rejected."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]
            [llm.sdk.gcp-auth :as gcp-auth]))

;; ---------------------------------------------------------------------------
;; ModelEntry schema
;; ---------------------------------------------------------------------------

(def ModelCost
  [:map
   [:input-per-million {:optional true} number?]
   [:output-per-million {:optional true} number?]
   [:cache-read-per-million {:optional true} number?]
   [:cache-write-per-million {:optional true} number?]
   [:request-cost {:optional true} number?]
   [:image-per-image {:optional true} number?]
   [:image-per-megapixel {:optional true} number?]
   [:transcription-per-minute {:optional true} number?]
   [:tts-per-million-chars {:optional true} number?]
   [:search-per-call {:optional true} number?]])

(def ModelSource
  [:enum :live-models-api :models-dev :models-dev-cache
   :bundled-snapshot :litellm-snapshot :override])

(def ModelSourceDescriptor
  [:map
   [:source ModelSource]
   [:source-url {:optional true} string?]
   [:source-revision {:optional true} string?]
   [:freshness {:optional true} [:enum :current :fresh :stale :bundled :caller]]
   [:fetched-at {:optional true} inst?]])

(def ModelEntry
  "Canonical model registry entry. Every layer (live /models, models.dev,
   bundled snapshot) emits maps of this shape. :model/source describes the
   highest-precedence contributor, while :model/sources retains every
   contributing tier and :model/cost-source identifies the pricing origin."
  [:map
   [:model/id string?]
   [:model/provider keyword?]
   [:model/family {:optional true} string?]
   [:model/display-name {:optional true} string?]
   [:model/context-length {:optional true} int?]
   [:model/max-output-tokens {:optional true} int?]
   [:model/capabilities {:optional true} [:set keyword?]]
   [:model/cost {:optional true} ModelCost]
   [:model/source ModelSource]
   [:model/source-url {:optional true} string?]
   [:model/source-revision {:optional true} string?]
   [:model/source-freshness {:optional true}
    [:enum :current :fresh :stale :bundled :caller]]
   [:model/availability {:optional true} [:enum :listed :configured :unknown]]
   [:model/fetched-at {:optional true} inst?]
   [:model/sources {:optional true} [:vector ModelSourceDescriptor]]
   [:model/cost-source {:optional true} ModelSourceDescriptor]])

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
;; Parsers (per wire shape) - public so tests can drive them with fixtures
;; ---------------------------------------------------------------------------

(defn parse-openai-style
  "Shape: {object 'list', data [{id, object, created, owned_by}]}.
   Used by OpenAI, DeepSeek, Kimi - none surface context length or
   pricing via /models, so entries are id-only."
  [body provider-id source-url]
  (let [ts (now)]
    (mapv (fn [m]
            {:model/id (:id m)
             :model/provider provider-id
             :model/source :live-models-api
             :model/source-url source-url
             :model/source-freshness :current
             :model/availability :listed
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
                     :model/source-freshness :current
                     :model/availability :listed
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

(defn- strip-model-name-prefix [^String s]
  (cond
    (nil? s) s
    (str/starts-with? s "publishers/google/models/") (subs s (count "publishers/google/models/"))
    (str/starts-with? s "models/") (subs s 7)
    :else s))

(defn parse-gemini-models
  "Shape (Gemini Native + Vertex):
     Native - {models [{name 'models/gemini-2.5-pro', ...}]}
     Vertex - {publisherModels [{name 'publishers/google/models/...', ...}]}
   Each entry carries displayName, inputTokenLimit, outputTokenLimit,
   supportedGenerationMethods (mapped to capability keywords)."
  [body provider-id source-url]
  (let [ts (now)
        entries (or (:models body) (:publisherModels body))]
    (mapv (fn [m]
            (let [model-id (strip-model-name-prefix (:name m))
                  caps (->> (:supportedGenerationMethods m)
                            (keep gemini-method->capability)
                            (into #{}))]
              (cond-> {:model/id model-id
                       :model/provider provider-id
                       :model/source :live-models-api
                       :model/source-url source-url
                       :model/source-freshness :current
                       :model/availability :listed
                       :model/fetched-at ts}
                (:displayName m) (assoc :model/display-name (:displayName m))
                (:inputTokenLimit m) (assoc :model/context-length (:inputTokenLimit m))
                (:outputTokenLimit m) (assoc :model/max-output-tokens (:outputTokenLimit m))
                (seq caps) (assoc :model/capabilities caps))))
          entries)))

(defn- parse-decimal
  "Parse a decimal string or numeric value. Returns nil for nil/empty/malformed
   input. OpenRouter documents pricing as decimal strings, but the live
   endpoint may emit JSON numbers for the same fields."
  [x]
  (cond
    (number? x) (double x)
    (and (string? x) (seq x))
    (try (Double/parseDouble x)
         (catch Exception _ nil))
    :else nil))

(defn- parse-decimal-string
  "OpenRouter encodes per-token pricing as decimal strings. Convert to
   per-million USD."
  [s]
  (some-> (parse-decimal s) (* 1000000.0)))

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
                         (or (parse-decimal-string (:cache_read pricing))
                             (parse-decimal-string (:input_cache_read pricing)))
                         (assoc :cache-read-per-million
                                (or (parse-decimal-string (:cache_read pricing))
                                    (parse-decimal-string (:input_cache_read pricing))))
                         (or (parse-decimal-string (:cache_write pricing))
                             (parse-decimal-string (:input_cache_write pricing)))
                         (assoc :cache-write-per-million
                                (or (parse-decimal-string (:cache_write pricing))
                                    (parse-decimal-string (:input_cache_write pricing))))
                         (parse-decimal (:request pricing))
                         (assoc :request-cost
                                (parse-decimal (:request pricing)))
                         (parse-decimal (:image pricing))
                         (assoc :image-per-image
                                (parse-decimal (:image pricing)))
                         (parse-decimal (:web_search pricing))
                         (assoc :search-per-call
                                (parse-decimal (:web_search pricing))))
                  base {:model/id (:id m)
                        :model/provider provider-id
                        :model/source :live-models-api
                        :model/source-url source-url
                        :model/source-freshness :current
                        :model/availability :listed
                        :model/fetched-at ts}]
              (cond-> base
                (:name m) (assoc :model/display-name (:name m))
                (:context_length m) (assoc :model/context-length (:context_length m))
                (:max_completion_tokens top)
                (assoc :model/max-output-tokens (:max_completion_tokens top))
                (seq cost) (assoc :model/cost cost))))
          (:data body))))

;; ---------------------------------------------------------------------------
;; Vertex auth - full ADC chain, same code path as sdk/complete uses
;; for actual chat requests. See llm.sdk.gcp-auth for the resolution
;; order (provider-opts > env bearer > SA JSON > authorized_user
;; well-known file > GCE metadata server).
;; ---------------------------------------------------------------------------

(defn- vertex-auth-token
  "Resolve a Vertex OAuth token via the same ADC chain that sdk/complete
   uses for chat requests. /models fetches don't carry a request map,
   so we pass an empty one - every layer of the chain still gets a
   chance via env, well-known file, or metadata server.

   Falls back to a synthetic profile when get-provider returns nil
   (test paths that don't load the full SDK)."
  []
  (gcp-auth/resolve-access-token
   {} (or (provider/get-provider :vertex-gemini)
          {:profile/id :vertex-gemini})))

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
  ;; The provider profile may not be registered yet (tests that pull
  ;; in llm.sdk.models without loading the full SDK), so degrade
  ;; gracefully: get-provider returns nil and resolve-project/auth
  ;; fall through to env vars and the well-known file.
  (let [p (or (provider/get-provider :vertex-gemini)
              {:profile/id :vertex-gemini})
        project (gcp-auth/resolve-project {} p)
        raw-location (or (get-in p [:profile/quirks :vertex-location])
                         (System/getenv "GOOGLE_CLOUD_LOCATION"))
        ;; The Vertex /publishers/google/models catalog endpoint does
        ;; NOT serve location=global (returns 404). Fall back to a
        ;; regional host for the catalog probe only - chat completion
        ;; elsewhere keeps honouring whatever the user configured.
        location (cond
                   (nil? raw-location) "us-central1"
                   (= raw-location "global") "us-central1"
                   :else raw-location)]
    (when-not project
      (throw (ex-info (str "Vertex /models needs a GCP project for the "
                           "X-Goog-User-Project quota header. Set "
                           "GOOGLE_CLOUD_PROJECT, or run "
                           "`gcloud auth application-default login --quota-project=<project>` "
                           "so ADC picks it up from the well-known file.")
                      {:provider :vertex-gemini :error :missing-config})))
    (let [;; The catalog is at /v1beta1/publishers/google/models on the
          ;; regional aiplatform host. It's NOT project-scoped in the
          ;; path - the project is supplied via the X-Goog-User-Project
          ;; quota header instead.
          url (str "https://" location "-aiplatform.googleapis.com/v1beta1/publishers/google/models")
          token (vertex-auth-token)
          headers {"Authorization" (str "Bearer " token)
                   "X-Goog-User-Project" project
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
  (let [profile (provider/get-provider pid)]
    (if (and (true? (:profile/supports-model-listing profile))
             (= :openai-chat (:profile/protocol-family profile)))
      (openai-style-fetch pid)
      (throw (ex-info (str "Provider does not support live /models: " pid)
                      {:provider pid :error :unsupported})))))

;; ---------------------------------------------------------------------------
;; Supported providers
;; ---------------------------------------------------------------------------

(def ^:private native-listing-providers
  "Providers with non-generic listing implementations. The fallback set keeps
   direct use of this namespace useful before adapter namespaces register their
   profiles; registered profile metadata is authoritative when present."
  #{:openai :anthropic :gemini-native :vertex-gemini :openrouter})

(defn supports-models-listing?
  "Does this provider expose a /models endpoint we can call? Registered
   profile metadata is authoritative. Unknown custom providers are not assumed
   to use the generic OpenAI-compatible path."
  [provider-id]
  (if-let [profile (provider/get-provider provider-id)]
    (true? (:profile/supports-model-listing profile))
    (contains? native-listing-providers provider-id)))

(defn listing-provider-ids
  "Current registered/native providers whose profiles advertise model listing."
  []
  (->> (concat native-listing-providers (provider/list-providers))
       set
       (filter supports-models-listing?)
       set))
