(ns llm.sdk
  "Public API for clojure-llm-sdk.
   Complete, embed, stream, list-models, capabilities, normalize-usage,
   estimate-cost, provider registration."
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
            [llm.sdk.embed :as embed-driver]
            [llm.sdk.moderate :as moderate-driver]
            [llm.sdk.rerank :as rerank-driver]
            [llm.sdk.image :as image-driver]
            [llm.sdk.transcribe :as transcribe-driver]
            [llm.sdk.speak :as speak-driver]
            [llm.sdk.fallbacks :as fallbacks]
            [llm.sdk.request :as request]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.aws-eventstream :as aws-eventstream]
            ;; Ensure provider adapters are loaded so their transport
            ;; constructors are registered
            [llm.sdk.providers.openai-chat]
            [llm.sdk.providers.openai-embed]
            [llm.sdk.providers.openai-moderation]
            [llm.sdk.providers.openai-image]
            [llm.sdk.providers.vertex-imagen]
            [llm.sdk.providers.bedrock-image]
            [llm.sdk.providers.openai-transcribe]
            [llm.sdk.providers.openai-speak]
            [llm.sdk.providers.elevenlabs]
            [llm.sdk.providers.cohere-embed]
            [llm.sdk.providers.cohere-chat]
            [llm.sdk.providers.cohere-rerank]
            [llm.sdk.providers.voyage-rerank]
            [llm.sdk.providers.anthropic]
            [llm.sdk.providers.gemini-native]
            [llm.sdk.providers.vertex-gemini]
            [llm.sdk.providers.codex]
            [llm.sdk.providers.openrouter]
            [llm.sdk.providers.perplexity]
            [llm.sdk.providers.bedrock]
            [llm.sdk.providers.ollama-native]
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

(defn- sign-if-needed [profile req] (aws-sigv4/maybe-sign profile req))

(defn- binary-stream-events
  "Bedrock-style streaming: open a binary connection, decode AWS event-
   stream frames, hand each parsed frame to parse-stream-event."
  [transport profile req]
  (let [{:keys [body status]} (http/binary-stream-request req)]
    (when (and status (>= status 400))
      (throw (ex-info "Provider streaming API error"
                      {:status status :provider (:profile/id profile)})))
    (->> (aws-eventstream/frame-seq body)
         (map aws-eventstream/frame->json)
         (mapcat (fn [frame]
                   (let [ev (transport/parse-stream-event transport profile frame)]
                     (cond
                       (nil? ev) nil
                       (sequential? ev) ev
                       :else [ev])))))))

(defn- stamp [resp provider-id model]
  (when resp
    (pricing/stamp-response-cost-and-cache resp provider-id model)))

(defn- normalize-retry-policy
  "Translate the :retry kwarg into either nil (one-shot) or a policy map.
   true → default-policy; a map → merged into default-policy so callers
   can override only the keys they care about."
  [retry]
  (cond
    (or (nil? retry) (false? retry)) nil
    (true? retry) (retry/default-policy)
    (map? retry) (merge (retry/default-policy) retry)
    :else nil))

(defn- retry-after-ms-from
  "Read the Retry-After header off a hato response and convert to ms.
   Header lookup is case-insensitive at the source but hato returns a
   keyword-or-string map — we try both spellings."
  [resp]
  (let [headers (:headers resp)
        v (or (get headers "retry-after")
              (get headers "Retry-After")
              (get headers :retry-after))]
    (retry/parse-retry-after v)))

(def ^:dynamic *retry-sleep-fn*
  "Indirection for tests — replace to drive retry without actually
   sleeping. Default is Thread/sleep."
  (fn [ms] (Thread/sleep (long ms))))

