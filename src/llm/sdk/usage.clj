(ns llm.sdk.usage
  "Usage normalization across providers.

   Honesty rule: cache / reasoning / citation / search counters are
   present in the normalized map ONLY when the provider reported them.
   Absent != 0. Callers (and the response-stamping layer) use absence
   to distinguish 'provider was silent' from 'provider explicitly said 0',
   which matters for :cache/status surfaced on the canonical response.")

(defn ->int
  "Coerce a non-negative integer-like provider counter, returning nil when the
   value is absent or malformed. Explicit zero remains zero."
  [x]
  (cond
    (integer? x) (when-not (neg? x) x)
    (number? x) (let [d (double x)]
                  (when (and (Double/isFinite d)
                             (not (neg? d))
                             (== d (Math/floor d)))
                    (long d)))
    (string? x) (try
                  (let [n (Long/parseLong x)]
                    (when-not (neg? n) n))
                  (catch Exception _ nil))
    :else nil))

(defn- pick
  "First valid counter from a sequence of alternative raw locations."
  [& vs]
  (some ->int vs))

(defn- sum-if-present
  "Sum two optional counters while preserving absence versus an explicit zero."
  [a b]
  (when (or (some? a) (some? b))
    (+ (or a 0) (or b 0))))

(def ^:private cumulative-counter-keys
  #{:usage/input-tokens
    :usage/output-tokens
    :usage/total-tokens
    :usage/cached-input-tokens
    :usage/cache-write-tokens
    :usage/reasoning-tokens
    :usage/image-tokens
    :usage/audio-tokens
    :usage/citation-tokens
    :usage/search-queries
    :usage/search-units
    :usage/request-count})

(def ^:private derived-total-key ::derived-total?)

(defn- clean-counters
  [usage]
  (when usage
    (with-meta
      (reduce-kv
       (fn [result k v]
         (if (contains? cumulative-counter-keys k)
           (if-some [counter (->int v)]
             (assoc result k counter)
             result)
           (assoc result k v)))
       {}
       usage)
      (meta usage))))

(defn- deep-merge
  [& maps]
  (letfn [(merge-entry [a b]
            (if (and (map? a) (map? b))
              (merge-with merge-entry a b)
              b))]
    (apply merge-with merge-entry maps)))

