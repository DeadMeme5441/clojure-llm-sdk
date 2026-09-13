(ns llm.sdk.errors-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.errors :as errors])
  (:import [java.net SocketTimeoutException]
           [java.net.http HttpTimeoutException]))

(deftest test-auth-classification
  (let [e (errors/classify-error (Exception. "Invalid API key")
                                 :status 401)]
    (is (= :auth (:error/reason e)))
    (is (not (:error/retryable e)))))

(deftest test-rate-limit-classification
  (let [e (errors/classify-error (Exception. "Rate limit exceeded")
                                 :status 429)]
    (is (= :rate-limit (:error/reason e)))
    (is (:error/retryable e))))

(deftest test-server-error-classification
  (let [e (errors/classify-error (Exception. "Internal server error")
                                 :status 500)]
    (is (= :server (:error/reason e)))
    (is (:error/retryable e))))

(deftest test-context-overflow
  (let [e (errors/classify-error (Exception. "Context length exceeded")
                                 :status 400)]
    (is (= :invalid-request (:error/reason e)))
    (is (:error/should-compress e))))

(deftest test-billing-vs-rate-limit
  (let [billing (errors/classify-error (Exception. "Insufficient credits")
                                       :status 402)
        transient (errors/classify-error (Exception. "Usage limit, try again in 5m")
                                         :status 402)]
    (is (= :quota (:error/reason billing)))
    (is (= :rate-limit (:error/reason transient)))))

(deftest test-provider-policy-blocked
  (let [e (errors/classify-error (Exception. "No endpoints available matching your guardrail")
                                 :status 404)]
    (is (= :invalid-request (:error/reason e)))
    (is (false? (:error/should-fallback e)))))

(deftest test-thinking-signature
  (let [e (errors/classify-error (Exception. "Invalid thinking signature")
                                 :status 400)]
    (is (= :invalid-request (:error/reason e)))
    (is (:error/retryable e))))

(deftest test-timeout-exception-types
  (let [socket-timeout (SocketTimeoutException. "Socket timed out")
        http-timeout (HttpTimeoutException. "HTTP timed out")
        wrapped-http-timeout (Exception. "Request failed" http-timeout)
        socket-result (errors/classify-error socket-timeout)
        http-result (errors/classify-error http-timeout)
        wrapped-result (errors/classify-error wrapped-http-timeout)]
    (is (= :timeout (:error/reason socket-result)))
    (is (:error/retryable socket-result))
    (is (= :timeout (:error/reason http-result)))
    (is (:error/retryable http-result))
    (is (= :timeout (:error/reason wrapped-result)))
    (is (:error/retryable wrapped-result))
    (is (= :auth
           (:error/reason
            (errors/classify-error socket-timeout :status 401)))
        "HTTP status classification retains precedence over exception type")
    (is (= :unknown
           (:error/reason
            (errors/classify-error socket-timeout :error-type Exception)))
        "an explicit type continues to override inferred exception types")))

(deftest test-http-timeout-statuses
  (let [bedrock (errors/classify-api-error
                 :bedrock
                 "Bedrock image"
                 408
                 {:message "Processing time exceeded the model timeout length."})
        openrouter (errors/classify-api-error
                    :openrouter
                    "OpenRouter image"
                    524
                    {"error" {"code" 524
                              "message" "Request timed out. Please try again later"}})]
    (is (= :timeout (:error/reason bedrock)))
    (is (:error/retryable bedrock))
    (is (= "Processing time exceeded the model timeout length."
           (:error/message bedrock)))
    (is (= :timeout (:error/reason openrouter)))
    (is (:error/retryable openrouter))
    (is (= "Request timed out. Please try again later"
           (:error/message openrouter)))))

(deftest test-elevenlabs-validation-detail
  (let [secret "credential-that-must-not-escape"
        e (errors/classify-api-error
           :elevenlabs
           "ElevenLabs"
           422
           {:detail [{:loc ["body" "text"]
                      :msg "Field required"
                      :type "missing"}]
            :api_key secret})]
    (is (= :invalid-request (:error/reason e)))
    (is (false? (:error/retryable e)))
    (is (= "Field required" (:error/message e)))
    (is (not (re-find (re-pattern secret) (pr-str e))))))

(deftest test-bedrock-model-error
  (let [transient (errors/classify-api-error
                   :bedrock
                   "Bedrock image"
                   424
                   {:message "The request failed due to an error while processing the model."
                    :originalStatusCode 500
                    :resourceName "upstream-image-model"})
        validation (errors/classify-api-error
                    :bedrock
                    "Bedrock image"
                    424
                    {:message "The model rejected an invalid image option."
                     :originalStatusCode "400"
                     :resourceName "upstream-image-model"})
        unrelated (errors/classify-api-error
                   :other-provider
                   "Other provider"
                   424
                   {:message "Failed dependency"})]
    (is (= :provider-bug (:error/reason transient)))
    (is (:error/retryable transient))
    (is (= "The request failed due to an error while processing the model."
           (:error/message transient)))
    (is (= :invalid-request (:error/reason validation)))
    (is (false? (:error/retryable validation)))
    (is (= "The model rejected an invalid image option."
           (:error/message validation)))
    (is (= :invalid-request (:error/reason unrelated)))
    (is (false? (:error/retryable unrelated)))))
