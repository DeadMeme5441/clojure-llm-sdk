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

(defn- sum-if-present
  "Sum two optional counters while preserving absence versus an explicit zero."
  [a b]
  (when (or (some? a) (some? b))
    (+ (or a 0) (or b 0))))

(defn normalize-openai-usage
  "Normalize OpenAI Chat Completions and compatible usage shapes.

   Cache counters have several official locations: OpenAI-style token details,
   Together's flat cached_tokens, and Anthropic-style top-level fields exposed
   by proxies. Locations are alternatives rather than additive counters, so
   each logical counter is selected once before uncached input is calculated.

   OpenRouter adds cache_write_tokens to prompt_tokens_details. OpenAI image
   and audio responses use input/output or prompt/completion token-detail
   envelopes. Raw usage is retained so text/modality and provider billing
   details without canonical fields remain available."
  [u]
  (let [prompt-total (->int (or (:prompt_tokens u) (:input_tokens u)))
        completion (->int (or (:completion_tokens u) (:output_tokens u)))
        prompt-details (or (:prompt_tokens_details u) {})
        input-details (or (:input_tokens_details u) {})
        completion-details (or (:completion_tokens_details u) {})
        output-details (or (:output_tokens_details u) {})
        cache-read (pick (:cached_tokens prompt-details)
                         (:cached_tokens input-details)
                         (:cached_tokens u)
                         (:cache_read_input_tokens u))
        cache-write (pick (:cache_write_tokens prompt-details)
                          (:cache_write_tokens input-details)
                          (:cache_creation_input_tokens u))
        reasoning (pick (:reasoning_tokens completion-details)
                        (:reasoning_tokens output-details))
        image-input-tokens (pick (:image_tokens prompt-details)
                                 (:image_tokens input-details))
        image-output-tokens (pick (:image_tokens completion-details)
                                  (:image_tokens output-details))
        image-tokens (sum-if-present image-input-tokens image-output-tokens)
        audio-input-tokens (pick (:audio_tokens prompt-details)
                                 (:audio_tokens input-details))
        audio-output-tokens (pick (:audio_tokens completion-details)
                                  (:audio_tokens output-details))
        audio-tokens (sum-if-present audio-input-tokens audio-output-tokens)
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
      (some? audio-tokens) (assoc :usage/audio-tokens audio-tokens)
      (some? citation-tokens) (assoc :usage/citation-tokens citation-tokens)
      (some? search-queries) (assoc :usage/search-queries search-queries))))

(defn normalize-anthropic-usage
  "Normalize Anthropic Messages usage shape. Anthropic reports uncached,
   cache-read, and cache-creation input tokens as disjoint counts."
  [u]
  (let [input (->int (:input_tokens u))
        output (->int (:output_tokens u))
        cache-read (->int-or-nil (:cache_read_input_tokens u))
        cache-write (->int-or-nil (:cache_creation_input_tokens u))
        reasoning (->int-or-nil (get-in u [:output_tokens_details
                                           :thinking_tokens]))
        search-queries (->int-or-nil (get-in u [:server_tool_use
                                                :web_search_requests]))
        cr (or cache-read 0)
        cw (or cache-write 0)]
    (cond-> {:usage/input-tokens input
             :usage/output-tokens output
             :usage/total-tokens (+ input cr cw output)
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
      (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
      (some? reasoning) (assoc :usage/reasoning-tokens reasoning)
      (some? search-queries) (assoc :usage/search-queries search-queries))))

(defn normalize-gemini-usage
  "Normalize Gemini native usage shape."
  [u]
  (let [prompt (->int (:promptTokenCount u))
        completion (->int (:candidatesTokenCount u))
        total (->int-or-nil (:totalTokenCount u))
        cached (->int-or-nil (:cachedContentTokenCount u))
        reasoning (->int-or-nil (:thoughtsTokenCount u))
        c (or cached 0)]
    (cond-> {:usage/input-tokens (max 0 (- prompt c))
             :usage/output-tokens completion
             :usage/total-tokens (or total (+ prompt completion))
             :usage/request-count 1
             :usage/provider-raw u}
      (some? cached) (assoc :usage/cached-input-tokens cached)
      (some? reasoning) (assoc :usage/reasoning-tokens reasoning))))

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
     :sambanova :deepinfra :nebius :hyperbolic :novita
     :friendliai :featherless :cloudflare :dashscope :volcengine)
    (normalize-openai-usage raw-usage)
    :anthropic (normalize-anthropic-usage raw-usage)
    :gemini-native (normalize-gemini-usage raw-usage)
    :gemini-cloudcode (normalize-gemini-usage raw-usage)
    :vertex-gemini (normalize-gemini-usage raw-usage)
    (:codex :codex-backend) (normalize-codex-usage raw-usage)
    ;; fallback: try OpenAI shape
    (normalize-openai-usage raw-usage)))
