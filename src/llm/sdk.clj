(ns llm.sdk
  "Public API for clojure-llm-sdk.
   Complete, embed, stream, list-models, capabilities, normalize-usage,
   estimate-cost, provider registration."
  (:require [clojure.string :as str]
            [llm.sdk.provider :as provider]
            [llm.sdk.provider.auth :as provider-auth]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport :as transport]
            [llm.sdk.http :as http]
            [llm.sdk.sse :as sse]
            [llm.sdk.websocket :as websocket]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.catalog :as catalog]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]
            [llm.sdk.retry :as retry]
            [llm.sdk.registry :as registry]
            [llm.sdk.embed :as embed-driver]
            [llm.sdk.moderate :as moderate-driver]
            [llm.sdk.rerank :as rerank-driver]
            [llm.sdk.image :as image-driver]
            [llm.sdk.transcribe :as transcribe-driver]
            [llm.sdk.speak :as speak-driver]
            [llm.sdk.fallbacks :as fallbacks]
            [llm.sdk.request :as request]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.aws-eventstream :as aws-eventstream])
  (:import [java.io BufferedReader Closeable InputStreamReader]
           [java.nio.charset StandardCharsets]))

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
;; Model catalog - registry-backed lookups
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

(declare stamp)

(defn- sign-if-needed [profile req] (aws-sigv4/maybe-sign profile req))

(defn- validate-chat-request! [request]
  (when-not (schema/validate-request request)
    (throw (ex-info "Invalid llm.sdk chat request"
                    {:error/type :schema/invalid-request
                     :schema/explain (schema/explain-request request)}))))

(defn- close-once!
  [resource closed?]
  (when (compare-and-set! closed? false true)
    (when (instance? Closeable resource)
      (.close ^Closeable resource))))

(defn- read-event!
  [cursor close-fn]
  (try
    (let [source @cursor]
      (if-let [remaining (seq source)]
        (let [event (first remaining)]
          ;; Retain only the unread tail. A concurrent close must not let a
          ;; blocked read restore the cursor after the resource is released.
          (when (compare-and-set! cursor source (rest remaining))
            event))
        (do (close-fn) nil)))
    (catch Throwable t
      (try (close-fn) (catch Throwable _))
      (throw t))))

(defn- event-sequence [cursor close-fn]
  (lazy-seq
   (when-let [event (read-event! cursor close-fn)]
     (cons event (event-sequence cursor close-fn)))))

(deftype StreamHandle [cursor close-fn]
  clojure.lang.Seqable
  (seq [_]
    (seq (event-sequence cursor close-fn)))

  clojure.lang.IReduceInit
  (reduce [this f init]
    (try
      (loop [acc init]
        (if-let [event (read-event! cursor close-fn)]
          (let [next-acc (f acc event)]
            (if (reduced? next-acc) @next-acc (recur next-acc)))
          acc))
      (finally (.close ^Closeable this))))

  clojure.lang.IReduce
  (reduce [this f]
    (try
      (if-let [event (read-event! cursor close-fn)]
        (loop [acc event]
          (if-let [event (read-event! cursor close-fn)]
            (let [next-acc (f acc event)]
              (if (reduced? next-acc) @next-acc (recur next-acc)))
            acc))
        (f))
      (finally (.close ^Closeable this))))

  Closeable
  (close [_]
    (close-fn)))

(defn- stream-handle
  [events resource]
  (let [cursor (atom events)
        closed? (atom false)
        close-fn #(do (reset! cursor nil)
                      (close-once! resource closed?))]
    (StreamHandle. cursor close-fn)))

(declare event->seq)

(defn- binary-stream-events
  "Open a Bedrock-style binary stream and return its body with lazily decoded
   canonical events. The public StreamHandle owns the body."
  [transport profile req]
  (let [{:keys [body status]} (http/binary-stream-request req)]
    (if (and status (>= status 400))
      (let [parsed-body (http/decode-body body)
            classified (transport/parse-error transport profile status parsed-body)]
        (throw (ex-info "Provider streaming API error"
                        {:error classified
                         :status status
                         :body parsed-body
                         :provider (:profile/id profile)})))
      {:body body
       :events
       (->> (aws-eventstream/frame-seq body)
            (map aws-eventstream/frame->json)
            (mapcat (fn [frame]
                      (event->seq
                       (transport/parse-stream-event transport profile frame)))))})))

(defn- event->seq [ev]
  (cond
    (nil? ev) nil
    (sequential? ev) ev
    :else [ev]))

