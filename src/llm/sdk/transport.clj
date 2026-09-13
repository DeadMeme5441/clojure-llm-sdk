(ns llm.sdk.transport
  "Transport protocol definition. A transport owns the translation between
   canonical SDK shapes and provider-native wire formats."
  (:require [clojure.string :as str])
  (:import [java.nio.charset StandardCharsets]
           [java.util Base64]))

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
    "Given an SSE line string, return a StreamEvent map, a vector of
     StreamEvent maps (when one SSE line carries multiple semantic
     events, e.g. Perplexity's final chunk with citations + usage +
     finish_reason), or nil if the line should be ignored.")
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

(defn extract-tool-result
  "Return one canonical tool result from a :tool message.

   String and text-part content use message-level identifiers. A typed
   ToolResultPart supplies its own id, name, content, and error status.
   Mixed or conflicting representations are rejected rather than silently
   dropping tool output or routing it to the wrong call."
  [provider-id message]
  (let [content (:message/content message)
        parts (when (sequential? content) (vec content))
        results (filterv #(= :tool-result (:part/type %)) parts)
        unsupported (when (and parts (empty? results))
                      (first (remove #(= :text (:part/type %)) parts)))]
    (when-not (= :tool (:message/role message))
      (throw (ex-info "Tool results require a tool message role"
                      {:provider provider-id
                       :message/role (:message/role message)
                       :error/type :request/invalid-tool-result-role})))
    (when (< 1 (count results))
      (throw (ex-info "Tool messages support exactly one typed tool result"
                      {:provider provider-id
                       :tool-result/count (count results)
                       :error/type :request/multiple-tool-results})))
    (when (and (seq results) (< 1 (count parts)))
      (throw (ex-info "Typed tool results cannot be mixed with other content"
                      {:provider provider-id
                       :error/type :request/ambiguous-tool-result-content})))
    (when unsupported
      (throw (ex-info "Tool message content must be text or one typed tool result"
                      {:provider provider-id
                       :part/type (:part/type unsupported)
                       :error/type :request/unsupported-tool-result-content})))
    (let [typed (first results)
          message-id (:message/tool-call-id message)
          message-name (:message/name message)
          typed-id (:tool-result/id typed)
          typed-name (:tool-result/name typed)]
      (when (and message-id typed-id (not= message-id typed-id))
        (throw (ex-info "Tool result ID conflicts with its message tool-call ID"
                        {:provider provider-id
                         :message/tool-call-id message-id
                         :tool-result/id typed-id
                         :error/type :request/conflicting-tool-result-id})))
      (when (and message-name typed-name (not= message-name typed-name))
        (throw (ex-info "Tool result name conflicts with its message name"
                        {:provider provider-id
                         :message/name message-name
                         :tool-result/name typed-name
                         :error/type :request/conflicting-tool-result-name})))
      (let [result
            (if typed
              typed
              (cond-> {:part/type :tool-result
                       :tool-result/content
                       (cond
                         (string? content) content
                         parts (content->string parts)
                         :else nil)}
                message-id (assoc :tool-result/id message-id)
                message-name (assoc :tool-result/name message-name)))]
        (when-not (string? (:tool-result/content result))
          (throw (ex-info "Tool messages require string result content"
                          {:provider provider-id
                           :error/type :request/missing-tool-result-content})))
        result))))

(defn reject-error-tool-result!
  "Reject an error result when a provider wire format has no error-status field."
  [provider-id tool-result]
  (when (:tool-result/is-error tool-result)
    (throw (ex-info "This provider cannot preserve tool result error status"
                    {:provider provider-id
                     :tool-result/id (:tool-result/id tool-result)
                     :error/type :provider/unsupported-tool-result-error})))
  tool-result)

(defn- normalize-wire-key [key]
  (if (string? key) (keyword key) key))

(defn merge-extra-body
  "Merge provider extra_body fields into a canonical wire body.

   String key spellings are normalized to keywords. Any extra field that
   collides with a canonical body field or an explicitly reserved field is
   rejected rather than silently discarded."
  ([provider-id canonical extra-body]
   (merge-extra-body provider-id canonical extra-body #{}))
  ([provider-id canonical extra-body reserved-fields]
   (when-not (or (nil? extra-body) (map? extra-body))
     (throw (ex-info "Provider extra_body must be a map"
                     {:provider provider-id
                      :error/type :request/invalid-extra-body})))
   (let [protected-fields
         (into (into #{} (map normalize-wire-key) reserved-fields)
               (map normalize-wire-key)
               (keys canonical))]
     (reduce-kv
      (fn [body key value]
        (let [wire-key (normalize-wire-key key)]
          (when (contains? protected-fields wire-key)
            (throw
             (ex-info
              "Provider extra_body cannot override a protected request field"
              {:provider provider-id
               :field wire-key
               :error/type :request/protected-extra-body-override})))
          (assoc body wire-key value)))
      canonical
      (or extra-body {})))))

(defn image-url-or-data-uri!
  "Return an image URL, converting raw base64 :image/data to a data URI.
   Data URIs already supplied in either canonical field are preserved."
  [provider-id part]
  (let [data (:image/data part)
        url (:image/url part)]
    (cond
      (and (string? data) (not (str/blank? data)))
      (if (str/starts-with? (str/lower-case data) "data:")
        data
        (str "data:" (or (:image/mime-type part) "image/png")
             ";base64," data))

      (and (string? url) (not (str/blank? url)))
      url

      :else
      (throw (ex-info "Image attachment is missing :image/url or :image/data"
                      {:provider provider-id
                       :part/type (:part/type part)
                       :error/type :request/missing-image-source})))))

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

(defn stop-sequences
  "Normalize canonical :request/stop into providers' stop-sequence arrays.
   A single string is one sequence, not a seq of characters."
  [stop]
  (cond
    (nil? stop) nil
    (string? stop) [stop]
    (sequential? stop) (vec stop)
    :else [(str stop)]))

(defn bytes->base64 [^bytes bs]
  (.encodeToString (Base64/getEncoder) bs))

(defn text->base64 [^String s]
  (bytes->base64 (.getBytes s StandardCharsets/UTF_8)))

(defn file-mime-type [part]
  (or (:file/mime-type part) "application/pdf"))

(defn file-name [part]
  (or (:file/name part) "document"))

(defn file-binary-data
  "Return base64 encoded file bytes from :file/data or :file/bytes.
   :file/data is assumed to already be base64 encoded."
  [part]
  (or (:file/data part)
      (when-let [bs (:file/bytes part)]
        (if (bytes? bs)
          (bytes->base64 bs)
          (str bs)))))

(defn file-text-content [part]
  (:file/content part))

(defn file-data-for-input-file
  "Return base64 content for providers whose file input field expects
   encoded file data. Text content is UTF-8 encoded."
  [part]
  (or (file-binary-data part)
      (when-let [text (:file/content part)]
        (text->base64 text))))

(defn file-data-uri-for-input-file
  "Return a data URI for OpenAI file_data fields.
   OpenAI Responses and Chat Completions reject raw base64 here; they expect
   data:<mime>;base64,<payload>."
  [part]
  (when-let [data (file-data-for-input-file part)]
    (str "data:" (file-mime-type part) ";base64," data)))

(defn file-extension [part]
  (let [file-name (file-name part)
        name-extension
        (when (and (string? file-name)
                   (str/includes? file-name "."))
          (some-> file-name
                  (str/split #"\.")
                  last
                  str/lower-case
                  not-empty))]
    (or (:file/format part)
        name-extension
        (case (file-mime-type part)
          "application/pdf" "pdf"
          "text/csv" "csv"
          "text/html" "html"
          "text/plain" "txt"
          "text/markdown" "md"
          "application/msword" "doc"
          "application/vnd.openxmlformats-officedocument.wordprocessingml.document" "docx"
          "application/vnd.ms-excel" "xls"
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" "xlsx"
          "pdf"))))

(defn unsupported-file-part!
  [provider-id part]
  (throw (ex-info "File attachments are not supported by this provider transport"
                  {:provider provider-id
                   :part/type (:part/type part)
                   :file/name (:file/name part)
                   :error/type :provider/unsupported-file-attachment})))

(defn missing-file-source!
  [provider-id part]
  (throw (ex-info "File attachment is missing :file/id, :file/url, :file/data, :file/bytes, or :file/content"
                  {:provider provider-id
                   :file/name (:file/name part)
                   :error/type :request/missing-file-source})))