(defn- derivable-total
  [usage]
  (let [required [:usage/input-tokens :usage/output-tokens]
        optional [:usage/cached-input-tokens :usage/cache-write-tokens]]
    (when (and (every? #(number? (get usage %)) required)
               (every? #(or (not (contains? usage %))
                            (number? (get usage %)))
                       optional))
      (reduce + (map #(get usage % 0) (concat required optional))))))

(defn- mark-derived-total
  [usage derived?]
  (with-meta usage
    (assoc (meta usage) derived-total-key (boolean derived?))))

(defn- assoc-total
  [usage reported-total derived-total]
  (cond
    (some? reported-total)
    (mark-derived-total (assoc usage :usage/total-tokens reported-total) false)

    (some? derived-total)
    (mark-derived-total (assoc usage :usage/total-tokens derived-total) true)

    :else usage))

(defn merge-usage
  "Merge cumulative usage snapshots without summing them.

   Provider raw maps are merged deeply. A provider-reported total is
   authoritative and survives later sparse updates. A derived total is
   refreshed only while both input and output counts are known. Malformed
   counters in an update are treated as absent."
  [current update]
  (if (nil? update)
    current
    (let [current (clean-counters (or current {}))
          update (clean-counters update)
          current-total (:usage/total-tokens current)
          current-derived? (true? (get (meta current) derived-total-key))
          update-total? (contains? update :usage/total-tokens)
          update-derived? (true? (get (meta update) derived-total-key))
          update-authoritative? (and update-total? (not update-derived?))
          current-authoritative? (and (contains? current :usage/total-tokens)
                                      (not current-derived?))
          merged (deep-merge current update)
          derived (derivable-total merged)]
      (cond
        update-authoritative?
        (mark-derived-total merged false)

        current-authoritative?
        (mark-derived-total
         (assoc merged :usage/total-tokens current-total)
         false)

        update-total?
        (mark-derived-total merged true)

        (some? derived)
        (mark-derived-total
         (assoc merged :usage/total-tokens derived)
         true)

        :else
        (dissoc merged :usage/total-tokens)))))

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
  (let [u (or u {})
        prompt-total (pick (:prompt_tokens u) (:input_tokens u))
        completion (pick (:completion_tokens u) (:output_tokens u))
        prompt-details (if (map? (:prompt_tokens_details u))
                         (:prompt_tokens_details u) {})
        input-details (if (map? (:input_tokens_details u))
                        (:input_tokens_details u) {})
        completion-details (if (map? (:completion_tokens_details u))
                             (:completion_tokens_details u) {})
        output-details (if (map? (:output_tokens_details u))
                         (:output_tokens_details u) {})
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
        citation-tokens (->int (:citation_tokens u))
        search-queries (->int (:num_search_queries u))
        input (when (some? prompt-total)
                (max 0 (- prompt-total
                          (or cache-read 0)
                          (or cache-write 0))))
        reported-total (->int (:total_tokens u))
        derived-total (when (and (some? prompt-total) (some? completion))
                        (+ prompt-total completion))]
    (assoc-total
     (cond-> {:usage/request-count 1
              :usage/provider-raw u}
       (some? input) (assoc :usage/input-tokens input)
       (some? completion) (assoc :usage/output-tokens completion)
       (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
       (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
       (some? reasoning) (assoc :usage/reasoning-tokens reasoning)
       (some? image-tokens) (assoc :usage/image-tokens image-tokens)
       (some? audio-tokens) (assoc :usage/audio-tokens audio-tokens)
       (some? citation-tokens) (assoc :usage/citation-tokens citation-tokens)
       (some? search-queries) (assoc :usage/search-queries search-queries))
     reported-total
     derived-total)))

(defn normalize-anthropic-usage
  "Normalize Anthropic Messages usage shape. Anthropic reports uncached,
   cache-read, and cache-creation input tokens as disjoint counts."
  [u]
  (let [u (or u {})
        input (->int (:input_tokens u))
        output (->int (:output_tokens u))
        cache-read (->int (:cache_read_input_tokens u))
        cache-write (->int (:cache_creation_input_tokens u))
        reasoning (->int (get-in u [:output_tokens_details :thinking_tokens]))
        search-queries (->int (get-in u [:server_tool_use
                                         :web_search_requests]))
        total (when (and (some? input) (some? output))
                (+ input output (or cache-read 0) (or cache-write 0)))]
    (assoc-total
     (cond-> {:usage/request-count 1
              :usage/provider-raw u}
       (some? input) (assoc :usage/input-tokens input)
       (some? output) (assoc :usage/output-tokens output)
       (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
       (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
       (some? reasoning) (assoc :usage/reasoning-tokens reasoning)
       (some? search-queries) (assoc :usage/search-queries search-queries))
     nil
     total)))

(defn normalize-gemini-usage
  "Normalize Gemini native usage shape."
  [u]
  (let [u (or u {})
        prompt (->int (:promptTokenCount u))
        completion (->int (:candidatesTokenCount u))
        total (->int (:totalTokenCount u))
        cached (->int (:cachedContentTokenCount u))
        reasoning (->int (:thoughtsTokenCount u))
        input (when (some? prompt)
                (max 0 (- prompt (or cached 0))))
        derived-total (when (and (some? prompt) (some? completion))
                        (+ prompt completion))]
    (assoc-total
     (cond-> {:usage/request-count 1
              :usage/provider-raw u}
       (some? input) (assoc :usage/input-tokens input)
       (some? completion) (assoc :usage/output-tokens completion)
       (some? cached) (assoc :usage/cached-input-tokens cached)
       (some? reasoning) (assoc :usage/reasoning-tokens reasoning))
     total
     derived-total)))

(defn normalize-embedding-usage
  "Normalize an embedding-endpoint usage map. Embedding endpoints only meter
   input tokens, so output is a known zero even when omitted by the provider."
  [u]
  (let [u (or u {})
        prompt (->int (:prompt_tokens u))
        total (->int (:total_tokens u))]
    (assoc-total
     (cond-> {:usage/output-tokens 0
              :usage/request-count 1
              :usage/provider-raw u}
       (some? prompt) (assoc :usage/input-tokens prompt))
     total
     prompt)))

(defn normalize-codex-usage
  "Normalize OpenAI Responses/Codex usage shape."
  [u]
  (let [u (or u {})
        input-total (->int (:input_tokens u))
        details (if (map? (:input_tokens_details u))
                  (:input_tokens_details u) {})
        cache-read (->int (:cached_tokens details))
        cache-write (->int (:cache_creation_tokens details))
        output (->int (:output_tokens u))
        out-details (if (map? (:output_tokens_details u))
                      (:output_tokens_details u) {})
        reasoning (->int (:reasoning_tokens out-details))
        input (when (some? input-total)
                (max 0 (- input-total
                          (or cache-read 0)
                          (or cache-write 0))))
        reported-total (->int (:total_tokens u))
        derived-total (when (and (some? input-total) (some? output))
                        (+ input-total output))]
    (assoc-total
     (cond-> {:usage/request-count 1
              :usage/provider-raw u}
       (some? input) (assoc :usage/input-tokens input)
       (some? output) (assoc :usage/output-tokens output)
       (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
       (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
       (some? reasoning) (assoc :usage/reasoning-tokens reasoning))
     reported-total
     derived-total)))

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
