(ns llm.sdk.errors-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.errors :as errors]))

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

(deftest test-timeout-type
  (let [e (errors/classify-error (Exception. "Read timeout")
                                 :error-type "ReadTimeout")]
    (is (= :timeout (:error/reason e)))
    (is (:error/retryable e))))
