(ns llm.sdk.providers.bedrock.converse
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
   "claude-sonnet-4-5" "anthropic.claude-sonnet-4-5-20250929-v1:0"
   "claude-sonnet-4-6" "anthropic.claude-sonnet-4-6"
   "claude-opus-4" "anthropic.claude-opus-4-20250514-v1:0"
   "claude-opus-4-1" "anthropic.claude-opus-4-1-20250805-v1:0"
   "claude-opus-4-5" "anthropic.claude-opus-4-5-20251101-v1:0"
   "claude-opus-4-6" "anthropic.claude-opus-4-6-v1"
   "claude-haiku-4-5" "anthropic.claude-haiku-4-5-20251001-v1:0"
   "nova-micro" "amazon.nova-micro-v1:0"
   "nova-lite" "amazon.nova-lite-v1:0"
   "nova-pro" "amazon.nova-pro-v1:0"
   "nova-premier" "amazon.nova-premier-v1:0"
   "nova-canvas" "amazon.nova-canvas-v1:0"
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
        cache-write (present-int u :cacheWriteInputTokens)]
    (cond-> {:usage/input-tokens input-total
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
   "content_filtered" :content-filter
   "malformed_model_output" :incomplete
   "malformed_tool_use" :incomplete
   "model_context_window_exceeded" :incomplete})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- bedrock-document-name [part]
  (-> (t/file-name part)
      (str/replace #"[^A-Za-z0-9 \-\(\)\[\]]" " ")
      (str/replace #"\s+" " ")
      str/trim
      (not-empty)
      (or "Document")))

(defn- bedrock-document-source [part]
  (cond
    (and (:file/url part) (str/starts-with? (:file/url part) "s3://"))
    {:s3Location {:uri (:file/url part)}}

    (t/file-binary-data part)
    {:bytes (t/file-binary-data part)}

    (t/file-text-content part)
    {:text (t/file-text-content part)}

    :else
    (t/missing-file-source! :bedrock part)))

(defn- bedrock-image-format [part]
  (let [format (or (some-> (:image/mime-type part)
                           (str/split #"/")
                           second
                           str/lower-case)
                   (case (get part :image/detail :auto)
                     (:auto :low) "jpeg"
                     "png"))
        format (if (= format "jpg") "jpeg" format)]
    (if (#{"png" "jpeg" "gif" "webp"} format)
      format
      (throw (ex-info "Unsupported Bedrock Converse image format"
                      {:provider :bedrock
                       :format format
                       :error/type :request/unsupported-image-format})))))

(defn- bedrock-image-source [part]
  (let [url (:image/url part)]
    (cond
      (:image/data part)
      {:bytes (:image/data part)}

      (and url (str/starts-with? url "data:"))
      (if-let [[_ data] (str/split url #"," 2)]
        {:bytes data}
        (t/missing-file-source! :bedrock part))

      (and url (str/starts-with? url "s3://"))
      {:s3Location {:uri url}}

      :else
      (t/missing-file-source! :bedrock part))))

(defn- reasoning->bedrock [part]
  {:reasoningContent
   (if (:reasoning/encrypted part)
     {:redactedContent (:reasoning/text part)}
     {:reasoningText
      (cond-> {:text (:reasoning/text part)}
        (:reasoning/signature part)
        (assoc :signature (:reasoning/signature part)))})})

(defn- tool-call->bedrock [part]
  {:toolUse
   {:toolUseId (:tool-call/id part)
    :name (:tool-call/name part)
    :input (try
             (json/parse-string (:tool-call/arguments part))
             (catch Exception _ {}))}})

(defn- tool-result->bedrock [part]
  {:toolResult
   (cond-> {:toolUseId (:tool-result/id part)
            :content [{:text (:tool-result/content part)}]}
     (contains? part :tool-result/is-error)
     (assoc :status (if (:tool-result/is-error part) "error" "success")))})

(defn- file->bedrock-document [part]
  (cond-> {:document {:format (t/file-extension part)
                      :name (bedrock-document-name part)
                      :source (bedrock-document-source part)}}
    (contains? part :file/citations)
    (assoc-in [:document :citations] {:enabled (boolean (:file/citations part))})
    (:file/context part)
    (assoc-in [:document :context] (:file/context part))))

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
              :image {:image {:format (bedrock-image-format part)
                              :source (bedrock-image-source part)}}
              :file (file->bedrock-document part)
              :reasoning (reasoning->bedrock part)
              :tool-call (tool-call->bedrock part)
              :tool-result (tool-result->bedrock part)
              :unknown/provider-native
              (if (= :bedrock (:unknown/provider part))
                (:unknown/data part)
                {:text (str part)})
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
                  (cond-> {:toolUseId (or (:message/tool-call-id msg) "tool_0")
                           :content [{:text (t/content->string (:message/content msg))}]}
                    (some? (get-in msg [:message/provider-data :bedrock/status]))
                    (assoc :status (get-in msg [:message/provider-data :bedrock/status])))}]}

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

(defn- bedrock-provider-options [request]
  (let [options (or (:request/provider-options request) {})]
    (merge (dissoc options :bedrock)
           (or (:bedrock options) {}))))

(defn- reasoning-fields [model reasoning]
  (when reasoning
    (cond
      (and (= false (:enabled reasoning))
           (not (str/includes? model "anthropic.")))
      nil

      (not (str/includes? model "anthropic."))
      (throw (ex-info "Canonical Bedrock reasoning is only translated for Anthropic Claude models"
                      {:provider :bedrock
                       :model model
                       :error/type :request/unsupported-reasoning}))

      (= false (:enabled reasoning))
      {:thinking {:type "disabled"}}

      (:budget reasoning)
      {:thinking {:type "enabled"
                  :budget_tokens (:budget reasoning)}}

      (and (not= false (:enabled reasoning))
           (str/includes? model "-4-6"))
      (cond-> {:thinking {:type "adaptive"}}
        (:effort reasoning)
        (assoc :output_config
               {:effort (case (:effort reasoning)
                          (:minimal :low) "low"
                          :medium "medium"
                          :high "high"
                          :xhigh "max")}))

      (:enabled reasoning)
      (throw (ex-info "Bedrock Claude extended thinking requires :request/reasoning :budget"
                      {:provider :bedrock
                       :model model
                       :error/type :request/invalid-reasoning}))

      :else nil)))

(defn- response-format->output-config [response-format]
  (when (and (= :json_schema (:type response-format))
             (:json-schema response-format))
    {:textFormat
     {:type "json_schema"
      :structure
      {:jsonSchema
       (cond-> {:schema (json/generate-string (:json-schema response-format))}
         (:name response-format) (assoc :name (:name response-format))
         (:description response-format) (assoc :description (:description response-format)))}}}))

(defn- metadata->bedrock [metadata]
  (when metadata
    (into {}
          (map (fn [[k v]]
                 [(if (keyword? k) (name k) (str k)) (str v)]))
          metadata)))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- aws-region []
  (or (System/getenv "AWS_REGION")
      (System/getenv "AWS_DEFAULT_REGION")
      "us-east-1"))

(defn- bedrock-base-url []
  (str "https://bedrock-runtime." (aws-region) ".amazonaws.com"))

(defn- cache-point-block [ttl]
  {:cachePoint (cond-> {:type "default"}
                 ttl (assoc :ttl ttl))})

(defn- append-cache-point
  "Append a cachePoint sentinel to the end of an array (system or message
   content list). Bedrock Converse interprets a cachePoint block as
   'cache everything up to and including the previous block'."
  [items ttl]
  (when (sequential? items)
    (conj (vec items) (cache-point-block ttl))))

