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
  "Normalize OpenAI Chat Completions usage shape.

   Falls back to Anthropic-style top-level cache fields when an
   OpenAI-compatible proxy (OpenRouter, Vercel AI Gateway, Cline)
   routes a Claude model and surfaces cache stats outside of
   prompt_tokens_details. Without this fallback cache writes count as
   0 and cache reads are missed entirely — port of cline/cline#10266.

   Perplexity adds :citation_tokens and :num_search_queries to the
   same envelope — both pass through to canonical fields when present
   (they're a no-op for any provider that doesn't emit them)."
  [u]
  (let [prompt-total (->int (:prompt_tokens u))
        completion (->int (:completion_tokens u))
        details (get u :prompt_tokens_details {})
        cache-read (let [from-details (->int (:cached_tokens details))]
                     (if (pos? from-details)
                       from-details
                       (->int (:cache_read_input_tokens u))))
        cache-write (let [from-details (->int (:cache_write_tokens details))]
                      (if (pos? from-details)
                        from-details
                        (->int (:cache_creation_input_tokens u))))
        out-details (get u :output_tokens_details {})
        reasoning (->int (:reasoning_tokens out-details))
        citation-tokens (->int (:citation_tokens u))
        search-queries (->int (:num_search_queries u))]
    (cond-> {:usage/input-tokens (max 0 (- prompt-total cache-read cache-write))
             :usage/output-tokens completion
             :usage/cached-input-tokens cache-read
             :usage/cache-write-tokens cache-write
             :usage/reasoning-tokens reasoning
             :usage/total-tokens (or (->int (:total_tokens u))
                                     (+ prompt-total completion))
             :usage/request-count 1
             :usage/provider-raw u}
      (pos? citation-tokens) (assoc :usage/citation-tokens citation-tokens)
      (pos? search-queries) (assoc :usage/search-queries search-queries))))

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

(defn normalize-embedding-usage
  "Normalize an embedding-endpoint usage map. Embedding responses lack
   completion_tokens; OpenAI returns {prompt_tokens, total_tokens}, the
   compatible providers do the same. Caches and reasoning aren't a thing
   here."
  [u]
  (let [prompt (->int (:prompt_tokens u))
        total (->int (:total_tokens u))]
    {:usage/input-tokens prompt
     :usage/output-tokens 0
     :usage/total-tokens (if (pos? total) total prompt)
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
    (:openai :openrouter :deepseek :kimi
     :mistral :groq :cerebras :together :xai :perplexity)
    (normalize-openai-usage raw-usage)
    :anthropic (normalize-anthropic-usage raw-usage)
    :gemini-native (normalize-gemini-usage raw-usage)
    :gemini-cloudcode (normalize-gemini-usage raw-usage)
    :codex (normalize-codex-usage raw-usage)
    ;; fallback: try OpenAI shape
    (normalize-openai-usage raw-usage)))
