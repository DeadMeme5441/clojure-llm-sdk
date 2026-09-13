(ns llm.sdk.providers.ollama.native
  "Native Ollama adapter — /api/chat (chat) and /api/embed (embeddings).

   Ollama also exposes an OpenAI-compat /v1/chat/completions endpoint
   targeted by the existing :ollama profile. This namespace implements the
   sibling :ollama-native transport for callers who want the native shape —
   older Ollama versions, vision input via the native :images field, or
   workflows that need native :options keys (e.g. :num_ctx, :num_predict,
   :mirostat).

   Streaming: Ollama uses NDJSON (one JSON object per line), NOT
   SSE. We re-use the http/sse-request line reader and parse each line
   as a raw JSON object instead of stripping a 'data: ' prefix."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

(defn- base-url [profile]
  (or (:profile/base-url profile)
      (System/getenv "OLLAMA_BASE_URL")
      "http://localhost:11434"))

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- invalid-content!
  [role part]
  (throw
   (ex-info
    (str "Ollama native does not support content part "
         (:part/type part) " for " role " messages")
    {:provider :ollama-native
     :message/role role
     :part/type (:part/type part)})))

(defn- data-uri->base64
  [url]
  (let [comma (str/index-of url ",")
        header (when comma (subs url 0 comma))
        lower-header (some-> header str/lower-case)]
    (if (and comma
             (str/starts-with? lower-header "data:")
             (str/ends-with? lower-header ";base64"))
      (subs url (inc comma))
      (throw
       (ex-info
        "Ollama native images require a base64 data URI"
        {:provider :ollama-native
         :image/url url})))))

(defn- image->base64
  [part]
  (let [data (:image/data part)
        url (:image/url part)]
    (cond
      (and (string? data) (str/starts-with? data "data:"))
      (data-uri->base64 data)

      (string? data)
      data

      (and (string? url) (str/starts-with? url "data:"))
      (data-uri->base64 url)

      (some? url)
      (throw
       (ex-info
        "Ollama native does not fetch image URLs; provide base64 :image/data or a base64 data URI"
        {:provider :ollama-native
         :image/url url}))

      :else
      (throw
       (ex-info
        "Ollama native images require base64 :image/data or a base64 data URI"
        {:provider :ollama-native
         :part part})))))

