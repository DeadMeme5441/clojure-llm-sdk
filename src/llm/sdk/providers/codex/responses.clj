(ns llm.sdk.providers.codex.responses
  "OpenAI Responses API (Codex) transport adapter.
   Covers both the standard OpenAI Responses API (api.openai.com)
   and the Codex backend (chatgpt.com/backend-api/codex).
   
   For the Codex backend, auth is read from ~/.codex/auth.json
   (shared with the official OpenAI Codex CLI)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors])
  (:import [java.io File]
           [java.security MessageDigest]
           [java.util Base64]))

(declare ^:private parse-stream-data-codex)

;; ---------------------------------------------------------------------------
;; Finish reason mapping
;; ---------------------------------------------------------------------------

(def ^:private status-map
  {"completed" :stop
   "incomplete" :incomplete
   "failed" :unknown
   "cancelled" :unknown})

;; ---------------------------------------------------------------------------
;; ID helpers  (deterministic call_ids for prompt-cache stability)
;; ---------------------------------------------------------------------------

(defn- sha256-hex [s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes s "UTF-8")]
    (.update digest bytes)
    (let [hash (.digest digest)]
      (str/join (map #(format "%02x" (bit-and % 0xFF)) hash)))))

(defn- deterministic-call-id
  "Generate a deterministic call_id from tool call content.
   Used as a fallback when the API doesn't provide a call_id.
   Deterministic IDs prevent cache invalidation."
  [fn-name arguments index]
  (let [seed (str fn-name ":" arguments ":" index)
        digest (subs (sha256-hex seed) 0 12)]
    (str "call_" digest)))

(defn- derive-responses-function-call-id
  "Build a valid Responses function_call.id (must start with fc_)."
  [call-id response-item-id]
  (cond
    (and (string? response-item-id)
         (str/starts-with? response-item-id "fc_"))
    response-item-id

    (and (string? call-id)
         (str/starts-with? call-id "fc_"))
    call-id

    (and (string? call-id)
         (str/starts-with? call-id "call_")
         (> (count call-id) 5))
    (str "fc_" (subs call-id 5))

    (seq call-id)
    (let [sanitized (str/replace call-id #"[^A-Za-z0-9_-]" "")]
      (if (seq sanitized)
        (str "fc_" (subs sanitized 0 (min 48 (count sanitized))))
        (let [digest (subs (sha256-hex (or response-item-id "fallback")) 0 24)]
          (str "fc_" digest))))

    :else
    (let [digest (subs (sha256-hex (or response-item-id "fallback")) 0 24)]
      (str "fc_" digest))))

;; ---------------------------------------------------------------------------
;; Codex backend auth (~/.codex/auth.json)
;; ---------------------------------------------------------------------------

(defn- codex-auth-file-path []
  (let [codex-home (or (System/getenv "CODEX_HOME")
                       (str (System/getProperty "user.home") "/.codex"))]
    (str codex-home "/auth.json")))

(def ^:private codex-auth-cache
  "Memoized Codex CLI auth file parse. The cache is invalidated when
   auth.json path, mtime, or length changes. We still stat the file per
   backend request, but avoid reparsing and rereading stable credentials."
  (atom nil))

(defn- codex-auth-file-state [path]
  (try
    (let [f (File. path)]
      (when (.isFile f)
        {:path (.getAbsolutePath f)
         :modified-ms (.lastModified f)
         :length (.length f)}))
    (catch Exception _ nil)))

(defn- jwt-claims [token]
  (when (string? token)
    (try
      (let [parts (str/split token #"\.")
            payload-b64 (when (> (count parts) 1)
                          (let [p (nth parts 1)
                                pad (mod (- 4 (mod (count p) 4)) 4)]
                            (str p (apply str (repeat pad "=")))))
            payload (when payload-b64
                      (String. (.decode (Base64/getUrlDecoder) payload-b64) "UTF-8"))
            claims (when payload (json/parse-string payload false))]
        claims)
      (catch Exception _ nil))))

(defn- jwt-account-id [token]
  (let [claims (jwt-claims token)]
    (or (get-in claims ["https://api.openai.com/auth" "chatgpt_account_id"])
        (get claims "chatgpt_account_id"))))

(defn- jwt-fedramp? [token]
  (let [claims (jwt-claims token)]
    (true? (get-in claims ["https://api.openai.com/auth" "chatgpt_account_is_fedramp"]))))

(defn- non-blank-string [x]
  (when (and (string? x) (seq (str/trim x)))
    (str/trim x)))

(defn- parse-codex-auth-file [path]
  (when-let [data (try (json/parse-string (slurp path) true)
                       (catch Exception _ nil))]
    (when-let [tokens (:tokens data)]
      (let [access-token (non-blank-string (:access_token tokens))
            refresh-token (non-blank-string (:refresh_token tokens))
            id-token (:id_token tokens)]
        (when access-token
          {:access-token access-token
           :refresh-token refresh-token
           :account-id (or (non-blank-string (:account_id tokens))
                           (when (map? id-token) (non-blank-string (:chatgpt_account_id id-token)))
                           (jwt-account-id id-token)
                           (jwt-account-id access-token))
           :account-is-fedramp? (or (when (map? id-token)
                                      (true? (:chatgpt_account_is_fedramp id-token)))
                                    (jwt-fedramp? id-token)
                                    (jwt-fedramp? access-token))
           :auth-mode (:auth_mode data)})))))

(defn read-codex-auth
  "Read Codex OAuth tokens from ~/.codex/auth.json.
   Returns a map with :access-token, :refresh-token, :account-id, :auth-mode.
   Returns nil if the file doesn't exist or is invalid.

   The parsed file is cached and invalidated by path, modified time, and
   length so backend calls do not reread stable Codex CLI credentials on
   every request."
  []
  (let [path (codex-auth-file-path)
        state (codex-auth-file-state path)]
    (if-not state
      (do
        (reset! codex-auth-cache nil)
        nil)
      (let [cached @codex-auth-cache]
        (if (= state (:state cached))
          (:auth cached)
          (let [auth (parse-codex-auth-file path)]
            (reset! codex-auth-cache {:state state :auth auth})
            auth))))))

(defn codex-backend-auth-headers
  "Build headers for the chatgpt.com/backend-api/codex endpoint.
   Includes Cloudflare bypass headers required by the Codex backend."
  []
  (when-let [auth (read-codex-auth)]
    (cond-> {"Authorization" (str "Bearer " (:access-token auth))
             "User-Agent" "codex_cli_rs/0.0.0 (clojure-llm-sdk)"
             "originator" "codex_cli_rs"}
      (:account-id auth) (assoc "ChatGPT-Account-ID" (:account-id auth))
      (:account-is-fedramp? auth) (assoc "X-OpenAI-Fedramp" "true"))))

(defn- require-codex-backend-auth-headers []
  (or (codex-backend-auth-headers)
      (throw (ex-info "Codex backend OAuth credentials are unavailable"
                      {:error/type :auth/missing-codex-backend-token
                       :provider :codex-backend
                       :auth/file (codex-auth-file-path)}))))

(defn codex-backend-available?
  "Return true if valid Codex backend credentials are available."
  []
  (boolean (read-codex-auth)))

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- unsupported-content-part! [part]
  (throw (ex-info "OpenAI Responses does not support this content part"
                  {:error/type :provider/unsupported-content-part
                   :provider :codex
                   :part/type (:part/type part)})))

(defn- content->input-items [content]
  (cond
    (string? content)
    [{:type "input_text" :text content}]

    (sequential? content)
    (mapv (fn [part]
            (case (:part/type part)
              :text {:type "input_text" :text (:text part)}
              :image {:type "input_image" :image_url (:image/url part)}
              :file (let [file-data (t/file-data-uri-for-input-file part)
                          file-id (:file/id part)
                          file-url (:file/url part)]
                      (when-not (or file-data file-id file-url)
                        (t/missing-file-source! :codex part))
                      (cond-> {:type "input_file"}
                        file-data (assoc :file_data file-data)
                        file-id (assoc :file_id file-id)
                        file-url (assoc :file_url file-url)
                        (:file/name part) (assoc :filename (:file/name part))))
              (unsupported-content-part! part)))
          content)

    :else
    (throw (ex-info "Unsupported message content for OpenAI Responses"
                    {:error/type :provider/unsupported-content
                     :provider :codex
                     :content content}))))

(defn- provider-items [provider-data item-key]
  (or (get provider-data item-key)
      (some #(when (map? %) (get % item-key))
            (vals provider-data))))

(defn- indexed-provider-items [provider-data item-key]
  (let [stored (provider-items provider-data item-key)]
    (cond
      (map? stored) (->> stored
                         (sort-by (fn [[idx]]
                                    (if (number? idx)
                                      [0 idx]
                                      [1 (str idx)])))
                         (mapv (fn [[idx item]] [idx item])))
      (sequential? stored) (mapv vector (range) stored)
      :else [])))

(defn- tool-call-key [tc]
  [(:tool-call/id tc) (:tool-call/name tc) (:tool-call/arguments tc)])

(defn- message-tool-calls [msg]
  (let [calls (concat (or (t/extract-tool-calls-from-parts
                           (:message/content msg))
                          [])
                      (or (:message/tool-calls msg) []))]
    (:calls
     (reduce
      (fn [{:keys [positions] :as acc} tc]
        (let [k (tool-call-key tc)]
          (if-let [pos (get positions k)]
            (update-in acc [:calls pos :tool-call/provider-data]
                       merge (:tool-call/provider-data tc))
            (-> acc
                (update :calls conj tc)
                (assoc-in [:positions k] (count (:calls acc)))))))
      {:calls [] :positions {}}
      calls))))

(defn- response-call-type [tc]
  (let [provider-data (:tool-call/provider-data tc)
        wire-type (or (:wire_type provider-data)
                      (get-in provider-data [:response_item :type]))]
    (if (contains? #{"custom" "custom_tool_call"} wire-type)
      "custom_tool_call"
      "function_call")))

(defn- tool-call->response-item [tc]
  (let [item-type (response-call-type tc)
        provider-data (:tool-call/provider-data tc)
        native-item (:response_item provider-data)
        argument-key (if (= "custom_tool_call" item-type) :input :arguments)]
    (merge native-item
           {:type item-type
            :call_id (:tool-call/id tc)
            :name (:tool-call/name tc)
            argument-key (or (:tool-call/arguments tc) "")})))

(defn- replayable-message-items [msg]
  (let [phase (or (:message/phase msg)
                  (get-in msg [:message/provider-data :phase]))
        entries (indexed-provider-items (:message/provider-data msg)
                                        :codex_message_items)
        content (:message/content msg)
        content-text (when (seq content) (t/content->string content))
        replay-text (->> entries
                         (map second)
                         (mapcat :content)
                         (keep #(when (= "output_text" (:type %)) (:text %)))
                         (apply str))
        replay? (and (seq entries)
                     (or (nil? content-text) (= content-text replay-text))
                     (or (nil? phase)
                         (every? #(= (if (keyword? phase)
                                       (str/replace (name phase) "-" "_")
                                       phase)
                                     (:phase (second %)))
                                 entries)))]
    (if replay?
      entries
      (when (seq content-text)
        [[(ffirst entries)
          (cond-> {:type "message" :role "assistant"
                   :status "completed"
                   :content [{:type "output_text" :text content-text}]}
            phase
            (assoc :phase (if (keyword? phase)
                            (str/replace (name phase) "-" "_")
                            phase)))]]))))

(defn- ordered-response-items [entries]
  (let [entries (map-indexed (fn [ordinal [idx item]]
                               {:index idx :ordinal ordinal :item item})
                             entries)]
    (if (every? #(number? (:index %)) entries)
      (mapv :item (sort-by (juxt :index :ordinal) entries))
      (mapv :item entries))))

(defn- validate-assistant-content! [content reasoning-entries]
  (when (sequential? content)
    (doseq [part content]
      (case (:part/type part)
        :text nil
        :tool-call nil
        :reasoning
        (when-not (seq reasoning-entries)
          (throw (ex-info
                  "OpenAI Responses reasoning replay requires provider metadata"
                  {:error/type :provider/missing-replay-state
                   :provider :codex
                   :part/type :reasoning})))
        (unsupported-content-part! part)))))

(defn- assistant->responses-input [msg]
  (let [reasoning-entries (indexed-provider-items
                           (:message/provider-data msg)
                           :codex_reasoning_items)
        _ (validate-assistant-content! (:message/content msg)
                                       reasoning-entries)
        message-entries (or (replayable-message-items msg) [])
        calls (message-tool-calls msg)
        call-entries (mapv (fn [tc]
                             [(get-in tc [:tool-call/provider-data :output_index])
                              (tool-call->response-item tc)])
                           calls)
        items (ordered-response-items
               (concat reasoning-entries message-entries call-entries))]
    (if (seq items)
      items
      {:role "assistant" :content ""})))

(defn- tool-output [content]
  (cond
    (string? content) content
    (sequential? content) (content->input-items content)
    (nil? content) ""
    :else (throw (ex-info "Unsupported tool output for OpenAI Responses"
                          {:provider :codex :content content}))))

(defn- tool-result->responses-input [msg calls-by-id]
  (let [call-id (:message/tool-call-id msg)
        prior-call (get calls-by-id call-id)
        item-type (if (= "custom_tool_call" (some-> prior-call response-call-type))
                    "custom_tool_call_output"
                    "function_call_output")]
    (when-not (and (string? call-id) (seq (str/trim call-id)))
      (throw (ex-info "OpenAI Responses tool results require a tool call ID"
                      {:provider :codex :message/role :tool})))
    {:type item-type
     :call_id call-id
     :output (tool-output (:message/content msg))}))

(defn- message->responses-input [msg calls-by-id]
  (case (:message/role msg)
    :user
    {:role "user" :content (content->input-items (:message/content msg))}

    :assistant
    (assistant->responses-input msg)

    :tool
    (tool-result->responses-input msg calls-by-id)

    :system
    {:role "system" :content (content->input-items (:message/content msg))}

    :developer
    {:role "developer" :content (content->input-items (:message/content msg))}

    {:role "user" :content (content->input-items (:message/content msg))}))

(defn- messages->responses-input [messages]
  (:items
   (reduce
    (fn [{:keys [calls-by-id] :as acc} msg]
      (let [converted (message->responses-input msg calls-by-id)
            items (if (sequential? converted) converted [converted])
            calls (when (= :assistant (:message/role msg))
                    (message-tool-calls msg))]
        (cond-> (update acc :items into items)
          (seq calls)
          (update :calls-by-id into
                  (keep (fn [tc]
                          (when-let [id (:tool-call/id tc)]
                            [id tc])))
                  calls))))
    {:items [] :calls-by-id {}}
    messages)))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- custom-tool-format->codex [format]
  (case (:type format)
    :grammar (let [{:keys [definition syntax]} (:grammar format)]
               {:type "grammar"
                :definition definition
                :syntax (name syntax)})
    :text {:type "text"}
    nil))

(defn- tool->codex [tool]
  (case (:type tool)
    :custom
    (let [{:keys [name description format]} (:custom tool)]
      (cond-> {:type "custom" :name name}
        description (assoc :description description)
        format (assoc :format (custom-tool-format->codex format))))

    :function
    (let [fn-data (:function tool)]
      (cond-> {:type "function"
               :name (:name fn-data)
               :description (or (:description fn-data) "")
               :parameters (or (:parameters fn-data) {:type "object"})}
        (contains? fn-data :strict) (assoc :strict (:strict fn-data))))

    (throw (ex-info "Unsupported OpenAI Responses tool type"
                    {:provider :codex :tool/type (:type tool)}))))

(defn- tool-choice->codex [choice]
  (case choice
    :auto "auto"
    :none "none"
    :required "required"
    (when (map? choice)
      (case (:type choice)
        :custom {:type "custom"
                 :name (get-in choice [:custom :name])}
        :function {:type "function"
                   :name (get-in choice [:function :name])}
        nil))))

(defn- response-format->codex [fmt]
  {:format
   (case (:type fmt)
     :json_schema
     (cond-> {:type "json_schema"
              :name (or (:name fmt) "response")
              :schema (:json-schema fmt)}
       (:description fmt) (assoc :description (:description fmt))
       (contains? fmt :strict) (assoc :strict (:strict fmt)))

     :json_object {:type "json_object"}
     {:type "text"})})

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- base-url-host [profile]
  (try
    (some-> (:profile/base-url profile) java.net.URI. .getHost str/lower-case)
    (catch Exception _ nil)))

(defn- codex-backend? [profile]
  (let [host (base-url-host profile)]
    (or (= :codex-backend (:profile/id profile))
        (= host "chatgpt.com")
        (str/ends-with? (or host "") ".chatgpt.com"))))

(defn- xai-host? [profile]
  (= "api.x.ai" (base-url-host profile)))

(defn- github-copilot? [profile]
  (let [host (base-url-host profile)
        path (try
               (some-> (:profile/base-url profile) java.net.URI. .getPath str/lower-case)
               (catch Exception _ ""))]
    (or (str/includes? (or host "") "githubcopilot")
        (and (= host "api.github.com")
             (str/includes? (or path "") "/copilot")))))

(defn build-request-codex
  [profile request]
  (let [model (:request/model request)
        messages (:request/messages request)
        backend? (codex-backend? profile)
        xai? (xai-host? profile)
        github? (github-copilot? profile)
        system-message? (and (seq messages)
                             (= (:message/role (first messages)) :system))
        instructions (when system-message?
                       (t/content->string (:message/content (first messages))))
        ;; Codex backend requires instructions; fall back to a default.
        instructions (or instructions (when backend? "You are a helpful assistant."))
        payload-messages (if system-message? (rest messages) messages)
        input (messages->responses-input payload-messages)
        tools (when (seq (:request/tools request))
                (mapv tool->codex (:request/tools request)))
        reasoning-config (:request/reasoning request)
        provider-extra-body (get-in request [:request/provider-options :extra_body])
        reasoning-enabled (if (nil? reasoning-config) false (:enabled reasoning-config true))
        ;; Caching wiring:
        ;;   * api.openai.com/v1/responses → top-level prompt_cache_key
        ;;   * api.x.ai/v1/responses        → extra_body.prompt_cache_key
        ;;                                    + x-grok-conv-id header
        ;;   * chatgpt.com Codex backend    → top-level prompt_cache_key
        ;;                                    + session_id / x-client-request-id
        ;;                                    extra_headers
        ;;   * GitHub Copilot Responses     → suppressed (opt-out)
        cache-on? (cache/cache-enabled? request)
        scope-id (when cache-on? (cache/scope-id request))
        prompt-cache-key (when (and scope-id (not github?)) scope-id)
        top-level-key (when (and prompt-cache-key (not xai?)) prompt-cache-key)
        xai-extra-body (when (and prompt-cache-key xai?)
                         {:prompt_cache_key prompt-cache-key})
        codex-backend-headers (when (and prompt-cache-key backend?)
                                {"session_id" prompt-cache-key
                                 "x-client-request-id" prompt-cache-key})
        xai-headers (when (and prompt-cache-key xai?)
                      {"x-grok-conv-id" prompt-cache-key})
        body (merge
              {:model model
               :input input
               :store false}
              (when instructions
                {:instructions instructions})
              (when tools
                {:tools tools
                 :tool_choice (or (tool-choice->codex (:request/tool-choice request))
                                  "auto")
                 :parallel_tool_calls true})
              (when reasoning-enabled
                {:reasoning {:effort (name (get reasoning-config :effort :medium))
                             :summary "auto"}
                 :include ["reasoning.encrypted_content"]})
              ;; Codex backend does NOT support max_output_tokens
              (when (and (:request/max-tokens request) (not backend?))
                {:max_output_tokens (:request/max-tokens request)})
              (when (:request/temperature request)
                {:temperature (:request/temperature request)})
              (when (:request/top-p request)
                {:top_p (:request/top-p request)})
              (when (:request/response-format request)
                {:text (response-format->codex (:request/response-format request))})
              ;; Codex backend always streams; standard Responses streams on request.
              (when (or backend? (:request/stream? request))
                {:stream true})
              (when top-level-key
                {:prompt_cache_key top-level-key})
              (when xai-extra-body
                {:extra_body xai-extra-body})
              (when (:request/metadata request)
                {:metadata (:request/metadata request)})
              provider-extra-body)
        ;; Auth headers
        base-headers (if backend?
                       (merge (require-codex-backend-auth-headers)
                              {"Accept" "text/event-stream"})
                       (provider/default-headers profile
                                                 (provider/resolve-auth-token profile)))
        headers (merge base-headers codex-backend-headers xai-headers
                       (:profile/default-headers profile))]
    {:method :post
     :url (str (:profile/base-url profile) "/responses")
     :headers headers
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- parse-output-item [output-index item]
  (let [item-type (:type item)
        item-status (some-> (:status item) str str/lower-case str/trim)
        incomplete? (contains? #{"queued" "in_progress" "incomplete"} item-status)]
    (case item-type
      "message"
      (let [texts (keep #(when (= (:type %) "output_text") (:text %))
                        (:content item))]
        {:output-index output-index
         :text (str/join "" texts)
         :message-item (select-keys item [:id :type :role :status :phase :content])})

      "reasoning"
      (let [encrypted (:encrypted_content item)
            summary-text (->> (:summary item)
                              (keep #(when (= "summary_text" (:type %)) (:text %)))
                              (str/join ""))]
        {:output-index output-index
         :reasoning (cond
                      (seq summary-text) summary-text
                      (and (string? encrypted) (seq encrypted)) encrypted)
         :reasoning-details (select-keys
                             item
                             [:id :type :encrypted_content :summary :content :status])})

      "function_call"
      (when-not incomplete?
        (let [fn-name (or (:name item) "")
              arguments (:arguments item "{}")
              arguments-str (if (string? arguments)
                              arguments
                              (json/generate-string arguments))
              raw-call-id (:call_id item)
              raw-item-id (:id item)
              call-id (if (and (string? raw-call-id)
                               (seq (str/trim raw-call-id)))
                        (str/trim raw-call-id)
                        (deterministic-call-id fn-name arguments-str output-index))
              response-item-id (derive-responses-function-call-id
                                call-id raw-item-id)]
          {:output-index output-index
           :raw-item item
           :tool-call {:id call-id
                       :name fn-name
                       :arguments arguments-str}
           :response-item-id response-item-id
           :wire-type "function_call"}))

      "custom_tool_call"
      (when-not incomplete?
        (let [tool-input (or (:input item) "")
              call-id (or (:call_id item) (:id item)
                          (deterministic-call-id (:name item)
                                                 tool-input
                                                 output-index))]
          {:output-index output-index
           :raw-item item
           :tool-call {:id call-id
                       :name (or (:name item) "")
                       :arguments tool-input}
           :response-item-id (:id item)
           :wire-type "custom_tool_call"}))

      nil)))

(defn- call-provider-data
  [item output-index response-item-id wire-type]
  (cond-> {:wire_type wire-type
           :output_index output-index
           :response_item (select-keys
                           item
                           [:type :id :call_id :name :async :caller :namespace])}
    response-item-id (assoc :response_item_id response-item-id)))
(defn- parsed-tool-call [parsed]
  (when-let [tc (:tool-call parsed)]
    {:part/type :tool-call
     :tool-call/id (:id tc)
     :tool-call/name (:name tc)
     :tool-call/arguments (:arguments tc)
     :tool-call/provider-data
     (call-provider-data (:raw-item parsed)
                         (:output-index parsed)
                         (:response-item-id parsed)
                         (:wire-type parsed))}))

(defn- parsed-parts [parsed]
  (into []
        (keep identity)
        [(when (contains? parsed :text)
           {:part/type :text :text (:text parsed)})
         (when-let [reasoning (:reasoning parsed)]
           {:part/type :reasoning :reasoning/text reasoning})
         (parsed-tool-call parsed)]))

;; SSE helpers (must be defined before parse-response-codex)

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- parse-sse-text
  "Parse a multi-line SSE response body into a sequence of parsed data maps.
   Handles both 'data: {...}' lines and 'event: xxx\ndata: {...}' pairs."
  [sse-text]
  (->> (str/split-lines sse-text)
       (keep parse-sse-line)))

(defn- stream-index [data]
  (or (:output_index data)
      (:item_index data)
      (:index data)
      (get-in data [:item :index])
      0))

(defn- event->seq [ev]
  (cond
    (nil? ev) []
    (sequential? ev) ev
    :else [ev]))

(defn- maybe-many [events]
  (let [events (vec (remove nil? events))]
    (case (count events)
      0 nil
      1 (first events)
      events)))

(defn- response-completion-events [data finish-reason]
  (let [response (:response data)]
    (maybe-many
     [(when-let [u (:usage response)]
        (stream/usage-event (usage/normalize-usage :codex u)))
      (stream/end-event :finish-reason finish-reason)])))

(defn parse-response-codex
  [profile raw]
  ;; Handle SSE text responses (Codex backend returns SSE even for non-streaming)
  (let [provider-id (or (:profile/id profile) :codex)]
    (if (string? raw)
    (let [data-maps (parse-sse-text raw)
          events (mapcat #(event->seq (parse-stream-data-codex profile %)) data-maps)
          ;; Only add a fallback end-event if the SSE didn't already include one
          has-end? (some #(= (:event/type %) :stream/end) events)
          events (concat [(stream/start-event)] events (when-not has-end? [(stream/end-event)]))
          model (some #(get-in % [:response :model]) data-maps)
          resp-id (some #(get-in % [:response :id]) data-maps)]
      (-> (stream/reduce-events events)
          (stream/acc->response provider-id model)
          (assoc :response/id resp-id)
          (assoc :response/raw raw)))
    ;; Standard JSON response
    (let [items (:output raw)
          ;; Fallback: if output is empty but output_text exists, synthesize
          items (if (and (or (nil? items) (empty? items))
                         (string? (:output_text raw))
                         (seq (str/trim (:output_text raw))))
                  [{:type "message" :role "assistant" :status "completed"
                    :content [{:type "output_text"
                               :text (str/trim (:output_text raw))}]}]
                  items)
          parsed (into []
                       (keep identity)
                       (map-indexed parse-output-item items))
          reasoning-details
          (into {}
                (keep (fn [{:keys [output-index reasoning-details]}]
                        (when reasoning-details
                          [output-index reasoning-details])))
                parsed)
          message-items
          (into {}
                (keep (fn [{:keys [output-index message-item]}]
                        (when message-item
                          [output-index message-item])))
                parsed)
          tool-calls (into [] (keep parsed-tool-call) parsed)
          parts (into [] (mapcat parsed-parts) parsed)
          status (some-> (:status raw) name str/lower-case)
          finish-reason (if (and (= "completed" status) (seq tool-calls))
                          :tool-calls
                          (get status-map status :unknown))
          provider-data (cond-> {}
                          (seq reasoning-details)
                          (assoc :codex_reasoning_items reasoning-details)
                          (seq message-items)
                          (assoc :codex_message_items message-items)
                          (:error raw) (assoc :error (:error raw))
                          (:incomplete_details raw)
                          (assoc :incomplete_details (:incomplete_details raw))
                          (:moderation raw) (assoc :moderation (:moderation raw))
                          (:service_tier raw)
                          (assoc :service_tier (:service_tier raw))
                          (:conversation raw) (assoc :conversation (:conversation raw)))
          response (cond-> {:response/id (:id raw)
                            :response/provider provider-id
                            :response/model (:model raw)
                            :response/parts parts
                            :response/finish-reason finish-reason
                            :response/raw raw}
                     (seq tool-calls)
                     (assoc :response/tool-calls tool-calls)
                     (:usage raw)
                     (assoc :response/usage
                            (usage/normalize-usage :codex (:usage raw)))
                     (seq provider-data)
                     (assoc :response/provider-data provider-data))]
      (if (contains? #{"failed" "cancelled"} status)
        (let [error (or (:error raw)
                        {:message (str "Response generation " status)
                         :status status})]
          (try
            (stream/events->response
             [(stream/error-event
               {:error/type :provider
                :error/message (or (:message error)
                                   "Response generation failed")
                :error/raw raw})]
             provider-id
             (:model raw))
            (catch clojure.lang.ExceptionInfo e
              (throw (ex-info (.getMessage e)
                              (assoc (ex-data e)
                                     :partial-response response)
                              e)))))
        response)))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-stream-data-codex
  [profile data]
  (when data
    (let [t (:type data)
          provider-id (or (:profile/id profile) :codex)]
      (cond
        (= t "response.output_text.delta")
        (stream/content-delta (:delta data))

        ;; Public Responses events carry visible reasoning text and summaries.
        (contains? #{"response.reasoning_summary_text.delta"
                     "response.reasoning_text.delta"} t)
        (stream/reasoning-delta (:delta data))

        ;; The ChatGPT Codex backend also emits encrypted reasoning deltas.
        (= t "response.reasoning.delta")
        (stream/reasoning-delta (:delta data) :encrypted true)

        (and (= t "response.output_item.done")
             (contains? #{"reasoning" "message"} (get-in data [:item :type])))
        (stream/provider-state-event
         provider-id
         {:responses/event data
          (if (= "reasoning" (get-in data [:item :type]))
            :codex_reasoning_items
            :codex_message_items)
          {(stream-index data) (:item data)}})

        (= t "response.output_item.added")
        (let [item (:item data)
              idx (stream-index data)
              item-type (:type item)]
          (when (contains? #{"function_call" "custom_tool_call"} item-type)
            (let [call-id (or (:call_id item) (:id item)
                              (str "tool_call_" idx))
                  response-item-id
                  (if (= "function_call" item-type)
                    (derive-responses-function-call-id call-id (:id item))
                    (:id item))]
              (stream/tool-call-start
               idx
               call-id
               (or (:name item) "")
               :provider-data
               (call-provider-data item idx response-item-id item-type)))))

        (= t "response.function_call_arguments.delta")
        (stream/tool-call-delta (stream-index data) (:delta data))

        (= t "response.function_call_arguments.done")
        (stream/tool-call-end (stream-index data))

        (= t "response.custom_tool_call_input.delta")
        (stream/tool-call-delta (stream-index data) (:delta data))

        (= t "response.custom_tool_call_input.done")
        (stream/tool-call-end (stream-index data))

        (= t "response.usage")
        (when-let [u (:usage data)]
          (stream/usage-event (usage/normalize-usage :codex u)))

        (contains? #{"error" "response.error"} t)
        (stream/error-event {:error/type :provider
                             :error/message (or (get-in data [:error :message])
                                                (:message data)
                                                "Unknown provider error")
                             :error/raw data})

        (= t "response.completed")
        (response-completion-events data :stop)

        (= t "response.incomplete")
        (response-completion-events data :incomplete)

        (= t "response.failed")
        (let [error (or (get-in data [:response :error])
                        (:error data)
                        {:message "Response generation failed"})]
          (maybe-many
           (concat
            [(stream/error-event
              {:error/type :provider
               :error/message (or (:message error)
                                  "Response generation failed")
               :error/raw error})]
            (event->seq (response-completion-events data :unknown)))))

        ;; Preserve current lifecycle, content-part, annotation, refusal, and
        ;; tool events that have no canonical stream equivalent.
        (and (string? t) (str/starts-with? t "response."))
        (stream/provider-state-event provider-id
                                     {:responses/event data})

        :else nil))))

(defn parse-stream-event-codex
  [profile line]
  (parse-stream-data-codex profile (parse-sse-line line)))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-codex
  [profile status body]
  (errors/classify-api-error (or (:profile/id profile) :codex)
                             "Codex"
                             status
                             body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord CodexTransport []
  t/Transport
  (build-request [_this profile request]
    (build-request-codex profile request))

  (parse-response [_this profile raw]
    (parse-response-codex profile raw))

  (parse-stream-event [_this profile line]
    (parse-stream-event-codex profile line))

  (parse-error [_this profile status body]
    (parse-error-codex profile status body))

  (normalize-usage [_this _profile raw]
    (usage/normalize-usage :codex raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :reasoning :encrypted-reasoning :file-attachments}))

(defn make-transport []
  (->CodexTransport))

;; Register
(provider/register-provider
 {:profile/id :codex
  :profile/protocol-family :codex
  :profile/base-url "https://api.openai.com/v1"
  :profile/auth-strategy :bearer
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :reasoning :encrypted-reasoning :file-attachments}
  :profile/env-var-names ["OPENAI_API_KEY"]
  :profile/transport-constructor make-transport})

(provider/register-provider
 {:profile/id :codex-backend
  :profile/protocol-family :codex
  :profile/base-url "https://chatgpt.com/backend-api/codex"
  :profile/auth-strategy :oauth-external
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :reasoning :encrypted-reasoning :file-attachments}
  :profile/env-var-names []
  :profile/transport-constructor make-transport})
