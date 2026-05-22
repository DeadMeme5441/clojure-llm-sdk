(ns llm.sdk.providers.ollama-native
  "Native Ollama adapter — /api/chat (chat) and /api/embed (embeddings).

   Ollama also exposes an OpenAI-compat /v1/chat/completions endpoint
   that the existing :ollama profile (registered under T2-03) targets.
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
         (mapv :image/url))))

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
        images (collect-images content)
        base (cond-> {:role (name (:message/role msg))
                      :content text}
               (seq images) (assoc :images images))]
    (cond
      (= :tool (:message/role msg))
      (assoc base
             :role "tool"
             :tool_call_id (or (:message/tool-call-id msg) "tool_0"))

      (seq (:message/tool-calls msg))
      (assoc base :tool_calls
             (mapv (fn [tc]
                     {:function {:name (:tool-call/name tc)
                                 :arguments (try (json/parse-string
                                                  (:tool-call/arguments tc) true)
                                                 (catch Exception _ {}))}})
                   (:message/tool-calls msg)))

      :else base)))

(defn- tool->ollama [tool]
  ;; Ollama accepts OpenAI-shaped tool definitions verbatim.
  tool)

;; ---------------------------------------------------------------------------
;; Chat request
;; ---------------------------------------------------------------------------

(defn build-request-ollama
  [profile request]
  (let [stream? (boolean (:request/stream? request))
        messages (mapv message->ollama (:request/messages request))
        opts (cond-> {}
               (:request/temperature request)
               (assoc :temperature (:request/temperature request))
               (:request/top-p request) (assoc :top_p (:request/top-p request))
               (:request/max-tokens request)
               (assoc :num_predict (:request/max-tokens request))
               (:request/stop request)
               (assoc :stop (vec (:request/stop request))))
        extras (get-in request [:request/provider-options :ollama] {})
        body (cond-> {:model (:request/model request)
                      :messages messages
                      :stream stream?}
               (seq opts) (assoc :options opts)
               (:keep_alive extras) (assoc :keep_alive (:keep_alive extras))
               (seq (:request/tools request))
               (assoc :tools (mapv tool->ollama (:request/tools request)))
               (:request/response-format request)
               (assoc :format (case (get-in request [:request/response-format :type])
                                :json_object "json"
                                :json_schema (get-in request [:request/response-format :json-schema])
                                "json")))]
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

(defn parse-response-ollama
  [_profile raw]
  (let [msg (:message raw)
        text (:content msg)
        tool-calls (vec
                    (mapv (fn [tc]
                            {:part/type :tool-call
                             :tool-call/id (or (:id tc) (str (random-uuid)))
                             :tool-call/name (get-in tc [:function :name])
                             :tool-call/arguments
                             (let [a (get-in tc [:function :arguments])]
                               (if (string? a) a (json/generate-string a)))})
                          (:tool_calls msg)))
        finish (get finish-reason-map (:done_reason raw) :stop)]
    {:response/provider :ollama-native
     :response/model (:model raw)
     :response/parts (cond-> []
                       (seq text) (conj {:part/type :text :text text})
                       (seq tool-calls) (into tool-calls))
     :response/tool-calls (not-empty tool-calls)
     :response/finish-reason finish
     :response/usage (usage-from raw)
     :response/raw raw}))

;; ---------------------------------------------------------------------------
;; Stream parsing — Ollama emits NDJSON (one JSON per line).
;; ---------------------------------------------------------------------------

(defn parse-stream-event-ollama
  [_profile line]
  (when (and (string? line) (not (str/blank? line)))
    (when-let [data (try (json/parse-string line true)
                         (catch Exception _ nil))]
      (let [msg (:message data)
            done? (:done data)
            tool-calls (get msg :tool_calls)
            text (:content msg)]
        (cond
          (seq tool-calls)
          (let [tc (first tool-calls)]
            [(stream/tool-call-start 0
                                     (or (:id tc) (str (random-uuid)))
                                     (get-in tc [:function :name]))
             (stream/tool-call-delta 0
                                     (let [a (get-in tc [:function :arguments])]
                                       (if (string? a) a (json/generate-string a))))])

          (and (seq text) (not done?))
          (stream/content-delta text)

          done?
          (let [u (usage-from data)
                events []
                events (cond-> events
                         u (conj (stream/usage-event u)))
                fr (or (get finish-reason-map (:done_reason data)) :stop)
                events (conj events (stream/end-event :finish-reason fr))]
            events)

          :else nil)))))

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
  {:method :post
   :url (str (base-url profile) "/api/embed")
   :headers {"Content-Type" "application/json"}
   :body {:model (:embed/model request)
          :input (vec (:embed/inputs request))}})

(defn parse-embed-response-ollama
  [_profile raw]
  (let [vectors (:embeddings raw)]
    {:embed/provider :ollama-native
     :embed/model (:model raw)
     :embed/vectors (mapv (fn [i v]
                            {:embed/index i
                             :embed/vector (vec v)})
                          (range) vectors)
     :response/usage (when (or (:prompt_eval_count raw) (:total_duration raw))
                       {:usage/input-tokens (or (:prompt_eval_count raw) 0)
                        :usage/output-tokens 0
                        :usage/total-tokens (or (:prompt_eval_count raw) 0)
                        :usage/request-count 1})
     :response/raw raw}))

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
  (request-capabilities [_] #{:chat :streaming :tools :multimodal}))

(defn make-transport [] (->OllamaNativeTransport))

(defrecord OllamaNativeEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request] (build-embed-request-ollama profile request))
  (parse-embed-response [_ profile raw] (parse-embed-response-ollama profile raw))
  (parse-embed-error [_ profile status body] (parse-embed-error-ollama profile status body))
  (normalize-embed-usage [_ _ raw] raw))

(defn make-embed-transport [] (->OllamaNativeEmbedTransport))

;; Register :ollama-native — the OpenAI-compat :ollama profile lives
;; on as the default for new Ollama installs; :ollama-native is opt-in.
(provider/register-provider
 {:profile/id :ollama-native
  :profile/protocol-family :ollama-native
  :profile/base-url "http://localhost:11434"
  :profile/auth-strategy :none
  :profile/supports-model-listing true
  :profile/capabilities #{:chat :streaming :tools :embedding :multimodal}
  :profile/env-var-names ["OLLAMA_BASE_URL"]
  :profile/transport-constructor make-transport
  :profile/embed-transport-constructor make-embed-transport})