(defn- parse-stream-lines
  "Frame standards-compliant SSE records (while preserving non-SSE lines), then
   parse each record without reading ahead into the following event."
  [transport profile lines]
  (letfn [(step [records]
            (lazy-seq
             (when-let [records (seq records)]
               (concat
                (event->seq
                 (transport/parse-stream-event transport profile (first records)))
                (step (rest records))))))]
    (step (sse/event-seq lines))))

(defn- collect-sse-events-until-end
  "Read an SSE InputStream eagerly until EOF or the provider emits a
   terminal :stream/end event, then close it. This is used by providers
   that return SSE even for the public non-streaming complete path."
  [transport profile body]
  (with-open [reader (BufferedReader.
                      (InputStreamReader. body StandardCharsets/UTF_8))]
    (loop [records (sse/event-seq (line-seq reader))
           events []
           raw []]
      (if-let [records (seq records)]
        (let [record (first records)
              parsed (vec (event->seq
                           (transport/parse-stream-event transport profile record)))
              events' (into events parsed)
              raw' (conj raw record)]
          (if (some #(= :stream/end (:event/type %)) parsed)
            {:events events'
             :raw (str (str/join "\n\n" raw') "\n\n")}
            (recur (rest records) events' raw')))
        {:events events
         :raw (str (str/join "\n\n" raw) "\n\n")}))))

(defn- streaming-response [req]
  (let [send (if (= :websocket (:transport req))
               websocket/response
               http/sse-response)
        outcome (try {:response (send req)}
                     (catch Exception e {:exception e}))
        response (:response outcome)
        exception (:exception outcome)
        failure (some-> exception ex-data)
        rejected? (or (= 401 (:status response))
                      (and (= 401 (:status failure))
                           (= :handshake (:phase failure))
                           (false? (:request-sent? failure))))]
    (if (and rejected? (:auth/recover! req))
      (do
        (when (instance? Closeable (:body response))
          (.close ^Closeable (:body response)))
        ;; Authentication rejection precedes generation. Retry once with
        ;; refreshed credentials; never replay a partially consumed response.
        (send (-> req
                  (update :headers provider-auth/replace-auth-headers
                          ((:auth/recover! req)))
                  (dissoc :auth/recover!))))
      (if exception (throw exception) response))))

(defn- complete-sse-non-streaming
  [transport profile req provider-id model]
  (let [{:keys [status body]} (streaming-response req)]
    (if (and (number? status) (>= status 400))
      (let [classified (transport/parse-error transport profile status body)]
        (throw (ex-info "Provider API error"
                        {:error classified
                         :status status
                         :body body
                         :provider provider-id
                         :attempts 1})))
      (let [{:keys [events raw]} (collect-sse-events-until-end transport profile body)
            has-end? (some #(= :stream/end (:event/type %)) events)
            parsed-events (concat [(stream/start-event)]
                                  events
                                  (when-not has-end?
                                    [(stream/end-event :finish-reason :incomplete)]))
            parsed (try
                     (transport/parse-response transport profile raw)
                     (catch Throwable _
                       (stream/events->response parsed-events provider-id model)))]
        (stamp parsed
               provider-id model)))))

(defn- canonicalize-chat-response [resp provider-id model]
  (let [resp (cond-> resp
               (nil? (:response/provider resp)) (assoc :response/provider provider-id)
               (nil? (:response/model resp)) (assoc :response/model model))]
    (cond-> resp
      (nil? (:response/id resp)) (dissoc :response/id)
      (nil? (:response/tool-calls resp)) (dissoc :response/tool-calls)
      (nil? (:response/usage resp)) (dissoc :response/usage)
      (nil? (:response/cost resp)) (dissoc :response/cost)
      (nil? (:response/cache resp)) (dissoc :response/cache)
      (nil? (:response/provider-data resp)) (dissoc :response/provider-data))))

(defn- stamp [resp provider-id model]
  (when resp
    (-> resp
        (canonicalize-chat-response provider-id model)
        (pricing/stamp-response-cost-and-cache provider-id model)
        (canonicalize-chat-response provider-id model))))

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
   keyword-or-string map - we try both spellings."
  [resp]
  (let [headers (:headers resp)
        v (or (get headers "retry-after")
              (get headers "Retry-After")
              (get headers :retry-after))]
    (retry/parse-retry-after v)))

(def ^:dynamic *retry-sleep-fn*
  "Indirection for tests - replace to drive retry without actually
   sleeping. Default is Thread/sleep."
  (fn [ms] (Thread/sleep (long ms))))

(defn- complete-non-streaming
  "Run the non-streaming request path, with optional retry. `policy`
   is nil (one-shot) or a normalized policy map."
  [transport profile req provider-id model policy]
  (if (true? (get-in req [:body :stream]))
    (complete-sse-non-streaming transport profile req provider-id model)
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
              (throw (ex-info "Provider transport error"
                              {:error classified
                               :provider provider-id
                               :attempts attempt}
                              e)))))))))

