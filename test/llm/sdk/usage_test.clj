(ns llm.sdk.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.usage :as usage]))

(deftest test-normalize-openai-usage
  (let [u (usage/normalize-openai-usage
           {:prompt_tokens 1000
            :completion_tokens 500
            :total_tokens 1500
            :prompt_tokens_details {:cached_tokens 200
                                    :cache_write_tokens 50}})]
    (is (= 750 (:usage/input-tokens u)))
    (is (= 500 (:usage/output-tokens u)))
    (is (= 200 (:usage/cached-input-tokens u)))
    (is (= 50 (:usage/cache-write-tokens u)))
    (is (= 1500 (:usage/total-tokens u)))))

(deftest test-normalize-anthropic-usage
  (let [u (usage/normalize-anthropic-usage
           {:input_tokens 2000
            :output_tokens 800
            :cache_read_input_tokens 400
            :cache_creation_input_tokens 100})]
    (is (= 1500 (:usage/input-tokens u)))
    (is (= 800 (:usage/output-tokens u)))
    (is (= 400 (:usage/cached-input-tokens u)))
    (is (= 100 (:usage/cache-write-tokens u)))))

(deftest test-normalize-gemini-usage
  (let [u (usage/normalize-gemini-usage
           {:promptTokenCount 300
            :candidatesTokenCount 100
            :totalTokenCount 400
            :cachedContentTokenCount 50})]
    (is (= 250 (:usage/input-tokens u)))
    (is (= 100 (:usage/output-tokens u)))
    (is (= 50 (:usage/cached-input-tokens u)))))

(deftest test-normalize-codex-usage
  (let [u (usage/normalize-codex-usage
           {:input_tokens 1000
            :output_tokens 500
            :total_tokens 1500
            :input_tokens_details {:cached_tokens 200
                                   :cache_creation_tokens 50}})]
    (is (= 750 (:usage/input-tokens u)))
    (is (= 500 (:usage/output-tokens u)))
    (is (= 200 (:usage/cached-input-tokens u)))
    (is (= 50 (:usage/cache-write-tokens u)))))

(deftest test-normalize-usage-dispatch
  (is (= 10 (:usage/input-tokens (usage/normalize-usage :openai {:prompt_tokens 10}))))
  (is (= 10 (:usage/input-tokens (usage/normalize-usage :anthropic {:input_tokens 10}))))
  (is (= 10 (:usage/input-tokens (usage/normalize-usage :deepseek {:prompt_tokens 10}))))
  (is (= 7 (:usage/input-tokens
            (usage/normalize-usage :codex-backend
                                   {:input_tokens 10
                                    :input_tokens_details {:cached_tokens 3}
                                    :output_tokens 2})))))

(deftest test-normalize-openai-usage-openrouter-toplevel-cache-fallback
  (testing "OpenRouter Claude proxies surface Anthropic cache fields at top level (cline/cline#10266)"
    (let [u (usage/normalize-openai-usage
             {:prompt_tokens 2500
              :completion_tokens 800
              :total_tokens 3300
              :cache_read_input_tokens 1500
              :cache_creation_input_tokens 200
              :prompt_tokens_details {}})]
      (is (= 800 (:usage/input-tokens u)))
      (is (= 800 (:usage/output-tokens u)))
      (is (= 1500 (:usage/cached-input-tokens u)))
      (is (= 200 (:usage/cache-write-tokens u))))))

(deftest test-normalize-openai-usage-details-wins-over-toplevel
  (testing "prompt_tokens_details takes precedence when present"
    (let [u (usage/normalize-openai-usage
             {:prompt_tokens 1000
              :completion_tokens 400
              :prompt_tokens_details {:cached_tokens 300 :cache_write_tokens 50}
              :cache_read_input_tokens 9999})]  ; should be ignored
      (is (= 300 (:usage/cached-input-tokens u))))))
