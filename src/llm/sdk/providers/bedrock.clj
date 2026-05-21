(ns llm.sdk.providers.bedrock
  "AWS Bedrock Converse API transport adapter.
   Uses AWS Signature V4 auth via hato's built-in support.
   Supports guardrails and cross-region inference."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Finish reason mapping
;; ---------------------------------------------------------------------------

(def ^:private stop-reason-map
  {"end_turn" :stop
   "tool_use" :tool-calls
   "max_tokens" :length
   "stop_sequence" :stop
   "guardrail_intervened" :content-filter
   "content_filtered" :content-filter})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- content->bedrock [content]
  (cond
    (string? content)
    [{:text content}]
    (sequential? content)
    (mapv (fn [part]
            (case (:part/type part)
              :text {:text (:text part)}
              :image {:image {:format (case (get part :image/detail :auto)
                                       (:auto :low) "jpeg"
                                       "png")
                              :source {:bytes (:image/url part)}}}
              {:text (str part)}))
          content)
    :else [{:text (str content)}]))

(defn- message->bedrock [msg tool-name-by-id]
  (let [role (case (:message/role msg)
               :user "user"
               :assistant "assistant"
               "user")]
    (cond
      (= (:message/role msg) :tool)
      {:role "user"
       :content [{:toolResult
                  {:toolUseId (or (:message/tool-call-id msg) "tool_0")
                   :content [{:text (t/content->string (:message/content msg))}]}}]}

      (seq (:message/tool-calls msg))
      {:role "assistant"
       :content (mapv (fn [tc]
                        {:toolUse
                         {:toolUseId (:tool-call/id tc)
                          :name (:tool-call/name tc)
                          :input (try (json/parse-string (:tool-call/arguments tc))
                                      (catch Exception _ {}))}})
                      (:message/tool-calls msg))}

      :else
      {:role role :content (content->bedrock (:message/content msg))})))

(defn- build-messages [messages]
  (let [tool-name-by-id (into {}
                              (mapcat (fn [msg]
                                        (when (seq (:message/tool-calls msg))
                                          (map #(vector (:tool-call/id %) (:tool-call/name %))
                                               (:message/tool-calls msg))))
                                      messages))]
    (mapv #(message->bedrock % tool-name-by-id) messages)))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- tool->bedrock [tool]
  (let [fn-data (:function tool)]
    {:toolSpec
     {:name (:name fn-data)
      :description (or (:description fn-data) "")
      :inputSchema {:json (or (:parameters fn-data) {:type "object"})}}}))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-bedrock
  [profile request]
  (let [model (:request/model request)
        region (or (System/getenv "AWS_REGION") "us-east-1")
        messages (remove #(= (:message/role %) :system) (:request/messages request))
        system-texts (keep #(when (= (:message/role %) :system)
                              (t/content->string (:message/content %)))
                           (:request/messages request))
        body (merge
              {:modelId model
               :messages (build-messages messages)}
              (when (seq system-texts)
                {:system (mapv #(hash-map :text %) system-texts)})
              (when (seq (:request/tools request))
                {:toolConfig
                 {:tools (mapv tool->bedrock (:request/tools request))}})
              (when (:request/temperature request)
                {:inferenceConfig {:temperature (:request/temperature request)}})
              (when (:request/max-tokens request)
                {:inferenceConfig {:maxTokens (:request/max-tokens request)}})
              (when (:request/top-p request)
                {:inferenceConfig {:topP (:request/top-p request)}}))]
    {:method :post
     :url (str "https://bedrock-runtime." region ".amazonaws.com/model/" model "/converse")
     :headers (merge {"Content-Type" "application/json"
                      "Accept" "application/json"}
                     (provider/default-headers profile
                                               (provider/resolve-auth-token profile)))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn parse-response-bedrock
  [profile raw]
  (let [output (:output raw)
        msg (:message output)
        content (:content msg)
        text-parts (keep #(when (:text %) (:text %)) content)
        tool-calls (vec (keep #(when (:toolUse %)
                            (let [tu (:toolUse %)]
                              {:part/type :tool-call
                               :tool-call/id (:toolUseId tu)
                               :tool-call/name (:name tu)
                               :tool-call/arguments (json/generate-string (:input tu))}))
                          content))
        stop-reason (get stop-reason-map (:stopReason raw) :stop)
        usage-raw (:usage raw)]
    {:response/provider :bedrock
     :response/model (:modelId raw)
     :response/parts (into []
                           (concat
                            (map #(hash-map :part/type :text :text %) text-parts)
                            tool-calls))
     :response/tool-calls (not-empty tool-calls)
     :response/finish-reason stop-reason
     :response/usage (when usage-raw
                       {:usage/input-tokens (:inputTokens usage-raw 0)
                        :usage/output-tokens (:outputTokens usage-raw 0)
                        :usage/total-tokens (:totalTokens usage-raw 0)
                        :usage/request-count 1
                        :usage/provider-raw usage-raw})
     :response/raw raw}))

;; ---------------------------------------------------------------------------
;; Stream parsing (Bedrock streaming is event-stream with JSON lines)
;; ---------------------------------------------------------------------------

(defn- parse-event-line [line]
  (try (json/parse-string line true)
       (catch Exception _ nil)))

(defn parse-stream-event-bedrock
  [profile line]
  (when-let [data (parse-event-line line)]
    (let [t (:type data)]
      (cond
        (= t "contentBlockDelta")
        (let [delta (:delta (:contentBlockDelta data))]
          (when (:text delta)
            (stream/content-delta (:text delta))))

        (= t "contentBlockStart")
        (when (= (get-in data [:contentBlockStart :contentBlock :type]) "toolUse")
          (let [block (get-in data [:contentBlockStart :contentBlock])]
            (stream/tool-call-start 0 (:toolUseId block) (:name block))))

        (= t "metadata")
        (when-let [u (get-in data [:metadata :usage])]
          (stream/usage-event {:usage/input-tokens (:inputTokens u 0)
                               :usage/output-tokens (:outputTokens u 0)
                               :usage/total-tokens (:totalTokens u 0)
                               :usage/request-count 1}))

        (= t "messageStop")
        (stream/end-event :finish-reason (get stop-reason-map
                                               (get-in data [:messageStop :stopReason])
                                               :stop))

        :else nil))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-bedrock
  [profile status body]
  (errors/classify-error (Exception. "Bedrock API error")
                         :status status
                         :body body
                         :provider :bedrock))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord BedrockTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-bedrock profile request))

  (parse-response [this profile raw]
    (parse-response-bedrock profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-bedrock profile line))

  (parse-error [this profile status body]
    (parse-error-bedrock profile status body))

  (normalize-usage [this profile raw]
    {:usage/input-tokens (:inputTokens raw 0)
     :usage/output-tokens (:outputTokens raw 0)
     :usage/total-tokens (:totalTokens raw 0)
     :usage/request-count 1
     :usage/provider-raw raw})

  (request-capabilities [_]
    #{:chat :streaming :tools :guardrails}))

(defn make-transport []
  (->BedrockTransport))

;; Register
(provider/register-provider
 {:profile/id :bedrock
  :profile/protocol-family :bedrock
  :profile/base-url "https://bedrock-runtime.us-east-1.amazonaws.com"
  :profile/auth-strategy :aws-sigv4
  :profile/supports-model-listing true
  :profile/capabilities #{:chat :streaming :tools :guardrails}
  :profile/env-var-names ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_REGION"]
  :profile/transport-constructor make-transport})
