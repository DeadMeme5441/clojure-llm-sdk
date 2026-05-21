(ns llm.sdk.providers.openai-chat
  "OpenAI Chat Completions transport adapter.
   Covers OpenAI, OpenRouter, DeepSeek, and other OpenAI-compatible providers."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- message->openai [msg]
  (let [role (name (:message/role msg))
        content (:message/content msg)]
    (cond
      (string? content)
      {:role role :content content}

      (sequential? content)
      {:role role
       :content (mapv (fn [part]
                        (case (:part/type part)
                          :text {:type "text" :text (:text part)}
                          :image {:type "image_url"
                                  :image_url {:url (:image/url part)
                                              :detail (name (get part :image/detail :auto))}}
                          {:type "text" :text (str part)}))
                      content)}

      :else
      {:role role :content (str content)})))

(defn- tool->openai [tool]
  tool)

(defn- tool-choice->openai [tc]
  (case tc
    :auto "auto"
    :none "none"
    :required "required"
    (when (map? tc)
      {:type "function"
       :function {:name (get-in tc [:function :name])}})))

(defn- build-extra-body [profile request]
  (let [reasoning (:request/reasoning request)
        provider-id (:profile/id profile)
        quirks (:profile/quirks profile)]
    (merge
     ;; Provider preferences for OpenRouter
     (when (= provider-id :openrouter)
       (let [prefs (get-in request [:request/provider-options :provider])]
         (when prefs {:provider prefs})))

     ;; Reasoning
     (when (and reasoning (not= provider-id :anthropic))
       (cond
         ;; DeepSeek: explicit thinking type
         (get quirks :thinking-explicit)
         {:thinking {:type (if (:enabled reasoning false) "enabled" "disabled")}}

         ;; Default OpenAI-style reasoning
         :else
         (when (:enabled reasoning)
           {:reasoning {:enabled true
                        :effort (name (get reasoning :effort :medium))}})))

     ;; Any caller-supplied provider options under extra_body key
     (get-in request [:request/provider-options :extra_body]))))