(defn- ensure-terminal-end
  "Move provider end markers behind any trailing metadata and emit exactly one
   SDK terminal event. EOF without a provider end is classified as incomplete
   so an unexpectedly truncated stream is observable."
  [events]
  (letfn [(step [remaining terminal-seen? finish-reason]
            (lazy-seq
             (if-let [remaining (seq remaining)]
               (let [event (first remaining)]
                 (if (= :stream/end (:event/type event))
                   (step (rest remaining)
                         true
                         (or (:event/finish-reason event) finish-reason))
                   (cons event
                         (step (rest remaining)
                               terminal-seen?
                               finish-reason))))
               (list (stream/end-event
                      :finish-reason
                      (if terminal-seen?
                        (or finish-reason :unknown)
                        :incomplete))))))]
    (step events false nil)))

(defn- close-quietly!
  [closeable]
  (when (instance? java.io.Closeable closeable)
    (try
      (.close ^java.io.Closeable closeable)
      (catch Throwable _))))

(defn- stop-at-provider-end
  "The Codex backend's SSE response end is also its transport terminator. Stop
   reading there so a server that keeps the HTTP connection alive cannot stall
   SDK terminal delivery. Other protocols must remain open for usage trailers."
  [events]
  (letfn [(step [remaining]
            (lazy-seq
             (when-let [remaining (seq remaining)]
               (let [event (first remaining)]
                 (if (= :stream/end (:event/type event))
                   (list event)
                   (cons event (step (rest remaining))))))))]
    (step events)))

