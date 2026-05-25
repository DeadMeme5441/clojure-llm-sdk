(ns llm.sdk.errors
  "Structured error classification.
   Ported from Hermes error_classifier.py with simplified pipeline."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Error taxonomy
;; ---------------------------------------------------------------------------

(def error-reasons
  #{:auth :rate-limit :timeout :server :overloaded
    :invalid-request :unsupported-parameter :content-filter
    :quota :network :provider-bug :unknown})

(defn error?
  [reason]
  (contains? error-reasons reason))

;; ---------------------------------------------------------------------------
;; Pattern lists
;; ---------------------------------------------------------------------------

(def ^:private billing-patterns
  #{"insufficient credits" "insufficient_quota" "insufficient balance"
    "credit balance" "credits have been exhausted" "top up your credits"
    "payment required" "billing hard limit" "exceeded your current quota"
    "account is deactivated" "plan does not include"})

(def ^:private rate-limit-patterns
  #{"rate limit" "rate_limit" "too many requests" "throttled"
    "requests per minute" "tokens per minute" "requests per day"
    "try again in" "please retry after" "resource_exhausted"
    "rate increased too quickly" "throttlingexception"
    "too many concurrent requests" "servicequotaexceededexception"})

(def ^:private context-overflow-patterns
  #{"context length" "context size" "maximum context" "token limit"
    "too many tokens" "reduce the length" "exceeds the limit"
    "context window" "prompt is too long" "prompt exceeds max length"
    "exceeds the max_model_len" "max_model_len" "prompt length"
    "input is too long" "maximum model length" "context length exceeded"
    "truncating input" "slot context" "n_ctx_slot"
    "超过最大长度" "上下文长度"})

(def ^:private auth-patterns
  #{"invalid api key" "invalid_api_key" "authentication"
    "unauthorized" "forbidden" "invalid token" "token expired"
    "token revoked" "access denied"})

(def ^:private model-not-found-patterns
  #{"is not a valid model" "invalid model" "model not found"
    "model_not_found" "does not exist" "no such model"
    "unknown model" "unsupported model"})

(def ^:private provider-policy-patterns
  #{"no endpoints available matching your guardrail"
    "no endpoints available matching your data policy"
    "no endpoints found matching your data policy"})

(def ^:private timeout-types
  #{"ReadTimeout" "ConnectTimeout" "PoolTimeout"
    "ConnectError" "RemoteProtocolError"
    "ConnectionError" "ConnectionResetError"
    "ConnectionAbortedError" "BrokenPipeError"
    "TimeoutError" "ReadError" "ServerDisconnectedError"
    "SSLError" "SSLZeroReturnError" "SSLWantReadError"
    "SSLWantWriteError" "SSLEOFError" "SSLSyscallError"
    "APIConnectionError" "APITimeoutError"})

;; ---------------------------------------------------------------------------
;; Extractors
;; ---------------------------------------------------------------------------

(defn- error-body->msg [body]
  (cond
    (string? body) body
    (map? body)
    (or (some-> body :error :message str)
        (some-> body :message str)
        "")
    :else ""))

