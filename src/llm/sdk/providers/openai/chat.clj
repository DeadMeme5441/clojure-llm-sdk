(ns llm.sdk.providers.openai.chat
  "OpenAI Chat Completions transport adapter.
   Covers OpenAI, OpenRouter, DeepSeek, and other OpenAI-compatible providers."
  (:require [clojure.string :as str]
            [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai.embeddings :as openai-embeddings]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- wire-type-name [value]
  (if (keyword? value) (name value) value))

(defn- custom-tools-supported? [profile]
  (or (= :openai (:profile/id profile))
      (true? (get-in profile [:profile/quirks :custom-tools]))))

(defn- unsupported-custom-tool! [profile]
  (throw
   (ex-info "Custom tools are not supported by this OpenAI-compatible provider"
            {:provider (:profile/id profile)
             :error/type :provider/unsupported-custom-tool})))

(defn- tool-call->openai [tc]
  (let [provider-data (:tool-call/provider-data tc)
        wire-type (wire-type-name
                   (or (:wire_type provider-data)
                       (when (:custom provider-data) "custom")
                       "function"))
        native-state (select-keys provider-data
                                  [:extra_content :call_id :response_item_id])]
    (merge
     {:id (:tool-call/id tc)
      :type wire-type}
     native-state
     (if (= "custom" wire-type)
       {:custom (merge (:custom provider-data)
                       {:name (:tool-call/name tc)
                        :input (or (:tool-call/arguments tc) "")})}
       {:function {:name (:tool-call/name tc)
                   :arguments (or (:tool-call/arguments tc) "")}}))))

(defn- assistant-tool-calls [profile msg]
  (let [content-calls (t/extract-tool-calls-from-parts (:message/content msg))
        calls (->> (concat (:message/tool-calls msg) content-calls)
                   distinct
                   vec)]
    (when (and (not (custom-tools-supported? profile))
               (some #(= "custom"
                         (wire-type-name
                          (get-in % [:tool-call/provider-data :wire_type])))
                     calls))
      (unsupported-custom-tool! profile))
    (when (seq calls)
      (mapv tool-call->openai calls))))

(defn- openai-file-part-supported? [profile]
  (= :openai (:profile/id profile)))

(defn- file->openai-chat [profile part]
  (when-not (openai-file-part-supported? profile)
    (t/unsupported-file-part! (:profile/id profile) part))
  (let [file-data (t/file-data-uri-for-input-file part)
        file-id (:file/id part)]
    (when-not (or file-data file-id)
      (t/missing-file-source! (:profile/id profile) part))
    {:type "file"
     :file (cond-> {}
             (:file/name part) (assoc :filename (:file/name part))
             file-data (assoc :file_data file-data)
             file-id (assoc :file_id file-id))}))

(defn- unsupported-message-part! [profile part]
  (throw
   (ex-info "Message content part is not supported by this provider transport"
            {:provider (:profile/id profile)
             :part/type (:part/type part)
             :error/type :provider/unsupported-content-part})))

(defn- content-part->openai [profile part]
  (case (:part/type part)
    :text {:type "text" :text (:text part)}
    :image {:type "image_url"
            :image_url {:url (t/image-url-or-data-uri!
                              (:profile/id profile) part)
                        :detail (name (get part :image/detail :auto))}}
    :input-audio {:type "input_audio"
                  :input_audio {:data (:audio/data part)
                                :format (name (:audio/format part))}}
    :file (file->openai-chat profile part)
    ;; Assistant tool calls and reasoning use dedicated sibling fields.
    :tool-call nil
    :reasoning nil
    (unsupported-message-part! profile part)))

(defn- reasoning-text-from-parts [content]
  (when (sequential? content)
    (not-empty
     (apply str
            (keep #(when (= :reasoning (:part/type %))
                     (:reasoning/text %))
                  content)))))

(defn- replay-provider-data [profile msg]
  (let [provider-data (:message/provider-data msg)
        stream-data (get-in provider-data
                            [(:profile/id profile) :chat-completion/delta])]
    (merge (select-keys stream-data
                        [:reasoning :reasoning_content :reasoning_details])
           (select-keys provider-data
                        [:reasoning :reasoning_content :reasoning_details]))))

