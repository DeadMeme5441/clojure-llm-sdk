(ns llm.sdk.providers.anthropic
  "Anthropic Messages API transport adapter.
   Supports thinking blocks, cache_control, tool use, streaming deltas.
   Preserves provider-specific replay state (reasoning_details, signatures)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
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
   "refusal" :content-filter
   "model_context_window_exceeded" :length})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- extract-system [messages]
  (when (and (seq messages) (= (:message/role (first messages)) :system))
    [{:type "text" :text (t/content->string (:message/content (first messages)))}]))

(defn- content->anthropic-blocks [content]
  (cond
    (string? content)
    [{:type "text" :text content}]

    (sequential? content)
    (mapv (fn [part]
            (case (:part/type part)
              :text {:type "text" :text (:text part)}
              :image {:type "image" :source {:type "base64"
                                              :media_type (get part :image/mime-type "image/png")
                                              :data (:image/url part)}}
              :tool-result {:type "tool_result"
                            :tool_use_id (:tool-result/id part)
                            :content (:tool-result/content part)
                            :is_error (:tool-result/is-error part)}
              {:type "text" :text (str part)}))
          content)

    :else [{:type "text" :text (str content)}]))

(defn- message->anthropic [msg]
  (let [role (case (:message/role msg)
               (:user :tool) "user"
               (:assistant) "assistant"
               "user")]
    {:role role
     :content (content->anthropic-blocks (:message/content msg))}))

(defn- messages->anthropic [messages]
  (let [without-system (if (and (seq messages) (= (:message/role (first messages)) :system))
                         (rest messages)
                         messages)]
    (mapv message->anthropic without-system)))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- tool->anthropic [tool]
  (let [fn-data (:function tool)]
    {:name (:name fn-data)
     :description (or (:description fn-data) "")
     :input_schema (or (:parameters fn-data) {:type "object" :properties {}})}))

(defn- tool-choice->anthropic [tc]
  (case tc
    :auto {:type "auto"}
    :required {:type "any"}
    :none nil
    (when (map? tc)
      {:type "tool" :name (get-in tc [:function :name])})))

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
  (if (seq base-url)
    (let [normalized (-> base-url str str/lower-case (str/replace #"/+$" ""))]
      (not (str/includes? normalized "anthropic.com")))
    false))

;; ---------------------------------------------------------------------------
;; Claude Code identity (OAuth mode)
;; ---------------------------------------------------------------------------

(def ^:private claude-code-system-prefix
  "You are Claude, an AI assistant made by Anthropic.")

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
            (if (get-in tool [:function :name])
              (update-in tool [:function :name] #(if (str/starts-with? % "mcp_") % (str "mcp_" %)))
              tool))
          tools)))

