(ns llm.sdk.usage
  "Usage normalization across providers.

   Honesty rule: cache / reasoning / citation / search counters are
   present in the normalized map ONLY when the provider reported them.
   Absent != 0. Callers (and the response-stamping layer) use absence
   to distinguish 'provider was silent' from 'provider explicitly said 0',
   which matters for :cache/status surfaced on the canonical response.")

(defn ->int [x]
  (cond
    (int? x) x
    (number? x) (int x)
    (string? x) (try (Integer/parseInt x) (catch Exception _ 0))
    :else 0))

(defn- ->int-or-nil
  "Coerce to int when x is a number-like value, else nil. Distinguishes
   'provider didn't say' (nil) from 'provider said 0' (0)."
  [x]
  (cond
    (nil? x) nil
    (int? x) x
    (number? x) (int x)
    (string? x) (try (Integer/parseInt x) (catch Exception _ nil))
    :else nil))

(defn- pick
  "First non-nil from a sequence of raw values, coerced via ->int-or-nil."
  [& vs]
  (some ->int-or-nil vs))

(defn normalize-openai-usage
  "Normalize OpenAI Chat Completions usage shape.

   Falls back to Anthropic-style top-level cache fields when an
   OpenAI-compatible proxy (OpenRouter, Vercel AI Gateway, Cline)
   routes a Claude model and surfaces cache stats outside of
   prompt_tokens_details. Without this fallback cache writes count as
   0 and cache reads are missed entirely — port of cline/cline#10266.

   Perplexity adds :citation_tokens and :num_search_queries to the
   same envelope — both pass through to canonical fields when present."
  [u]
  (let [prompt-total (->int (or (:prompt_tokens u) (:input_tokens u)))
        completion (->int (or (:completion_tokens u) (:output_tokens u)))
        details (or (:prompt_tokens_details u)
                    (:input_tokens_details u)
                    {})
        cache-read (pick (:cached_tokens details) (:cache_read_input_tokens u))
        cache-write (pick (:cache_write_tokens details) (:cache_creation_input_tokens u))
        out-details (or (:completion_tokens_details u)
                        (:output_tokens_details u)
                        {})
        reasoning (->int-or-nil (:reasoning_tokens out-details))
        image-input-tokens (->int-or-nil (:image_tokens details))
        image-output-tokens (->int-or-nil (:image_tokens out-details))
        image-tokens (when (or image-input-tokens image-output-tokens)
                       (+ (or image-input-tokens 0)
                          (or image-output-tokens 0)))
        citation-tokens (->int-or-nil (:citation_tokens u))
        search-queries (->int-or-nil (:num_search_queries u))
        cr (or cache-read 0)
        cw (or cache-write 0)]
    (cond-> {:usage/input-tokens (max 0 (- prompt-total cr cw))
             :usage/output-tokens completion
             :usage/total-tokens (or (->int-or-nil (:total_tokens u))
                                     (+ prompt-total completion))
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
      (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
      (some? reasoning) (assoc :usage/reasoning-tokens reasoning)
      (some? image-tokens) (assoc :usage/image-tokens image-tokens)
      (some? citation-tokens) (assoc :usage/citation-tokens citation-tokens)
      (some? search-queries) (assoc :usage/search-queries search-queries))))

(defn normalize-anthropic-usage
  "Normalize Anthropic Messages usage shape."
  [u]
  (let [input (->int (:input_tokens u))
        output (->int (:output_tokens u))
        cache-read (->int-or-nil (:cache_read_input_tokens u))
        cache-write (->int-or-nil (:cache_creation_input_tokens u))
        cr (or cache-read 0)
        cw (or cache-write 0)]
    (cond-> {:usage/input-tokens (max 0 (- input cr cw))
             :usage/output-tokens output
             :usage/total-tokens (+ input output)
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
      (some? cache-write) (assoc :usage/cache-write-tokens cache-write))))

(defn normalize-gemini-usage
  "Normalize Gemini native usage shape."
  [u]
  (let [prompt (->int (:promptTokenCount u))
        completion (->int (:candidatesTokenCount u))
        total (->int-or-nil (:totalTokenCount u))
        cached (->int-or-nil (:cachedContentTokenCount u))
        c (or cached 0)]
    (cond-> {:usage/input-tokens (max 0 (- prompt c))
             :usage/output-tokens completion
             :usage/total-tokens (or total (+ prompt completion))
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cached) (assoc :usage/cached-input-tokens cached))))

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
        cache-read (->int-or-nil (:cached_tokens details))
        cache-write (->int-or-nil (:cache_creation_tokens details))
        output (->int (:output_tokens u))
        out-details (get u :output_tokens_details {})
        reasoning (->int-or-nil (:reasoning_tokens out-details))
        cr (or cache-read 0)
        cw (or cache-write 0)]
    (cond-> {:usage/input-tokens (max 0 (- input-total cr cw))
             :usage/output-tokens output
             :usage/total-tokens (or (->int-or-nil (:total_tokens u))
                                     (+ input-total output))
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
      (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
      (some? reasoning) (assoc :usage/reasoning-tokens reasoning))))

(defn normalize-usage
  "Dispatch to the correct normalizer based on provider keyword."
  [provider raw-usage]
  (case provider
    (:openai :openrouter :deepseek :kimi :kimi-code
     :mistral :groq :cerebras :together :xai :perplexity :huggingface
     :sambanova :deepinfra :lambda :nebius :hyperbolic :novita
     :friendliai :featherless :cloudflare :dashscope :volcengine)
    (normalize-openai-usage raw-usage)
    :anthropic (normalize-anthropic-usage raw-usage)
    :gemini-native (normalize-gemini-usage raw-usage)
    :gemini-cloudcode (normalize-gemini-usage raw-usage)
    :vertex-gemini (normalize-gemini-usage raw-usage)
    (:codex :codex-backend) (normalize-codex-usage raw-usage)
    ;; fallback: try OpenAI shape
    (normalize-openai-usage raw-usage)))