(defn- supported-part?
  [role part-type]
  (contains?
   (case role
     :user #{:text :image}
     :assistant #{:text :image :reasoning :tool-call}
     (:system :developer) #{:text}
     :tool #{:text :tool-result}
     #{})
   part-type))

(defn- content-parts
  [msg]
  (let [content (:message/content msg)]
    (cond
      (nil? content) []
      (string? content) []
      (sequential? content)
      (mapv (fn [part]
              (when-not (and (map? part)
                             (supported-part? (:message/role msg)
                                              (:part/type part)))
                (invalid-content! (:message/role msg) part))
              part)
            content)
      :else
      (throw
       (ex-info
        "Ollama native message content must be a string or canonical content parts"
        {:provider :ollama-native
         :message/role (:message/role msg)
         :content content})))))

(defn- content-text
  [msg parts]
  (let [content (:message/content msg)]
    (if (string? content)
      content
      (->> parts
           (keep (fn [part]
                   (case (:part/type part)
                     :text (:text part)
                     :tool-result (:tool-result/content part)
                     nil)))
           (str/join "")))))

(defn- tool-call-key
  [tc]
  (if-let [id (:tool-call/id tc)]
    [:id id]
    [:call (:tool-call/name tc) (:tool-call/arguments tc)]))

(defn- merge-duplicate-tool-call
  [left right]
  (let [provider-data (merge (:tool-call/provider-data left)
                             (:tool-call/provider-data right))]
    (cond-> (merge left right)
      (seq provider-data)
      (assoc :tool-call/provider-data provider-data))))

(defn- canonical-tool-calls
  [msg parts]
  (let [from-content (filterv #(= :tool-call (:part/type %)) parts)
        from-field (vec (:message/tool-calls msg))]
    (reduce
     (fn [calls tc]
       (if-let [idx (first
                     (keep-indexed
                      (fn [idx existing]
                        (when (= (tool-call-key existing)
                                 (tool-call-key tc))
                          idx))
                      calls))]
         (update calls idx merge-duplicate-tool-call tc)
         (conj calls tc)))
     []
     (concat from-content from-field))))

(defn- tool-arguments->object
  [tc]
  (let [arguments (:tool-call/arguments tc)]
    (try
      (let [parsed (if (str/blank? arguments)
                     {}
                     (json/parse-string arguments true))]
        (if (map? parsed)
          parsed
          (throw
           (ex-info
            "Ollama native tool-call arguments must encode a JSON object"
            {:provider :ollama-native
             :tool-call/id (:tool-call/id tc)
             :tool-call/arguments arguments}))))
      (catch clojure.lang.ExceptionInfo e
        (throw e))
      (catch Exception e
        (throw
         (ex-info
          "Ollama native tool-call arguments must encode a JSON object"
          {:provider :ollama-native
           :tool-call/id (:tool-call/id tc)
           :tool-call/arguments arguments}
          e))))))

(defn- canonical-tool-call->ollama
  [fallback-index tc]
  (let [provider-data (:tool-call/provider-data tc)
        native (:ollama/tool-call provider-data)
        native-type (or (:type native) (:wire_type provider-data))
        index (or (get-in native [:function :index]) fallback-index)
        function {:index index
                  :name (:tool-call/name tc)
                  :arguments (tool-arguments->object tc)}]
    (when (and native-type
               (not (contains? #{"function" :function} native-type)))
      (throw
       (ex-info
        "Ollama native only supports function tool calls; custom tool calls are unsupported"
        {:provider :ollama-native
         :tool-call/id (:tool-call/id tc)
         :tool/type native-type})))
    (cond-> {:function function}
      native-type (assoc :type native-type)
      (:tool-call/id tc) (assoc :id (:tool-call/id tc)))))


(defn- message->ollama
  [msg parts tool-calls tool-name-by-id]
  (let [role (:message/role msg)
        tool-result
        (when (= role :tool)
          (->> (t/extract-tool-result :ollama-native msg)
               (t/reject-error-tool-result! :ollama-native)))
        wire-role (case role
                    (:system :developer) "system"
                    :user "user"
                    :assistant "assistant"
                    :tool "tool"
                    (throw
                     (ex-info
                      (str "Ollama native does not support message role " role)
                      {:provider :ollama-native
                       :message/role role})))
        text (if tool-result
               (:tool-result/content tool-result)
               (content-text msg parts))
        reasoning (->> parts
                       (filter #(= :reasoning (:part/type %)))
                       (map :reasoning/text)
                       (str/join ""))
        images (->> parts
                    (filter #(= :image (:part/type %)))
                    (mapv image->base64))]
    (when (and (seq tool-calls) (not= role :assistant))
      (throw
       (ex-info
        "Ollama native tool calls are only valid on assistant messages"
        {:provider :ollama-native
         :message/role role})))
    (if (= role :tool)
      (let [tool-call-id (or (:tool-result/id tool-result)
                             (:message/tool-call-id msg))
            tool-name (or (:tool-result/name tool-result)
                          (:message/name msg)
                          (get tool-name-by-id tool-call-id))]
        (when (str/blank? tool-name)
          (throw
           (ex-info
            "Ollama native tool results require a tool name or a resolvable prior tool-call ID"
            {:provider :ollama-native
             :tool-call/id tool-call-id})))
        (cond-> {:role wire-role
                 :content text
                 :tool_name tool-name}
          tool-call-id (assoc :tool_call_id tool-call-id)))
      (cond-> {:role wire-role
               :content text}
        (seq reasoning) (assoc :thinking reasoning)
        (seq images) (assoc :images images)
        (seq tool-calls)
        (assoc :tool_calls
               (mapv canonical-tool-call->ollama
                     (range)
                     tool-calls))))))

(defn- messages->ollama
  [messages]
  (:messages
   (reduce
    (fn [{:keys [messages tool-name-by-id]} msg]
      (let [parts (content-parts msg)
            calls (canonical-tool-calls msg parts)
            wire-message (message->ollama
                          msg parts calls tool-name-by-id)
            updated-tool-name-by-id
            (reduce (fn [names tc]
                      (if (and (some? (:tool-call/id tc))
                               (not (str/blank? (:tool-call/name tc))))
                        (assoc names
                               (:tool-call/id tc)
                               (:tool-call/name tc))
                        names))
                    tool-name-by-id
                    calls)]
        {:messages (conj messages wire-message)
         :tool-name-by-id updated-tool-name-by-id}))
    {:messages [] :tool-name-by-id {}}
    messages)))

(defn- tool->ollama [tool]
  (when-not (= :function (:type tool))
    (throw
     (ex-info
      "Ollama native only supports function tools; custom tools are unsupported"
      {:provider :ollama-native
       :tool/type (:type tool)})))
  (let [function (:function tool)]
    {:type "function"
     :function (cond-> {:name (:name function)
                        :parameters (or (:parameters function)
                                        {:type "object" :properties {}})}
                 (:description function)
                 (assoc :description (:description function)))}))

(defn- reasoning->ollama [reasoning]
  (when reasoning
    (cond
      (false? (:enabled reasoning))
      false

      (:effort reasoning)
      (case (:effort reasoning)
        :none false
        :low "low"
        :medium "medium"
        :high "high"
        :max "max"
        (throw (ex-info
                (str "Ollama does not support reasoning effort "
                     (name (:effort reasoning)))
                {:provider :ollama-native
                 :effort (:effort reasoning)
                 :supported-efforts #{:none :low :medium :high :max}})))

      (contains? reasoning :enabled)
      (boolean (:enabled reasoning))

      :else nil)))

;; ---------------------------------------------------------------------------
;; Chat request
;; ---------------------------------------------------------------------------

(defn build-request-ollama
  [profile request]
  (let [stream? (boolean (:request/stream? request))
        messages (messages->ollama (:request/messages request))
        extras (get-in request [:request/provider-options :ollama] {})
        opts (merge
              (:options extras)
              (cond-> {}
                (contains? request :request/temperature)
                (assoc :temperature (:request/temperature request))
                (contains? request :request/top-p)
                (assoc :top_p (:request/top-p request))
                (contains? request :request/max-tokens)
                (assoc :num_predict (:request/max-tokens request))
                (contains? request :request/stop)
                (assoc :stop (t/stop-sequences (:request/stop request)))))
        reasoning (reasoning->ollama (:request/reasoning request))
        format (case (get-in request [:request/response-format :type])
                 :json_object "json"
                 :json_schema (get-in request [:request/response-format :json-schema])
                 nil)
        body (cond-> {:model (:request/model request)
                      :messages messages
                      :stream stream?}
               (seq opts) (assoc :options opts)
               (contains? extras :keep_alive)
               (assoc :keep_alive (:keep_alive extras))
               (seq (:request/tools request))
               (assoc :tools (mapv tool->ollama (:request/tools request)))
               format (assoc :format format)
               (some? reasoning) (assoc :think reasoning)
               (contains? extras :think) (assoc :think (:think extras))
               (contains? extras :logprobs) (assoc :logprobs (:logprobs extras))
               (contains? extras :top_logprobs)
               (assoc :top_logprobs (:top_logprobs extras)))]
    {:method :post
     :url (str (base-url profile) "/api/chat")
     :headers {"Content-Type" "application/json"
               "Accept" (if stream?
                          "application/x-ndjson"
                          "application/json")}
     :body body}))

;; ---------------------------------------------------------------------------
;; Chat response parsing
;; ---------------------------------------------------------------------------

(def ^:private finish-reason-map
  {"stop" :stop
   "length" :length
   "load" :stop
   "tool_calls" :tool-calls})

(def ^:private usage-raw-keys
  [:total_duration
   :load_duration
   :prompt_eval_count
   :prompt_eval_cached_count
   :prompt_eval_duration
   :eval_count
   :eval_duration])

(defn- usage-from
  [raw]
  (let [prompt-total (usage/->int (:prompt_eval_count raw))
        cached (usage/->int (:prompt_eval_cached_count raw))
        output (usage/->int (:eval_count raw))
        input (when (some? prompt-total)
                (max 0 (- prompt-total (or cached 0))))
        total (when (and (some? prompt-total) (some? output))
                (+ prompt-total output))]
    (when (some some? [prompt-total cached output])
      (cond-> {:usage/request-count 1
               :usage/provider-raw (select-keys raw usage-raw-keys)}
        (some? input) (assoc :usage/input-tokens input)
        (some? cached) (assoc :usage/cached-input-tokens cached)
        (some? output) (assoc :usage/output-tokens output)
        (some? total) (assoc :usage/total-tokens total)))))

(defn- stream-usage-from
  [raw]
  (let [prompt-total (usage/->int (:prompt_eval_count raw))
        cached (usage/->int (:prompt_eval_cached_count raw))
        output (usage/->int (:eval_count raw))
        input (when (some? prompt-total)
                (max 0 (- prompt-total (or cached 0))))]
    (when (some some? [prompt-total cached output])
      (cond-> {:usage/request-count 1
               :usage/provider-raw (select-keys raw usage-raw-keys)}
        (some? input) (assoc :usage/input-tokens input)
        (some? cached) (assoc :usage/cached-input-tokens cached)
        (some? output) (assoc :usage/output-tokens output)))))

(defn- tool-call-arguments-string
  [arguments]
  (if (string? arguments)
    arguments
    (json/generate-string (or arguments {}))))

(defn- tool-call->part [fallback-index tc]
  (let [index (or (get-in tc [:function :index]) fallback-index)
        arguments (get-in tc [:function :arguments])]
    {:part/type :tool-call
     :tool-call/id (or (:id tc) (str "ollama_call_" index))
     :tool-call/name (or (get-in tc [:function :name]) "")
     :tool-call/arguments (tool-call-arguments-string arguments)
     :tool-call/provider-data {:ollama/tool-call tc
                               :ollama/index index}}))

(defn parse-response-ollama
  [_profile raw]
  (let [msg (:message raw)
        text (:content msg)
        thinking (:thinking msg)
        images (mapv #(hash-map :part/type :image :image/data %)
                     (:images msg))
        tool-calls (mapv tool-call->part
                         (range)
                         (:tool_calls msg))
        finish (if (contains? finish-reason-map (:done_reason raw))
                 (get finish-reason-map (:done_reason raw))
                 (if (nil? (:done_reason raw)) :stop :unknown))
        usage (usage-from raw)]
    (cond-> {:response/provider :ollama-native
             :response/model (:model raw)
             :response/parts (cond-> []
                               (seq thinking)
                               (conj {:part/type :reasoning
                                      :reasoning/text thinking})
                               (seq text) (conj {:part/type :text :text text})
                               (seq images) (into images)
                               (seq tool-calls) (into tool-calls))
             :response/finish-reason finish
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage (assoc :response/usage usage))))

;; ---------------------------------------------------------------------------
;; Stream parsing — Ollama emits NDJSON (one JSON per line).
;; ---------------------------------------------------------------------------

(defn parse-stream-event-ollama
  [_profile line]
  (when (and (string? line) (not (str/blank? line)))
    (when-let [data (try (json/parse-string line true)
                         (catch Exception _ nil))]
      (if (contains? data :error)
        (stream/error-event
         {:error/type :provider
          :error/message (or (:error data) "Unknown Ollama stream error")
          :error/raw data})
        (let [msg (:message data)
              tool-events
              (mapcat
               (fn [[fallback-index tc]]
                 (let [index (or (get-in tc [:function :index])
                                 fallback-index)
                       id (or (:id tc) (str "ollama_call_" index))
                       arguments (get-in tc [:function :arguments])]
                   [(stream/tool-call-start
                     index
                     id
                     (or (get-in tc [:function :name]) "")
                     :provider-data {:ollama/tool-call tc
                                     :ollama/index index})
                    (stream/tool-call-delta
                     index
                     (tool-call-arguments-string arguments))
                    (stream/tool-call-end index)]))
               (map-indexed vector (:tool_calls msg)))
              events (cond-> []
                       (seq (:thinking msg))
                       (conj (stream/reasoning-delta (:thinking msg)))
                       (seq (:content msg))
                       (conj (stream/content-delta (:content msg))))
              events (into events tool-events)
              usage (when (:done data) (stream-usage-from data))
              events (if (:done data)
                       (cond-> events
                         usage
                         (conj (stream/usage-event usage))
                         true
                         (conj
                          (stream/end-event
                           :finish-reason
                           (if (contains? finish-reason-map
                                          (:done_reason data))
                             (get finish-reason-map (:done_reason data))
                             (if (nil? (:done_reason data))
                               :stop
                               :unknown)))))
                       events)]
          (case (count events)
            0 nil
            1 (first events)
            events))))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-ollama
  [_profile status body]
  (errors/classify-error (Exception. "Ollama API error")
                         :status status
                         :body body
                         :provider :ollama-native))

;; ---------------------------------------------------------------------------
;; Embeddings — /api/embed
;; ---------------------------------------------------------------------------

(defn build-embed-request-ollama
  [profile request]
  (let [extras (get-in request [:embed/provider-options :ollama] {})
        body (cond-> {:model (:embed/model request)
                      :input (vec (:embed/inputs request))}
               (contains? request :embed/dimensions)
               (assoc :dimensions (:embed/dimensions request))
               (contains? extras :truncate)
               (assoc :truncate (:truncate extras))
               (contains? extras :keep_alive)
               (assoc :keep_alive (:keep_alive extras))
               (seq (:options extras))
               (assoc :options (:options extras)))]
    {:method :post
     :url (str (base-url profile) "/api/embed")
     :headers {"Content-Type" "application/json"}
     :body body}))

(defn- embed-usage-from [raw]
  (when (some #(contains? raw %)
              [:prompt_eval_count :total_duration :load_duration])
    (let [input (usage/->int (:prompt_eval_count raw))]
      (cond-> {:usage/request-count 1
               :usage/provider-raw
               (select-keys raw
                            [:prompt_eval_count :total_duration
                             :load_duration])}
        (some? input) (assoc :usage/input-tokens input
                             :usage/total-tokens input)))))

(defn parse-embed-response-ollama
  [_profile raw]
  (let [vectors (mapv vec (:embeddings raw))
        usage (embed-usage-from raw)]
    (cond-> {:embed/provider :ollama-native
             :embed/model (:model raw)
             :embed/vectors vectors
             :embed/raw raw}
      (seq vectors) (assoc :embed/dimensions (count (first vectors)))
      usage (assoc :response/usage usage))))

(defn parse-embed-error-ollama
  [_profile status body]
  (errors/classify-error (Exception. "Ollama embed API error")
                         :status status
                         :body body
                         :provider :ollama-native))

;; ---------------------------------------------------------------------------
;; Transport records
;; ---------------------------------------------------------------------------

(defrecord OllamaNativeTransport []
  t/Transport
  (build-request [_ profile request] (build-request-ollama profile request))
  (parse-response [_ profile raw] (parse-response-ollama profile raw))
  (parse-stream-event [_ profile line] (parse-stream-event-ollama profile line))
  (parse-error [_ profile status body] (parse-error-ollama profile status body))
  (normalize-usage [_ _ raw] (usage-from raw))
  (request-capabilities [_]
    #{:chat :streaming :tools :multimodal :json-schema :reasoning}))

(defn make-transport [] (->OllamaNativeTransport))

(defrecord OllamaNativeEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request] (build-embed-request-ollama profile request))
  (parse-embed-response [_ profile raw] (parse-embed-response-ollama profile raw))
  (parse-embed-error [_ profile status body] (parse-embed-error-ollama profile status body))
  (normalize-embed-usage [_ _ raw] (embed-usage-from raw)))

(defn make-embed-transport [] (->OllamaNativeEmbedTransport))

