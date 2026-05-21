(ns llm.sdk.providers.codex
  "OpenAI Responses API (Codex) transport adapter.
   Covers both the standard OpenAI Responses API (api.openai.com)
   and the Codex backend (chatgpt.com/backend-api/codex).
   
   For the Codex backend, auth is read from ~/.codex/auth.json
   (shared with the official OpenAI Codex CLI)."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors])
  (:import [java.security MessageDigest]
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

(defn read-codex-auth
  "Read Codex OAuth tokens from ~/.codex/auth.json.
   Returns a map with :access-token, :refresh-token, :account-id, :auth-mode.
   Returns nil if the file doesn't exist or is invalid."
  []
  (let [path (codex-auth-file-path)]
    (when-let [data (try (json/parse-string (slurp path) true)
                         (catch Exception _ nil))]
      (when-let [tokens (:tokens data)]
        (let [access-token (:access_token tokens)
              ;; Extract ChatGPT-Account-ID from JWT payload
              account-id (when (string? access-token)
                           (try
                             (let [parts (str/split access-token #"\.")
                                   payload-b64 (when (> (count parts) 1)
                                                 (let [p (nth parts 1)]
                                                   (str p (apply str (repeat (- 4 (mod (count p) 4)) "=")))))
                                   payload (when payload-b64
                                             (String. (.decode (Base64/getUrlDecoder) payload-b64) "UTF-8"))
                                   claims (when payload (json/parse-string payload true))]
                               (get-in claims ["https://api.openai.com/auth" "chatgpt_account_id"]))
                             (catch Exception _ nil)))]
          {:access-token access-token
           :refresh-token (:refresh_token tokens)
           :account-id account-id
           :auth-mode (:auth_mode data)})))))

(defn codex-backend-auth-headers
  "Build headers for the chatgpt.com/backend-api/codex endpoint.
   Includes Cloudflare bypass headers required by the Codex backend."
  []
  (when-let [auth (read-codex-auth)]
    (cond-> {"Authorization" (str "Bearer " (:access-token auth))
             "User-Agent" "codex_cli_rs/0.0.0 (clojure-llm-sdk)"
             "originator" "codex_cli_rs"}
      (:account-id auth) (assoc "ChatGPT-Account-ID" (:account-id auth)))))

(defn codex-backend-available?
  "Return true if valid Codex backend credentials are available."
  []
  (boolean (read-codex-auth)))

;; ---------------------------------------------------------------------------
;; Message status normalization
;; ---------------------------------------------------------------------------

(def ^:private valid-message-statuses #{"completed" "incomplete" "in_progress"})

(defn- normalize-message-status
  "Normalize a Responses assistant message status for replay.
   The API accepts completed/incomplete/in_progress on replayed
   assistant output messages."
  [value]
  (let [normalized (some-> value str str/lower-case
                           (str/replace #"[- ]" "_")
                           str/trim)]
    (if (valid-message-statuses normalized)
      normalized
      "completed")))

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
                     :content [{:type "output_text" :text (t/content->string (:message/content msg))}]}]))]
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

(defn- codex-backend? [profile]
  (let [base-url (str/lower-case (or (:profile/base-url profile) ""))]
    (str/includes? base-url "chatgpt.com")))

(defn build-request-codex
  [profile request]
  (let [model (:request/model request)
        messages (:request/messages request)
        backend? (codex-backend? profile)
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
              (when (:request/metadata request)
                {:metadata (:request/metadata request)}))
        ;; Auth headers
        headers (if backend?
                  (merge (codex-backend-auth-headers)
                         {"Accept" "text/event-stream"})
                  (provider/default-headers profile
                                            (provider/resolve-auth-token profile)))]
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
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= payload "[DONE]")
        (try (json/parse-string payload true)
             (catch Exception _ nil))))))

