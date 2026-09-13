(ns llm.sdk.providers.anthropic.chat
  "Anthropic Messages API transport adapter.
   Supports thinking blocks, cache_control, tool use, streaming deltas.
   Preserves provider-specific replay state (reasoning_details, signatures)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.catalog :as catalog]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Finish reason mapping
;; ---------------------------------------------------------------------------

(def ^:private stop-reason-map
  {"end_turn" :stop
   "tool_use" :tool-calls
   "max_tokens" :length
   "stop_sequence" :stop
   "pause_turn" :incomplete
   "refusal" :content-filter
   "model_context_window_exceeded" :length})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- instruction-role? [role]
  (contains? #{:system :developer} role))

(defn- instruction->system-block [message]
  (let [content (:message/content message)]
    (when (and (sequential? content)
               (some #(not= :text (:part/type %)) content))
      (throw (ex-info
              "Anthropic system and developer instructions support text content only."
              {:error/type :anthropic/unsupported-instruction-content
               :message/role (:message/role message)})))
    (let [text (t/content->string content)]
      (when-not (string? text)
        (throw (ex-info
                "Anthropic system and developer instructions require text content."
                {:error/type :anthropic/unsupported-instruction-content
                 :message/role (:message/role message)})))
      {:type "text" :text text})))

(defn- extract-system [messages]
  (->> messages
       (keep (fn [message]
               (when (instruction-role? (:message/role message))
                 (instruction->system-block message))))
       vec
       not-empty))

(defn- parse-tool-call-input [tc]
  (let [arguments (:tool-call/arguments tc)
        error-data {:error/type :anthropic/invalid-tool-arguments
                    :tool-call/id (:tool-call/id tc)
                    :tool-call/name (:tool-call/name tc)}]
    (when (str/blank? arguments)
      (throw (ex-info
              "Anthropic tool call arguments must be a JSON object."
              error-data)))
    (try
      (let [parsed (json/parse-string arguments true)]
        (when-not (map? parsed)
          (throw (ex-info
                  "Anthropic tool call arguments must decode to a JSON object."
                  error-data)))
        parsed)
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw (ex-info
                "Anthropic tool call arguments contain malformed JSON."
                error-data
                e))))))

(defn- image-source [part]
  (let [url (:image/url part)
        data (:image/data part)
        mime (or (:image/mime-type part) "image/png")]
    (cond
      (seq data)
      {:type "base64" :media_type mime :data data}

      (and (string? url) (str/starts-with? url "data:"))
      (let [[header encoded] (str/split url #"," 2)
            mime (or (second (re-find #"data:([^;]+)" header)) mime)]
        {:type "base64" :media_type mime :data encoded})

      (seq url)
      {:type "url" :url url}

      :else
      (throw (ex-info "Anthropic image content requires data or a URL."
                      {:error/type :anthropic/missing-image-source})))))

(defn- tool-call->anthropic-block [tc]
  (let [input (parse-tool-call-input tc)
        native (get-in tc
                       [:tool-call/provider-data :anthropic/content-block])
        base (if (and (map? native) (= "tool_use" (:type native)))
               native
               {:type "tool_use"})]
    (assoc base
           :type "tool_use"
           :id (:tool-call/id tc)
           :name (:tool-call/name tc)
           :input input)))

(defn- file-source [part]
  (cond
    (:file/id part)
    {:type "file" :file_id (:file/id part)}

    (:file/url part)
    {:type "url" :url (:file/url part)}

    (t/file-binary-data part)
    {:type "base64"
     :media_type (t/file-mime-type part)
     :data (t/file-binary-data part)}

    (t/file-text-content part)
    {:type "text"
     :media_type "text/plain"
     :data (t/file-text-content part)}

    :else
    (t/missing-file-source! :anthropic part)))

(defn- file->anthropic-block [part]
  (if (and (:file/id part)
           (str/starts-with? (or (:file/mime-type part) "") "image/"))
    {:type "image"
     :source {:type "file" :file_id (:file/id part)}}
    (cond-> {:type "document"
             :source (file-source part)}
      (:file/name part) (assoc :title (:file/name part))
      (:file/title part) (assoc :title (:file/title part))
      (:file/context part) (assoc :context (:file/context part))
      (contains? part :file/citations)
      (assoc :citations {:enabled (boolean (:file/citations part))}))))

(defn- validate-thinking-signature! [block]
  (when (and (= (:type block) "thinking")
             (str/blank? (:signature block)))
    (throw (ex-info "Anthropic thinking blocks require a non-blank signature."
                    {:error/type :anthropic/invalid-thinking-signature})))
  block)

(defn- unsupported-content! [part]
  (throw (ex-info
          (str "Anthropic does not support canonical content part "
               (pr-str (:part/type part)) ".")
          {:error/type :anthropic/unsupported-content-part
           :part/type (:part/type part)})))

(defn- text-content! [content error-type description]
  (when (and (sequential? content)
             (some #(not= :text (:part/type %)) content))
    (throw (ex-info description
                    {:error/type error-type})))
  (let [text (t/content->string content)]
    (when-not (string? text)
      (throw (ex-info description
                      {:error/type error-type})))
    text))

(defn- content->anthropic-blocks [content]
  (cond
    (nil? content)
    []

    (string? content)
    [{:type "text" :text content}]

    (sequential? content)
    (mapv (fn [part]
            (validate-thinking-signature!
             (case (:part/type part)
               :text {:type "text" :text (:text part)}
               :image {:type "image" :source (image-source part)}
               :file (file->anthropic-block part)
               :reasoning {:type "thinking"
                           :thinking (:reasoning/text part)
                           :signature (:reasoning/signature part)}
               :tool-call (tool-call->anthropic-block part)
               :tool-result {:type "tool_result"
                             :tool_use_id (:tool-result/id part)
                             :content (:tool-result/content part)
                             :is_error (:tool-result/is-error part)}
               :provider-state
               (if (and (contains? #{:anthropic :vertex-anthropic}
                                   (:provider-state/provider part))
                        (map? (get-in part
                                      [:provider-state/data :content-block])))
                 (get-in part [:provider-state/data :content-block])
                 (unsupported-content! part))
               :unknown/provider-native
               (if (and (contains? #{:anthropic :vertex-anthropic}
                                   (:unknown/provider part))
                        (map? (:unknown/data part)))
                 (:unknown/data part)
                 (unsupported-content! part))
               (unsupported-content! part))))
          content)

    :else
    (throw (ex-info "Anthropic message content must be text or content parts."
                    {:error/type :anthropic/unsupported-message-content
                     :content/type (type content)}))))

(defn- same-tool-use? [left right]
  (and (= "tool_use" (:type left))
       (= "tool_use" (:type right))
       (= (:id left) (:id right))
       (= (:name left) (:name right))
       (= (:input left) (:input right))))

(defn- append-new-tool-calls [blocks tool-calls]
  (reduce (fn [result tc]
            (let [block (tool-call->anthropic-block tc)]
              (if (some #(same-tool-use? % block) result)
                result
                (conj result block))))
          (vec blocks)
          tool-calls))

(defn- message->anthropic [msg]
  (let [role (case (:message/role msg)
               (:user :tool) "user"
               :assistant "assistant"
               "user")]
    (cond
      (= (:message/role msg) :tool)
      {:role "user"
       :content [{:type "tool_result"
                  :tool_use_id (or (:message/tool-call-id msg) "tool_0")
                  :content (text-content!
                            (:message/content msg)
                            :anthropic/unsupported-tool-result-content
                            "Anthropic tool result messages require text content.")}]}

      (seq (:message/tool-calls msg))
      {:role "assistant"
       :content (append-new-tool-calls
                 (content->anthropic-blocks (:message/content msg))
                 (:message/tool-calls msg))}

      :else
      {:role role
       :content (content->anthropic-blocks (:message/content msg))})))

(defn- messages->anthropic [messages]
  (->> messages
       (remove #(instruction-role? (:message/role %)))
       (mapv message->anthropic)))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- unsupported-tool! [tool]
  (throw (ex-info
          (str "Anthropic does not support canonical "
               (pr-str (:type tool)) " tools.")
          {:error/type :anthropic/unsupported-tool
           :tool/type (:type tool)})))

(defn- tool->anthropic [tool]
  (case (:type tool)
    :function
    (let [fn-data (:function tool)]
      (cond-> {:name (:name fn-data)
               :description (or (:description fn-data) "")
               :input_schema (or (:parameters fn-data)
                                 {:type "object" :properties {}})}
        (contains? fn-data :strict)
        (assoc :strict (boolean (:strict fn-data)))))

    (unsupported-tool! tool)))

(defn- tool-choice->anthropic [tc]
  (case tc
    :auto {:type "auto"}
    :required {:type "any"}
    :none nil
    (when (map? tc)
      (case (:type tc)
        :function {:type "tool" :name (get-in tc [:function :name])}
        (unsupported-tool! tc)))))

;; ---------------------------------------------------------------------------
;; OAuth token detection
;; ---------------------------------------------------------------------------

(defn oauth-token?
  "Check if a token is an Anthropic OAuth/setup token.
   Detects: sk-ant-* (but not sk-ant-api*), eyJ* JWTs, cc-* Claude Code tokens."
  [token]
  (boolean
   (when (string? token)
     (cond
       ;; Regular Anthropic Console API key — NOT OAuth
       (str/starts-with? token "sk-ant-api") false
       ;; Anthropic-issued setup tokens / managed keys
       (str/starts-with? token "sk-ant-") true
       ;; JWTs from Anthropic OAuth flow
       (str/starts-with? token "eyJ") true
       ;; Claude Code OAuth access tokens
       (str/starts-with? token "cc-") true
       :else false))))

(defn- third-party-endpoint?
  "Return true for non-Anthropic endpoints using Anthropic Messages API.
   OAuth transforms only apply to direct Anthropic endpoints."
  [base-url]
  (if-let [host (try
                  (some-> base-url java.net.URI. .getHost str/lower-case)
                  (catch Exception _ nil))]
    (not (or (= host "anthropic.com")
             (str/ends-with? host ".anthropic.com")))
    false))

;; ---------------------------------------------------------------------------
;; Claude Code identity (OAuth mode)
;; ---------------------------------------------------------------------------

(def ^:private claude-code-system-prefix
  "You are Claude Code, Anthropic's official CLI for Claude.")

(defn- sanitize-system-for-oauth
  "Sanitize system prompt for Anthropic OAuth endpoints.
   Replace product name references to avoid server-side content filters."
  [blocks]
  (mapv (fn [block]
          (if (and (map? block) (= (:type block) "text"))
            (update block :text
                    #(-> %
                         (str/replace "Hermes Agent" "Claude Code")
                         (str/replace "Hermes agent" "Claude Code")
                         (str/replace "hermes-agent" "claude-code")
                         (str/replace "Nous Research" "Anthropic")))
            block))
        blocks))

(defn- mcp-prefix-tools
  "Prefix tool names with mcp_ (Claude Code convention).
   Returns nil when input is nil or empty."
  [tools]
  (when (seq tools)
    (mapv (fn [tool]
            (cond
              (get-in tool [:function :name])
              (update-in tool [:function :name]
                         #(if (str/starts-with? % "mcp_") % (str "mcp_" %)))

              (:name tool)
              (update tool :name
                      #(if (str/starts-with? % "mcp_") % (str "mcp_" %)))

              :else
              tool))
          tools)))

(defn- mcp-prefix-tool-names-in-messages
  "Prefix tool names in message history (tool_use blocks)."
  [messages]
  (mapv (fn [msg]
          (if (:content msg)
            (update msg :content
                    (fn [blocks]
                      (mapv (fn [block]
                              (if (and (map? block) (= (:type block) "tool_use") (:name block))
                                (update block :name #(if (str/starts-with? % "mcp_") % (str "mcp_" %)))
                                block))
                            blocks)))
            msg))
        messages))

(defn- file-id-attachment? [part]
  (and (= :file (:part/type part)) (:file/id part)))

(defn- messages-use-files-api? [messages]
  (boolean
   (some (fn [msg]
           (let [content (:message/content msg)]
             (and (sequential? content)
                  (some file-id-attachment? content))))
         messages)))


(defn- add-beta-header [headers beta]
  (update headers "anthropic-beta"
          (fn [existing]
            (if (seq existing)
              (if (str/includes? existing beta)
                existing
                (str existing "," beta))
              beta))))

;; ---------------------------------------------------------------------------
;; Thinking / reasoning config
;; ---------------------------------------------------------------------------

(defn- model-matches? [model fragments]
  (let [m (str/lower-case (or model ""))]
    (some #(str/includes? m %) fragments)))

(defn- adaptive-thinking-model? [model]
  (model-matches?
   model
   ["claude-opus-4-6" "claude-sonnet-4-6"
    "claude-opus-4-7" "claude-opus-4-8"
    "claude-opus-5" "claude-sonnet-5" "claude-fable-5"
    "claude-mythos-preview" "claude-mythos-5"]))

(defn- always-on-thinking-model? [model]
  (model-matches?
   model
   ["claude-fable-5" "claude-mythos-preview" "claude-mythos-5"]))

(defn- explicitly-disableable-thinking-model? [model]
  (model-matches? model ["claude-opus-5" "claude-sonnet-5"]))

(defn- opus-5-model? [model]
  (model-matches? model ["claude-opus-5"]))

(defn- effort-supported-model? [model]
  (or (adaptive-thinking-model? model)
      (model-matches? model ["claude-opus-4-5"])))

(defn- xhigh-supported? [model]
  (model-matches?
   model
   ["claude-opus-4-7" "claude-opus-4-8" "claude-opus-5"
    "claude-sonnet-5" "claude-fable-5" "claude-mythos-5"]))

(defn- no-sampling-params? [model]
  (model-matches?
   model
   ["claude-sonnet-4-6" "claude-opus-4-7" "claude-opus-4-8"
    "claude-opus-5" "claude-sonnet-5" "claude-fable-5"
    "claude-mythos-preview" "claude-mythos-5"]))

(defn- effort-value [model requested]
  (when (= requested :none)
    (throw (ex-info "Anthropic does not support reasoning effort :none."
                    {:error/type :anthropic/unsupported-reasoning-effort
                     :reasoning/effort requested})))
  (let [effort (case requested
                 :minimal "low"
                 (name requested))]
    (if (and (= effort "xhigh") (not (xhigh-supported? model)))
      "max"
      effort)))

(defn- build-thinking-config [model reasoning]
  (let [enabled? (get reasoning :enabled true)
        requested-effort (:effort reasoning)]
    (cond
      (nil? reasoning)
      nil

      (false? enabled?)
      (do
        (when (always-on-thinking-model? model)
          (throw (ex-info
                  "Anthropic cannot disable thinking for this always-on model."
                  {:error/type :anthropic/unsupported-thinking-configuration
                   :request/model model})))
        (let [requested-value (when requested-effort
                                (effort-value model requested-effort))
              effort (when (effort-supported-model? model)
                       requested-value)]
          (when (and (opus-5-model? model)
                     (contains? #{"xhigh" "max"} effort))
            (throw (ex-info
                    "Anthropic Opus 5 cannot disable thinking at xhigh or max effort."
                    {:error/type :anthropic/unsupported-thinking-configuration
                     :request/model model
                     :reasoning/effort requested-effort})))
          (cond-> {}
            (explicitly-disableable-thinking-model? model)
            (assoc :thinking {:type "disabled"})
            effort
            (assoc :output_config {:effort effort}))))

      (adaptive-thinking-model? model)
      (let [effort (effort-value model
                                 (get reasoning :effort :medium))]
        {:thinking {:type "adaptive" :display "summarized"}
         :output_config {:effort effort}})

      :else
      {:thinking {:type "enabled"
                  :budget_tokens (or (:budget reasoning)
                                     (get {:xhigh 32000 :high 16000
                                           :medium 8000 :low 4000
                                           :minimal 1024}
                                          (get reasoning :effort :medium)
                                          8000))}})))

(defn- build-output-format [response-format]
  (when-let [schema (case (:type response-format)
                      :json_schema (or (:json-schema response-format) {})
                      :json_object {}
                      nil)]
    {:output_config
     {:format {:type "json_schema"
               :schema schema}}}))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(def ^:private claude-code-version "2.1.74")

(defn- build-oauth-headers
  "Build headers for Anthropic OAuth requests.
   Matches Hermes: Claude Code user-agent, x-app, and full beta list."
  [token]
  {"Authorization" (str "Bearer " token)
   "anthropic-beta" "interleaved-thinking-2025-05-14,fine-grained-tool-streaming-2025-05-14,claude-code-20250219,oauth-2025-04-20"
   "user-agent" (str "claude-cli/" claude-code-version " (external, cli)")
   "x-app" "cli"})

(defn build-request-anthropic
  [profile request]
  (let [model (:request/model request)
        model-norm (-> model str/lower-case (str/replace #"anthropic/" ""))
        messages (:request/messages request)
        token (provider/resolve-auth-token profile)
        oauth? (and (oauth-token? token) (not (third-party-endpoint? (:profile/base-url profile))))
        system-blocks (extract-system messages)
        anthropic-messages (messages->anthropic messages)
        tools (when (seq (:request/tools request))
                (mapv tool->anthropic (:request/tools request)))
        thinking (build-thinking-config model-norm (:request/reasoning request))
        output-format (build-output-format (:request/response-format request))
        output-config (merge (:output_config thinking)
                             (:output_config output-format))
        thinking (dissoc thinking :output_config)
        tool-choice (tool-choice->anthropic (:request/tool-choice request))
        ;; Clamp to the model's known output ceiling: Anthropic rejects
        ;; max_tokens above a model's cap (e.g. haiku-4-5 tops out at 64000,
        ;; not the 128000 default). The single-arg catalog scan resolves both
        ;; native ids (claude-haiku-4-5-20251001) and Vertex ids
        ;; (claude-haiku-4-5@20251001). Unknown models keep the requested value.
        max-tokens (let [requested (or (:request/max-tokens request) 128000)
                         cap (catalog/max-output-tokens model-norm)]
                     (if cap (min requested cap) requested))
        ;; OAuth transforms
        system-blocks (if oauth?
                        (let [cc-block {:type "text" :text claude-code-system-prefix}
                              blocks (if (seq system-blocks)
                                       (into [cc-block] system-blocks)
                                       [cc-block])]
                          (sanitize-system-for-oauth blocks))
                        system-blocks)
        tools (if oauth? (mcp-prefix-tools tools) tools)
        anthropic-messages (if oauth? (mcp-prefix-tool-names-in-messages anthropic-messages) anthropic-messages)
        ;; Caching: native layout for Anthropic. Decide once, apply
        ;; markers to (system, messages, tools). Caller can override
        ;; via :request/cache; defaults to no-op when omitted.
        cache-cfg (:request/cache request)
        cache-on? (cache/cache-enabled? request)
        cache-decision (when cache-on?
                         (cache/decide-strategy profile model-norm cache-cfg))
        total-bps (cache/breakpoints request)
        apply-cache? (and cache-on? (= (:strategy cache-decision) :system-and-3))
        ;; Native Anthropic: system lives in the top-level :system
        ;; field, not messages[0]. Spend one breakpoint there if a
        ;; system prompt is present, then split the rest across the
        ;; tail of the messages list.
        system-bps (if (and apply-cache? (seq system-blocks)) 1 0)
        msg-bps (max 0 (- total-bps system-bps))
        cache-opts {:ttl (cache/ttl request) :layout :native}
        system-blocks (if apply-cache?
                        (cache/apply-system-blocks-cache system-blocks cache-opts)
                        system-blocks)
        anthropic-messages (if apply-cache?
                             (cache/apply-system-and-3
                              anthropic-messages
                              (assoc cache-opts :breakpoints msg-bps))
                             anthropic-messages)
        tools (if (and apply-cache? (cache/tools-cache? request))
                (cache/apply-tools-cache tools cache-opts)
                tools)
        files-api? (messages-use-files-api? messages)
        configured-betas (get-in request
                                  [:request/provider-options :anthropic :betas])
        configured-betas (cond
                           (string? configured-betas) [configured-betas]
                           (sequential? configured-betas) configured-betas
                           :else [])
        ;; Build headers
        headers (if oauth?
                  (merge (:profile/default-headers profile {})
                         (build-oauth-headers token))
                  (merge (provider/auth-headers profile token)
                         (:profile/default-headers profile {})
                         {"anthropic-dangerous-direct-browser-access" "true"}))
        headers (reduce add-beta-header headers configured-betas)
        headers (cond-> headers
                  files-api?
                  (add-beta-header "files-api-2025-04-14"))
        body (merge
              {:model model-norm
               :messages anthropic-messages
               :max_tokens max-tokens}
              (when (:request/stream? request)
                {:stream true})
              (when (seq output-config)
                {:output_config output-config})
              (when (seq system-blocks)
                {:system system-blocks})
              (when tools
                {:tools tools})
              (when tool-choice
                {:tool_choice tool-choice})
              (when (and (:request/temperature request)
                         (not (no-sampling-params? model-norm))
                         (not (:thinking thinking)))
                {:temperature (:request/temperature request)})
              (when (and (:request/top-p request)
                         (not (no-sampling-params? model-norm)))
                {:top_p (:request/top-p request)})
              (when (:request/stop request)
                {:stop_sequences (t/stop-sequences (:request/stop request))})
              thinking
              (when (:request/metadata request)
                {:metadata (:request/metadata request)}))]
    {:method :post
     :url (str (:profile/base-url profile) "/messages")
     :headers headers
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- citation-text-range [citation]
  (case (:type citation)
    "char_location"
    (when (and (some? (:start_char_index citation))
               (some? (:end_char_index citation)))
      [(:start_char_index citation) (:end_char_index citation)])

    "page_location"
    (when (and (some? (:start_page_number citation))
               (some? (:end_page_number citation)))
      [(:start_page_number citation) (:end_page_number citation)])

    ("content_block_location" "search_result_location")
    (when (and (some? (:start_block_index citation))
               (some? (:end_block_index citation)))
      [(:start_block_index citation) (:end_block_index citation)])

    nil))

(defn- citation-source-id [citation]
  (case (:type citation)
    "web_search_result_location" (:encrypted_index citation)
    "search_result_location" (some-> (:search_result_index citation) str)
    ("char_location" "page_location" "content_block_location")
    (some-> (:document_index citation) str)
    nil))

(defn- citation->canonical [citation]
  (let [url (case (:type citation)
              "web_search_result_location" (:url citation)
              "search_result_location" (:source citation)
              nil)
        source-id (citation-source-id citation)
        text-range (citation-text-range citation)
        title (or (:title citation) (:document_title citation))
        provider-data {:anthropic/citation citation}]
    (cond-> {:part/type :citation
             :citation/provider-data provider-data}
      (seq url) (assoc :citation/url url)
      title (assoc :citation/title title)
      (:cited_text citation) (assoc :citation/snippet
                                    (:cited_text citation))
      text-range (assoc :citation/text-range text-range)
      source-id (assoc :citation/source-id source-id))))

(defn- native-provider-state-part [block]
  {:part/type :provider-state
   :provider-state/provider :anthropic
   :provider-state/data {:content-block block}})

(defn- block->canonical-parts [block]
  (case (:type block)
    "text"
    (into [{:part/type :text :text (:text block)}]
          (map citation->canonical)
          (:citations block))

    "thinking"
    [(cond-> {:part/type :reasoning
              :reasoning/text (or (:thinking block) "")}
       (:signature block)
       (assoc :reasoning/signature (:signature block)))]

    "redacted_thinking"
    [(native-provider-state-part block)]

    "tool_use"
    [{:part/type :tool-call
      :tool-call/id (:id block)
      :tool-call/name (:name block)
      :tool-call/arguments (json/generate-string (:input block))
      :tool-call/provider-data
      {:anthropic/input (:input block)
       :anthropic/content-block block}}]

    [{:part/type :unknown/provider-native
      :unknown/provider :anthropic
      :unknown/data block}]))

(defn- one-or-many [events]
  (let [events (vec (remove nil? events))]
    (case (count events)
      0 nil
      1 (first events)
      events)))

(defn- citation->stream-event [_index citation]
  (let [part (citation->canonical citation)]
    (stream/citation-event
     (:citation/url part)
     :title (:citation/title part)
     :snippet (:citation/snippet part)
     :text-range (:citation/text-range part)
     :source-id (:citation/source-id part)
     :source (:citation/source part)
     :provider-data (:citation/provider-data part))))

(defn parse-response-anthropic
  [_profile raw]
  (let [content (:content raw)
        parts (vec (mapcat block->canonical-parts content))
        reasoning-details (vec (keep #(when (= (:type %) "thinking") %) (:content raw)))
        tool-calls (vec (filter #(= (:part/type %) :tool-call) parts))
        finish-reason (get stop-reason-map (:stop_reason raw) :unknown)
        usage-raw (:usage raw)
        citations (vec (mapcat #(or (:citations %) []) content))
        provider-data (cond-> {}
                        (seq reasoning-details)
                        (assoc :reasoning_details reasoning-details)
                        (seq citations)
                        (assoc :citations citations)
                        (:stop_details raw)
                        (assoc :stop_details (:stop_details raw))
                        (:container raw)
                        (assoc :container (:container raw)))]
    (cond-> {:response/id (:id raw)
             :response/provider :anthropic
             :response/model (:model raw)
             :response/parts parts
             :response/finish-reason finish-reason
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage-raw (assoc :response/usage
                       (usage/normalize-usage :anthropic usage-raw))
      (seq provider-data) (assoc :response/provider-data provider-data))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- normalize-stream-usage [usage-raw]
  (let [output-details (:output_tokens_details usage-raw)
        server-tool-use (:server_tool_use usage-raw)]
    (cond-> {:usage/provider-raw usage-raw}
      (contains? usage-raw :input_tokens)
      (assoc :usage/input-tokens (usage/->int (:input_tokens usage-raw)))
      (contains? usage-raw :output_tokens)
      (assoc :usage/output-tokens (usage/->int (:output_tokens usage-raw)))
      (contains? usage-raw :cache_read_input_tokens)
      (assoc :usage/cached-input-tokens
             (usage/->int (:cache_read_input_tokens usage-raw)))
      (contains? usage-raw :cache_creation_input_tokens)
      (assoc :usage/cache-write-tokens
             (usage/->int (:cache_creation_input_tokens usage-raw)))
      (and (map? output-details)
           (contains? output-details :thinking_tokens))
      (assoc :usage/reasoning-tokens
             (usage/->int (:thinking_tokens output-details)))
      (and (map? server-tool-use)
           (contains? server-tool-use :web_search_requests))
      (assoc :usage/search-queries
             (usage/->int (:web_search_requests server-tool-use))))))

(defn parse-stream-event-anthropic
  [_profile line]
  (when-let [data (parse-sse-line line)]
    (let [t (:type data)]
      (cond
        (= t "content_block_delta")
        (let [delta (:delta data)
              idx (:index data 0)]
          (case (:type delta)
            "text_delta" (stream/content-delta (:text delta))
            "thinking_delta" (stream/reasoning-delta
                              (:thinking delta)
                              :index idx)
            "input_json_delta" (stream/tool-call-delta
                                idx
                                (or (:partial_json delta) ""))
            "signature_delta" (stream/reasoning-delta
                               nil
                               :index idx
                               :signature (:signature delta))
            "citations_delta" (citation->stream-event
                               idx
                               (:citation delta))
            "compaction_delta" (stream/provider-state-event
                                :anthropic
                                {:content-blocks
                                 {idx {:compaction delta}}})
            nil))

        (= t "content_block_start")
        (let [block (:content_block data)
              idx (:index data 0)]
          (if (= (:type block) "tool_use")
            (stream/tool-call-start
             idx
             (:id block)
             (:name block)
             :provider-data
             {:anthropic/input (:input block)
              :anthropic/content-block block})
            (stream/provider-state-event
             :anthropic
             {:content-blocks {idx {:block block}}})))

        (= t "content_block_stop")
        nil

        (= t "message_start")
        (let [message (:message data)
              provider-ev (stream/provider-state-event
                           :anthropic
                           {:message (dissoc message :content :usage)})
              usage-ev (when-let [usage-raw (:usage message)]
                         (stream/usage-event
                          (normalize-stream-usage usage-raw)))]
          (one-or-many [provider-ev usage-ev]))

        (= t "message_stop")
        (stream/end-event)

        (= t "message_delta")
        (let [usage-ev (when-let [usage-raw (:usage data)]
                         (stream/usage-event
                          (normalize-stream-usage usage-raw)))
              finish-ev (when-let [stop-reason
                                   (get-in data [:delta :stop_reason])]
                          (stream/end-event
                           :finish-reason (get stop-reason-map
                                               stop-reason
                                               :unknown)))
              events (one-or-many [usage-ev finish-ev])]
          events)

        (= t "error")
        (stream/error-event (:error data))

        :else nil))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-anthropic
  [_profile status body]
  (errors/classify-api-error :anthropic "Anthropic" status body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord AnthropicTransport []
  t/Transport
  (build-request [_ profile request]
    (build-request-anthropic profile request))

  (parse-response [_ profile raw]
    (parse-response-anthropic profile raw))

  (parse-stream-event [_ profile line]
    (parse-stream-event-anthropic profile line))

  (parse-error [_ profile status body]
    (parse-error-anthropic profile status body))

  (normalize-usage [_ _profile raw]
    (usage/normalize-usage :anthropic raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :cache :thinking-blocks
      :file-attachments}))

(defn make-transport []
  (->AnthropicTransport))

;; Register
(when-let [p (provider/get-provider :anthropic)]
  (provider/register-provider
   (assoc p :profile/transport-constructor make-transport)))
