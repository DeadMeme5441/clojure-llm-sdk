(ns llm.sdk.usage
  "Usage normalization across providers."
  (:require [clojure.walk :as walk]))

(defn ->int [x]
  (cond
    (int? x) x
    (number? x) (int x)
    (string? x) (try (Integer/parseInt x) (catch Exception _ 0))
    :else 0))

(defn normalize-openai-usage
  "Normalize OpenAI Chat Completions usage shape."
  [u]
  (let [prompt-total (->int (:prompt_tokens u))
        completion (->int (:completion_tokens u))
        details (get u :prompt_tokens_details {})
        cache-read (->int (:cached_tokens details))
        cache-write (->int (:cache_write_tokens details))
        out-details (get u :output_tokens_details {})
        reasoning (->int (:reasoning_tokens out-details))]
    {:usage/input-tokens (max 0 (- prompt-total cache-read cache-write))
     :usage/output-tokens completion
     :usage/cached-input-tokens cache-read
     :usage/cache-write-tokens cache-write
     :usage/reasoning-tokens reasoning
     :usage/total-tokens (or (->int (:total_tokens u))
                             (+ prompt-total completion))
     :usage/request-count 1
     :usage/provider-raw u}))

(defn normalize-anthropic-usage
  "Normalize Anthropic Messages usage shape."
  [u]
  (let [input (->int (:input_tokens u))
        output (->int (:output_tokens u))
        cache-read (->int (:cache_read_input_tokens u))
        cache-write (->int (:cache_creation_input_tokens u))]
    {:usage/input-tokens (max 0 (- input cache-read cache-write))
     :usage/output-tokens output
     :usage/cached-input-tokens cache-read
     :usage/cache-write-tokens cache-write
     :usage/total-tokens (+ input output)
     :usage/request-count 1
     :usage/provider-raw u}))

(defn normalize-gemini-usage
  "Normalize Gemini native usage shape."
  [u]
  (let [prompt (->int (:promptTokenCount u))
        completion (->int (:candidatesTokenCount u))
        total (->int (:totalTokenCount u))
        cached (->int (:cachedContentTokenCount u))]
    {:usage/input-tokens (max 0 (- prompt cached))
     :usage/output-tokens completion
     :usage/cached-input-tokens cached
     :usage/total-tokens total
     :usage/request-count 1
     :usage/provider-raw u}))

(defn normalize-codex-usage
  "Normalize OpenAI Responses/Codex usage shape."
  [u]
  (let [input-total (->int (:input_tokens u))
        details (get u :input_tokens_details {})
        cache-read (->int (:cached_tokens details))
        cache-write (->int (:cache_creation_tokens details))
        output (->int (:output_tokens u))
        out-details (get u :output_tokens_details {})
        reasoning (->int (:reasoning_tokens out-details))]
    {:usage/input-tokens (max 0 (- input-total cache-read cache-write))
     :usage/output-tokens output
     :usage/cached-input-tokens cache-read
     :usage/cache-write-tokens cache-write
     :usage/reasoning-tokens reasoning
     :usage/total-tokens (or (->int (:total_tokens u))
                             (+ input-total output))
     :usage/request-count 1
     :usage/provider-raw u}))

(defn normalize-usage
  "Dispatch to the correct normalizer based on provider keyword."
  [provider raw-usage]
  (case provider
    (:openai :openrouter :deepseek) (normalize-openai-usage raw-usage)
    :anthropic (normalize-anthropic-usage raw-usage)
    :gemini-native (normalize-gemini-usage raw-usage)
    :gemini-cloudcode (normalize-gemini-usage raw-usage)
    :codex (normalize-codex-usage raw-usage)
    ;; fallback: try OpenAI shape
    (normalize-openai-usage raw-usage)))
