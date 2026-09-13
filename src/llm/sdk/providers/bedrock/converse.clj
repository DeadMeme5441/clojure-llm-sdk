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
   "claude-sonnet-5" "anthropic.claude-sonnet-5"
   "claude-opus-4" "anthropic.claude-opus-4-20250514-v1:0"
   "claude-opus-4-1" "anthropic.claude-opus-4-1-20250805-v1:0"
   "claude-opus-4-5" "anthropic.claude-opus-4-5-20251101-v1:0"
   "claude-opus-4-6" "anthropic.claude-opus-4-6-v1"
   "claude-opus-4-7" "anthropic.claude-opus-4-7"
   "claude-opus-4-8" "anthropic.claude-opus-4-8"
   "claude-opus-5" "anthropic.claude-opus-5"
   "claude-haiku-4-5" "anthropic.claude-haiku-4-5-20251001-v1:0"
   "claude-fable-5" "anthropic.claude-fable-5"
   "claude-fable-5-1" "anthropic.claude-fable-5-1"
   "claude-mythos-preview" "anthropic.claude-mythos-preview"
   "claude-mythos-5" "anthropic.claude-mythos-5"
   "claude-mythos-5-1" "anthropic.claude-mythos-5-1"
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

(def ^:private document-formats
  #{"pdf" "csv" "doc" "docx" "xls" "xlsx" "html" "txt" "md"})

(defn- invalid-request! [message type data]
  (throw (ex-info message
                  (merge {:provider :bedrock
                          :error/type type}
                         data))))

(defn- bedrock-document-name [part]
  (let [document-name (t/file-name part)]
    (when-not (and (string? document-name)
                   (<= 1 (count document-name) 200)
                   (re-matches #"[A-Za-z0-9\s()\[\]-]+" document-name)
                   (not (re-find #"\s{2,}" document-name)))
      (invalid-request! "Invalid Bedrock Converse document name"
                        :request/invalid-document-name
                        {:document-name document-name}))
    document-name))

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

(defn- tool-call-input [part]
  (if-let [native-input (or (get-in part
                                    [:tool-call/provider-data
                                     :bedrock/tool-use
                                     :input])
                            (get-in part
                                    [:tool-call/provider-data
                                     :bedrock/input]))]
    native-input
    (try
      (json/parse-string (:tool-call/arguments part))
      (catch Exception cause
        (throw (ex-info "Invalid JSON tool-call arguments for Bedrock Converse"
                        {:provider :bedrock
                         :tool-call/id (:tool-call/id part)
                         :error/type :request/invalid-tool-call-arguments}
                        cause))))))

(defn- tool-call->bedrock [part]
  (let [native-tool-use (get-in part
                                [:tool-call/provider-data
                                 :bedrock/tool-use])]
    {:toolUse
     (assoc (or native-tool-use {})
            :toolUseId (:tool-call/id part)
            :name (:tool-call/name part)
            :input (tool-call-input part))}))

(defn- tool-result->bedrock [part]
  {:toolResult
   (cond-> {:toolUseId (:tool-result/id part)
            :content [{:text (:tool-result/content part)}]}
     (contains? part :tool-result/is-error)
     (assoc :status (if (:tool-result/is-error part) "error" "success")))})

(defn- bedrock-document-format [part]
  (let [format (some-> (t/file-extension part) name str/lower-case)]
    (when-not (contains? document-formats format)
      (invalid-request! "Unsupported Bedrock Converse document format"
                        :request/unsupported-document-format
                        {:format format}))
    format))

(defn- file->bedrock-document [part]
  (cond-> {:document {:format (bedrock-document-format part)
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
                (invalid-request!
                 "Cannot replay another provider's native content on Bedrock"
                 :request/unsupported-content
                 {:part-type (:part/type part)
                  :part-provider (:unknown/provider part)}))
              (invalid-request! "Unsupported Bedrock Converse content part"
                                :request/unsupported-content
                                {:part-type (:part/type part)})))
          content)

    :else
    [{:text (str content)}]))

(defn- append-tool-calls [content tool-calls]
  (first
   (reduce
    (fn [[blocks seen] tool-call]
      (let [block (tool-call->bedrock tool-call)
            native (:toolUse block)]
        (if (contains? seen native)
          [blocks seen]
          [(conj blocks block) (conj seen native)])))
    [(vec content) (set (keep :toolUse content))]
    tool-calls)))

(defn- validate-message-content! [role content]
  (let [document? (boolean (some :document content))
        image? (boolean (some :image content))
        text? (boolean (some #(contains? % :text) content))]
    (when (and document? (not= role :user))
      (invalid-request! "Bedrock Converse documents require a user message"
                        :request/invalid-document-role
                        {:role role}))
    (when (and document? (not text?))
      (invalid-request!
       "Bedrock Converse document messages require accompanying text"
       :request/document-without-text
       {:role role}))
    (when (and image? (not= role :user))
      (invalid-request! "Bedrock Converse images require a user message"
                        :request/invalid-image-role
                        {:role role}))))

(defn- validate-canonical-media-role! [msg]
  (let [role (:message/role msg)
        content (:message/content msg)]
    (when (sequential? content)
      (when (and (some #(= :file (:part/type %)) content)
                 (not= role :user))
        (invalid-request! "Bedrock Converse documents require a user message"
                          :request/invalid-document-role
                          {:role role}))
      (when (and (some #(= :image (:part/type %)) content)
                 (not= role :user))
        (invalid-request! "Bedrock Converse images require a user message"
                          :request/invalid-image-role
                          {:role role}))
      (when (and (contains? #{:system :developer} role)
                 (some #(not= :text (:part/type %)) content))
        (invalid-request!
         "Bedrock Converse system instructions support only text content"
         :request/unsupported-content
         {:role role})))))

(defn- message->bedrock [msg]
  (let [role (:message/role msg)]
    (cond
      (= role :tool)
      {:role "user"
       :content [{:toolResult
                  (cond-> {:toolUseId (or (:message/tool-call-id msg) "tool_0")
                           :content [{:text (t/content->string
                                             (:message/content msg))}]}
                    (some? (get-in msg [:message/provider-data
                                       :bedrock/status]))
                    (assoc :status
                           (get-in msg [:message/provider-data
                                       :bedrock/status])))}]}

      (contains? #{:user :assistant} role)
      (let [_ (when (and (seq (:message/tool-calls msg))
                         (not= role :assistant))
                (invalid-request!
                 "Bedrock Converse tool calls require an assistant message"
                 :request/invalid-tool-call-role
                 {:role role}))
            content (append-tool-calls
                     (content->bedrock (:message/content msg))
                     (:message/tool-calls msg))]
        (validate-message-content! role content)
        {:role (name role)
         :content content})

      :else
      (invalid-request! "Unsupported Bedrock Converse message role"
                        :request/unsupported-role
                        {:role role}))))

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

(def ^:private adaptive-only-claude-models
  ["claude-fable-5" "claude-mythos-5" "claude-mythos-preview"])

(def ^:private adaptive-claude-models
  (into adaptive-only-claude-models
        ["claude-opus-4-6" "claude-opus-4-7" "claude-opus-4-8"
         "claude-opus-5" "claude-sonnet-4-6" "claude-sonnet-5"]))

(def ^:private extended-claude-models
  ["claude-3-7-sonnet" "claude-sonnet-4-20250514"
   "claude-sonnet-4-5" "claude-sonnet-4-6"
   "claude-opus-4-20250514" "claude-opus-4-1"
   "claude-opus-4-5" "claude-opus-4-6"
   "claude-haiku-4-5"])

(def ^:private manual-thinking-unsupported-models
  (into adaptive-only-claude-models
        ["claude-opus-4-7" "claude-opus-4-8"
         "claude-opus-5" "claude-sonnet-5"]))

(defn- model-matches? [model fragments]
  (let [model (str/lower-case (or model ""))]
    (boolean (some #(str/includes? model %) fragments))))

(defn- claude-model? [model]
  (str/includes? (str/lower-case (or model "")) "anthropic.claude-"))

(defn- nova-reasoning-model? [model]
  (model-matches? model ["amazon.nova-pro-" "amazon.nova-lite-"]))

(defn- canonical-effort [model effort]
  (case effort
    :minimal "low"
    :low "low"
    :medium "medium"
    :high "high"
    :xhigh "xhigh"
    :max "max"
    (invalid-request! "Unsupported Bedrock reasoning effort"
                      :request/unsupported-reasoning-effort
                      {:model model :effort effort})))

(defn- claude-effort [model effort]
  (let [effort (canonical-effort model effort)]
    (when (and (= effort "xhigh")
               (not (model-matches? model
                                    ["claude-opus-4-6"
                                     "claude-opus-5"])))
      (invalid-request! "Claude model does not support xhigh reasoning effort"
                        :request/unsupported-reasoning-effort
                        {:model model :effort :xhigh}))
    (when (and (= effort "max")
               (not (model-matches? model
                                    ["claude-opus-4-6"
                                     "claude-sonnet-4-6"
                                     "claude-opus-5"])))
      (invalid-request! "Claude model does not support max reasoning effort"
                        :request/unsupported-reasoning-effort
                        {:model model :effort :max}))
    effort))

(defn- nova-reasoning-fields [model reasoning]
  (when (contains? reasoning :budget)
    (invalid-request! "Amazon Nova reasoning does not accept a token budget"
                      :request/invalid-reasoning
                      {:model model}))
  (when (and (false? (:enabled reasoning))
             (contains? reasoning :effort))
    (invalid-request! "Disabled Amazon Nova reasoning cannot set effort"
                      :request/invalid-reasoning
                      {:model model :effort (:effort reasoning)}))
  {:reasoningConfig
   (cond-> {:type (if (false? (:enabled reasoning))
                    "disabled"
                    "enabled")}
     (contains? reasoning :effort)
     (assoc :maxReasoningEffort
            (case (:effort reasoning)
              :low "low"
              :medium "medium"
              :high "high"
              (invalid-request!
               "Amazon Nova supports only low, medium, or high reasoning effort"
               :request/unsupported-reasoning-effort
               {:model model :effort (:effort reasoning)}))))})

(defn- claude-reasoning-fields [model reasoning]
  (let [enabled? (not (false? (:enabled reasoning)))
        budget (:budget reasoning)
        adaptive? (model-matches? model adaptive-claude-models)
        extended? (model-matches? model extended-claude-models)
        adaptive-only? (model-matches? model adaptive-only-claude-models)]
    (when-not (or adaptive? extended?)
      (invalid-request! "Canonical reasoning is not supported for this Claude model"
                        :request/unsupported-reasoning
                        {:model model}))
    (cond
      (not enabled?)
      (do
        (when adaptive-only?
          (invalid-request! "Claude model requires adaptive thinking"
                            :request/invalid-reasoning
                            {:model model}))
        (when (contains? reasoning :budget)
          (invalid-request! "Disabled Claude thinking cannot set a budget"
                            :request/invalid-reasoning
                            {:model model}))
        (when (and (contains? reasoning :effort)
                   (not (model-matches? model ["claude-opus-5"])))
          (invalid-request!
           "Only Claude Opus 5 supports effort with disabled thinking"
           :request/invalid-reasoning
           {:model model :effort (:effort reasoning)}))
        (let [effort (when (contains? reasoning :effort)
                       (claude-effort model (:effort reasoning)))]
          (when (#{"xhigh" "max"} effort)
            (invalid-request!
             "Claude Opus 5 disabled thinking caps effort at high"
             :request/unsupported-reasoning-effort
             {:model model :effort (:effort reasoning)}))
          (cond-> {:thinking {:type "disabled"}}
            effort (assoc :output_config {:effort effort}))))

      budget
      (do
        (when (model-matches? model manual-thinking-unsupported-models)
          (invalid-request! "Claude model does not support manual thinking budgets"
                            :request/invalid-reasoning
                            {:model model}))
        (when (contains? reasoning :effort)
          (invalid-request! "Manual Claude thinking cannot set adaptive effort"
                            :request/invalid-reasoning
                            {:model model}))
        (when (< budget 1024)
          (invalid-request! "Claude thinking budget must be at least 1024 tokens"
                            :request/invalid-reasoning
                            {:model model :budget budget}))
        {:thinking {:type "enabled" :budget_tokens budget}})

      adaptive?
      (cond-> {:thinking {:type "adaptive"}}
        (contains? reasoning :effort)
        (assoc :output_config
               {:effort (claude-effort model (:effort reasoning))}))

      :else
      (invalid-request!
       "Bedrock Claude extended thinking requires :request/reasoning :budget"
       :request/invalid-reasoning
       {:model model}))))

(defn- reasoning-fields [model reasoning]
  (when reasoning
    (cond
      (nova-reasoning-model? model)
      (nova-reasoning-fields model reasoning)

      (claude-model? model)
      (claude-reasoning-fields model reasoning)

      (false? (:enabled reasoning))
      nil

      :else
      (invalid-request!
       "Canonical Bedrock reasoning is unsupported for this model"
       :request/unsupported-reasoning
       {:model model}))))

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

(defn- endpoint-region [base-url]
  (try
    (let [host (some-> base-url java.net.URI. .getHost str/lower-case)]
      (second
       (re-matches
        #"bedrock(?:-agent)?-runtime(?:-fips)?\.([a-z0-9-]+)\.(?:amazonaws\.com(?:\.cn)?|api\.aws)"
        (or host ""))))
    (catch Exception _ nil)))

(defn- bedrock-base-url [profile]
  (str/replace
   (or (:profile/base-url profile)
       (str "https://bedrock-runtime." (aws-region) ".amazonaws.com"))
   #"/+$"
   ""))
(defn- agent-runtime-base-url [base-url]
  (str/replace base-url
               #"(?i)^(https?://)bedrock-runtime(?=[.-])"
               "$1bedrock-agent-runtime"))


(defn- signing-region [profile options base-url]
  (or (endpoint-region base-url)
      (:aws-region options)
      (:profile/aws-region profile)
      (aws-region)))

(defn runtime-routing
  "Resolve a Bedrock endpoint and its SigV4 region. The two-argument
   form keeps the existing Bedrock Runtime route used by Converse and
   InvokeModel. Passing :agent-runtime converts standard AWS Runtime
   hosts to Agent Runtime while leaving explicit custom endpoints intact."
  ([profile options]
   (runtime-routing profile options :runtime))
  ([profile options runtime]
   (let [base-url (bedrock-base-url profile)
         base-url (case runtime
                    :runtime base-url
                    :agent-runtime (agent-runtime-base-url base-url)
                    (throw
                     (ex-info "Unsupported Bedrock runtime route"
                              {:runtime runtime})))]
     {:base-url base-url
      :region (signing-region profile options base-url)})))

(defn- validate-cache-ttl! [ttl]
  (when (and (some? ttl) (not (#{"5m" "1h"} ttl)))
    (invalid-request! "Bedrock Converse cache TTL must be 5m or 1h"
                      :request/unsupported-cache-ttl
                      {:ttl ttl})))

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

(defn- active-claude-thinking? [model fields]
  (and (claude-model? model)
       (contains? #{"adaptive" "enabled"}
                  (get-in fields [:thinking :type]))))

(defn- forced-tool-choice? [tool-choice]
  (or (= tool-choice :required)
      (and (map? tool-choice)
           (= (:type tool-choice) :function))))

(defn- validate-thinking-combinations!
  [model fields request additional-model-fields]
  (when (active-claude-thinking? model fields)
    (let [sampling-fields
          (cond-> []
            (some? (:request/temperature request)) (conj :temperature)
            (some? (:request/top-p request)) (conj :top-p)
            (some #(contains? additional-model-fields %)
                  [:top_k :topK "top_k" "topK"])
            (conj :top-k))]
      (when (seq sampling-fields)
        (invalid-request!
         "Claude thinking is incompatible with modified sampling parameters"
         :request/incompatible-reasoning
         {:model model :sampling-fields sampling-fields}))
      (when (forced-tool-choice? (:request/tool-choice request))
        (invalid-request! "Claude thinking does not support forced tool use"
                          :request/incompatible-reasoning
                          {:model model
                           :tool-choice (:request/tool-choice request)}))
      (when-let [budget (get-in fields [:thinking :budget_tokens])]
        (when (and (some? (:request/max-tokens request))
                   (>= budget (:request/max-tokens request)))
          (invalid-request!
           "Claude thinking budget must be less than max tokens"
           :request/invalid-reasoning
           {:model model
            :budget budget
            :max-tokens (:request/max-tokens request)}))))))

(defn build-request-bedrock
  [profile request]
  (let [stream? (boolean (:request/stream? request))
        canonical-model (:request/model request)
        model (resolve-model-id canonical-model)
        options (bedrock-provider-options request)
        _valid-media-roles
        (doseq [message (:request/messages request)]
          (validate-canonical-media-role! message))
        instruction-role? #(contains? #{:system :developer}
                                      (:message/role %))
        messages (remove instruction-role? (:request/messages request))
        system-texts (keep #(when (instruction-role? %)
                              (t/content->string (:message/content %)))
                           (:request/messages request))
        cache-on? (cache/cache-enabled? request)
        cache-ttl (get-in request [:request/cache :ttl])
        _cache-valid (validate-cache-ttl! cache-ttl)
        system-content (when (seq system-texts)
                         (mapv #(hash-map :text %) system-texts))
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
        reasoning (reasoning-fields model (:request/reasoning request))
        native-additional-model-fields
        (or (:additional-model-request-fields options) {})
        additional-model-fields
        (merge native-additional-model-fields reasoning)
        _thinking-valid
        (validate-thinking-combinations!
         model additional-model-fields request native-additional-model-fields)
        inference-config
        (cond-> {}
          (some? (:request/temperature request))
          (assoc :temperature (:request/temperature request))
          (some? (:request/max-tokens request))
          (assoc :maxTokens (:request/max-tokens request))
          (some? (:request/top-p request))
          (assoc :topP (:request/top-p request))
          (:request/stop request)
          (assoc :stopSequences (t/stop-sequences (:request/stop request))))
        output-config (or (:output-config options)
                          (response-format->output-config
                           (:request/response-format request)))
        request-metadata (merge (metadata->bedrock
                                 (:request/metadata request))
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
        {:keys [base-url region]} (runtime-routing profile options)
        url (str base-url "/model/" model path)]
    {:method :post
     :url url
     :headers (cond-> {"Content-Type" "application/json"
                       "Accept" "application/json"}
                stream? (assoc "Accept" "application/vnd.amazon.eventstream"))
     :llm.sdk.providers.bedrock/aws-service "bedrock"
     :llm.sdk.providers.bedrock/aws-region region
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- citation-snippet [citation]
  (not-empty
   (str/join "\n" (keep :text (:sourceContent citation)))))

(defn- citation-text-range [citation]
  (let [{:keys [start end]}
        (get-in citation [:location :documentChar])]
    (when (and (int? start) (int? end))
      [start end])))

(defn- citation->canonical [citation]
  (let [url (get-in citation [:location :web :url])
        source-id (some-> (:source citation) str)
        snippet (citation-snippet citation)
        text-range (citation-text-range citation)]
    (cond-> {:part/type :citation
             :citation/provider-data {:bedrock/citation citation}}
      url (assoc :citation/url url)
      source-id (assoc :citation/source-id source-id)
      (:title citation) (assoc :citation/title (:title citation))
      snippet (assoc :citation/snippet snippet)
      text-range (assoc :citation/text-range text-range))))

(defn- content-block->canonical [part]
  (cond
    (:text part)
    [{:part/type :text :text (:text part)}]

    (:toolUse part)
    (let [tu (:toolUse part)]
      [{:part/type :tool-call
        :tool-call/id (:toolUseId tu)
        :tool-call/name (:name tu)
        :tool-call/arguments (json/generate-string (:input tu))
        :tool-call/provider-data {:bedrock/tool-use tu}}])

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
    (let [citations-content (:citationsContent part)]
      (into
       (mapv (fn [generated]
               {:part/type :text :text (:text generated)})
             (keep #(when (:text %) %)
                   (:content citations-content)))
       (map citation->canonical)
       (:citations citations-content)))

    :else
    [{:part/type :unknown/provider-native
      :unknown/provider :bedrock
      :unknown/data part}]))

(defn parse-response-bedrock
  [_profile raw]
  (let [output (:output raw)
        msg (:message output)
        content (:content msg)
        parts (into [] (mapcat content-block->canonical) content)
        tool-calls (vec (filter #(= (:part/type %) :tool-call) parts))
        stop-reason (get stop-reason-map (:stopReason raw) :unknown)
        usage-raw (:usage raw)
        provider-data
        (not-empty
         (select-keys raw
                      [:additionalModelResponseFields
                       :metrics
                       :performanceConfig
                       :serviceTier
                       :trace]))]
    (cond-> {:response/provider :bedrock
             :response/model (:modelId raw)
             :response/parts parts
             :response/finish-reason stop-reason
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage-raw (assoc :response/usage (normalize-bedrock-usage usage-raw))
      provider-data (assoc :response/provider-data provider-data))))

;; ---------------------------------------------------------------------------
;; Stream parsing — handles either an eventstream frame map
;; (preferred, emitted by aws-eventstream/frame-seq) or a raw JSON line
;; (legacy fallback for tests + callers that pre-parsed frames).
;; ---------------------------------------------------------------------------

(defn- parse-event-line [line]
  (try (json/parse-string line true)
       (catch Exception _ nil)))

(defn- one-or-many [events]
  (let [events (vec (remove nil? events))]
    (case (count events)
      0 nil
      1 (first events)
      events)))

(defn- citation-stream-event [citation]
  (let [canonical (citation->canonical citation)]
    (stream/citation-event
     (:citation/url canonical)
     :title (:citation/title canonical)
     :snippet (:citation/snippet canonical)
     :text-range (:citation/text-range canonical)
     :source-id (:citation/source-id canonical)
     :provider-data (:citation/provider-data canonical))))

(defn- frame->event
  "Turn a decoded frame into a canonical StreamEvent, a vector of events, or
   nil when the frame carries no observable data."
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
        (stream/reasoning-delta (:text reasoning) :index index)

        (:redactedContent reasoning)
        (stream/reasoning-delta (:redactedContent reasoning)
                                :encrypted true
                                :index index)

        (:signature reasoning)
        (stream/reasoning-delta nil
                                :index index
                                :signature (:signature reasoning))

        (:toolUse delta)
        (stream/tool-call-delta index (get-in delta [:toolUse :input]))

        (:citation delta)
        (citation-stream-event (:citation delta))

        (:image delta)
        (stream/provider-state-event
         :bedrock
         {:content-blocks {index {:image-delta (:image delta)}}})

        (:toolResult delta)
        (stream/provider-state-event
         :bedrock
         {:content-blocks {index
                           {:tool-result-delta (:toolResult delta)}}})))

    "contentBlockStart"
    (let [index (or (:contentBlockIndex data) 0)
          start (:start data)
          tool-use (:toolUse start)]
      (if tool-use
        (stream/tool-call-start
         index
         (:toolUseId tool-use)
         (:name tool-use)
         :provider-data {:bedrock/tool-use tool-use})
        (stream/provider-state-event
         :bedrock
         {:content-blocks {index {:start start}}})))

    "contentBlockStop"
    (stream/provider-state-event
     :bedrock
     {:content-blocks {(or (:contentBlockIndex data) 0) {:stopped true}}})

    "messageStart"
    (stream/provider-state-event :bedrock {:messageStart data})

    "messageStop"
    (let [provider-data
          (not-empty (select-keys data [:additionalModelResponseFields]))]
      (one-or-many
       [(when provider-data
          (stream/provider-state-event :bedrock provider-data))
        (stream/end-event
         :finish-reason
         (get stop-reason-map (:stopReason data) :unknown))]))

    "metadata"
    (one-or-many
     [(when-let [u (:usage data)]
        (stream/usage-event (normalize-bedrock-usage u)))
      (when-let [provider-data (not-empty (dissoc data :usage))]
        (stream/provider-state-event :bedrock provider-data))])

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

    ;; Accept the same legacy intermediate shape as either an already-parsed
    ;; map or a JSON line, then normalize both through frame->event.
    (and (map? input) (:type input))
    (frame->event {:event-type (:type input)
                   :data (or (get input (keyword (:type input))) input)})

    (string? input)
    (when-let [data (parse-event-line input)]
      (parse-stream-event-bedrock nil data))

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
  :profile/base-url (str "https://bedrock-runtime." (aws-region)
                         ".amazonaws.com")
  :profile/auth-strategy :aws-sigv4
  :profile/aws-service "bedrock"
  :profile/aws-region (aws-region)
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning
                          :guardrails :cache :multimodal :file-attachments}
  :profile/env-var-names ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_REGION"]
  :profile/binary-stream :aws-eventstream
  :profile/transport-constructor make-transport})