(defn- reasoning-replay-field [profile model]
  (let [configured (get-in profile
                           [:profile/quirks :reasoning-replay-field])]
    (if (= :model-specific configured)
      (let [model (str/lower-case (or model ""))]
        (if (str/includes? model "kimi")
          :reasoning_content
          :reasoning))
      configured)))

(defn- message->openai [profile model msg]
  (let [tool-result
        (when (= :tool (:message/role msg))
          (t/reject-error-tool-result!
           (:profile/id profile)
           (t/extract-tool-result (:profile/id profile) msg)))
        role (name (:message/role msg))
        content (if tool-result
                  (:tool-result/content tool-result)
                  (:message/content msg))
        tool-calls (assistant-tool-calls profile msg)
        tool-call-id (or (:message/tool-call-id msg)
                         (:tool-result/id tool-result))
        tool-name (:tool-result/name tool-result)
        text-content (cond
                       (nil? content) nil
                       (string? content) content
                       (sequential? content)
                       (not-empty
                        (into []
                              (keep #(content-part->openai profile %))
                              content))
                       :else
                       (throw
                        (ex-info "Message content must be text or typed parts"
                                 {:provider (:profile/id profile)
                                  :error/type
                                  :request/invalid-message-content})))
        replay-data (replay-provider-data profile msg)
        replay-field (reasoning-replay-field profile model)
        replay-data
        (if (and (= :assistant (:message/role msg))
                 replay-field
                 (not (contains? replay-data replay-field)))
          (if-let [text (reasoning-text-from-parts content)]
            (assoc replay-data replay-field text)
            replay-data)
          replay-data)]
    (cond-> (merge {:role role} replay-data)
      (some? text-content) (assoc :content text-content)
      (seq tool-calls) (assoc :tool_calls tool-calls)
      tool-call-id (assoc :tool_call_id tool-call-id)
      tool-name (assoc :name tool-name))))

(defn- tool->openai [profile tool]
  (case (:type tool)
    :custom
    (do
      (when-not (custom-tools-supported? profile)
        (unsupported-custom-tool! profile))
      (cond-> {:type "custom"
               :custom (assoc (:custom tool) :name (get-in tool [:custom :name]))}
        (keyword? (get-in tool [:custom :format :type]))
        (update-in [:custom :format :type] name)
        (keyword? (get-in tool [:custom :format :grammar :syntax]))
        (update-in [:custom :format :grammar :syntax] name)))

    {:type "function" :function (:function tool)}))

(defn- tool-choice->openai [profile tc]
  (case tc
    :auto "auto"
    :none "none"
    :required "required"
    (when (map? tc)
      (case (:type tc)
        :custom
        (do
          (when-not (custom-tools-supported? profile)
            (unsupported-custom-tool! profile))
          {:type "custom"
           :custom {:name (get-in tc [:custom :name])}})
        {:type "function"
         :function {:name (get-in tc [:function :name])}}))))

(defn- response-format->openai [fmt]
  (case (:type fmt)
    :json_schema
    {:type "json_schema"
     :json_schema (cond-> {:name (or (:name fmt) "response")
                           :schema (:json-schema fmt)}
                    (:description fmt)
                    (assoc :description (:description fmt))
                    (contains? fmt :strict)
                    (assoc :strict (:strict fmt)))}

    :json_object {:type "json_object"}
    {:type "text"}))

(defn- reasoning-enabled? [reasoning]
  (not= false (:enabled reasoning)))

(defn- low-medium-high-effort [effort & {:keys [allow-none?]}]
  (case effort
    :none (when allow-none? "none")
    :minimal "low"
    :low "low"
    :medium "medium"
    :high "high"
    (:xhigh :max) "high"
    "medium"))

(defn- low-high-max-effort [effort]
  (case effort
    (:none :minimal :low) "low"
    (:medium :high) "high"
    (:xhigh :max) "max"
    "high"))

(defn- deepseek-effort [effort]
  (case effort
    (:minimal :low) "low"
    (:medium :high :xhigh) "high"
    :max "max"
    "high"))

(defn- groq-reasoning-body [request reasoning]
  (let [model (str/lower-case (:request/model request))
        enabled? (reasoning-enabled? reasoning)
        effort (:effort reasoning :medium)
        gpt-oss? (str/includes? model "gpt-oss")
        qwen-36? (str/includes? model "qwen3.6")
        qwen-38? (str/includes? model "qwen3.8")
        incompatible-raw? (or (seq (:request/tools request))
                              (:request/response-format request))
        effort-value (cond
                       gpt-oss? (when enabled?
                                  (low-medium-high-effort effort))
                       qwen-36? (if (or (not enabled?) (= :none effort))
                                  "none"
                                  "default")
                       qwen-38? (if (not enabled?)
                                  "none"
                                  (low-medium-high-effort effort
                                                          :allow-none? true))
                       enabled? (low-medium-high-effort effort
                                                        :allow-none? true))
        include? (when (contains? reasoning :exclude)
                   (not (:exclude reasoning)))]
    (merge
     (when (some? include?) {:include_reasoning include?})
     (when effort-value {:reasoning_effort effort-value})
     (when (and enabled?
                (not gpt-oss?)
                (not incompatible-raw?)
                (not (contains? reasoning :exclude)))
       {:reasoning_format "raw"}))))

(defn- kimi-reasoning-body [profile model reasoning]
  (let [model (str/lower-case model)
        effort-key (:effort reasoning :high)
        enabled? (and (reasoning-enabled? reasoning)
                      (not= :none effort-key))
        effort (low-high-max-effort effort-key)
        code-endpoint? (= :kimi-code (:profile/id profile))
        effort-model? (if code-endpoint?
                        (and (or (str/includes? model "k3")
                                 (str/includes? model "kimi-for-coding"))
                             (not (str/includes? model "highspeed")))
                        (str/includes? model "k3"))
        k27-code? (or (str/includes? model "k2.7-code")
                      (and code-endpoint?
                           (str/includes? model "highspeed")))]
    (cond
      effort-model? (when enabled? {:reasoning_effort effort})
      k27-code? (when enabled?
                  {:thinking {:type "enabled" :keep "all"}})
      :else {:thinking {:type (if enabled? "enabled" "disabled")}})))

(defn- together-reasoning-body [model reasoning]
  (let [model (str/lower-case model)
        effort (:effort reasoning :medium)
        enabled? (and (reasoning-enabled? reasoning)
                      (not= :none effort))
        gpt-oss? (str/includes? model "gpt-oss")
        deepseek-v4? (str/includes? model "deepseek-v4")]
    (cond
      gpt-oss?
      (when enabled?
        {:reasoning_effort (low-medium-high-effort effort)})

      deepseek-v4?
      (cond-> {:reasoning {:enabled enabled?}}
        enabled? (assoc :reasoning_effort
                        (case effort
                          (:minimal :low :medium) "high"
                          (:high :xhigh :max) "max"
                          "high")))

      :else
      {:reasoning {:enabled enabled?}})))

(defn- cerebras-reasoning-body [model reasoning]
  (let [model (str/lower-case model)
        enabled? (reasoning-enabled? reasoning)
        effort (:effort reasoning :medium)]
    (cond
      (str/includes? model "qwen-3.6")
      {:reasoning_effort (if (or (not enabled?) (= :none effort))
                           "none"
                           "default")}

      (or (str/includes? model "qwen-3.8")
          (str/includes? model "gemma-4"))
      {:reasoning_effort (if enabled?
                           (low-medium-high-effort effort :allow-none? true)
                           "none")}

      (or (str/includes? model "gpt-oss")
          (str/includes? model "kimi-k2.7"))
      (when-let [effort-value
                 (when enabled?
                   (low-medium-high-effort effort))]
        {:reasoning_effort effort-value})

      :else
      {:reasoning_effort (if enabled?
                           (low-medium-high-effort effort :allow-none? true)
                           "none")})))

(defn- build-reasoning-body [profile request]
  (when-let [reasoning (:request/reasoning request)]
    (let [provider-id (:profile/id profile)
          model (:request/model request)
          mode (get-in profile [:profile/quirks :reasoning-mode])
          effort (:effort reasoning :medium)
          enabled? (reasoning-enabled? reasoning)]
      (case mode
        :deepseek
        (let [thinking? (and enabled? (not= :none effort))]
          (merge {:thinking {:type (if thinking? "enabled" "disabled")}}
                 (when thinking?
                   {:reasoning_effort (deepseek-effort effort)})))

        (:kimi :kimi-code)
        (kimi-reasoning-body profile model reasoning)

        :mistral
        {:reasoning_effort (if (or (not enabled?) (= :none effort))
                             "none"
                             "high")}

        :groq
        (groq-reasoning-body request reasoning)

        :cerebras
        (cerebras-reasoning-body model reasoning)

        :together
        (together-reasoning-body model reasoning)

        :xai
        {:reasoning_effort (if enabled?
                             (case effort
                               :minimal "low"
                               :max "xhigh"
                               (name effort))
                             "none")}

        :sambanova
        (when-let [effort-value
                   (when enabled?
                     (low-medium-high-effort effort))]
          {:reasoning_effort effort-value})

        :deepinfra
        {:reasoning_effort (if enabled?
                             (low-medium-high-effort effort
                                                     :allow-none? true)
                             "none")}

        (when (= :openai provider-id)
          (when enabled?
            {:reasoning_effort (name effort)}))))))

(defn- dissoc-wire-key [m k]
  (dissoc m k (name k)))

(defn- apply-drops
  "Remove unsupported top-level fields, including string-key spellings."
  [body drops]
  (reduce dissoc-wire-key body drops))

(defn- complete-url
  "Build the chat-completions URL. Honours a profile-level
   :profile/url-builder fn (used by Azure deployment routing and the
   HuggingFace router) when present; otherwise falls back to the
   simple {base-url}/chat/completions form."
  [profile request]
  (if-let [builder (:profile/url-builder profile)]
    (builder profile request "/chat/completions")
    (str (:profile/base-url profile) "/chat/completions")))

(defn- wire-value [m k]
  (if (contains? m k)
    (get m k)
    (get m (name k))))

(defn- enable-stream-usage [body]
  (let [options (merge (when (map? (get body "stream_options"))
                         (get body "stream_options"))
                       (when (map? (:stream_options body))
                         (:stream_options body)))
        options (-> options
                    (dissoc :include_usage "include_usage")
                    (assoc :include_usage true))]
    (-> body
        (dissoc :stream_options "stream_options")
        (assoc :stream_options options))))

(defn- sanitize-groq-reasoning [body]
  (let [raw-format? (some #(= "raw" (wire-type-name %))
                          [(get body :reasoning_format)
                           (get body "reasoning_format")])
        include-present? (or (contains? body :include_reasoning)
                             (contains? body "include_reasoning"))
        tools-or-json? (or (seq (wire-value body :tools))
                           (some? (wire-value body :response_format)))]
    (if (or include-present?
            (and raw-format? tools-or-json?))
      (dissoc-wire-key body :reasoning_format)
      body)))

(defn build-request-openai
  [profile request]
  (let [model (:request/model request)
        wire-model (if (= :v1 (:azure/api-style profile))
                     (:azure/deployment profile)
                     model)
        messages (->> (:request/messages request)
                      t/sanitize-messages
                      (#(t/developer-role-swap % model))
                      (mapv #(message->openai profile model %)))
        tools (when (seq (:request/tools request))
                (mapv #(tool->openai profile %) (:request/tools request)))
        reasoning-body (build-reasoning-body profile request)
        provider-extra-body
        (get-in request [:request/provider-options :extra_body])
        cache-on? (cache/cache-enabled? request)
        cache-decision (when cache-on?
                         (cache/decide-strategy profile model
                                                (:request/cache request)))
        cache-opts {:ttl (cache/ttl request)
                    :layout :envelope
                    :breakpoints (cache/breakpoints request)}
        messages (if (and cache-on?
                          (= (:strategy cache-decision) :system-and-3))
                   (cache/apply-system-and-3 messages cache-opts)
                   messages)
        prompt-cache-key (when (and cache-on?
                                    (= (:strategy cache-decision) :prompt-key)
                                    (cache/scope-id request))
                           (cache/scope-id request))
        base-body
        (merge
         {:model wire-model
          :messages messages}
         (when tools {:tools tools})
         (when prompt-cache-key {:prompt_cache_key prompt-cache-key})
         (when (:request/tool-choice request)
           {:tool_choice
            (tool-choice->openai profile (:request/tool-choice request))})
         (when (:request/temperature request)
           {:temperature (:request/temperature request)})
         (when (:request/top-p request)
           {:top_p (:request/top-p request)})
         (when (:request/max-tokens request)
           {(if (or (= :openai (:profile/id profile))
                    (get-in profile
                            [:profile/quirks :max-completion-tokens]))
              :max_completion_tokens
              :max_tokens)
            (:request/max-tokens request)})
         (when (:request/stop request)
           {:stop (:request/stop request)})
         (when (:request/response-format request)
           {:response_format
            (response-format->openai (:request/response-format request))})
         (when (:request/metadata request)
           {:metadata (:request/metadata request)})
         (when (:request/stream? request)
           {:stream true}))
        body (t/merge-extra-body
              (:profile/id profile)
              (merge base-body reasoning-body)
              provider-extra-body
              #{:model :messages :stream})
        body (if (and (:request/stream? request)
                      (or (= :openai (:profile/id profile))
                          (true? (get-in profile
                                         [:profile/quirks :stream-usage]))))
               (enable-stream-usage body)
               body)
        body (if (= :groq (:profile/id profile))
               (sanitize-groq-reasoning body)
               body)
        body (apply-drops body (get-in profile [:profile/quirks :drops]))]
    {:method :post
     :url (complete-url profile request)
     :headers (provider/default-headers
               profile
               (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- parse-tool-call [tc]
  (let [fn-data (:function tc)
        custom-data (:custom tc)
        wire-type (wire-type-name
                   (or (:type tc)
                       (when custom-data "custom")
                       "function"))
        custom? (= "custom" wire-type)]
    {:part/type :tool-call
     :tool-call/id (:id tc)
     :tool-call/name (if custom? (:name custom-data) (:name fn-data))
     :tool-call/arguments (if custom? (:input custom-data) (:arguments fn-data))
     :tool-call/provider-data
     (cond-> {:wire_type wire-type}
       custom-data (assoc :custom custom-data)
       (:extra_content tc) (assoc :extra_content (:extra_content tc))
       (:call_id tc) (assoc :call_id (:call_id tc))
       (:response_item_id tc)
       (assoc :response_item_id (:response_item_id tc)))}))

(defn- legacy-function-call->tool-call [function-call]
  {:id (or (:id function-call) "tool_call_0")
   :type "function"
   :function (select-keys function-call [:name :arguments])})

(defn- text-content-chunk? [chunk]
  (or (string? chunk)
      (and (map? chunk)
           (contains? #{"text" :text} (:type chunk))
           (string? (:text chunk)))
      (and (map? chunk)
           (nil? (:type chunk))
           (string? (:text chunk)))))

(defn- thinking-content-chunk? [chunk]
  (and (map? chunk)
       (contains? #{"thinking" :thinking} (:type chunk))
       (or (string? (:thinking chunk))
           (sequential? (:thinking chunk)))))

(defn- content-chunk-text [chunk]
  (if (string? chunk) chunk (:text chunk)))

(defn- thinking-content-chunk-text [chunk]
  (let [thinking (:thinking chunk)]
    (if (string? thinking)
      thinking
      (apply str
             (keep #(when (text-content-chunk? %)
                      (content-chunk-text %))
                   thinking)))))

(defn- content-chunk->parts [chunk]
  (cond
    (text-content-chunk? chunk)
    (if-let [text (not-empty (content-chunk-text chunk))]
      [{:part/type :text :text text}]
      [])

    (thinking-content-chunk? chunk)
    (if-let [text (not-empty (thinking-content-chunk-text chunk))]
      [{:part/type :reasoning :reasoning/text text}]
      [])

    :else []))

(defn- content->parts [content]
  (cond
    (string? content)
    (if (seq content) [{:part/type :text :text content}] [])

    (sequential? content)
    (vec (mapcat content-chunk->parts content))

    :else []))

(defn- recognized-content-chunk? [chunk]
  (or (text-content-chunk? chunk)
      (thinking-content-chunk? chunk)))

(defn- unsupported-content-chunks [content]
  (when (sequential? content)
    (vec (remove recognized-content-chunk? content))))

(defn- normalize-finish-reason [profile finish-reason]
  (case finish-reason
    ("stop" nil) :stop
    "length" :length
    ("tool_calls" "function_call") :tool-calls
    "content_filter" :content-filter
    "insufficient_system_resource" (if (= :deepseek (:profile/id profile))
                                     :incomplete
                                     :unknown)
    :unknown))

(defn parse-response-openai
  [profile raw]
  (let [choice (first (:choices raw))
        msg (:message choice)
        wire-tool-calls (cond
                          (seq (:tool_calls msg)) (:tool_calls msg)
                          (:function_call msg)
                          [(legacy-function-call->tool-call
                            (:function_call msg))]
                          :else nil)
        tool-calls (mapv parse-tool-call wire-tool-calls)
        content (:content msg)
        content-parts (content->parts content)
        unsupported-content (unsupported-content-chunks content)
        reasoning (:reasoning msg)
        reasoning-content (or (:reasoning_content msg)
                              (get-in msg [:model_extra :reasoning_content]))
        separate-reasoning (or reasoning-content reasoning)
        reasoning-in-content?
        (some #(= :reasoning (:part/type %)) content-parts)
        finish-reason (normalize-finish-reason profile (:finish_reason choice))
        usage-raw (:usage raw)
        parts (cond-> (if (and (seq separate-reasoning)
                               (not reasoning-in-content?))
                        (into [{:part/type :reasoning
                                :reasoning/text separate-reasoning}]
                              content-parts)
                        content-parts)
                (seq tool-calls) (into tool-calls))
        provider-data
        (cond-> {}
          (some? reasoning)
          (assoc :reasoning reasoning)
          (some? reasoning-content)
          (assoc :reasoning_content reasoning-content)
          (some? (:reasoning_details msg))
          (assoc :reasoning_details (:reasoning_details msg))
          (contains? msg :audio) (assoc :audio (:audio msg))
          (contains? msg :refusal) (assoc :refusal (:refusal msg))
          (contains? msg :moderation) (assoc :moderation (:moderation msg))
          (contains? msg :annotations) (assoc :annotations (:annotations msg))
          (seq unsupported-content)
          (assoc :content_chunks unsupported-content))]
    (cond-> {:response/id (:id raw)
             :response/provider (:profile/id profile)
             :response/model (:model raw)
             :response/parts parts
             :response/finish-reason finish-reason
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage-raw
      (assoc :response/usage
             (usage/normalize-usage (:profile/id profile) usage-raw))
      (seq provider-data) (assoc :response/provider-data provider-data))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- content-chunk->stream-events [profile chunk]
  (cond
    (text-content-chunk? chunk)
    (if-let [text (not-empty (content-chunk-text chunk))]
      [(stream/content-delta text)]
      [])

    (thinking-content-chunk? chunk)
    (if-let [text (not-empty (thinking-content-chunk-text chunk))]
      [(stream/reasoning-delta text)]
      [])

    :else
    [(stream/provider-state-event
      (:profile/id profile)
      {:chat-completion/content-chunk chunk})]))

(defn parse-stream-event-openai
  [profile line]
  (when-let [data (parse-sse-line line)]
    (let [choice (first (:choices data))
          delta (:delta choice)
          legacy-function-call (:function_call delta)
          tc-deltas (cond-> (vec (:tool_calls delta))
                      legacy-function-call
                      (conj {:index 0
                             :type "function"
                             :function legacy-function-call}))
          tool-events
          (mapcat
           (fn [tc]
             (let [idx (:index tc 0)
                   wire-type (wire-type-name
                              (or (:type tc)
                                  (when (:custom tc) "custom")
                                  "function"))
                   custom? (= "custom" wire-type)
                   wire-data (if custom? (:custom tc) (:function tc))
                   args-key (if custom? :input :arguments)
                   args-present? (and (map? wire-data)
                                      (contains? wire-data args-key))
                   start? (or (:id tc) (:name wire-data))
                   provider-data
                   (cond-> {:wire_type wire-type}
                     (:custom tc) (assoc :custom (:custom tc))
                     (:extra_content tc)
                     (assoc :extra_content (:extra_content tc))
                     (:call_id tc) (assoc :call_id (:call_id tc))
                     (:response_item_id tc)
                     (assoc :response_item_id (:response_item_id tc)))
                   start-ev
                   (when start?
                     (stream/tool-call-start
                      idx
                      (or (:id tc) (str "tool_call_" idx))
                      (or (:name wire-data) "")
                      :provider-data provider-data))
                   delta-ev
                   (when args-present?
                     (stream/tool-call-delta
                      idx
                      (or (get wire-data args-key) "")))]
               (remove nil? [start-ev delta-ev])))
           tc-deltas)
          content-events
          (cond
            (string? (:content delta))
            (when (seq (:content delta))
              [(stream/content-delta (:content delta))])

            (sequential? (:content delta))
            (mapcat #(content-chunk->stream-events profile %)
                    (:content delta))

            :else nil)
          events (cond-> (vec content-events)
                   (seq (:reasoning_content delta))
                   (conj (stream/reasoning-delta
                          (:reasoning_content delta)))

                   (seq (:reasoning delta))
                   (conj (stream/reasoning-delta (:reasoning delta))))
          events (into events tool-events)
          provider-delta
          (select-keys delta
                       [:audio :refusal :moderation :reasoning_details])
          events
          (cond-> events
            (seq provider-delta)
            (conj
             (stream/provider-state-event
              (:profile/id profile)
              {:chat-completion/delta provider-delta})))
          events
          (cond-> events
            (:usage data)
            (conj
             (stream/usage-event
              (usage/normalize-usage (:profile/id profile)
                                     (:usage data))))

            (:finish_reason choice)
            (conj
             (stream/end-event
              :finish-reason
              (normalize-finish-reason profile
                                       (:finish_reason choice)))))]
      (case (count events)
        0 nil
        1 (first events)
        events))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-openai
  [profile status body]
  (errors/classify-api-error (:profile/id profile) "OpenAI" status body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenAIChatTransport []
  t/Transport
  (build-request [_this profile request]
    (build-request-openai profile request))

  (parse-response [_this profile raw]
    (parse-response-openai profile raw))

  (parse-stream-event [_this profile line]
    (parse-stream-event-openai profile line))

  (parse-error [_this profile status body]
    (parse-error-openai profile status body))

  (normalize-usage [_this profile raw]
    (usage/normalize-usage (:profile/id profile) raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :cache :multimodal
      :file-attachments}))

(defn make-transport []
  (->OpenAIChatTransport))

;; ---------------------------------------------------------------------------
;; Alias mechanism — register OpenAI-compat providers from a spec map
;; ---------------------------------------------------------------------------

(defn build-alias-profile
  "Construct an OpenAI-compat provider profile from a spec map.

   Spec keys:
     :id                       required, e.g. :mistral
     :base-url                 required, e.g. \"https://api.mistral.ai/v1\"
     :env-var-names            vector of env-var name strings
     :auth-strategy            defaults :bearer
     :auth-header-name         only with :api-key-header
     :auth-query-param         required with :api-key-query
     :default-headers          optional map
     :capabilities             defaults #{:chat :streaming :tools}
     :quirks                   optional map:
                                 :drops #{:k1 :k2} strip body keys
                                 :reasoning-mode provider wire policy
                                 :reasoning-replay-field native history key
                                 :stream-usage request final usage chunk
                                 :max-completion-tokens rename max field
                                 :custom-tools explicitly allow custom tools
     :supports-model-listing?  defaults true
     :supported-params         optional set (carried as
                                 :profile/supported-params
                                 enforces drop+warn)"
  [spec]
  (cond-> {:profile/id (:id spec)
           :profile/protocol-family :openai-chat
           :profile/base-url (:base-url spec)
           :profile/auth-strategy (:auth-strategy spec :bearer)
           :profile/env-var-names (vec (:env-var-names spec []))
           :profile/default-headers (:default-headers spec {})
           :profile/capabilities (:capabilities spec #{:chat :streaming :tools})
           :profile/quirks (:quirks spec {})
           :profile/supports-model-listing (boolean (get spec :supports-model-listing? true))
           :profile/transport-constructor make-transport}
    (:auth-header-name spec)
    (assoc :profile/auth-header-name (:auth-header-name spec))
    (:auth-query-param spec)
    (assoc :profile/auth-query-param (:auth-query-param spec))
    (:supported-params spec)
    (assoc :profile/supported-params (:supported-params spec))))

(defn register-alias!
  "Register an OpenAI-compat alias profile in one call."
  [spec]
  (provider/register-provider (build-alias-profile spec)))

;; ---------------------------------------------------------------------------
;; Azure OpenAI deployment routing
;;
;; Azure differs from openai.com on URL shape and (optionally) auth
;; header — body is identical. The URL builder pattern keeps the chat
;; adapter unchanged for vanilla OpenAI while letting deployment-
;; routed and HuggingFace-router profiles plug in.
;; ---------------------------------------------------------------------------

(defn azure-url-builder
  "URL builder for Azure OpenAI deployments.

   Classic profiles compose
     {base-url}/openai/deployments/{deployment}{path}?api-version=...

   V1 profiles compose
     {base-url}/openai/v1{path}"
  [profile _request path]
  (if (= :v1 (:azure/api-style profile))
    (str (:profile/base-url profile) "/openai/v1" path)
    (str (:profile/base-url profile)
         "/openai/deployments/"
         (:azure/deployment profile)
         path
         "?api-version="
         (:azure/api-version profile))))

(defn register-azure-deployment!
  "Register an Azure OpenAI deployment as a provider profile.

   Required:
     :id           keyword id for the profile (e.g. :azure-gpt4o-prod)
     :endpoint     base URL, e.g. \"https://my-rg.openai.azure.com\"
                   (no trailing slash, no /openai/deployments/...)
     :deployment   the deployment name configured in the Azure portal
     :api-version  e.g. \"2024-08-01-preview\" (classic API style only)

   Optional:
     :api-style          :classic (default) or :v1. V1 uses
                         /openai/v1 endpoints, omits the api-version query,
                         and sends the deployment as the body model.
     :env-var-names      vector of env-var names to read the API key from
                         (defaults to [\"AZURE_OPENAI_API_KEY\"])
     :auth-strategy      :api-key-header (default) or :bearer (AAD)
     :auth-header-name   defaults to \"api-key\" (only used with
                         :api-key-header). Set to \"Authorization\" or
                         pass :auth-strategy :bearer for AAD bearer.
     :capabilities       defaults #{:chat :streaming :tools
                                    :json-schema :reasoning :embedding}
     :quirks             optional quirks map passed through verbatim
     :default-headers    optional map merged into outgoing headers"
  [{:keys [id endpoint deployment api-version api-style
           env-var-names auth-strategy auth-header-name
           capabilities quirks default-headers]
    :or {api-style :classic
         auth-strategy :api-key-header
         auth-header-name "api-key"
         capabilities #{:chat :streaming :tools :json-schema :reasoning}
         quirks {}
         default-headers {}}}]
  (when-not (contains? #{:classic :v1} api-style)
    (throw (ex-info "register-azure-deployment! :api-style must be :classic or :v1"
                    {:id id :api-style api-style})))
  (when-not (and id endpoint deployment
                 (or (= :v1 api-style) api-version))
    (throw (ex-info "register-azure-deployment! needs :id :endpoint :deployment and, for classic routing, :api-version"
                    {:id id :endpoint endpoint
                     :deployment deployment :api-version api-version
                     :api-style api-style})))
  (provider/register-provider
   (cond-> {:profile/id id
            :profile/protocol-family :openai-chat
            :profile/base-url endpoint
            :profile/auth-strategy auth-strategy
            :profile/env-var-names (vec (or env-var-names ["AZURE_OPENAI_API_KEY"]))
            :profile/default-headers default-headers
            :profile/capabilities (conj capabilities :embedding)
            :profile/quirks quirks
            ;; Azure /models is per-deployment and not useful as a
            ;; catalog source — turn it off.
            :profile/supports-model-listing false
            :profile/transport-constructor make-transport
            :profile/embed-transport-constructor openai-embeddings/make-transport
            :profile/url-builder azure-url-builder
            :azure/endpoint endpoint
            :azure/deployment deployment
            :azure/api-version api-version
            :azure/api-style api-style}
     (= auth-strategy :api-key-header)
     (assoc :profile/auth-header-name auth-header-name))))