(defn- matches-any? [text patterns]
  (let [t (str/lower-case (or text ""))]
    (some #(str/includes? t %) patterns)))

;; ---------------------------------------------------------------------------
;; Classification
;; ---------------------------------------------------------------------------

(defn classify-error
  "Classify an exception or error response into a structured map.
   Options:
     :status     HTTP status code (int or nil)
     :body       Response body (map or string)
     :provider   Provider keyword
     :model      Model string
     :error-type Exception type name string"
  [e & {:keys [status body error-type]}]
  (let [msg (str/lower-case (str e " " (error-body->msg body)))
        type-name (or error-type (type e))]

    ;; 1. Provider-specific highest-priority patterns
    (cond
      (and (= status 400)
           (str/includes? msg "signature")
           (str/includes? msg "thinking"))
      {:error/reason :invalid-request
       :error/retryable true
       :error/message "Invalid thinking signature"
       :error/should-strip-thinking true}

      (and (= status 429)
           (str/includes? msg "extra usage")
           (str/includes? msg "long context"))
      {:error/reason :rate-limit
       :error/retryable true
       :error/message "Long context tier rate limit"
       :error/should-compress true}

      (and (= status 400)
           (str/includes? msg "long context beta")
           (str/includes? msg "not yet available"))
      {:error/reason :invalid-request
       :error/retryable true
       :error/message "Long context beta not available"
       :error/disable-beta true}

      (or (str/includes? msg "do not have an active grok subscription")
          (and (str/includes? msg "out of available resources")
               (str/includes? msg "grok")))
      {:error/reason :auth
       :error/retryable false
       :error/message "Grok subscription entitlement failure"
       :error/should-fallback true}

      ;; 2. Status code classification
      (= status 401)
      {:error/reason :auth
       :error/retryable false
       :error/message "Authentication failed"
       :error/should-rotate-credential true}

      (= status 403)
      {:error/reason :auth
       :error/retryable false
       :error/message "Forbidden"
       :error/should-fallback true}

      (= status 402)
      (if (and (matches-any? msg #{"usage limit" "quota" "limit exceeded"})
               (matches-any? msg #{"try again" "retry" "resets at" "reset in" "wait"}))
        {:error/reason :rate-limit
         :error/retryable true
         :error/message "Transient usage limit"}
        {:error/reason :quota
         :error/retryable false
         :error/message "Billing/quota exhausted"
         :error/should-fallback true})

      (= status 404)
      (cond
        (matches-any? msg provider-policy-patterns)
        {:error/reason :invalid-request
         :error/retryable false
         :error/message "Provider policy blocked"
         :error/should-fallback false}
        (matches-any? msg model-not-found-patterns)
        {:error/reason :invalid-request
         :error/retryable false
         :error/message "Model not found"
         :error/should-fallback true}
        :else
        {:error/reason :unknown
         :error/retryable true})

      (= status 413)
      {:error/reason :invalid-request
       :error/retryable true
       :error/message "Payload too large"
       :error/should-compress true}

      (= status 429)
      {:error/reason :rate-limit
       :error/retryable true
       :error/message "Rate limited"
       :error/should-rotate-credential true}

      (= status 400)
      (cond
        (matches-any? msg context-overflow-patterns)
        {:error/reason :invalid-request
         :error/retryable true
         :error/message "Context overflow"
         :error/should-compress true}
        (matches-any? msg rate-limit-patterns)
        {:error/reason :rate-limit
         :error/retryable true}
        (matches-any? msg billing-patterns)
        {:error/reason :quota
         :error/retryable false
         :error/should-fallback true}
        :else
        {:error/reason :invalid-request
         :error/retryable false
         :error/message "Bad request"
         :error/should-fallback true})

      (#{500 502} status)
      {:error/reason :server
       :error/retryable true
       :error/message "Server error"}

      (#{503 529} status)
      {:error/reason :overloaded
       :error/retryable true
       :error/message "Provider overloaded"}

      ;; 3. Error type / message classification (no status code)
      (matches-any? msg billing-patterns)
      {:error/reason :quota
       :error/retryable false
       :error/should-fallback true}

      (matches-any? msg rate-limit-patterns)
      {:error/reason :rate-limit
       :error/retryable true}

      (matches-any? msg context-overflow-patterns)
      {:error/reason :invalid-request
       :error/retryable true
       :error/should-compress true}

      (matches-any? msg auth-patterns)
      {:error/reason :auth
       :error/retryable false
       :error/should-rotate-credential true
       :error/should-fallback true}

      (matches-any? msg provider-policy-patterns)
      {:error/reason :invalid-request
       :error/retryable false
       :error/should-fallback false}

      (matches-any? msg model-not-found-patterns)
      {:error/reason :invalid-request
       :error/retryable false
       :error/should-fallback true}

      (timeout-types type-name)
      {:error/reason :timeout
       :error/retryable true}

      ;; 4. Fallback
      :else
      {:error/reason :unknown
       :error/retryable true
       :error/message "Unclassified error"})))
