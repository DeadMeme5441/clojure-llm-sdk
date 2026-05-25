(ns llm.sdk.providers.bedrock
  "AWS Bedrock Converse API transport adapter.

   Auth: AWS Signature V4 — sdk/complete dispatches on
   :profile/auth-strategy :aws-sigv4 and signs the request via
   llm.sdk.aws-sigv4 just before the HTTP send.

   Streaming: Bedrock's /converse-stream emits binary event-stream
   frames (vnd.amazon.eventstream). sdk/complete reads the raw
   InputStream via llm.sdk.aws-eventstream/frame-seq and hands
   each parsed frame to parse-stream-event-bedrock as a map.

   Model-id mapping: canonical short ids (e.g. claude-sonnet-4-5,
   nova-pro) are mapped to Bedrock's region-versioned id format
   (e.g. anthropic.claude-sonnet-4-5-20250101-v1:0); unknown ids
   pass through verbatim so callers can provide explicit ARNs."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Model id mapping — canonical short id → Bedrock fully-qualified id
;; ---------------------------------------------------------------------------
;;
;; Bedrock model ids encode publisher, model, release date, and version
;; (`anthropic.claude-sonnet-4-20250514-v1:0`). Most callers want to
;; pass the short canonical id; this table covers the common shortcuts.
;; Anything not in the table is forwarded verbatim, which lets callers
;; supply full Bedrock ARNs / inference-profile ids when needed.

(def model-id-mapping
  {"claude-3-5-sonnet" "anthropic.claude-3-5-sonnet-20241022-v2:0"
   "claude-3-5-sonnet-latest" "anthropic.claude-3-5-sonnet-20241022-v2:0"
   "claude-3-5-haiku" "anthropic.claude-3-5-haiku-20241022-v1:0"
   "claude-3-opus" "anthropic.claude-3-opus-20240229-v1:0"
   "claude-3-sonnet" "anthropic.claude-3-sonnet-20240229-v1:0"
   "claude-3-haiku" "anthropic.claude-3-haiku-20240307-v1:0"
   "claude-3-7-sonnet" "anthropic.claude-3-7-sonnet-20250219-v1:0"
   "claude-sonnet-4" "anthropic.claude-sonnet-4-20250514-v1:0"
   "claude-sonnet-4-5" "anthropic.claude-sonnet-4-5-20250101-v1:0"
   "claude-opus-4" "anthropic.claude-opus-4-20250514-v1:0"
   "claude-opus-4-1" "anthropic.claude-opus-4-1-20250805-v1:0"
   "claude-haiku-4-5" "anthropic.claude-haiku-4-5-20251001-v1:0"
   "nova-micro" "amazon.nova-micro-v1:0"
   "nova-lite" "amazon.nova-lite-v1:0"
   "nova-pro" "amazon.nova-pro-v1:0"
   "nova-premier" "amazon.nova-premier-v1:0"
   "command-r" "cohere.command-r-v1:0"
   "command-r-plus" "cohere.command-r-plus-v1:0"
   "llama3-1-8b" "meta.llama3-1-8b-instruct-v1:0"
   "llama3-1-70b" "meta.llama3-1-70b-instruct-v1:0"
   "llama3-1-405b" "meta.llama3-1-405b-instruct-v1:0"
   "llama3-2-11b" "meta.llama3-2-11b-instruct-v1:0"
   "llama3-2-90b" "meta.llama3-2-90b-instruct-v1:0"
   "llama3-3-70b" "meta.llama3-3-70b-instruct-v1:0"
   "mistral-large" "mistral.mistral-large-2407-v1:0"
   "mistral-large-2402" "mistral.mistral-large-2402-v1:0"
   "deepseek-r1" "deepseek.r1-v1:0"})

(defn resolve-model-id
  "Translate a canonical short id to the Bedrock fully-qualified id.
   Pass-through if the id already looks Bedrock-shaped (contains '.' or ':')
   or if it isn't in the table."
  [model]
  (when model
    (or (get model-id-mapping model)
        (when (or (str/includes? model ".")
                  (str/includes? model ":")
                  (str/starts-with? model "arn:"))
          model)
        ;; Unknown short ids — fall back to the verbatim string and let
        ;; Bedrock surface the validation error to the caller.
        model)))

;; ---------------------------------------------------------------------------
;; Usage normalization
;; ---------------------------------------------------------------------------

(defn- ->int [x]
  (cond
    (int? x) x
    (number? x) (int x)
    :else 0))

(defn- present-int [m k]
  (when (contains? m k)
    (->int (get m k))))

(defn- normalize-bedrock-usage [u]
  (let [input-total (->int (:inputTokens u))
        output (->int (:outputTokens u))
        total (present-int u :totalTokens)
        cache-read (present-int u :cacheReadInputTokens)
        cache-write (present-int u :cacheWriteInputTokens)
        cr (or cache-read 0)
        cw (or cache-write 0)]
    (cond-> {:usage/input-tokens (max 0 (- input-total cr cw))
             :usage/output-tokens output
             :usage/total-tokens (or total (+ input-total output))
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
      (some? cache-write) (assoc :usage/cache-write-tokens cache-write))))

;; ---------------------------------------------------------------------------
;; Finish reason mapping
;; ---------------------------------------------------------------------------

(def ^:private stop-reason-map
  {"end_turn" :stop
   "tool_use" :tool-calls
   "max_tokens" :length
   "stop_sequence" :stop
   "guardrail_intervened" :content-filter
   "content_filtered" :content-filter})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- content->bedrock [content]
  (cond
    (nil? content)
    []

    (string? content)
    [{:text content}]
    (sequential? content)
    (mapv (fn [part]
            (case (:part/type part)
              :text {:text (:text part)}
              :image {:image {:format (or (some-> (:image/mime-type part)
                                                   (str/split #"/")
                                                   second)
                                          (case (get part :image/detail :auto)
                                            (:auto :low) "jpeg"
                                            "png"))
                              :source {:bytes (or (:image/data part)
                                                  (:image/url part))}}}
              {:text (str part)}))
          content)
    :else [{:text (str content)}]))

(defn- message->bedrock [msg]
  (let [role (case (:message/role msg)
               :user "user"
               :assistant "assistant"
               "user")]
    (cond
      (= (:message/role msg) :tool)
      {:role "user"
       :content [{:toolResult
                  {:toolUseId (or (:message/tool-call-id msg) "tool_0")
                   :content [{:text (t/content->string (:message/content msg))}]}}]}

      (seq (:message/tool-calls msg))
      {:role "assistant"
       :content (into (content->bedrock (:message/content msg))
                      (map (fn [tc]
                             {:toolUse
                              {:toolUseId (:tool-call/id tc)
                               :name (:tool-call/name tc)
                               :input (try (json/parse-string (:tool-call/arguments tc))
                                           (catch Exception _ {}))}})
                           (:message/tool-calls msg)))}

      :else
      {:role role :content (content->bedrock (:message/content msg))})))

(defn- build-messages [messages]
  (mapv message->bedrock messages))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- tool->bedrock [tool]
  (let [fn-data (:function tool)]
    {:toolSpec
     {:name (:name fn-data)
      :description (or (:description fn-data) "")
      :inputSchema {:json (or (:parameters fn-data) {:type "object"})}}}))

(defn- tool-choice->bedrock [tc]
  (cond
    (= tc :auto) {:auto {}}
    (= tc :required) {:any {}}
    (= tc :none) nil
    (and (map? tc) (= (:type tc) :function))
    {:tool {:name (get-in tc [:function :name])}}
    :else nil))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- aws-region []
  (or (System/getenv "AWS_REGION")
      (System/getenv "AWS_DEFAULT_REGION")
      "us-east-1"))

