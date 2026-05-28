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

(declare parse-stream-event-codex)

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
              {:type "input_text" :text (str part)}))
          content)

    :else [{:type "input_text" :text (str content)}]))

(defn- message->responses-input [msg]
  (case (:message/role msg)
    :user
    {:role "user" :content (content->input-items (:message/content msg))}

    :assistant
    (let [items (concat
                 ;; Replay encrypted reasoning items from previous turns
                 (when-let [reasoning (:codex_reasoning_items (:message/provider-data msg))]
                   (mapv #(dissoc % :id) reasoning))
                 ;; Replay exact assistant message items from previous turns
                 (when-let [msg-items (:codex_message_items (:message/provider-data msg))]
                   msg-items)
                 ;; Current turn content
                 (when (seq (:message/content msg))
                   [{:type "message" :role "assistant"
                     :status "completed"
                     :content [{:type "output_text" :text (t/content->string (:message/content msg))}]}])
                 ;; Replay tool calls as function_call input items so the
                 ;; matching function_call_output (from a later :tool
                 ;; message) can be linked. Required by the Responses
                 ;; API on multi-turn conversations that lack
                 ;; previous_response_id continuity.
                 (when-let [tcs (seq (:message/tool-calls msg))]
                   (mapv (fn [tc]
                           {:type "function_call"
                            :call_id (:tool-call/id tc)
                            :name    (:tool-call/name tc)
                            :arguments (or (:tool-call/arguments tc) "")})
                         tcs)))]
      (if (seq items)
        items
        {:role "assistant" :content ""}))

    :tool
    {:type "function_call_output"
     :call_id (or (:message/tool-call-id msg) "call_0")
     :output (t/content->string (:message/content msg))}

    ;; fallback for system/developer roles (should be stripped before here)
    {:role "user" :content (t/content->string (:message/content msg))}))

(defn- messages->responses-input [messages]
  (mapcat (fn [msg]
            (let [converted (message->responses-input msg)]
              (if (sequential? converted) converted [converted])))
          messages))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- tool->codex [tool]
  (let [fn-data (:function tool)]
    {:type "function"
     :name (:name fn-data)
     :description (or (:description fn-data) "")
     :strict false
     :parameters (or (:parameters fn-data) {:type "object"})}))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- base-url-host [profile]
  (try
    (some-> (:profile/base-url profile) java.net.URI. .getHost str/lower-case)
    (catch Exception _ nil)))