(defn complete
  "Send a canonical request and return a canonical response.
   Provider must be a registered provider keyword (e.g. :openai).
   Request is a map conforming to llm.sdk.schema/Request.

   The returned response is stamped with :response/cost and
   :response/cache derived from its :response/usage. When pricing or
   cache stats are unknown, those fields carry honest :unknown markers
   - never substituted 0/$0.

   Options:
     :stream?   If true, returns a lazy, seqable and reducible Closeable stream
                owner (or the terminal response when :on-event is given).
     :on-event  Callback fn for each stream event (only if stream? true).
     :retry     Opt-in retry policy. nil/false → one-shot (default).
                true → use llm.sdk.retry/default-policy. A map → merged
                into the default policy; supply only the keys you want
                to override (e.g. {:retry/max-attempts 5}).
                Streaming requests are not retried - partial streams
                can't be safely resumed by the SDK.
     :config    Per-call runtime config: :api-key/:auth-token,
                :base-url, :headers, :http-client,
                :connect-timeout-ms, :timeout-ms.
                ChatGPT OAuth (:codex-backend) defaults to HTTP/SSE;
                :transport :websocket enables persistent incremental Responses.
                :incremental? false disables automatic WebSocket continuation."
  [provider-id request & {:keys [stream? on-event retry config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider" {:provider provider-id})))
        _ (validate-chat-request! request)
        constructor (or (:profile/transport-constructor profile)
                        (throw (ex-info "Provider does not support chat completions"
                                        {:provider provider-id
                                         :capability :chat})))
        transport (constructor)
        ;; Strip canonical fields the provider doesn't support
        ;; (and warn) when the profile opts in via :profile/supported-params.
        request (request/apply-supported-params profile request)
        ;; Adapters may need to know if streaming so they can pick the
        ;; right URL or shape (Bedrock /converse vs /converse-stream).
        request (cond-> request stream? (assoc :request/stream? true))
        req (transport/build-request transport profile request)
        req (provider/apply-http-options profile req)
        req (sign-if-needed profile req)
        req (if (= provider-id :codex-backend)
              (let [mode (get config :transport :sse)]
                (when-not (#{:websocket :sse} mode)
                  (throw (ex-info "Unsupported Codex backend transport"
                                  {:provider provider-id :transport mode})))
                (assoc req :transport mode
                       :incremental? (get config :incremental? true)))
              req)
        binary-stream? (= :aws-eventstream (:profile/binary-stream profile))
        model (:request/model request)]
    (if stream?
      ;; Streaming path - retry NOT applied; a partially-consumed stream
      ;; can't be safely resumed by the SDK. Wrap your own retry loop
      ;; if you need it.
      (let [{:keys [body events]}
            (if binary-stream?
              (binary-stream-events transport profile req)
              (let [{:keys [status body]} (streaming-response req)]
                (when (and (number? status) (>= status 400))
                  (try
                    (let [classified
                          (transport/parse-error transport profile status body)]
                      (throw (ex-info "Provider streaming API error"
                                      {:error classified
                                       :status status
                                       :body body
                                       :provider provider-id})))
                    (finally
                      (close-quietly! body))))
                {:body body
                 :events
                 (parse-stream-lines transport profile
                                     (http/line-seq-closeable body))}))
            events (if (and (= :codex-backend provider-id)
                            (= :sse (:transport req)))
                     (stop-at-provider-end events)
                     events)
            parsed-events (concat [(stream/start-event)]
                                  (ensure-terminal-end events))
            handle (stream-handle parsed-events body)]
        (if on-event
          (let [acc (reduce (fn [acc event]
                              (on-event event)
                              (stream/reduce-event acc event))
                            (stream/empty-accumulator)
                            handle)]
            (stamp (stream/acc->response acc provider-id model)
                   provider-id model))
          handle))
      ;; Non-streaming path
      (complete-non-streaming transport profile req provider-id model
                              (normalize-retry-policy retry)))))

;; ---------------------------------------------------------------------------
;; Embed
;; ---------------------------------------------------------------------------

(defn embed
  "Send a canonical embed request and return a canonical EmbedResponse.
   Provider must be a registered provider whose profile carries a
   :profile/embed-transport-constructor.
   Request keys: :embed/model, :embed/inputs (vector of strings),
   plus optional :embed/dimensions, :embed/encoding-format,
   :embed/user, :embed/provider-options."
  [provider-id request & opts]
  (apply embed-driver/embed provider-id request opts))

;; ---------------------------------------------------------------------------
;; Moderate
;; ---------------------------------------------------------------------------

(defn moderate
  "Send a canonical moderation request and return a ModerationResponse.
   Provider must carry :profile/moderation-transport-constructor
   in its profile.

   :moderation/inputs is a vector of either strings or maps shaped
   {:type :text :text \"...\"} / {:type :image_url :image_url \"https://...\"}.
   omni-moderation models accept the multi-modal shape; text-moderation
   models are text-only."
  [provider-id request & opts]
  (apply moderate-driver/moderate provider-id request opts))

;; ---------------------------------------------------------------------------
;; Rerank
;; ---------------------------------------------------------------------------

(defn rerank
  "Send a canonical rerank request and return a RerankResponse.
   Provider must carry :profile/rerank-transport-constructor
   in its profile.

   Required keys: :rerank/model, :rerank/query, :rerank/documents
   (vector of strings). Optional: :rerank/top-n,
   :rerank/return-documents, :rerank/provider-options."
  [provider-id request & opts]
  (apply rerank-driver/rerank provider-id request opts))

;; ---------------------------------------------------------------------------
;; Image generation
;; ---------------------------------------------------------------------------

(defn generate-image
  "Send a canonical image generation request and return an ImageGenResponse.
   Provider must carry :profile/image-transport-constructor
   in its profile.

   Required: :image/prompt. Optional: :image/model, :image/n,
   :image/size, :image/quality, :image/style,
   :image/response-format, :image/user, :image/provider-options."
  [provider-id request & opts]
  (apply image-driver/generate-image provider-id request opts))

;; ---------------------------------------------------------------------------
;; Transcribe
;; ---------------------------------------------------------------------------

(defn transcribe
  "Send a canonical audio-transcription request and return a TranscribeResponse.
   Provider must carry :profile/transcribe-transport-constructor
   in its profile.

   Required: :transcribe/file (java.io.File / path / bytes / InputStream)
   and :transcribe/model. Optional: :transcribe/language,
   :transcribe/prompt, :transcribe/temperature,
   :transcribe/response-format (:json|:text|:srt|:verbose_json|:vtt),
   :transcribe/timestamp-granularities (#{:segment :word}),
   :transcribe/provider-options."
  [provider-id request & opts]
  (apply transcribe-driver/transcribe provider-id request opts))

;; ---------------------------------------------------------------------------
;; Speak (text-to-speech)
;; ---------------------------------------------------------------------------

(defn speak
  "Send a canonical text-to-speech request and return a SpeakResponse
   {:audio/bytes byte-array :audio/content-type str ...}.
   Provider must carry :profile/speak-transport-constructor
   in its profile.

   Required: :speak/model, :speak/input. Optional: :speak/voice,
   :speak/format (:mp3|:opus|:aac|:flac|:wav|:pcm), :speak/speed,
   :speak/instructions, :speak/provider-options."
  [provider-id request & opts]
  (apply speak-driver/speak provider-id request opts))

;; ---------------------------------------------------------------------------
;; Fallbacks
;; ---------------------------------------------------------------------------

(defn with-fallbacks
  "Try each [provider-id model-id] pair in order against the given
   request, returning the first success. If all fail, throws ex-info
   with :attempts (vector of failure maps) and :providers.

   No credential pools, no cooldowns, no rate-limit tracking - that's
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
