(ns llm.sdk.providers.cohere-chat
  "Cohere /v2/chat native transport adapter.

   Cohere is OpenAI-compat-ish but differs enough to need its own
   adapter: it has a typed message-content array, a documents field,
   a citation_options control, citations on the response, and a
   streaming event taxonomy with separate content-start /
   content-delta / content-end plus tool-plan-delta and citation-*
   events.

   Reference: litellm-ref/llms/cohere/chat/v2_transformation.py."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
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
   "ERROR" :unknown})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

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
              ;; Surface tool-results inline as text — caller may have
              ;; rolled them into the previous message.
              {:type "text" :text (str part)}))
          content)
    :else (str content)))

(defn- message->cohere [msg]
  (case (:message/role msg)
    :system   {:role "system"    :content (content->cohere (:message/content msg))}
    :user     {:role "user"      :content (content->cohere (:message/content msg))}
    :assistant
    (let [base {:role "assistant"
                :content (content->cohere (:message/content msg))}
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
  (cond
    (= tc :auto) "AUTO"
    (= tc :required) "REQUIRED"
    (= tc :none) "NONE"
    :else nil))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- pop-extras [request]
  (or (get-in request [:request/provider-options :cohere]) {}))

(defn build-request-cohere
  [profile request]
  (let [stream? (boolean (:request/stream? request))
        messages (mapv message->cohere (:request/messages request))
        tools (when (seq (:request/tools request))
                (mapv tool->cohere (:request/tools request)))
        extras (pop-extras request)
        body (cond-> {:model (:request/model request)
                      :messages messages
                      :stream stream?}
               tools (assoc :tools tools)
               (:request/tool-choice request)
               (assoc :tool_choice (tool-choice->cohere (:request/tool-choice request)))
               (:request/temperature request)
               (assoc :temperature (:request/temperature request))
               (:request/top-p request) (assoc :p (:request/top-p request))
               (:request/max-tokens request)
               (assoc :max_tokens (:request/max-tokens request))
               (:request/stop request) (assoc :stop_sequences (vec (:request/stop request)))
               (:documents extras) (assoc :documents (:documents extras))
               (:citation_options extras) (assoc :citation_options (:citation_options extras))
               (:safety_mode extras) (assoc :safety_mode (:safety_mode extras)))
        chat-url (or (:profile/chat-url profile) "https://api.cohere.com/v2/chat")]
    {:method :post
     :url chat-url
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- usage->canonical [u]
  (when u
    (let [b (:billed_units u)
          ;; Prefer billed_units; fall back to tokens.
          input (or (:input_tokens b) (get-in u [:tokens :input_tokens]) 0)
          output (or (:output_tokens b) (get-in u [:tokens :output_tokens]) 0)]
      {:usage/input-tokens input
       :usage/output-tokens output
       :usage/total-tokens (+ input output)
       :usage/request-count 1
       :usage/provider-raw u})))

(defn- citation->part [c]
  (cond-> {:part/type :citation}
    (or (:url c)
        (some :url (:sources c)))
    (assoc :citation/url (or (:url c) (some :url (:sources c))))
    (:title c) (assoc :citation/title (:title c))
    (:text c) (assoc :citation/snippet (:text c))
    (and (int? (:start c)) (int? (:end c)))
    (assoc :citation/text-range [(:start c) (:end c)])))

(defn parse-response-cohere
  [_profile raw]
  (let [msg (:message raw)
        content (:content msg)
        text-parts (mapv (fn [p] {:part/type :text :text (:text p)})
                         (filter #(= "text" (:type %)) content))
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
             :response/parts (into [] (concat text-parts tool-calls citations))
             :response/finish-reason finish
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      (:usage raw) (assoc :response/usage (usage->canonical (:usage raw))))))

;; ---------------------------------------------------------------------------
;; Streaming
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (or (= payload "[DONE]") (str/blank? payload))
        (try (json/parse-string payload true)
             (catch Exception _ nil))))))

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
      (when-let [text (get-in data [:delta :message :content :text])]
        (stream/content-delta text))
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
  (errors/classify-error (Exception. "Cohere API error")
                         :status status
                         :body body
                         :provider :cohere))

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
  (request-capabilities [_] #{:chat :streaming :tools :citations}))

(defn make-transport [] (->CohereChatTransport))

;; Augment the existing :cohere profile (registered upstream by
;; provider.clj for embed + rerank) with chat support. We keep its
;; auth + env-var + /v1 base-url (which embed and rerank rely on) and
;; pin the chat path to /v2/chat via :profile/chat-url so /v2 lives
;; alongside /v1/embed and /v1/rerank under the same profile.
(let [existing (provider/get-provider :cohere)
      base (merge existing
                  {:profile/protocol-family :cohere
                   :profile/chat-url "https://api.cohere.com/v2/chat"
                   :profile/capabilities (into #{:chat :streaming :tools :citations}
                                                (:profile/capabilities existing #{}))
                   :profile/transport-constructor make-transport})]
  (provider/register-provider base))