(defn- parse-sse-text
  "Parse a multi-line SSE response body into a sequence of parsed data maps.
   Handles both 'data: {...}' lines and 'event: xxx\ndata: {...}' pairs."
  [sse-text]
  (->> (str/split-lines sse-text)
       (keep parse-sse-line)))

(defn parse-response-codex
  [profile raw]
  ;; Handle SSE text responses (Codex backend returns SSE even for non-streaming)
  (if (string? raw)
    (let [data-maps (parse-sse-text raw)
          ;; Convert each data map to a faux SSE line and parse it
          lines (map #(str "data: " (json/generate-string %)) data-maps)
          events (keep #(parse-stream-event-codex profile %) lines)
          ;; Only add a fallback end-event if the SSE didn't already include one
          has-end? (some #(= (:event/type %) :stream/end) events)
          events (concat [(stream/start-event)] events (when-not has-end? [(stream/end-event)]))
          model (some #(get-in % [:response :model]) data-maps)
          resp-id (some #(get-in % [:response :id]) data-maps)]
      (-> (stream/reduce-events events)
          (stream/acc->response :codex model)
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
                          (get status-map status :unknown))]
      {:response/id (:id raw)
       :response/provider :codex
       :response/model (:model raw)
       :response/parts (into []
                             (concat
                              (map #(hash-map :part/type :text :text %) text-parts)
                              (map #(hash-map :part/type :reasoning :reasoning/text %) reasoning-parts)
                              tool-calls))
       :response/tool-calls (not-empty tool-calls)
       :response/finish-reason finish-reason
       :response/usage (when-let [u (:usage raw)]
                         (usage/normalize-usage :codex u))
       :response/provider-data (not-empty
                                (cond-> {}
                                  (seq reasoning-details) (assoc :codex_reasoning_items reasoning-details)
                                  (seq message-items) (assoc :codex_message_items message-items)))
       :response/raw raw})))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn parse-stream-event-codex
  [profile line]
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
            (stream/tool-call-start 0
                                    (or (:call_id item) (:id item))
                                    (:name item))))

        ;; Tool call: arguments delta
        (= t "response.function_call_arguments.delta")
        (stream/tool-call-delta 0 (:delta data))

        ;; Tool call: arguments done (end)
        (= t "response.function_call_arguments.done")
        (stream/tool-call-end 0)

        ;; Tool call: output item done (alternative start signal)
        (= t "response.output_item.done")
        (let [item (:item data)]
          (case (:type item)
            "function_call"
            (stream/tool-call-start 0 (or (:call_id item) (:id item)) (:name item))
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
        (stream/end-event :finish-reason :stop)

        (= t "response.incomplete")
        (stream/end-event :finish-reason :incomplete)

        (= t "response.failed")
        (stream/end-event :finish-reason :unknown)

        :else nil))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-codex
  [profile status body]
  (errors/classify-error (Exception. "Codex API error")
                         :status status
                         :body body
                         :provider :codex))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord CodexTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-codex profile request))

  (parse-response [this profile raw]
    (parse-response-codex profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-codex profile line))

  (parse-error [this profile status body]
    (parse-error-codex profile status body))

  (normalize-usage [this profile raw]
    (usage/normalize-usage :codex raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :reasoning :encrypted-reasoning}))

(defn make-transport []
  (->CodexTransport))

;; Register
(provider/register-provider
 {:profile/id :codex
  :profile/protocol-family :codex
  :profile/base-url "https://api.openai.com/v1"
  :profile/auth-strategy :bearer
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :reasoning :encrypted-reasoning}
  :profile/env-var-names ["OPENAI_API_KEY"]
  :profile/transport-constructor make-transport})

(provider/register-provider
 {:profile/id :codex-backend
  :profile/protocol-family :codex
  :profile/base-url "https://chatgpt.com/backend-api/codex"
  :profile/auth-strategy :oauth-external
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :reasoning :encrypted-reasoning}
  :profile/env-var-names []
  :profile/transport-constructor make-transport})
