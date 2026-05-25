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

(defn- assistant-tool-calls [msg]
  (when-let [tcs (seq (:message/tool-calls msg))]
    (mapv (fn [tc]
            {:id        (:tool-call/id tc)
             :type      "function"
             :function  {:name      (:tool-call/name tc)
                         :arguments (or (:tool-call/arguments tc) "")}})
          tcs)))

(defn- message->openai [msg]
  (let [role (name (:message/role msg))
        content (:message/content msg)
        tool-calls (assistant-tool-calls msg)
        ;; Tool result messages must carry tool_call_id (per OpenAI/
        ;; DeepSeek/Kimi/etc. chat-completions schema). The canonical
        ;; Message carries it on :message/tool-call-id; surface it here.
        tool-call-id (:message/tool-call-id msg)
        text-content (cond
                       (string? content) content
                       (sequential? content)
                       (mapv (fn [part]
                               (case (:part/type part)
                                 :text  {:type "text" :text (:text part)}
                                 :image {:type "image_url"
                                         :image_url {:url (:image/url part)
                                                     :detail (name (get part :image/detail :auto))}}
                                 ;; tool-call parts are surfaced via :tool_calls below,
                                 ;; not as a content fragment.
                                 :tool-call nil
                                 {:type "text" :text (str part)}))
                             content)
                       :else (str content))
        text-content (if (sequential? text-content)
                       (vec (remove nil? text-content))
                       text-content)]
    (cond-> {:role role}
      (some? text-content)            (assoc :content text-content)
      (seq tool-calls)                (assoc :tool_calls tool-calls)
      tool-call-id                    (assoc :tool_call_id tool-call-id))))

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

