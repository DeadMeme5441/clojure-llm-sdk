(ns llm.sdk.providers.zai.chat
  "Native Z.AI chat-completions transport over the shared OpenAI wire codec."
  (:require [cheshire.core :as json]
            [llm.sdk.errors :as errors]
            [llm.sdk.providers.openai.chat :as openai]
            [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.usage :as usage]))

(def ^:private supported-native-options
  #{:do-sample :tool-stream :request-id :user-id})

(defn- unsupported-option! [option message]
  (throw (ex-info message
                  {:provider :zai
                   :option option
                   :accepted-options supported-native-options
                   :error/type :provider/unsupported-option})))

(defn- native-options [request]
  (let [all (or (:request/provider-options request) {})]
    (when-not (map? all)
      (unsupported-option! :request/provider-options
                           "Z.AI provider options must be a map"))
    (doseq [option (keys all)
            :when (not= :zai option)]
      (unsupported-option!
       option
       "Z.AI options belong under :request/provider-options :zai"))
    (let [options (or (:zai all) {})]
      (when-not (map? options)
        (unsupported-option! :zai "Z.AI :zai options must be a map"))
      (doseq [option (keys options)
              :when (not (contains? supported-native-options option))]
        (unsupported-option! option "Unsupported Z.AI chat option"))
      options)))

(defn- native-options->wire [options]
  (cond-> {}
    (contains? options :do-sample)
    (assoc :do_sample (:do-sample options))
    (contains? options :tool-stream)
    (assoc :tool_stream (:tool-stream options))
    (contains? options :request-id)
    (assoc :request_id (:request-id options))
    (contains? options :user-id)
    (assoc :user_id (:user-id options))))

(defn- validate-tool-choice! [request]
  (when-let [choice (:request/tool-choice request)]
    (when-not (= :auto choice)
      (throw (ex-info "Z.AI function tools only support automatic tool choice"
                      {:provider :zai
                       :tool-choice choice
                       :supported-tool-choices #{:auto}
                       :error/type :provider/unsupported-tool-choice})))))

(defn- validate-response-format! [request]
  (when (= :json_schema (get-in request [:request/response-format :type]))
    (throw (ex-info "Z.AI chat completions do not support JSON Schema response format"
                    {:provider :zai
                     :response-format :json_schema
                     :error/type :provider/unsupported-response-format}))))

(defn- reasoning->wire [reasoning]
  (when reasoning
    (let [effort (:effort reasoning)
          enabled? (and (get reasoning :enabled true)
                        (not= :none effort))]
      (merge {:thinking {:type (if enabled? "enabled" "disabled")}}
             (when (and enabled? effort)
               {:reasoning_effort (name effort)})))))

(defn- replaying-reasoning? [body]
  (some (fn [message]
          (and (= "assistant" (:role message))
               (contains? message :reasoning_content)))
        (:messages body)))

(defn- normalize-stop [body]
  (if (string? (:stop body))
    (update body :stop vector)
    body))

(defn- strip-unsupported-tool-fields [body]
  (if (:tools body)
    (update body :tools
            (fn [tools]
              (mapv #(update % :function dissoc :strict) tools)))
    body))

(defn build-request-zai [profile request]
  (validate-tool-choice! request)
  (validate-response-format! request)
  (let [extra-body (merge (native-options->wire (native-options request))
                          (reasoning->wire (:request/reasoning request)))
        shared-request (-> request
                           (dissoc :request/reasoning :request/metadata)
                           (assoc :request/provider-options
                                  {:extra_body extra-body}))
        base (openai/build-request-openai profile shared-request)
        body (-> (:body base)
                 normalize-stop
                 strip-unsupported-tool-fields
                 (dissoc :prompt_cache_key "prompt_cache_key"))
        body (if (replaying-reasoning? body)
               (update body :thinking
                       #(assoc (or % {}) :clear_thinking false))
               body)]
    (assoc base :body body)))

(defn- stringify-tool-arguments [tool-call]
  (let [arguments (get-in tool-call [:function :arguments])]
    (if (map? arguments)
      (assoc-in tool-call [:function :arguments]
                (json/generate-string arguments))
      tool-call)))

(defn- normalize-response-tool-arguments [raw]
  (update raw :choices
          (fn [choices]
            (mapv (fn [choice]
                    (update-in choice [:message :tool_calls]
                               (fn [calls]
                                 (when calls
                                   (mapv stringify-tool-arguments calls)))))
                  choices))))

(defn- canonical-finish-reason [wire-reason]
  (case wire-reason
    "sensitive" :content-filter
    ("model_context_window_exceeded" "network_error") :incomplete
    nil))

(defn parse-response-zai [profile raw]
  (let [normalized-raw (normalize-response-tool-arguments raw)
        response (openai/parse-response-openai profile normalized-raw)
        finish-reason (canonical-finish-reason
                       (get-in raw [:choices 0 :finish_reason]))]
    (cond-> (assoc response :response/raw raw)
      finish-reason (assoc :response/finish-reason finish-reason))))

(defn- normalize-stream-finish [event finish-reason]
  (if (and finish-reason (= :stream/end (:event/type event)))
    (assoc event :event/finish-reason finish-reason)
    event))

(defn parse-stream-event-zai [profile line]
  (let [parsed (openai/parse-stream-event-openai profile line)
        finish-reason (some-> (sse/parse-json-data line)
                              (get-in [:choices 0 :finish_reason])
                              canonical-finish-reason)]
    (if (sequential? parsed)
      (mapv #(normalize-stream-finish % finish-reason) parsed)
      (some-> parsed (normalize-stream-finish finish-reason)))))

(defrecord ZAIChatTransport []
  t/Transport
  (build-request [_ profile request]
    (build-request-zai profile request))
  (parse-response [_ profile raw]
    (parse-response-zai profile raw))
  (parse-stream-event [_ profile line]
    (parse-stream-event-zai profile line))
  (parse-error [_ _ status body]
    (errors/classify-api-error :zai "Z.AI" status body))
  (normalize-usage [_ _ raw]
    (usage/normalize-openai-usage raw))
  (request-capabilities [_]
    #{:chat :streaming :tools :reasoning :multimodal}))

(defn make-transport []
  (->ZAIChatTransport))