(defn- bedrock-base-url []
  (str "https://bedrock-runtime." (aws-region) ".amazonaws.com"))

(defn- cache-point-block []
  {:cachePoint {:type "default"}})

(defn- append-cache-point
  "Append a cachePoint sentinel to the end of an array (system or message
   content list). Bedrock Converse interprets a cachePoint block as
   'cache everything up to and including the previous block'."
  [items]
  (when (sequential? items)
    (conj (vec items) (cache-point-block))))

(defn- mark-last-message-cachable
  "Append a cachePoint block to the last message's content array.
   Used to pin a breakpoint at the end of the message history so all
   prior turns (and any prior breakpoints) become cache-resumable."
  [messages]
  (if (and (sequential? messages) (seq messages))
    (let [messages (vec messages)
          last-idx (dec (count messages))
          last-msg (nth messages last-idx)]
      (assoc messages last-idx
             (update last-msg :content append-cache-point)))
    messages))

(defn build-request-bedrock
  [_profile request]
  (let [stream? (boolean (:request/stream? request))
        canonical-model (:request/model request)
        model (resolve-model-id canonical-model)
        messages (remove #(= (:message/role %) :system) (:request/messages request))
        system-texts (keep #(when (= (:message/role %) :system)
                              (t/content->string (:message/content %)))
                           (:request/messages request))
        cache-on? (and (cache/cache-enabled? request)
                       (not= :none (get-in request [:request/cache :strategy])))
        system-content (when (seq system-texts) (mapv #(hash-map :text %) system-texts))
        system-content (if (and cache-on? system-content)
                         (append-cache-point system-content)
                         system-content)
        native-messages (build-messages messages)
        native-messages (if cache-on?
                          (mark-last-message-cachable native-messages)
                          native-messages)
        tool-config (when (seq (:request/tools request))
                      (cond-> {:tools (mapv tool->bedrock (:request/tools request))}
                        (:request/tool-choice request)
                        (assoc :toolChoice
                               (tool-choice->bedrock
                                (:request/tool-choice request)))))
        inference-config
        (cond-> {}
          (:request/temperature request)
          (assoc :temperature (:request/temperature request))
          (:request/max-tokens request)
          (assoc :maxTokens (:request/max-tokens request))
          (:request/top-p request)
          (assoc :topP (:request/top-p request))
          (:request/stop request)
          (assoc :stopSequences (vec (:request/stop request))))
        body (cond-> {:messages native-messages}
               (seq system-content) (assoc :system system-content)
               tool-config (assoc :toolConfig tool-config)
               (seq inference-config) (assoc :inferenceConfig inference-config)
               (seq (get-in request [:request/provider-options :additional-model-request-fields]))
               (assoc :additionalModelRequestFields
                      (get-in request [:request/provider-options :additional-model-request-fields])))
        path (if stream? "/converse-stream" "/converse")
        url (str (bedrock-base-url) "/model/" model path)]
    {:method :post
     :url url
     :headers (cond-> {"Content-Type" "application/json"
                       "Accept" "application/json"}
                stream? (assoc "Accept" "application/vnd.amazon.eventstream"))
     ::aws-service "bedrock"
     ::aws-region (aws-region)
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn parse-response-bedrock
  [_profile raw]
  (let [output (:output raw)
        msg (:message output)
        content (:content msg)
        parts (vec
               (keep (fn [part]
                       (cond
                         (:text part)
                         {:part/type :text :text (:text part)}

                         (:toolUse part)
                         (let [tu (:toolUse part)]
                           {:part/type :tool-call
                            :tool-call/id (:toolUseId tu)
                            :tool-call/name (:name tu)
                            :tool-call/arguments (json/generate-string (:input tu))})

                         :else nil))
                     content))
        tool-calls (vec (filter #(= (:part/type %) :tool-call) parts))
        stop-reason (get stop-reason-map (:stopReason raw) :stop)
        usage-raw (:usage raw)]
    (cond-> {:response/provider :bedrock
             :response/model (:modelId raw)
             :response/parts parts
             :response/finish-reason stop-reason
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage-raw (assoc :response/usage (normalize-bedrock-usage usage-raw)))))

;; ---------------------------------------------------------------------------
;; Stream parsing — handles either an eventstream frame map
;; (preferred, emitted by aws-eventstream/frame-seq) or a raw JSON line
;; (legacy fallback for tests + callers that pre-parsed frames).
;; ---------------------------------------------------------------------------

(defn- parse-event-line [line]
  (try (json/parse-string line true)
       (catch Exception _ nil)))

(defn- frame->event
  "Turn a single decoded frame into a canonical StreamEvent map (or nil
   when there's nothing to emit)."
  [{:keys [event-type data] :as _frame}]
  (case event-type
    "contentBlockDelta"
    (let [delta (:delta data)]
      (cond
        (:text delta) (stream/content-delta (:text delta))
        (:reasoningContent delta)
        (when-let [rt (get-in delta [:reasoningContent :text])]
          (stream/reasoning-delta rt))
        (:toolUse delta)
        (stream/tool-call-delta (or (:contentBlockIndex data) 0)
                                (get-in delta [:toolUse :input]))))

    "contentBlockStart"
    (let [block (get-in data [:start :toolUse])]
      (when block
        (stream/tool-call-start (or (:contentBlockIndex data) 0)
                                (:toolUseId block)
                                (:name block))))

    "messageStart" nil
    "messageStop"
    (stream/end-event :finish-reason
                      (get stop-reason-map (:stopReason data) :stop))

    "metadata"
    (when-let [u (:usage data)]
      (stream/usage-event (normalize-bedrock-usage u)))

    nil))

(defn parse-stream-event-bedrock
  [_profile input]
  (cond
    ;; Frame map produced by aws-eventstream/frame->json
    (and (map? input) (:event-type input))
    (frame->event input)

    ;; Legacy: caller passed a JSON line shaped like the older
    ;; intermediate format used by the prior scaffold. Translate it
    ;; into the frame shape and reuse the dispatcher above.
    (string? input)
    (when-let [data (parse-event-line input)]
      (frame->event {:event-type (:type data)
                     :data (or (get data (keyword (:type data))) data)}))

    :else nil))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-bedrock
  [_profile status body]
  (errors/classify-error (Exception. "Bedrock API error")
                         :status status
                         :body body
                         :provider :bedrock))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord BedrockTransport []
  t/Transport
  (build-request [_ profile request]
    (build-request-bedrock profile request))

  (parse-response [_ profile raw]
    (parse-response-bedrock profile raw))

  (parse-stream-event [_ profile input]
    (parse-stream-event-bedrock profile input))

  (parse-error [_ profile status body]
    (parse-error-bedrock profile status body))

  (normalize-usage [_ _profile raw]
    (normalize-bedrock-usage raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :guardrails :cache}))

(defn make-transport []
  (->BedrockTransport))

;; Register
(provider/register-provider
 {:profile/id :bedrock
  :profile/protocol-family :bedrock
  :profile/base-url "https://bedrock-runtime.us-east-1.amazonaws.com"
  :profile/auth-strategy :aws-sigv4
  :profile/aws-service "bedrock"
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :guardrails :cache}
  :profile/env-var-names ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_REGION"]
  :profile/binary-stream :aws-eventstream
  :profile/transport-constructor make-transport})