(defn- apply-drops
  "Honour the :drops quirk by removing the named keys from the top-level
   body and from :extra_body. Lets alias profiles strip request fields
   the upstream provider 400s on (e.g. Mistral rejects
   frequency_penalty / presence_penalty)."
  [body drops]
  (if (seq drops)
    (let [drop-set (set drops)
          body' (apply dissoc body drop-set)]
      (if-let [extra (:extra_body body')]
        (let [extra' (apply dissoc extra drop-set)]
          (if (seq extra')
            (assoc body' :extra_body extra')
            (dissoc body' :extra_body)))
        body'))
    body))

(defn- complete-url
  "Build the chat-completions URL. Honours a profile-level
   :profile/url-builder fn (used by Azure deployment routing and the
   HuggingFace router) when present; otherwise falls back to the
   simple {base-url}/chat/completions form."
  [profile request]
  (if-let [builder (:profile/url-builder profile)]
    (builder profile request "/chat/completions")
    (str (:profile/base-url profile) "/chat/completions")))

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
                 (response-format->openai (:request/response-format request))})
              (when (seq extra-body)
                {:extra_body extra-body}))
        body (apply-drops body (get-in profile [:profile/quirks :drops]))]
    {:method :post
     :url (complete-url profile request)
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
    #{:chat :streaming :tools :json-schema :reasoning :cache}))

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
     :default-headers          optional map
     :capabilities             defaults #{:chat :streaming :tools}
     :quirks                   optional map:
                                 :drops #{:k1 :k2}   strip body keys
                                 :thinking-explicit  send :thinking dict
     :supports-model-listing?  defaults true
     :supported-params         optional set (carried as
                                 :profile/supported-params; T2-12
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
    (:supported-params spec)
    (assoc :profile/supported-params (:supported-params spec))))

(defn register-alias!
  "Register an OpenAI-compat alias profile in one call."
  [spec]
  (provider/register-provider (build-alias-profile spec)))

;; ---------------------------------------------------------------------------
;; Attach the OpenAI-chat transport constructor to every built-in
;; OpenAI-compat profile registered by llm.sdk.provider.
;;
;; Listing the ids explicitly (rather than scanning by protocol-family)
;; matches the project's other adapter files — and means new entries
;; in provider.clj need a one-line addition here too, which is the
;; same place reviewers will look. The kimi-specific latent bug
;; (profile but no constructor) is fixed by including it in the list.
;; ---------------------------------------------------------------------------

(def ^:private compat-provider-ids
  [:openai :openrouter :deepseek :kimi :kimi-code
   :mistral :groq :cerebras :together :xai :huggingface
   ;; T2-19 aggregator aliases — all share the OpenAI chat wire shape.
   :sambanova :deepinfra :lambda :nebius :hyperbolic :novita
   :friendliai :featherless :cloudflare :dashscope :volcengine])

(doseq [pid compat-provider-ids]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (assoc p :profile/transport-constructor make-transport))))

;; ---------------------------------------------------------------------------
;; Azure OpenAI deployment routing (T2-05)
;;
;; Azure differs from openai.com on URL shape and (optionally) auth
;; header — body is identical. The URL builder pattern keeps the chat
;; adapter unchanged for vanilla OpenAI while letting deployment-
;; routed and HuggingFace-router profiles plug in.
;; ---------------------------------------------------------------------------

(defn azure-url-builder
  "URL builder for Azure OpenAI deployments. Composes
     {base-url}/openai/deployments/{deployment}{path}?api-version=...
   Reads :azure/deployment and :azure/api-version off the profile."
  [profile _request path]
  (str (:profile/base-url profile)
       "/openai/deployments/"
       (:azure/deployment profile)
       path
       "?api-version="
       (:azure/api-version profile)))

(defn register-azure-deployment!
  "Register an Azure OpenAI deployment as a provider profile.

   Required:
     :id           keyword id for the profile (e.g. :azure-gpt4o-prod)
     :endpoint     base URL, e.g. \"https://my-rg.openai.azure.com\"
                   (no trailing slash, no /openai/deployments/...)
     :deployment   the deployment name configured in the Azure portal
     :api-version  e.g. \"2024-08-01-preview\"

   Optional:
     :env-var-names      vector of env-var names to read the API key from
                         (defaults to [\"AZURE_OPENAI_API_KEY\"])
     :auth-strategy      :api-key-header (default) or :bearer (AAD)
     :auth-header-name   defaults to \"api-key\" (only used with
                         :api-key-header). Set to \"Authorization\" or
                         pass :auth-strategy :bearer for AAD bearer.
     :capabilities       defaults #{:chat :streaming :tools
                                    :json-schema :reasoning}
     :quirks             optional quirks map passed through verbatim
     :default-headers    optional map merged into outgoing headers"
  [{:keys [id endpoint deployment api-version
           env-var-names auth-strategy auth-header-name
           capabilities quirks default-headers]
    :or {auth-strategy :api-key-header
         auth-header-name "api-key"
         capabilities #{:chat :streaming :tools :json-schema :reasoning}
         quirks {}
         default-headers {}}}]
  (when-not (and id endpoint deployment api-version)
    (throw (ex-info "register-azure-deployment! needs :id :endpoint :deployment :api-version"
                    {:id id :endpoint endpoint
                     :deployment deployment :api-version api-version})))
  (provider/register-provider
   (cond-> {:profile/id id
            :profile/protocol-family :openai-chat
            :profile/base-url endpoint
            :profile/auth-strategy auth-strategy
            :profile/env-var-names (vec (or env-var-names ["AZURE_OPENAI_API_KEY"]))
            :profile/default-headers default-headers
            :profile/capabilities capabilities
            :profile/quirks quirks
            ;; Azure /models is per-deployment and not useful as a
            ;; catalog source — turn it off.
            :profile/supports-model-listing false
            :profile/transport-constructor make-transport
            :profile/url-builder azure-url-builder
            :azure/endpoint endpoint
            :azure/deployment deployment
            :azure/api-version api-version}
     (= auth-strategy :api-key-header)
     (assoc :profile/auth-header-name auth-header-name))))
