(ns llm.sdk.providers.openrouter.chat
  "OpenRouter chat-completions transport.
   OpenRouter extends the OpenAI wire shape with top-level provider routing,
   plugins, reasoning, session routing, and detailed usage."
  (:require [clojure.string :as str]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai.chat :as openai]
            [llm.sdk.providers.openrouter.embeddings]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; OpenRouter top-level request extensions
;; ---------------------------------------------------------------------------

(defn- build-openrouter-fields
  "Build fields that OpenRouter accepts at the top level of ChatRequest.
   `extra_body` is an OpenAI-client escape hatch, not an OpenRouter wire key,
   so caller-supplied entries are flattened before the request is sent."
  [profile request generic-extra]
  (let [model (:request/model request)
        reasoning (:request/reasoning request)
        provider-opts (:request/provider-options request)
        prefs (:provider provider-opts)
        pareto-score (get-in provider-opts [:pareto :min-coding-score])
        plugins (when (and (get-in profile [:profile/quirks :pareto-router])
                           (str/includes? model "pareto-code")
                           (some? pareto-score))
                  [{:id "pareto-router"
                    :min_coding_score (double pareto-score)}])
        reasoning-wire (when reasoning
                         (cond-> {}
                           (contains? reasoning :enabled)
                           (assoc :enabled (:enabled reasoning))
                           (:effort reasoning)
                           (assoc :effort (name (:effort reasoning)))
                           (:budget reasoning)
                           (assoc :max_tokens (:budget reasoning))
                           (contains? reasoning :exclude)
                           (assoc :exclude (:exclude reasoning))
                           (:summary reasoning)
                           (assoc :summary
                                  (if (keyword? (:summary reasoning))
                                    (name (:summary reasoning))
                                    (:summary reasoning)))))
        session-id (when (cache/cache-enabled? request)
                     (cache/scope-id request))]
    (merge {}
           generic-extra
           (when prefs {:provider prefs})
           (when plugins {:plugins plugins})
           (when (seq reasoning-wire) {:reasoning reasoning-wire})
           (when session-id {:session_id session-id})
           (when (:request/metadata request)
             {:metadata (:request/metadata request)})
           (:extra_body provider-opts))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-openrouter
  "Build an OpenRouter Chat Completions request.
   The shared OpenAI adapter supplies the compatible core fields; OpenRouter
   extensions are flattened into the top-level request object."
  [profile request]
  (let [base-req (openai/build-request-openai profile request)
        base-body (:body base-req)
        openrouter-fields (build-openrouter-fields
                           profile request (:extra_body base-body))
        body (merge (dissoc base-body :extra_body) openrouter-fields)
        body (cond-> body
               (:max_tokens body)
               (assoc :max_completion_tokens (:max_tokens body))
               (:max_tokens body)
               (dissoc :max_tokens)
               (:request/stream? request)
               (assoc :stream true))
        metadata-level (get-in request
                               [:request/provider-options :metadata-level])
        headers (cond->
                 (merge
                  (:headers base-req)
                  {"HTTP-Referer"
                   (or (System/getenv "OPENROUTER_HTTP_REFERER")
                       "https://github.com/DeadMeme5441/clojure-llm-sdk")
                   "X-OpenRouter-Title"
                   (or (System/getenv "OPENROUTER_APP_NAME")
                       "clojure-llm-sdk")})
                  (some? metadata-level)
                  (assoc "X-OpenRouter-Metadata"
                         (if (keyword? metadata-level)
                           (name metadata-level)
                           (str metadata-level))))]
    (assoc base-req :body body :headers headers)))

;; ---------------------------------------------------------------------------
;; Response parsing — delegate to OpenAI Chat
;; ---------------------------------------------------------------------------

(defn- reported-cost [usage-raw]
  (when (number? (:cost usage-raw))
    {:cost/usd (:cost usage-raw)
     :cost/estimated? false
     :cost/pricing-source :openrouter-reported
     :cost/source-url
     "https://openrouter.ai/docs/cookbook/administration/usage-accounting"
     :cost/breakdown
     (select-keys usage-raw
                  [:cost :cost_details :is_byok :server_tool_use])}))

(defn parse-response-openrouter
  [profile raw]
  (let [base (openai/parse-response-openai profile raw)
        provider-data (merge
                       (:response/provider-data base)
                       (select-keys raw
                                    [:openrouter_metadata :service_tier])
                       (when-let [native-finish
                                  (get-in raw
                                          [:choices 0 :native_finish_reason])]
                         {:native_finish_reason native-finish}))
        actual-cost (reported-cost (:usage raw))]
    (cond-> base
      (seq provider-data) (assoc :response/provider-data provider-data)
      actual-cost (assoc :response/cost actual-cost))))

;; ---------------------------------------------------------------------------
;; Stream parsing — delegate to OpenAI Chat
;; ---------------------------------------------------------------------------

(defn- attach-stream-cost [event]
  (if (= :stream/usage (:event/type event))
    (if-let [cost (reported-cost (get-in event [:usage :usage/provider-raw]))]
      (assoc event :cost cost)
      event)
    event))

(defn parse-stream-event-openrouter
  [profile line]
  (let [parsed (openai/parse-stream-event-openai profile line)]
    (if (sequential? parsed)
      (mapv attach-stream-cost parsed)
      (some-> parsed attach-stream-cost))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-openrouter
  [_profile status body]
  (errors/classify-error (Exception. "OpenRouter API error")
                         :status status
                         :body body
                         :provider :openrouter))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenRouterTransport []
  t/Transport
  (build-request [_this profile request]
    (build-request-openrouter profile request))

  (parse-response [_this profile raw]
    (parse-response-openrouter profile raw))

  (parse-stream-event [_this profile line]
    (parse-stream-event-openrouter profile line))

  (parse-error [_this profile status body]
    (parse-error-openrouter profile status body))

  (normalize-usage [_this _profile raw]
    (usage/normalize-usage :openrouter raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :provider-routing}))

(defn make-transport []
  (->OpenRouterTransport))

;; Register
(when-let [p (provider/get-provider :openrouter)]
  (provider/register-provider
   (assoc p :profile/transport-constructor make-transport)))
