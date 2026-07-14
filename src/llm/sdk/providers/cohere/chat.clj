(ns llm.sdk.providers.cohere.chat
  "Cohere /v2/chat native transport adapter.

   Cohere is OpenAI-compat-ish but differs enough to need its own
   adapter: it has a typed message-content array, a documents field,
   a citation_options control, citations on the response, and a
   streaming event taxonomy with separate content-start /
   content-delta / content-end plus tool-plan-delta and citation-*
   events.

  Reference: litellm-ref/llms/cohere/chat/v2_transformation.py."
  (:require [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Finish reason mapping
;; ---------------------------------------------------------------------------

(def ^:private finish-reason-map
  {"COMPLETE" :stop
   "MAX_TOKENS" :length
   "STOP_SEQUENCE" :stop
   "TOOL_CALL" :tool-calls
   "ERROR" :unknown
   "TIMEOUT" :unknown})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- file->cohere-document [part]
  (if-let [content (t/file-text-content part)]
    (cond-> {:id (t/file-name part)
             :data (cond-> {:snippet content}
                     (:file/name part) (assoc :title (:file/name part))
                     (:file/title part) (assoc :title (:file/title part))
                     (:file/context part) (assoc :context (:file/context part))
                     (:file/mime-type part) (assoc :mime_type (:file/mime-type part)))}
      (:file/url part) (assoc-in [:data :url] (:file/url part)))
    (t/unsupported-file-part! :cohere part)))

(defn- file-parts [content]
  (when (sequential? content)
    (filter #(= (:part/type %) :file) content)))

(defn- strip-file-parts [content]
  (if (sequential? content)
    (vec (remove #(= (:part/type %) :file) content))
    content))

(defn- content->cohere
  "Cohere v2 accepts either a plain string or a typed content array."
  [content]
  (cond
    (nil? content) ""
    (string? content) content
    (sequential? content)
    (mapv (fn [part]
            (case (:part/type part)
              :text {:type "text" :text (:text part)}
              :image {:type "image_url"
                      :image_url {:url (:image/url part)}}
              :file (t/unsupported-file-part! :cohere part)
              ;; Surface tool-results inline as text — caller may have
              ;; rolled them into the previous message.
              {:type "text" :text (str part)}))
          content)
    :else (str content)))

(defn- message->cohere [msg]
  (case (:message/role msg)
    :system   {:role "system"    :content (content->cohere (strip-file-parts (:message/content msg)))}
    :user     {:role "user"      :content (content->cohere (strip-file-parts (:message/content msg)))}
    :assistant
    (let [base {:role "assistant"
                :content (content->cohere (strip-file-parts (:message/content msg)))}
          tcs (:message/tool-calls msg)]
      (cond-> base
        (seq tcs) (assoc :tool_calls
                         (mapv (fn [tc]
                                 {:id (:tool-call/id tc)
                                  :type "function"
                                  :function {:name (:tool-call/name tc)
                                             :arguments (:tool-call/arguments tc)}})
                               tcs))))
    :tool {:role "tool"
           :tool_call_id (or (:message/tool-call-id msg) "tool_0")
           :content [{:type "document"
                      :document {:data (t/content->string (:message/content msg))}}]}))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- tool->cohere [tool]
  (let [fn-data (:function tool)]
    {:type "function"
     :function {:name (:name fn-data)
                :description (or (:description fn-data) "")
                :parameters (or (:parameters fn-data) {:type "object"})}}))

(defn- tool-choice->cohere [tc]
  (case tc
    :required "REQUIRED"
    :none "NONE"
    ;; Omitting tool_choice is Cohere's documented automatic mode.
    nil))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- pop-extras [request]
  (or (get-in request [:request/provider-options :cohere]) {}))

(defn- file-documents [messages]
  (mapv file->cohere-document
        (mapcat #(file-parts (:message/content %)) messages)))

(defn- response-format->cohere [format]
  (case (:type format)
    :text {:type "text"}
    :json_object {:type "json_object"}
    :json_schema {:type "json_object"
                  :json_schema (:json-schema format)}
    nil))

(defn- reasoning->cohere [reasoning]
  (when reasoning
    (cond-> {:type (if (false? (:enabled reasoning))
                     "disabled"
                     "enabled")}
      (:budget reasoning) (assoc :token_budget (:budget reasoning)))))

(defn build-request-cohere
  [profile request]
  (let [stream? (boolean (:request/stream? request))
        messages (mapv message->cohere (:request/messages request))
        tools (when (seq (:request/tools request))
                (mapv tool->cohere (:request/tools request)))
        extras (pop-extras request)
        canonical-documents (file-documents (:request/messages request))
        documents (vec (concat (or (:documents extras) [])
                               canonical-documents))
        tool-choice (tool-choice->cohere (:request/tool-choice request))
        response-format (response-format->cohere
                         (:request/response-format request))
        reasoning (reasoning->cohere (:request/reasoning request))
        strict-tools? (or (:strict_tools extras)
                          (some #(true? (get-in % [:function :strict]))
                                (:request/tools request)))
        provider-fields (select-keys
                         extras
                         [:citation_options :safety_mode :seed
                          :frequency_penalty :presence_penalty :k
                          :logprobs :priority])
        body (cond-> (merge {:model (:request/model request)
                             :messages messages
                             :stream stream?}
                            provider-fields)
               tools (assoc :tools tools)
               strict-tools? (assoc :strict_tools true)
               tool-choice (assoc :tool_choice tool-choice)
               (:request/temperature request)
               (assoc :temperature (:request/temperature request))
               (:request/top-p request) (assoc :p (:request/top-p request))
               (:request/max-tokens request)
               (assoc :max_tokens (:request/max-tokens request))
               (:request/stop request)
               (assoc :stop_sequences (t/stop-sequences (:request/stop request)))
               (seq documents) (assoc :documents documents)
               response-format (assoc :response_format response-format)
               reasoning (assoc :thinking reasoning))
        body (if-let [extra-body (:extra_body extras)]
               (merge body extra-body)
               body)
        chat-url (or (:profile/chat-url profile)
                     "https://api.cohere.com/v2/chat")]
    {:method :post
     :url chat-url
     :headers (provider/default-headers
               profile
               (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- usage->canonical [u]
  (when u
    (let [b (:billed_units u)
          tokens (:tokens u)
          input (long (or (:input_tokens tokens)
                          (:input_tokens b)
                          0))
          output (long (or (:output_tokens tokens)
                           (:output_tokens b)
                           0))]
      (cond-> {:usage/input-tokens input
               :usage/output-tokens output
               :usage/total-tokens (+ input output)
               :usage/request-count 1
               :usage/provider-raw u}
        (:cached_tokens u)
        (assoc :usage/cached-input-tokens (long (:cached_tokens u)))))))

(defn- citation->part [c]
  (let [source (first (:sources c))
        document (:document source)
        url (or (:url c) (:url source) (:url document))
        title (or (:title c) (:title source) (:title document))
        source-id (or (:id source) (:id c))]
    (cond-> {:part/type :citation}
      url (assoc :citation/url url)
      title (assoc :citation/title title)
      (:text c) (assoc :citation/snippet (:text c))
      source-id (assoc :citation/source-id source-id)
      (and (int? (:start c)) (int? (:end c)))
      (assoc :citation/text-range [(:start c) (:end c)]))))

(defn parse-response-cohere
  [_profile raw]
  (let [msg (:message raw)
        content (:content msg)
        text-parts (mapv (fn [p] {:part/type :text :text (:text p)})
                         (filter #(= "text" (:type %)) content))
        reasoning-parts
        (mapv (fn [p] {:part/type :reasoning
                       :reasoning/text (:thinking p)})
              (filter #(= "thinking" (:type %)) content))
        tool-calls (vec
                    (mapv (fn [tc]
                            {:part/type :tool-call
                             :tool-call/id (:id tc)
                             :tool-call/name (get-in tc [:function :name])
                             :tool-call/arguments (get-in tc [:function :arguments])})
                          (:tool_calls msg)))
        citations (mapv citation->part (:citations msg))
        finish (or (get finish-reason-map (:finish_reason raw)) :stop)]
    (cond-> {:response/id (:id raw)
             :response/provider :cohere
             :response/model (:model raw)
             :response/parts (into [] (concat text-parts reasoning-parts
                                              tool-calls citations))
             :response/finish-reason finish
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      (:usage raw) (assoc :response/usage (usage->canonical (:usage raw))))))

;; ---------------------------------------------------------------------------
;; Streaming
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- citation-event-from [c]
  (let [url (or (:url c) (some :url (:sources c)))]
    (when url
      (stream/citation-event url
                             :title (:title c)
                             :snippet (:text c)))))

(defn parse-stream-event-cohere
  [_profile line]
  (when-let [data (parse-sse-line line)]
    (case (:type data)
      "message-start" nil
      "content-start" nil
      "content-delta"
      (let [content (get-in data [:delta :message :content])]
        (case (:type content)
          "thinking" (when-let [text (:thinking content)]
                       (stream/reasoning-delta text))
          (when-let [text (:text content)]
            (stream/content-delta text))))
      "content-end" nil

      "tool-plan-delta"
      (when-let [text (get-in data [:delta :message :tool_plan])]
        (stream/reasoning-delta text))

      "tool-call-start"
      (let [idx (or (:index data) 0)
            tc (get-in data [:delta :message :tool_calls])]
        (stream/tool-call-start idx (:id tc) (get-in tc [:function :name])))

      "tool-call-delta"
      (let [idx (or (:index data) 0)
            args (get-in data [:delta :message :tool_calls :function :arguments])]
        (stream/tool-call-delta idx (or args "")))

      "tool-call-end"
      (stream/tool-call-end (or (:index data) 0))

      "citation-start"
      (when-let [c (get-in data [:delta :message :citations])]
        (citation-event-from c))

      "citation-end" nil

      "message-end"
      (let [events []
            usage (usage->canonical (get-in data [:delta :usage]))
            events (cond-> events
                     usage (conj (stream/usage-event usage)))
            fr (get finish-reason-map
                    (get-in data [:delta :finish_reason]) :stop)
            events (conj events (stream/end-event :finish-reason fr))]
        events)

      nil)))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-cohere
  [_profile status body]
  (errors/classify-api-error :cohere "Cohere" status body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord CohereChatTransport []
  t/Transport
  (build-request [_ profile request] (build-request-cohere profile request))
  (parse-response [_ profile raw] (parse-response-cohere profile raw))
  (parse-stream-event [_ profile line] (parse-stream-event-cohere profile line))
  (parse-error [_ profile status body] (parse-error-cohere profile status body))
  (normalize-usage [_ _ raw] (usage->canonical raw))
  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :citations
      :file-attachments}))

(defn make-transport [] (->CohereChatTransport))

;; Augment the existing :cohere profile with native v2 chat support while
;; preserving the embedding and rerank constructors installed by their
;; provider namespaces.
(let [existing (provider/get-provider :cohere)
      base (merge existing
                  {:profile/protocol-family :cohere
                   :profile/chat-url "https://api.cohere.com/v2/chat"
                   :profile/capabilities
                   (into #{:chat :streaming :tools :json-schema :reasoning
                           :citations :file-attachments}
                         (:profile/capabilities existing #{}))
                   :profile/transport-constructor make-transport})]
  (provider/register-provider base))
