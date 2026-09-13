(ns llm.sdk.providers.cohere.chat
  "Cohere /v2/chat native transport adapter.

   Cohere is OpenAI-compat-ish but differs enough to need its own
   adapter: it has a typed message-content array, a documents field,
   a citation_options control, citations on the response, and a
   streaming event taxonomy with separate content-start /
   content-delta / content-end plus tool-plan-delta and citation-*
   events.

  Reference: litellm-ref/llms/cohere/chat/v2_transformation.py."
  (:require [clojure.string :as str]
            [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
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

(defn- unsupported-content-part! [part]
  (throw (ex-info
          (str "Cohere does not support canonical content part "
               (pr-str (:part/type part)) ".")
          {:provider :cohere
           :part/type (:part/type part)
           :error/type :provider/unsupported-content-part})))

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
                      :image_url
                      {:url (t/image-url-or-data-uri! :cohere part)}}
              :reasoning {:type "thinking"
                          :thinking (:reasoning/text part)}
              :file (t/unsupported-file-part! :cohere part)
              (unsupported-content-part! part)))
          content)
    :else
    (unsupported-content-part! content)))

(defn- canonical-tool-call->cohere [tc]
  (let [native (:tool-call/provider-data tc)
        base (if (map? native) native {})]
    (-> base
        (assoc :id (:tool-call/id tc)
               :type "function")
        (assoc :function
               (merge (:function base)
                      {:name (:tool-call/name tc)
                       :arguments (:tool-call/arguments tc)})))))

(defn- same-tool-call? [a b]
  (= (select-keys a [:tool-call/id :tool-call/name :tool-call/arguments])
     (select-keys b [:tool-call/id :tool-call/name :tool-call/arguments])))

(defn- assistant-tool-calls [msg]
  (let [content-calls (or (t/extract-tool-calls-from-parts
                           (:message/content msg))
                          [])
        field-calls (or (:message/tool-calls msg) [])]
    (reduce (fn [calls tc]
              (if (some #(same-tool-call? % tc) calls)
                calls
                (conj calls tc)))
            []
            (concat content-calls field-calls))))

(defn- assistant-content [content]
  (content->cohere
   (if (sequential? content)
     (vec (remove #(#{:file :tool-call} (:part/type %)) content))
     content)))

(defn- message->cohere [msg]
  (case (:message/role msg)
    (:system :developer)
    {:role "system"
     :content (content->cohere (strip-file-parts (:message/content msg)))}

    :user
    {:role "user"
     :content (content->cohere (strip-file-parts (:message/content msg)))}

    :assistant
    (let [base {:role "assistant"
                :content (assistant-content (:message/content msg))}
          tcs (assistant-tool-calls msg)]
      (cond-> base
        (seq tcs) (assoc :tool_calls
                         (mapv canonical-tool-call->cohere tcs))))

    :tool
    (let [result (->> (t/extract-tool-result :cohere msg)
                      (t/reject-error-tool-result! :cohere))]
      {:role "tool"
       :tool_call_id (or (:tool-result/id result) "tool_0")
       :content [{:type "document"
                  :document {:data (:tool-result/content result)}}]})

    (throw (ex-info
            (str "Cohere does not support message role "
                 (pr-str (:message/role msg)) ".")
            {:provider :cohere
             :message/role (:message/role msg)
             :error/type :provider/unsupported-message-role}))))

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
    :auto nil
    :required "REQUIRED"
    :none "NONE"
    (when (some? tc)
      (throw (ex-info "Cohere cannot force a specific function tool"
                      {:provider :cohere
                       :tool-choice tc
                       :error/type :provider/unsupported-tool-choice})))))

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

(defn- chat-url [profile]
  (let [base (-> (or (:profile/base-url profile)
                     "https://api.cohere.com")
                 (str/replace #"/+$" ""))]
    (cond
      (str/ends-with? base "/v2/chat") base
      (str/ends-with? base "/v1")
      (str (subs base 0 (- (count base) 3)) "/v2/chat")
      (str/ends-with? base "/v2") (str base "/chat")
      :else (str base "/v2/chat"))))

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
        body (t/merge-extra-body (:profile/id profile)
                                 body
                                 (:extra_body extras)
                                 #{:model :messages :stream})
        chat-url (chat-url profile)]
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
    (let [billed (:billed_units u)
          tokens (:tokens u)
          input (or (usage/->int (:input_tokens tokens))
                    (usage/->int (:input_tokens billed)))
          output (or (usage/->int (:output_tokens tokens))
                     (usage/->int (:output_tokens billed)))
          total (when (and (some? input) (some? output))
                  (+ input output))
          cached (usage/->int (:cached_tokens u))]
      (cond-> {:usage/request-count 1
               :usage/provider-raw u}
        (some? input) (assoc :usage/input-tokens input)
        (some? output) (assoc :usage/output-tokens output)
        (some? total) (assoc :usage/total-tokens total)
        (some? cached) (assoc :usage/cached-input-tokens cached)))))

(defn- citation->part [c]
  (let [source (first (:sources c))
        document (:document source)
        url (or (:url c) (:url source) (:url document))
        title (or (:title c) (:title source) (:title document))
        source-id (or (:id source) (:id document) (:id c))]
    (cond-> {:part/type :citation
             :citation/provider-data c}
      url (assoc :citation/url url)
      title (assoc :citation/title title)
      (:text c) (assoc :citation/snippet (:text c))
      source-id (assoc :citation/source-id source-id)
      (and (integer? (:start c)) (integer? (:end c)))
      (assoc :citation/text-range [(:start c) (:end c)]))))

(defn parse-response-cohere
  [_profile raw]
  (let [msg (:message raw)
        content (:content msg)
        content-parts
        (into []
              (keep (fn [part]
                      (case (:type part)
                        "text" {:part/type :text :text (:text part)}
                        "thinking" {:part/type :reasoning
                                    :reasoning/text (:thinking part)}
                        nil)))
              content)
        tool-calls (vec
                    (mapv (fn [tc]
                            {:part/type :tool-call
                             :tool-call/id (:id tc)
                             :tool-call/name (get-in tc [:function :name])
                             :tool-call/arguments (get-in tc [:function :arguments])
                             :tool-call/provider-data tc})
                          (:tool_calls msg)))
        citations (mapv citation->part (:citations msg))
        finish (or (get finish-reason-map (:finish_reason raw)) :stop)]
    (cond-> {:response/id (:id raw)
             :response/provider :cohere
             :response/model (:model raw)
             :response/parts (into content-parts (concat tool-calls citations))
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
  (let [source (first (:sources c))
        document (:document source)
        url (or (:url c) (:url source) (:url document))
        title (or (:title c) (:title source) (:title document))
        source-id (or (:id source) (:id document) (:id c))
        text-range (when (and (integer? (:start c)) (integer? (:end c)))
                     [(:start c) (:end c)])]
    (stream/citation-event url
                           :title title
                           :snippet (:text c)
                           :text-range text-range
                           :source-id source-id
                           :provider-data c)))

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
                       (stream/reasoning-delta text :index (:index data)))
          (when-let [text (:text content)]
            (stream/content-delta text))))
      "content-end" nil

      "tool-plan-delta"
      (when-let [text (get-in data [:delta :message :tool_plan])]
        (stream/reasoning-delta text))

      "tool-call-start"
      (let [idx (or (:index data) 0)
            tc (get-in data [:delta :message :tool_calls])]
        (stream/tool-call-start idx (:id tc) (get-in tc [:function :name])
                                :provider-data tc))

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
      :file-attachments :multimodal}))

(defn make-transport [] (->CohereChatTransport))

