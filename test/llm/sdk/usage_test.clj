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
            :cache_creation_input_tokens 100
            :output_tokens_details {:thinking_tokens 75}
            :server_tool_use {:web_search_requests 2}})]
    (is (= 2000 (:usage/input-tokens u)))
    (is (= 3300 (:usage/total-tokens u)))
    (is (= 800 (:usage/output-tokens u)))
    (is (= 400 (:usage/cached-input-tokens u)))
    (is (= 100 (:usage/cache-write-tokens u)))
    (is (= 75 (:usage/reasoning-tokens u)))
    (is (= 2 (:usage/search-queries u)))))

(deftest test-normalize-gemini-usage
  (let [u (usage/normalize-gemini-usage
           {:promptTokenCount 300
            :candidatesTokenCount 100
            :totalTokenCount 400
            :cachedContentTokenCount 50
            :thoughtsTokenCount 25})]
    (is (= 250 (:usage/input-tokens u)))
    (is (= 100 (:usage/output-tokens u)))
    (is (= 50 (:usage/cached-input-tokens u)))
    (is (= 25 (:usage/reasoning-tokens u)))))

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

(deftest test-normalize-openai-usage-together-flat-cache
  (testing "Together reports cached prompt tokens in the flat usage envelope"
    (let [raw {:prompt_tokens 1000
               :completion_tokens 200
               :total_tokens 1200
               :cached_tokens 240
               :cost 0.004
               :cost_details {:upstream_inference_cost 0.003}}
          u (usage/normalize-openai-usage raw)]
      (is (= 760 (:usage/input-tokens u)))
      (is (= 240 (:usage/cached-input-tokens u)))
      (is (not (contains? u :usage/cache-write-tokens)))
      (is (= raw (:usage/provider-raw u))))))

(deftest test-normalize-openai-usage-openrouter-nested-cache-write
  (testing "OpenRouter reports cache writes inside prompt token details"
    (let [u (usage/normalize-openai-usage
             {:prompt_tokens 1000
              :completion_tokens 200
              :total_tokens 1200
              :prompt_tokens_details {:cached_tokens 300
                                      :cache_write_tokens 50}})]
      (is (= 650 (:usage/input-tokens u)))
      (is (= 300 (:usage/cached-input-tokens u)))
      (is (= 50 (:usage/cache-write-tokens u))))))

(deftest test-normalize-openai-usage-cache-locations-are-fallbacks
  (testing "duplicate representations of one cache counter are not subtracted twice"
    (let [u (usage/normalize-openai-usage
             {:prompt_tokens 1000
              :completion_tokens 200
              :prompt_tokens_details {:cached_tokens 300
                                      :cache_write_tokens 50}
              :cached_tokens 300
              :cache_read_input_tokens 300
              :cache_creation_input_tokens 50})]
      (is (= 650 (:usage/input-tokens u)))
      (is (= 300 (:usage/cached-input-tokens u)))
      (is (= 50 (:usage/cache-write-tokens u))))))

(deftest test-normalize-openai-usage-image-generation-shape
  (let [u (usage/normalize-openai-usage
           {:input_tokens 11
            :input_tokens_details {:image_tokens 0
                                   :text_tokens 11}
            :output_tokens 1056
            :output_tokens_details {:image_tokens 1056
                                    :text_tokens 0}
            :total_tokens 1067})]
    (is (= 11 (:usage/input-tokens u)))
    (is (= 1056 (:usage/output-tokens u)))
    (is (= 1056 (:usage/image-tokens u)))
    (is (= 1067 (:usage/total-tokens u)))))

(deftest test-normalize-openai-usage-audio-chat-shape
  (let [raw {:prompt_tokens 40
             :prompt_tokens_details {:audio_tokens 15
                                     :cached_tokens 10}
             :completion_tokens 20
             :completion_tokens_details {:audio_tokens 5}
             :total_tokens 60
             :service_tier "default"}
        u (usage/normalize-openai-usage raw)]
    (is (= 30 (:usage/input-tokens u)))
    (is (= 20 (:usage/output-tokens u)))
    (is (= 20 (:usage/audio-tokens u)))
    (is (= raw (:usage/provider-raw u)))))

(deftest test-normalize-openai-usage-preserves-explicit-modality-zero
  (let [u (usage/normalize-openai-usage
           {:input_tokens 7
            :input_tokens_details {:image_tokens 0
                                   :audio_tokens 0}
            :output_tokens 3
            :output_tokens_details {:image_tokens 0
                                    :audio_tokens 0}})]
    (is (= 0 (:usage/image-tokens u)))
    (is (= 0 (:usage/audio-tokens u)))))

(deftest sparse-and-malformed-usage-remains-absent
  (testing "an empty OpenAI envelope retains raw metadata without inventing tokens"
    (let [normalized (usage/normalize-openai-usage {})]
      (is (= {} (:usage/provider-raw normalized)))
      (is (not (contains? normalized :usage/input-tokens)))
      (is (not (contains? normalized :usage/output-tokens)))
      (is (not (contains? normalized :usage/total-tokens)))))
  (testing "an output-only envelope does not invent an input count or total"
    (let [normalized
          (usage/normalize-openai-usage {:completion_tokens 7})]
      (is (= 7 (:usage/output-tokens normalized)))
      (is (not (contains? normalized :usage/input-tokens)))
      (is (not (contains? normalized :usage/total-tokens)))))
  (testing "malformed counters are absent while explicit zero remains known"
    (let [malformed
          (usage/normalize-openai-usage
           {:prompt_tokens "not-a-counter"
            :completion_tokens {:value 3}
            :total_tokens -1})
          zero
          (usage/normalize-openai-usage
           {:prompt_tokens 0 :completion_tokens "0" :total_tokens 0})]
      (is (not (contains? malformed :usage/input-tokens)))
      (is (not (contains? malformed :usage/output-tokens)))
      (is (not (contains? malformed :usage/total-tokens)))
      (is (= 0 (:usage/input-tokens zero)))
      (is (= 0 (:usage/output-tokens zero)))
      (is (= 0 (:usage/total-tokens zero))))))
