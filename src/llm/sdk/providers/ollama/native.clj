(ns llm.sdk.providers.ollama.native
  "Native Ollama adapter — /api/chat (chat) and /api/embed (embeddings).

   Ollama also exposes an OpenAI-compat /v1/chat/completions endpoint
   that the existing :ollama profile (registered) targets.
   This namespace registers a sibling :ollama-native profile for callers
   who want the native shape — older Ollama versions, vision input via
   the native :images field, or workflows that need the native
   :options keys (e.g. :num_ctx, :num_predict, :mirostat).

   Streaming: Ollama uses NDJSON (one JSON object per line), NOT
   SSE. We re-use the http/sse-request line reader and parse each line
   as a raw JSON object instead of stripping a 'data: ' prefix."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.errors :as errors]))

(defn- base-url [profile]
  (or (:profile/base-url profile)
      (System/getenv "OLLAMA_BASE_URL")
      "http://localhost:11434"))

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- collect-images
  "Ollama native takes images as a sibling :images vector of base64
   strings, not as content parts."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= (:part/type %) :image))
         (mapv (fn [part]
                 (let [url (:image/url part)]
                   (or (:image/data part)
                       (when (and (string? url) (str/starts-with? url "data:"))
                         (second (str/split url #"," 2)))
                       url)))))))

(defn- message->ollama [msg]
  (let [content (:message/content msg)
        text (cond
               (string? content) content
               (sequential? content)
               (->> content
                    (filter #(= (:part/type %) :text))
                    (map :text)
                    (str/join ""))
               :else "")
        thinking (when (sequential? content)
                   (->> content
                        (filter #(= (:part/type %) :reasoning))
                        (map :reasoning/text)
                        (str/join "")))
        images (collect-images content)
        base (cond-> {:role (name (:message/role msg))
                      :content text}
               (seq thinking) (assoc :thinking thinking)
               (seq images) (assoc :images images))]
    (cond
      (= :tool (:message/role msg))
      (cond-> (assoc base :role "tool")
        (:message/name msg) (assoc :tool_name (:message/name msg)))

      (seq (:message/tool-calls msg))
      (assoc base :tool_calls
             (mapv (fn [idx tc]
                     {:type "function"
                      :function {:index idx
                                 :name (:tool-call/name tc)
                                 :arguments (try
                                              (json/parse-string
                                               (:tool-call/arguments tc) true)
                                              (catch Exception _ {}))}})
                   (range)
                   (:message/tool-calls msg)))

      :else base)))

(defn- tool->ollama [tool]
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
        :low "low"
        :medium "medium"
        :high "high"
        (throw (ex-info
                (str "Ollama does not support reasoning effort "
                     (name (:effort reasoning)))
                {:provider :ollama-native
                 :effort (:effort reasoning)
                 :supported-efforts #{:low :medium :high}})))

      (contains? reasoning :enabled)
      (boolean (:enabled reasoning))

      :else nil)))

;; ---------------------------------------------------------------------------
;; Chat request
;; ---------------------------------------------------------------------------

(defn build-request-ollama
  [profile request]
  (let [stream? (boolean (:request/stream? request))
        messages (mapv message->ollama (:request/messages request))
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
     :headers {"Content-Type" "application/json"}
     :body body}))

;; ---------------------------------------------------------------------------
;; Chat response parsing
;; ---------------------------------------------------------------------------

(def ^:private finish-reason-map
  {"stop" :stop
   "length" :length
   "load" :stop
   "tool_calls" :tool-calls})

(defn- usage-from [raw]
  (when (or (:prompt_eval_count raw) (:eval_count raw))
    (let [input (or (:prompt_eval_count raw) 0)
          output (or (:eval_count raw) 0)]
      {:usage/input-tokens input
       :usage/output-tokens output
       :usage/total-tokens (+ input output)
       :usage/request-count 1
       :usage/provider-raw (select-keys raw [:total_duration :load_duration
                                              :prompt_eval_duration
                                              :eval_duration])})))

(defn- tool-call->part [fallback-index tc]
  (let [index (or (get-in tc [:function :index]) fallback-index)
        arguments (get-in tc [:function :arguments])]
    {:part/type :tool-call
     :tool-call/id (or (:id tc) (str "ollama_call_" index))
     :tool-call/name (or (get-in tc [:function :name]) "")
     :tool-call/arguments (if (string? arguments)
                            arguments
                            (json/generate-string (or arguments {})))}))

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
        finish (get finish-reason-map (:done_reason raw) :stop)
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
      (let [msg (:message data)
            tool-events
            (mapcat
             (fn [[fallback-index tc]]
               (let [index (or (get-in tc [:function :index]) fallback-index)
                     arguments (get-in tc [:function :arguments])]
                 [(stream/tool-call-start
                   index
                   (or (:id tc) (str "ollama_call_" index))
                   (or (get-in tc [:function :name]) ""))
                  (stream/tool-call-delta
                   index
                   (if (string? arguments)
                     arguments
                     (json/generate-string (or arguments {}))))
                  (stream/tool-call-end index)]))
             (map-indexed vector (:tool_calls msg)))
            events (cond-> []
                     (seq (:thinking msg))
                     (conj (stream/reasoning-delta (:thinking msg)))
                     (seq (:content msg))
                     (conj (stream/content-delta (:content msg))))
            events (into events tool-events)
            events (if (:done data)
                     (cond-> events
                       (usage-from data)
                       (conj (stream/usage-event (usage-from data)))
                       true
                       (conj (stream/end-event
                              :finish-reason
                              (or (get finish-reason-map (:done_reason data))
                                  :stop))))
                     events)]
        (case (count events)
          0 nil
          1 (first events)
          events)))))

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
  (when (or (:prompt_eval_count raw) (:total_duration raw))
    (let [input (or (:prompt_eval_count raw) 0)]
      {:usage/input-tokens input
       :usage/output-tokens 0
       :usage/total-tokens input
       :usage/request-count 1
       :usage/provider-raw
       (select-keys raw [:total_duration :load_duration])})))

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

;; Register :ollama-native — the OpenAI-compat :ollama profile lives
;; on as the default for new Ollama installs; :ollama-native is opt-in.
(provider/register-provider
 {:profile/id :ollama-native
  :profile/protocol-family :ollama-native
  :profile/base-url (or (System/getenv "OLLAMA_BASE_URL")
                        "http://localhost:11434")
  :profile/auth-strategy :none
  :profile/supports-model-listing false
  :profile/capabilities #{:chat :streaming :tools :embedding :multimodal
                          :json-schema :reasoning}
  :profile/env-var-names []
  :profile/transport-constructor make-transport
  :profile/embed-transport-constructor make-embed-transport})