(defn- codex-backend? [profile]
  (let [host (base-url-host profile)]
    (or (= host "chatgpt.com")
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
        instructions (when (and (seq messages) (= (:message/role (first messages)) :system))
                       (t/content->string (:message/content (first messages))))
        ;; Codex backend requires instructions; fall back to a default
        instructions (or instructions (when backend? "You are a helpful assistant."))
        payload-messages (if instructions (rest messages) messages)
        input (messages->responses-input payload-messages)
        tools (when (seq (:request/tools request))
                (mapv tool->codex (:request/tools request)))
        reasoning-config (:request/reasoning request)
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
                 :tool_choice "auto"
                 :parallel_tool_calls true})
              (when reasoning-enabled
                {:reasoning {:effort (name (get reasoning-config :effort :medium))
                             :summary "auto"}
                 :include ["reasoning.encrypted_content"]})
              ;; Codex backend does NOT support max_output_tokens
              (when (and (:request/max-tokens request) (not backend?))
                {:max_output_tokens (:request/max-tokens request)})
              ;; Codex backend requires stream=true
              (when backend?
                {:stream true})
              (when top-level-key
                {:prompt_cache_key top-level-key})
              (when xai-extra-body
                {:extra_body xai-extra-body})
              (when (:request/metadata request)
                {:metadata (:request/metadata request)}))
        ;; Auth headers
        base-headers (if backend?
                       (merge (require-codex-backend-auth-headers)
                              {"Accept" "text/event-stream"})
                       (provider/default-headers profile
                                                 (provider/resolve-auth-token profile)))
        headers (merge base-headers codex-backend-headers xai-headers)]
    {:method :post
     :url (str (:profile/base-url profile) "/responses")
     :headers headers
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- parse-output-item [item]
  (let [item-type (:type item)
        item-status (some-> (:status item) str str/lower-case str/trim)
        incomplete? (contains? #{"queued" "in_progress" "incomplete"} item-status)]
    (case item-type
      "message"
      (let [texts (keep #(when (= (:type %) "output_text") (:text %))
                        (:content item))]
        {:text (str/join "" texts)
         :message-item (cond-> (select-keys item [:id :role :status :phase :content])
                         (:text texts) (assoc :extracted_text (str/join "" texts)))})

      "reasoning"
      (let [encrypted (:encrypted_content item)]
        {:reasoning (when (and (string? encrypted) (seq encrypted)) encrypted)
         :reasoning-details (when (seq encrypted)
                              (cond-> {:type "reasoning" :encrypted_content encrypted}
                                (:id item) (assoc :id (:id item))
                                (:summary item) (assoc :summary (:summary item))))})

      "function_call"
      ;; Skip incomplete function_calls — they lack arguments
      (when-not incomplete?
        (let [fn-name (or (:name item) "")
              arguments (:arguments item "{}")
              arguments-str (if (string? arguments) arguments (json/generate-string arguments))
              raw-call-id (:call_id item)
              raw-item-id (:id item)
              call-id (if (and (string? raw-call-id) (seq (str/trim raw-call-id)))
                        (str/trim raw-call-id)
                        (deterministic-call-id fn-name arguments-str
                                               (count (filter #(= (:type %) "function_call")
                                                              [item]))))
              response-item-id (derive-responses-function-call-id call-id raw-item-id)]
          {:tool-call {:id call-id
                       :name fn-name
                       :arguments arguments-str}
           :response-item-id response-item-id}))

      nil)))

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
          ;; Convert each data map to a faux SSE line and parse it
          lines (map #(str "data: " (json/generate-string %)) data-maps)
          events (mapcat #(event->seq (parse-stream-event-codex profile %)) lines)
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
                    :content [{:type "output_text" :text (str/trim (:output_text raw))}]}]
                  items)
          parsed (keep parse-output-item items)
          text-parts (keep :text parsed)
          reasoning-parts (keep :reasoning parsed)
          reasoning-details (keep :reasoning-details parsed)
          message-items (keep :message-item parsed)
          tool-calls (vec (keep #(when-let [tc (:tool-call %)]
                                   {:part/type :tool-call
                                    :tool-call/id (:id tc)
                                    :tool-call/name (:name tc)
                                    :tool-call/arguments (:arguments tc)
                                    :tool-call/provider-data
                                    (cond-> {}
                                      (:response-item-id %) (assoc :response_item_id (:response-item-id %)))})
                                parsed))
          status (:status raw)
          finish-reason (if (seq tool-calls)
                          :tool-calls
                          (get status-map status :unknown))
          provider-data (cond-> {}
                          (seq reasoning-details) (assoc :codex_reasoning_items reasoning-details)
                          (seq message-items) (assoc :codex_message_items message-items))]
      (cond-> {:response/id (:id raw)
               :response/provider provider-id
               :response/model (:model raw)
               :response/parts (into []
                                      (concat
                                       (map #(hash-map :part/type :text :text %) text-parts)
                                       (map #(hash-map :part/type :reasoning :reasoning/text %) reasoning-parts)
                                       tool-calls))
               :response/finish-reason finish-reason
               :response/raw raw}
        (seq tool-calls) (assoc :response/tool-calls tool-calls)
        (:usage raw) (assoc :response/usage
                            (usage/normalize-usage :codex (:usage raw)))
        (seq provider-data) (assoc :response/provider-data provider-data))))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn parse-stream-event-codex
  [_profile line]
  (when-let [data (parse-sse-line line)]
    (let [t (:type data)]
      (cond
        ;; Text content delta
        (= t "response.output_text.delta")
        (stream/content-delta (get-in data [:delta]))

        ;; Reasoning delta (encrypted)
        (= t "response.reasoning.delta")
        (stream/reasoning-delta (get-in data [:delta]) :encrypted true)

        ;; Tool call: item added (start)
        (= t "response.output_item.added")
        (let [item (:item data)]
          (when (= (:type item) "function_call")
            (stream/tool-call-start (stream-index data)
                                    (or (:call_id item) (:id item)
                                        (str "tool_call_" (stream-index data)))
                                    (or (:name item) ""))))

        ;; Tool call: arguments delta
        (= t "response.function_call_arguments.delta")
        (stream/tool-call-delta (stream-index data) (:delta data))

        ;; Tool call: arguments done (end)
        (= t "response.function_call_arguments.done")
        (stream/tool-call-end (stream-index data))

        ;; Tool call: output item done (alternative start signal)
        (= t "response.output_item.done")
        (let [item (:item data)]
          (case (:type item)
            "function_call"
            (stream/tool-call-start (stream-index data)
                                    (or (:call_id item) (:id item)
                                        (str "tool_call_" (stream-index data)))
                                    (or (:name item) ""))
            nil))

        ;; Usage event
        (= t "response.usage")
        (when-let [u (:usage data)]
          (stream/usage-event (usage/normalize-usage :codex u)))

        ;; Error event
        (= t "response.error")
        (stream/error-event {:error/type :provider
                             :error/message (or (get-in data [:error :message])
                                                "Unknown provider error")
                             :error/raw data})

        ;; Completion events
        (= t "response.completed")
        (response-completion-events data :stop)

        (= t "response.incomplete")
        (response-completion-events data :incomplete)

        (= t "response.failed")
        (response-completion-events data :unknown)

        :else nil))))

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
