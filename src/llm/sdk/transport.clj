(ns llm.sdk.transport
  "Transport protocol definition. A transport owns the translation between
   canonical SDK shapes and provider-native wire formats."
  (:require [clojure.string :as str]
            [llm.sdk.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Protocol
;; ---------------------------------------------------------------------------

(defprotocol Transport
  "Provider-specific format conversion and normalization."
  (build-request [this profile request]
    "Given a provider profile and canonical request, return the native
     request map (body + url + method + headers) ready for HTTP execution.")
  (parse-response [this profile raw-response]
    "Given a provider profile and raw HTTP response body, return a
     canonical Response map.")
  (parse-stream-event [this profile line]
    "Given an SSE line string, return a StreamEvent map or nil if the
     line should be ignored.")
  (parse-error [this profile status body]
    "Given HTTP status and body, return a classified error map.")
  (normalize-usage [this profile raw-usage]
    "Given raw provider usage data, return canonical Usage map.")
  (request-capabilities [this]
    "Return set of capabilities supported by this transport."))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn map-finish-reason
  "Map provider finish reason strings to canonical keywords."
  [raw mapping]
  (let [r (when raw (keyword (str/lower-case (name raw))))]
    (get mapping r :unknown)))

(defn content->string
  "Extract text string from message content (string or vector of parts)."
  [content]
  (cond
    (string? content) content
    (sequential? content)
    (->> content
         (filter #(= (:part/type %) :text))
         (map :text)
         (str/join ""))
    :else nil))

(defn parts->text-parts
  "Convert a string or seq of parts into a vector of text parts."
  [content]
  (cond
    (string? content) [{:part/type :text :text content}]
    (sequential? content) (vec content)
    :else []))

(defn extract-tool-calls-from-parts
  "Extract tool-call parts from message content."
  [content]
  (when (sequential? content)
    (vec (filter #(= (:part/type %) :tool-call) content))))

(defn extract-text-from-parts
  "Extract text string from parts."
  [content]
  (when (sequential? content)
    (->> content
         (filter #(= (:part/type %) :text))
         (map :text)
         (str/join ""))))

(defn sanitize-messages
  "Strip internal fields from messages before sending to strict providers.
   Removes codex_reasoning_items, codex_message_items, tool_name, call_id,
   response_item_id from tool_calls."
  [messages]
  (mapv
   (fn [msg]
     (let [m (dissoc msg :codex_reasoning_items :codex_message_items :tool_name)]
       (if-let [tcs (:message/tool-calls m)]
         (assoc m :message/tool-calls
                (mapv #(dissoc % :call_id :response_item_id) tcs))
         m)))
   messages))

(defn developer-role-swap
  "Swap system → developer for models that require it."
  [messages model]
  (let [model-lower (str/lower-case (or model ""))]
    (if (and (seq messages)
             (= (:message/role (first messages)) :system)
             (or (str/includes? model-lower "gpt-5")
                 (str/includes? model-lower "codex")
                 (str/includes? model-lower "o3")))
      (assoc-in messages [0 :message/role] :developer)
      messages)))