(defn- mark-last-message-cachable
  "Append a cachePoint block to the last message's content array.
   Used to pin a breakpoint at the end of the message history so all
   prior turns (and any prior breakpoints) become cache-resumable."
  [messages ttl]
  (if (and (sequential? messages) (seq messages))
    (let [messages (vec messages)
          last-idx (dec (count messages))
          last-msg (nth messages last-idx)]
      (assoc messages last-idx
             (update last-msg :content append-cache-point ttl)))
    messages))

(defn build-request-bedrock
  [_profile request]
  (let [stream? (boolean (:request/stream? request))
        canonical-model (:request/model request)
        model (resolve-model-id canonical-model)
        options (bedrock-provider-options request)
        messages (remove #(= (:message/role %) :system) (:request/messages request))
        system-texts (keep #(when (= (:message/role %) :system)
                              (t/content->string (:message/content %)))
                           (:request/messages request))
        cache-on? (cache/cache-enabled? request)
        cache-ttl (get-in request [:request/cache :ttl])
        system-content (when (seq system-texts) (mapv #(hash-map :text %) system-texts))
        system-content (if (and cache-on? system-content)
                         (append-cache-point system-content cache-ttl)
                         system-content)
        native-messages (build-messages messages)
        native-messages (if cache-on?
                          (mark-last-message-cachable native-messages cache-ttl)
                          native-messages)
        tool-choice (tool-choice->bedrock (:request/tool-choice request))
        tools (when (seq (:request/tools request))
                (cond-> (mapv tool->bedrock (:request/tools request))
                  (and cache-on? (cache/tools-cache? request))
                  (conj (cache-point-block cache-ttl))))
        tool-config (when tools
                      (cond-> {:tools tools}
                        tool-choice (assoc :toolChoice tool-choice)))
        inference-config
        (cond-> {}
          (:request/temperature request)
          (assoc :temperature (:request/temperature request))
          (:request/max-tokens request)
          (assoc :maxTokens (:request/max-tokens request))
          (:request/top-p request)
          (assoc :topP (:request/top-p request))
          (:request/stop request)
          (assoc :stopSequences (t/stop-sequences (:request/stop request))))
        additional-model-fields
        (merge (reasoning-fields model (:request/reasoning request))
               (:additional-model-request-fields options))
        output-config (or (:output-config options)
                          (response-format->output-config (:request/response-format request)))
        request-metadata (merge (metadata->bedrock (:request/metadata request))
                                (:request-metadata options))
        body (cond-> {:messages native-messages}
               (seq system-content) (assoc :system system-content)
               tool-config (assoc :toolConfig tool-config)
               (seq inference-config) (assoc :inferenceConfig inference-config)
               (seq additional-model-fields)
               (assoc :additionalModelRequestFields additional-model-fields)
               (seq (:additional-model-response-field-paths options))
               (assoc :additionalModelResponseFieldPaths
                      (:additional-model-response-field-paths options))
               (:guardrail-config options)
               (assoc :guardrailConfig (:guardrail-config options))
               output-config
               (assoc :outputConfig output-config)
               (:performance-config options)
               (assoc :performanceConfig (:performance-config options))
               (:prompt-variables options)
               (assoc :promptVariables (:prompt-variables options))
               (seq request-metadata)
               (assoc :requestMetadata request-metadata)
               (:service-tier options)
               (assoc :serviceTier (:service-tier options)))
        path (if stream? "/converse-stream" "/converse")
        url (str (bedrock-base-url) "/model/" model path)]
    {:method :post
     :url url
     :headers (cond-> {"Content-Type" "application/json"
                       "Accept" "application/json"}
                stream? (assoc "Accept" "application/vnd.amazon.eventstream"))
     :llm.sdk.providers.bedrock/aws-service "bedrock"
     :llm.sdk.providers.bedrock/aws-region (aws-region)
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- content-block->canonical [part]
  (cond
    (:text part)
    [{:part/type :text :text (:text part)}]

    (:toolUse part)
    (let [tu (:toolUse part)]
      [{:part/type :tool-call
        :tool-call/id (:toolUseId tu)
        :tool-call/name (:name tu)
        :tool-call/arguments (json/generate-string (:input tu))}])

    (get-in part [:reasoningContent :reasoningText])
    (let [reasoning (get-in part [:reasoningContent :reasoningText])]
      [(cond-> {:part/type :reasoning
                :reasoning/text (:text reasoning)}
         (:signature reasoning)
         (assoc :reasoning/signature (:signature reasoning)))])

    (get-in part [:reasoningContent :redactedContent])
    [{:part/type :reasoning
      :reasoning/text (get-in part [:reasoningContent :redactedContent])
      :reasoning/encrypted true}]

    (:citationsContent part)
    (mapv (fn [generated]
            {:part/type :text :text (:text generated)})
          (keep #(when (:text %) %) (get-in part [:citationsContent :content])))

    :else []))

(defn parse-response-bedrock
  [_profile raw]
  (let [output (:output raw)
        msg (:message output)
        content (:content msg)
        parts (into [] (mapcat content-block->canonical) content)
        tool-calls (vec (filter #(= (:part/type %) :tool-call) parts))
        stop-reason (get stop-reason-map (:stopReason raw) :unknown)
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
    (let [delta (:delta data)
          reasoning (:reasoningContent delta)
          index (or (:contentBlockIndex data) 0)]
      (cond
        (:text delta)
        (stream/content-delta (:text delta))

        (:text reasoning)
        (stream/reasoning-delta (:text reasoning))

        (:redactedContent reasoning)
        (stream/reasoning-delta (:redactedContent reasoning) :encrypted true)

        (:signature reasoning)
        (stream/provider-state-event
         :bedrock
         {:content-block-index index
          :reasoning/signature (:signature reasoning)})

        (:toolUse delta)
        (stream/tool-call-delta index (get-in delta [:toolUse :input]))

        (:citation delta)
        (stream/provider-state-event :bedrock {:citation (:citation delta)})

        (:image delta)
        (stream/provider-state-event :bedrock {:image-delta (:image delta)})

        (:toolResult delta)
        (stream/provider-state-event :bedrock {:tool-result-delta (:toolResult delta)})))

    "contentBlockStart"
    (let [block (get-in data [:start :toolUse])]
      (when block
        (stream/tool-call-start (or (:contentBlockIndex data) 0)
                                (:toolUseId block)
                                (:name block))))

    "messageStart" nil
    "messageStop"
    (stream/end-event :finish-reason
                      (get stop-reason-map (:stopReason data) :unknown))

    "metadata"
    (let [usage-event (when-let [u (:usage data)]
                        (stream/usage-event (normalize-bedrock-usage u)))
          provider-data (not-empty (dissoc data :usage))
          state-event (when provider-data
                        (stream/provider-state-event :bedrock provider-data))]
      (cond
        (and usage-event state-event) [usage-event state-event]
        usage-event usage-event
        state-event state-event))

    (when (and event-type (str/ends-with? event-type "Exception"))
      (stream/error-event {:provider :bedrock
                           :type event-type
                           :data data}))))

(defn parse-stream-event-bedrock
  [_profile input]
  (cond
    ;; Normal event frame produced by aws-eventstream/frame->json.
    (and (map? input) (:event-type input))
    (frame->event input)

    ;; EventStream exception frames use :exception-type instead of
    ;; :event-type; frame->json retains it in the decoded headers.
    (and (map? input) (get-in input [:headers ":exception-type"]))
    (stream/error-event
     {:provider :bedrock
      :type (get-in input [:headers ":exception-type"])
      :data (:data input)})

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
    #{:chat :streaming :tools :json-schema :reasoning :guardrails :cache
      :multimodal :file-attachments}))

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
  :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning
                          :guardrails :cache :multimodal :file-attachments}
  :profile/env-var-names ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_REGION"]
  :profile/binary-stream :aws-eventstream
  :profile/transport-constructor make-transport})