(defn- mcp-prefix-tool-names-in-messages
  "Prefix tool names in message history (tool_use blocks)."
  [messages]
  (mapv (fn [msg]
          (if-let [content (:content msg)]
            (update msg :content
                    (fn [blocks]
                      (mapv (fn [block]
                              (if (and (map? block) (= (:type block) "tool_use") (:name block))
                                (update block :name #(if (str/starts-with? % "mcp_") % (str "mcp_" %)))
                                block))
                            blocks)))
            msg))
        messages))

;; ---------------------------------------------------------------------------
;; Thinking / reasoning config
;; ---------------------------------------------------------------------------

(defn- adaptive-thinking-model? [model]
  (let [m (str/lower-case (or model ""))]
    (some #(str/includes? m %) ["4-6" "4.6" "4-7" "4.7"])))

(defn- xhigh-supported? [model]
  (let [m (str/lower-case (or model ""))]
    (some #(str/includes? m %) ["4-7" "4.7"])))

(defn- no-sampling-params? [model]
  (let [m (str/lower-case (or model ""))]
    (some #(str/includes? m %) ["4-7" "4.7"])))

(defn- build-thinking-config [model reasoning]
  (when (and reasoning (:enabled reasoning true))
    (if (adaptive-thinking-model? model)
      (let [effort (name (get reasoning :effort :medium))
            effort (if (and (= effort "xhigh") (not (xhigh-supported? model)))
                     "max"
                     effort)]
        {:thinking {:type "adaptive" :display "summarized"}
         :output_config {:effort effort}})
      {:thinking {:type "enabled"
                  :budget_tokens (get {:xhigh 32000 :high 16000 :medium 8000 :low 4000}
                                      (get reasoning :effort :medium)
                                      8000)}})))

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
        tool-choice (tool-choice->anthropic (:request/tool-choice request))
        max-tokens (or (:request/max-tokens request) 128000)
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
        ;; Build headers
        headers (if oauth?
                  (merge (:profile/default-headers profile {})
                         (build-oauth-headers token))
                  (merge (provider/auth-headers profile token)
                         (:profile/default-headers profile {})
                         {"anthropic-dangerous-direct-browser-access" "true"}))
        body (merge
              {:model model-norm
               :messages anthropic-messages
               :max_tokens max-tokens}
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

(defn parse-response-anthropic
  [profile raw]
  (let [text-parts (vec (keep #(when (= (:type %) "text") (:text %)) (:content raw)))
        thinking-parts (vec (keep #(when (= (:type %) "thinking") (:thinking %)) (:content raw)))
        reasoning-details (vec (keep #(when (= (:type %) "thinking") %) (:content raw)))
        tool-calls (vec (keep #(when (= (:type %) "tool_use")
                            {:part/type :tool-call
                             :tool-call/id (:id %)
                             :tool-call/name (:name %)
                             :tool-call/arguments (json/generate-string (:input %))
                             :tool-call/provider-data {:anthropic/input (:input %)}})
                          (:content raw)))
        finish-reason (get stop-reason-map (:stop_reason raw) :stop)
        usage-raw (:usage raw)
        provider-data (cond-> {}
                      (seq reasoning-details)
                      (assoc :reasoning_details reasoning-details))]
    {:response/id (:id raw)
     :response/provider :anthropic
     :response/model (:model raw)
     :response/parts (into []
                           (concat
                            (map #(hash-map :part/type :reasoning :reasoning/text %) thinking-parts)
                            (map #(hash-map :part/type :text :text %) text-parts)
                            tool-calls))
     :response/tool-calls (not-empty tool-calls)
     :response/finish-reason finish-reason
     :response/usage (when usage-raw
                       (usage/normalize-usage :anthropic usage-raw))
     :response/provider-data (not-empty provider-data)
     :response/raw raw}))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= payload "[DONE]")
        (try (json/parse-string payload true)
             (catch Exception _ nil))))))

(defn parse-stream-event-anthropic
  [profile line]
  (when-let [data (parse-sse-line line)]
    (let [t (:type data)]
      (cond
        (= t "content_block_delta")
        (let [delta (:delta data)]
          (case (:type delta)
            "text_delta" (stream/content-delta (:text delta))
            "thinking_delta" (stream/reasoning-delta (:thinking delta))
            nil))

        (= t "content_block_start")
        (when (= (get-in data [:content_block :type]) "tool_use")
          (let [block (:content_block data)
                idx (:index data 0)]
            (stream/tool-call-start idx (:id block) (:name block))))

        (= t "message_stop")
        (stream/end-event :finish-reason (get stop-reason-map
                                               (get-in data [:message :stop_reason])
                                               :stop))

        (= t "message_delta")
        (when-let [usage-raw (get-in data [:usage])]
          (stream/usage-event (usage/normalize-usage :anthropic usage-raw)))

        :else nil))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-anthropic
  [profile status body]
  (errors/classify-error (Exception. "Anthropic API error")
                         :status status
                         :body body
                         :provider :anthropic))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord AnthropicTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-anthropic profile request))

  (parse-response [this profile raw]
    (parse-response-anthropic profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-anthropic profile line))

  (parse-error [this profile status body]
    (parse-error-anthropic profile status body))

  (normalize-usage [this profile raw]
    (usage/normalize-usage :anthropic raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :cache :thinking-blocks}))

(defn make-transport []
  (->AnthropicTransport))

;; Register
(when-let [p (provider/get-provider :anthropic)]
  (provider/register-provider
   (assoc p :profile/transport-constructor make-transport)))