(defn- complete-non-streaming
  "Run the non-streaming request path, with optional retry. `policy`
   is nil (one-shot) or a normalized policy map."
  [transport profile req provider-id model policy]
  (loop [attempt 1]
    (let [outcome
          (try
            (let [resp (http/request req)
                  status (:status resp)
                  body (:body resp)]
              (if (and (number? status) (>= status 400))
                {:tag :http-error
                 :err (transport/parse-error transport profile status body)
                 :status status :body body :resp resp}
                {:tag :ok
                 :response (transport/parse-response transport profile body)}))
            (catch Exception e
              {:tag :exception :ex e}))]
      (case (:tag outcome)
        :ok
        (stamp (:response outcome) provider-id model)

        :http-error
        (let [classified (:err outcome)
              decision (when policy (retry/should-retry? classified policy attempt))]
          (if (:retry? decision)
            (let [wait (max (or (retry-after-ms-from (:resp outcome)) 0)
                            (:delay-ms decision))]
              (*retry-sleep-fn* wait)
              (recur (inc attempt)))
            (throw (ex-info "Provider API error"
                            {:error classified
                             :status (:status outcome)
                             :body (:body outcome)
                             :provider provider-id
                             :attempts attempt}))))

        :exception
        (let [e (:ex outcome)
              classified (errors/classify-error e :provider provider-id)
              decision (when policy (retry/should-retry? classified policy attempt))]
          (if (:retry? decision)
            (do (*retry-sleep-fn* (:delay-ms decision))
                (recur (inc attempt)))
            (throw e)))))))

(defn complete
  "Send a canonical request and return a canonical response.
   Provider must be a registered provider keyword (e.g. :openai).
   Request is a map conforming to llm.sdk.schema/Request.

   The returned response is stamped with :response/cost and
   :response/cache derived from its :response/usage. When pricing or
   cache stats are unknown, those fields carry honest :unknown markers
   — never substituted 0/$0.

   Options:
     :stream?   If true, returns a lazy seq of stream events
                (or a list-of-events plus terminal response when
                :on-event is given).
     :on-event  Callback fn for each stream event (only if stream? true).
     :retry     Opt-in retry policy. nil/false → one-shot (default).
                true → use llm.sdk.retry/default-policy. A map → merged
                into the default policy; supply only the keys you want
                to override (e.g. {:retry/max-attempts 5}).
                Streaming requests are not retried — partial streams
                can't be safely resumed by the SDK."
  [provider-id request & {:keys [stream? on-event retry]}]
  (let [profile (or (provider/get-provider provider-id)
                    (throw (ex-info "Unknown provider" {:provider provider-id})))
        transport ((:profile/transport-constructor profile))
        ;; T2-12 — strip canonical fields the provider doesn't support
        ;; (and warn) when the profile opts in via :profile/supported-params.
        request (request/apply-supported-params profile request)
        ;; Adapters may need to know if streaming so they can pick the
        ;; right URL or shape (Bedrock /converse vs /converse-stream).
        request (cond-> request stream? (assoc :request/stream? true))
        req (transport/build-request transport profile request)
        req (sign-if-needed profile req)
        binary-stream? (= :aws-eventstream (:profile/binary-stream profile))
        model (:request/model request)]
    (if stream?
      ;; Streaming path — retry NOT applied; a partially-consumed stream
      ;; can't be safely resumed by the SDK. Wrap your own retry loop
      ;; if you need it.
      (let [events (if binary-stream?
                     (binary-stream-events transport profile req)
                     (let [ev-seq (http/sse-request req)]
                       (mapcat (fn [line]
                                 (let [ev (transport/parse-stream-event
                                           transport profile line)]
                                   (cond
                                     (nil? ev) nil
                                     (sequential? ev) ev
                                     :else [ev])))
                               ev-seq)))
            parsed-events (concat [(stream/start-event)] events [(stream/end-event)])]
        (if on-event
          (do (doseq [ev parsed-events] (on-event ev))
              (stamp (stream/events->response parsed-events provider-id model)
                     provider-id model))
          parsed-events))
      ;; Non-streaming path
      (complete-non-streaming transport profile req provider-id model
                              (normalize-retry-policy retry)))))

;; ---------------------------------------------------------------------------
;; Embed
;; ---------------------------------------------------------------------------

(defn embed
  "Send a canonical embed request and return a canonical EmbedResponse.
   Provider must be a registered provider whose profile carries a
   :profile/embed-transport-constructor (currently :openai under T2-01;
   T2-07 extends this to Cohere/Voyage/Mistral/Together/Jina).
   Request keys: :embed/model, :embed/inputs (vector of strings),
   plus optional :embed/dimensions, :embed/encoding-format,
   :embed/user, :embed/provider-options."
  [provider-id request]
  (embed-driver/embed provider-id request))

