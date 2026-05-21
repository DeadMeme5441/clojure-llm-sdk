(ns llm.sdk.providers.openrouter
  "OpenRouter transport adapter.
   Builds on OpenAI Chat Completions with OpenRouter-specific quirks:
   - provider preferences routing in extra_body
   - Pareto Code router plugin
   - reasoning config in extra_body (not top-level)
   - special model naming and error handling."
  (:require [clojure.string :as str]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai-chat :as openai]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; OpenRouter-specific extra_body assembly
;; ---------------------------------------------------------------------------

(defn- build-openrouter-extra-body
  "Build OpenRouter-specific extra_body fields.
   Merges on top of any existing extra_body from the OpenAI adapter."
  [profile request base-extra-body]
  (let [model (:request/model request)
        reasoning (:request/reasoning request)
        provider-opts (:request/provider-options request)
        quirks (:profile/quirks profile)
        ;; Provider preferences for routing
        prefs (get-in provider-opts [:provider])
        ;; Pareto Code router plugin
        pareto-score (get-in provider-opts [:pareto :min-coding-score])
        plugins (when (and (get quirks :pareto-router)
                           (str/includes? model "pareto-code")
                           pareto-score)
                  [{:id "pareto-router"
                    :min_coding_score (double pareto-score)}])
        ;; Reasoning in extra_body for OpenRouter (not top-level)
        reasoning-extra (when (and reasoning (:enabled reasoning))
                          {:reasoning {:enabled true
                                       :effort (name (get reasoning :effort :medium))}})
        ;; Cache routing key — OpenRouter forwards it to the upstream
        ;; provider that supports prompt_cache_key (e.g. xAI Grok). It
        ;; is harmless for providers that don't recognize the field.
        cache-scope (when (cache/cache-enabled? request) (cache/scope-id request))
        cache-extra (when cache-scope {:prompt_cache_key cache-scope})]
    (merge {}
           base-extra-body
           (when prefs {:provider prefs})
           (when plugins {:plugins plugins})
           reasoning-extra
           cache-extra
           ;; Any caller-supplied extra_body under provider-options
           (get-in provider-opts [:extra_body]))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-openrouter
  "Build an OpenRouter request.
   Delegates to OpenAI Chat adapter for base structure, then injects
   OpenRouter-specific extra_body fields."
  [profile request]
  (let [;; Build base request via OpenAI Chat adapter
        base-req (openai/build-request-openai profile request)
        ;; Extract existing extra_body (may contain provider prefs from OpenAI adapter)
        base-extra-body (get-in base-req [:body :extra_body])
        ;; Build OpenRouter-specific extra_body
        or-extra-body (build-openrouter-extra-body profile request base-extra-body)
        ;; Merge into body — always include extra_body as a map for OpenRouter
        body (assoc (:body base-req) :extra_body (or or-extra-body {}))
        ;; Add OpenRouter-specific headers
        headers (merge (:headers base-req)
                       {"HTTP-Referer" (or (System/getenv "OPENROUTER_HTTP_REFERER")
                                           "https://github.com/DeadMeme5441/clojure-llm-sdk")
                        "X-Title" (or (System/getenv "OPENROUTER_APP_NAME")
                                      "clojure-llm-sdk")})]
    (assoc base-req
           :body body
           :headers headers)))

;; ---------------------------------------------------------------------------
;; Response parsing — delegate to OpenAI Chat
;; ---------------------------------------------------------------------------

(defn parse-response-openrouter
  [profile raw]
  (openai/parse-response-openai profile raw))

;; ---------------------------------------------------------------------------
;; Stream parsing — delegate to OpenAI Chat
;; ---------------------------------------------------------------------------

(defn parse-stream-event-openrouter
  [profile line]
  (openai/parse-stream-event-openai profile line))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-openrouter
  [profile status body]
  (errors/classify-error (Exception. "OpenRouter API error")
                         :status status
                         :body body
                         :provider :openrouter))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenRouterTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-openrouter profile request))

  (parse-response [this profile raw]
    (parse-response-openrouter profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-openrouter profile line))

  (parse-error [this profile status body]
    (parse-error-openrouter profile status body))

  (normalize-usage [this profile raw]
    (usage/normalize-usage :openrouter raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :reasoning :provider-routing}))

(defn make-transport []
  (->OpenRouterTransport))

;; Register
(when-let [p (provider/get-provider :openrouter)]
  (provider/register-provider
   (assoc p :profile/transport-constructor make-transport)))