(defn build-request-openai
  [profile request]
  (let [model (:request/model request)
        messages (->> (:request/messages request)
                      t/sanitize-messages
                      (#(t/developer-role-swap % model))
                      (mapv message->openai))
        tools (when (seq (:request/tools request))
                (mapv tool->openai (:request/tools request)))
        extra-body (build-extra-body profile request)
        ;; Caching:
        ;;   :system-and-3 envelope  → mark messages in place
        ;;     (OpenRouter Claude/Qwen and other OpenAI-wire proxies
        ;;      that honour Anthropic-style cache_control)
        ;;   :prompt-key             → set body.prompt_cache_key
        ;;     (OpenAI, DeepSeek, Kimi all accept the field; DeepSeek
        ;;      and Kimi rely on server-side implicit cache and ignore
        ;;      the key, so it's a safe pass-through)
        ;;
        ;; The OpenRouter adapter delegates here for the base body
        ;; then layers its own routing/plugins; the cache decision
        ;; runs in both so the body coming out of here is already
        ;; cache-aware for either path.
        cache-on? (cache/cache-enabled? request)
        cache-decision (when cache-on? (cache/decide-strategy profile model (:request/cache request)))
        cache-opts {:ttl (cache/ttl request) :layout :envelope
                    :breakpoints (cache/breakpoints request)}
        messages (if (and cache-on? (= (:strategy cache-decision) :system-and-3))
                   (cache/apply-system-and-3 messages cache-opts)
                   messages)
        prompt-cache-key (when (and cache-on?
                                    (= (:strategy cache-decision) :prompt-key)
                                    (cache/scope-id request))
                           (cache/scope-id request))
        body (merge
              {:model model
               :messages messages}
              (when tools {:tools tools})
              (when prompt-cache-key {:prompt_cache_key prompt-cache-key})
              (when (:request/tool-choice request)
                {:tool_choice (tool-choice->openai (:request/tool-choice request))})
              (when (:request/temperature request)
                {:temperature (:request/temperature request)})
              (when (:request/top-p request)
                {:top_p (:request/top-p request)})
              (when (:request/max-tokens request)
                {:max_tokens (:request/max-tokens request)})
              (when (:request/stop request)
                {:stop (:request/stop request)})
              (when (:request/response-format request)
                {:response_format
                 (let [fmt (:request/response-format request)]
                   (case (:type fmt)
                     :json_schema {:type "json_schema"
                                   :json_schema {:schema (:json-schema fmt)}}
                     :json_object {:type "json_object"}
                     {:type "text"}))})
              (when (seq extra-body)
                {:extra_body extra-body}))]
    {:method :post
     :url (str (:profile/base-url profile) "/chat/completions")
     :headers (provider/default-headers profile
                                 (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- parse-tool-call [tc]
  (let [fn-data (:function tc)]
    {:part/type :tool-call
     :tool-call/id (:id tc)
     :tool-call/name (:name fn-data)
     :tool-call/arguments (:arguments fn-data)
     :tool-call/provider-data
     (cond-> {}
       (:extra_content tc) (assoc :extra_content (:extra_content tc))
       (:call_id tc) (assoc :call_id (:call_id tc))
       (:response_item_id tc) (assoc :response_item_id (:response_item_id tc)))}))

(defn parse-response-openai
  [profile raw]
  (let [choice (first (:choices raw))
        msg (:message choice)
        tool-calls (vec (when (seq (:tool_calls msg))
                     (mapv parse-tool-call (:tool_calls msg))))
        content (:content msg)
        reasoning (:reasoning msg)
        reasoning-content (or (:reasoning_content msg)
                              (get-in msg [:model_extra :reasoning_content]))
        finish-reason (case (:finish_reason choice)
                        ("stop" nil) :stop
                        "length" :length
                        "tool_calls" :tool-calls
                        "content_filter" :content-filter
                        :unknown)
        usage-raw (:usage raw)
        provider-data (cond-> {}
                      reasoning-content (assoc :reasoning_content reasoning-content)
                      (:reasoning_details msg) (assoc :reasoning_details (:reasoning_details msg)))]
    {:response/id (:id raw)
     :response/provider (:profile/id profile)
     :response/model (:model raw)
     :response/parts (cond-> []
                       (seq content) (conj {:part/type :text :text content})
                       (seq reasoning) (conj {:part/type :reasoning :reasoning/text reasoning})
                       (seq tool-calls) (into tool-calls))
     :response/tool-calls (not-empty tool-calls)
     :response/finish-reason finish-reason
     :response/usage (when usage-raw
                       (usage/normalize-usage (:profile/id profile) usage-raw))
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

(defn parse-stream-event-openai
  [profile line]
  (when-let [data (parse-sse-line line)]
    (let [choice (first (:choices data))
          delta (:delta choice)
          tc-deltas (:tool_calls delta)]
      (cond
        ;; Content delta
        (seq (:content delta))
        (stream/content-delta (:content delta))

        ;; Reasoning delta (DeepSeek / Moonshot / etc)
        (seq (:reasoning_content delta))
        (stream/reasoning-delta (:reasoning_content delta))

        ;; Tool call delta
        (seq tc-deltas)
        (let [tc (first tc-deltas)
              idx (:index tc 0)]
          (if (:id tc)
            (stream/tool-call-start idx (:id tc) (get-in tc [:function :name]))
            (stream/tool-call-delta idx (get-in tc [:function :arguments] ""))))

        ;; Usage at end
        (:usage data)
        (stream/usage-event (usage/normalize-usage (:profile/id profile) (:usage data)))

        ;; Finish
        (:finish_reason choice)
        (stream/end-event :finish-reason (case (:finish_reason choice)
                                           ("stop" nil) :stop
                                           "length" :length
                                           "tool_calls" :tool-calls
                                           "content_filter" :content-filter
                                           :unknown))

        :else nil))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-openai
  [profile status body]
  (errors/classify-error (Exception. "OpenAI API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenAIChatTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-openai profile request))

  (parse-response [this profile raw]
    (parse-response-openai profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-openai profile line))

  (parse-error [this profile status body]
    (parse-error-openai profile status body))

  (normalize-usage [this profile raw]
    (usage/normalize-usage (:profile/id profile) raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :cache}))

(defn make-transport []
  (->OpenAIChatTransport))

;; Register transport constructors on profiles
(require '[llm.sdk.provider :as provider])

(doseq [pid [:openai :openrouter :deepseek]]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (assoc p :profile/transport-constructor make-transport))))
