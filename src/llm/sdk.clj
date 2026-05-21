(ns llm.sdk
  "Public API for clojure-llm-sdk.
   Complete, stream, list-models, capabilities, normalize-usage, estimate-cost,
   provider registration."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.http :as http]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.catalog :as catalog]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]
            [llm.sdk.retry :as retry]
            [llm.sdk.registry :as registry]
            [llm.sdk.models :as models]
            ;; Ensure provider adapters are loaded so their transport
            ;; constructors are registered
            [llm.sdk.providers.openai-chat]
            [llm.sdk.providers.anthropic]
            [llm.sdk.providers.gemini-native]
            [llm.sdk.providers.vertex-gemini]
            [llm.sdk.providers.codex]
            [llm.sdk.providers.openrouter]
            [llm.sdk.providers.bedrock]
            [llm.sdk.providers.fake]))

;; ---------------------------------------------------------------------------
;; Provider discovery
;; ---------------------------------------------------------------------------

(defn list-providers
  "Return a seq of registered provider keywords."
  []
  (provider/list-providers))

(defn provider-profile
  "Get a provider profile by keyword."
  [provider-id]
  (provider/get-provider provider-id))

;; ---------------------------------------------------------------------------
;; Model catalog — registry-backed lookups
;; ---------------------------------------------------------------------------

(defn list-models
  "List known model ids. With no args, returns a sorted distinct seq
   across every provider the registry knows. With a provider keyword,
   returns the ModelEntry maps under that provider."
  ([] (catalog/list-models))
  ([provider-id] (catalog/models-by-provider provider-id)))

(defn model-capabilities
  "Return the capability set for a model. Single-arg form scans across
   providers (prefers native over alias); two-arg form is provider-aware."
  ([model-id]
   (:model/capabilities (catalog/get-model model-id)))
  ([provider-id model-id]
   (:model/capabilities (catalog/get-model provider-id model-id))))

(defn model-context-length
  "Return the context length for a model in tokens, or nil if unknown."
  ([model-id] (catalog/context-length model-id))
  ([provider-id model-id] (catalog/context-length provider-id model-id)))

(defn model-info
  "Return the full registry ModelEntry for (provider, model), or just
   (model) when the id is globally unique. Includes context-length,
   max-output-tokens, capabilities, cost, source provenance."
  ([model-id] (catalog/get-model model-id))
  ([provider-id model-id] (catalog/get-model provider-id model-id)))

(defn refresh-models!
  "Hit each provider's live /models endpoint and merge results into the
   registry's live tier. With no args, refreshes every provider that
   supports live model listing (skipping :codex/:codex-backend/:bedrock).
   With :provider <kw>, refreshes only that one. Returns a map of
   provider → {:count n} or {:error msg :data data}."
  [& {:keys [provider]}]
  (if provider
    {provider (try {:count (count (registry/refresh! provider))}
                   (catch Exception e
                     {:error (ex-message e) :data (ex-data e)}))}
    (registry/refresh-all!)))

(defn register-model-info
  "Inject a caller-provided model entry into the registry override
   tier. Useful when targeting custom endpoints models.dev doesn't
   know about. Entry takes the canonical ModelEntry shape minus the
   provider/id (those are passed explicitly)."
  [provider-id model-id entry]
  (registry/register-entry! provider-id model-id entry))

;; ---------------------------------------------------------------------------
;; Complete
;; ---------------------------------------------------------------------------

(defn complete
  "Send a canonical request and return a canonical response.
   Provider must be a registered provider keyword (e.g. :openai).
   Request is a map conforming to llm.sdk.schema/Request.
   Options:
     :stream?   If true, returns a core.async channel of stream events.
     :on-event  Callback fn for each stream event (only if stream? true)."
  [provider-id request & {:keys [stream? on-event]}]
  (let [profile (or (provider/get-provider provider-id)
                    (throw (ex-info "Unknown provider" {:provider provider-id})))
        transport ((:profile/transport-constructor profile))
        req (transport/build-request transport profile request)]
    (if stream?
      ;; Streaming path
      (let [events (http/sse-request req)
            parsed-events (keep #(transport/parse-stream-event transport profile %) events)
            parsed-events (concat [(stream/start-event)] parsed-events [(stream/end-event)])]
        (if on-event
          (do (doseq [ev parsed-events] (on-event ev))
              (stream/events->response parsed-events provider-id (:request/model request)))
          parsed-events))
      ;; Non-streaming path
      (let [resp (http/request req)
            status (:status resp)
            body (:body resp)]
        (if (>= status 400)
          (let [err (transport/parse-error transport profile status body)]
            (throw (ex-info "Provider API error"
                            {:error err
                             :status status
                             :body body
                             :provider provider-id})))
          (transport/parse-response transport profile body))))))

;; ---------------------------------------------------------------------------
;; Usage / pricing
;; ---------------------------------------------------------------------------

(defn normalize-usage
  "Normalize raw provider usage data to canonical Usage shape."
  [provider raw-usage]
  (usage/normalize-usage provider raw-usage))

(defn estimate-cost
  "Estimate cost for a provider+model given canonical Usage.
   Optionally fetch live pricing first with :fetch-pricing? true."
  [provider model usage & {:keys [fetch-pricing? api-key]}]
  (when fetch-pricing?
    (let [route (pricing/resolve-billing-route model :provider provider)]
      (pricing/fetch-pricing! route :api-key api-key)))
  (pricing/estimate-cost-for-model provider model usage))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(defn classify-error
  "Classify an exception or error response."
  [e & opts]
  (apply errors/classify-error e opts))

;; ---------------------------------------------------------------------------
;; Retry policy
;; ---------------------------------------------------------------------------

(defn default-retry-policy
  "Return the default retry policy map."
  []
  (retry/default-policy))

;; ---------------------------------------------------------------------------
;; Cache strategy inspection
;; ---------------------------------------------------------------------------

(defn cache-strategy
  "Inspect which cache strategy + layout the SDK will use for a given
   provider, model, and (optional) :request/cache map. Useful for
   debugging cache misses without sending a real request.

   Returns {:strategy :system-and-3|:prompt-key|:explicit|:cache-point|:none
            :layout   :native|:envelope|nil
            :reason   string}"
  ([provider-id model] (cache-strategy provider-id model nil))
  ([provider-id model request-cache]
   (when-let [profile (provider/get-provider provider-id)]
     (cache/decide-strategy profile model request-cache))))