;; ---------------------------------------------------------------------------
;; Moderate
;; ---------------------------------------------------------------------------

(defn moderate
  "Send a canonical moderation request and return a ModerationResponse.
   Provider must carry :profile/moderation-transport-constructor
   (currently :openai under T2-13).

   :moderation/inputs is a vector of either strings or maps shaped
   {:type :text :text \"...\"} / {:type :image_url :image_url \"https://...\"}.
   omni-moderation models accept the multi-modal shape; text-moderation
   models are text-only."
  [provider-id request]
  (moderate-driver/moderate provider-id request))

;; ---------------------------------------------------------------------------
;; Rerank
;; ---------------------------------------------------------------------------

(defn rerank
  "Send a canonical rerank request and return a RerankResponse.
   Providers carrying :profile/rerank-transport-constructor (currently
   :cohere, :jina, :voyage under T2-16).

   Required keys: :rerank/model, :rerank/query, :rerank/documents
   (vector of strings). Optional: :rerank/top-n,
   :rerank/return-documents, :rerank/provider-options."
  [provider-id request]
  (rerank-driver/rerank provider-id request))

;; ---------------------------------------------------------------------------
;; Image generation
;; ---------------------------------------------------------------------------

(defn generate-image
  "Send a canonical image generation request and return an ImageGenResponse.
   Provider must carry :profile/image-transport-constructor
   (currently :openai under T2-10).

   Required: :image/prompt. Optional: :image/model, :image/n,
   :image/size, :image/quality, :image/style,
   :image/response-format, :image/user, :image/provider-options."
  [provider-id request]
  (image-driver/generate-image provider-id request))

;; ---------------------------------------------------------------------------
;; Transcribe
;; ---------------------------------------------------------------------------

(defn transcribe
  "Send a canonical audio-transcription request and return a TranscribeResponse.
   Provider must carry :profile/transcribe-transport-constructor
   (currently :openai and :groq under T2-14).

   Required: :transcribe/file (java.io.File / path / bytes / InputStream)
   and :transcribe/model. Optional: :transcribe/language,
   :transcribe/prompt, :transcribe/temperature,
   :transcribe/response-format (:json|:text|:srt|:verbose_json|:vtt),
   :transcribe/timestamp-granularities (#{:segment :word}),
   :transcribe/provider-options."
  [provider-id request]
  (transcribe-driver/transcribe provider-id request))

;; ---------------------------------------------------------------------------
;; Speak (text-to-speech)
;; ---------------------------------------------------------------------------

(defn speak
  "Send a canonical text-to-speech request and return a SpeakResponse
   {:audio/bytes byte-array :audio/content-type str ...}.
   Provider must carry :profile/speak-transport-constructor
   (currently :openai and :elevenlabs under T2-15).

   Required: :speak/model, :speak/input. Optional: :speak/voice,
   :speak/format (:mp3|:opus|:aac|:flac|:wav|:pcm), :speak/speed,
   :speak/instructions, :speak/provider-options."
  [provider-id request]
  (speak-driver/speak provider-id request))

;; ---------------------------------------------------------------------------
;; Fallbacks
;; ---------------------------------------------------------------------------

(defn with-fallbacks
  "Try each [provider-id model-id] pair in order against the given
   request, returning the first success. If all fail, throws ex-info
   with :attempts (vector of failure maps) and :providers.

   No credential pools, no cooldowns, no rate-limit tracking — that's
   credential-pool routing, which is explicitly out of scope for this
   SDK. Compose this with your own resilience layer for those needs."
  ([providers request]
   (fallbacks/with-fallbacks providers request))
  ([providers request opts]
   (fallbacks/with-fallbacks providers request opts)))

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

(defn canonical-cost
  "Build a canonical :response/cost map (the same shape complete/embed
   stamp on responses). Useful for after-the-fact attribution.
   Returns {:cost/usd :unknown :cost/estimated? true ...} when pricing
   or usage is unknown."
  [provider model usage]
  (pricing/canonical-cost provider model usage))

(defn canonical-cache
  "Build a canonical :response/cache map from a canonical Usage."
  [usage]
  (pricing/canonical-cache (or usage {})))

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
