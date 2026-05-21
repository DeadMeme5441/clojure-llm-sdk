(ns llm.sdk.cache
  "Provider-agnostic context caching helpers.

   Strategies the SDK supports:
     :system-and-3   Anthropic-style cache_control on system + last N
                     non-system messages (default N=3 → 4 breakpoints).
                     Two layouts:
                       :native     markers on inner content blocks
                                   (api.anthropic.com, MiniMax /anthropic,
                                    Zhipu GLM, LiteLLM proxy)
                       :envelope   markers on the outer message dict
                                   (OpenRouter Claude, Nous Portal,
                                    Alibaba/Qwen on OpenCode, DashScope)
     :explicit       Send a reference to a pre-created cached content
                     resource (Gemini cachedContents/*, OpenAI cached
                     ID, etc.)
     :prompt-key     Pass a prompt_cache_key to the provider for
                     server-side cache routing (OpenAI Chat/Responses,
                     xAI, Codex backend).
     :cache-point    Insert sentinel content blocks in the message
                     stream (AWS Bedrock Converse cachePoint).
     :none           Caching disabled.

   References:
     hermes-agent/agent/prompt_caching.py        (system_and_3)
     hermes-agent/agent/agent_runtime_helpers.py (anthropic_prompt_cache_policy)
     hermes-agent/agent/transports/codex.py      (prompt_cache_key wiring)"
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Cache markers (Anthropic ephemeral cache_control)
;; ---------------------------------------------------------------------------

(defn marker
  "Build a cache_control marker for the given TTL ('5m' or '1h')."
  ([] (marker "5m"))
  ([ttl]
   (cond-> {:type "ephemeral"}
     (= ttl "1h") (assoc :ttl "1h"))))

(defn- apply-marker-to-blocks
  "Add cache_control to the last content block of a list."
  [blocks ttl-marker]
  (if (and (sequential? blocks) (seq blocks))
    (let [last-idx (dec (count blocks))
          last-block (nth blocks last-idx)]
      (if (map? last-block)
        (assoc (vec blocks) last-idx (assoc last-block :cache_control ttl-marker))
        blocks))
    blocks))

(defn apply-marker-to-message
  "Place a cache_control marker on a message in either layout.

   Layout:
     :native    — Anthropic Messages API. Markers go on the *last*
                  inner content block. Tool messages get the marker on
                  the outer envelope (they have no inner content
                  blocks). Empty content gets the marker on the
                  envelope.
     :envelope  — OpenRouter / OpenAI-wire proxies. Marker on the
                  outer message dict, regardless of inner shape.

   `message` is a provider-native dict (after canonical→native
   conversion). Returns a new map; does not mutate input."
  [message ttl-marker layout]
  (let [content (:content message)
        role (:role message)]
    (cond
      (= layout :envelope)
      (assoc message :cache_control ttl-marker)

      (= role "tool")
      ;; Native Anthropic 'tool' role messages don't exist (tool results
      ;; live inside user messages as tool_result blocks), but for
      ;; OpenAI-wire we'd still want the marker on the envelope.
      (assoc message :cache_control ttl-marker)

      (or (nil? content) (= content ""))
      (assoc message :cache_control ttl-marker)

      (string? content)
      (assoc message :content
             [{:type "text" :text content :cache_control ttl-marker}])

      (sequential? content)
      (assoc message :content (apply-marker-to-blocks content ttl-marker))

      :else
      (assoc message :cache_control ttl-marker))))

;; ---------------------------------------------------------------------------
;; system_and_3 layout
;; ---------------------------------------------------------------------------

(defn apply-system-and-3
  "Apply Anthropic system_and_3 caching to a list of native messages.

   Places up to `breakpoints` (default 4) cache_control markers:
     - system message (if present) gets the first breakpoint
     - the last (breakpoints-1) non-system messages get the rest
   All at the same TTL.

   `messages`     — vector of native provider messages (already
                    converted from canonical Messages).
   `opts`         — {:ttl '5m'|'1h' :layout :native|:envelope
                     :breakpoints int}

   Returns a new vector; does not mutate input."
  ([messages] (apply-system-and-3 messages {}))
  ([messages {:keys [ttl layout breakpoints]
              :or {ttl "5m" layout :native breakpoints 4}}]
   (if (or (empty? messages) (zero? breakpoints))
     (vec messages)
     (let [mk (marker ttl)
           messages (vec messages)
           sys? (= (:role (first messages)) "system")
           used (if sys? 1 0)
           remaining (- breakpoints used)
           non-sys-indices (vec (keep-indexed
                                 (fn [i m] (when (not= (:role m) "system") i))
                                 messages))
           pick (when (pos? remaining)
                  (set (take-last remaining non-sys-indices)))
           with-sys (if sys?
                      (assoc messages 0 (apply-marker-to-message
                                          (first messages) mk layout))
                      messages)]
       (reduce (fn [acc i]
                 (assoc acc i (apply-marker-to-message (nth acc i) mk layout)))
               with-sys
               pick)))))

(defn apply-system-blocks-cache
  "Place cache_control on Anthropic system *content blocks* (the top-level
   system field, which is a list of {:type 'text' :text ...}). Native
   Anthropic only — the OpenAI-wire envelope path puts system in
   messages[0] instead.

   Marks the LAST block in the system array, which lets a long system
   prompt warm the cache without consuming a non-system breakpoint."
  [system-blocks {:keys [ttl] :or {ttl "5m"}}]
  (if (and (sequential? system-blocks) (seq system-blocks))
    (apply-marker-to-blocks (vec system-blocks) (marker ttl))
    system-blocks))

(defn apply-tools-cache
  "Mark the last tool in an Anthropic tools[] array as cacheable.
   This caches the entire tool schema across sessions. Native layout
   only — OpenAI-wire chat completions does not support cache_control
   on tools[]."
  [tools {:keys [ttl] :or {ttl "5m"}}]
  (if (and (sequential? tools) (seq tools))
    (let [tools (vec tools)
          last-idx (dec (count tools))]
      (assoc tools last-idx (assoc (nth tools last-idx)
                                   :cache_control (marker ttl))))
    tools))

;; ---------------------------------------------------------------------------
;; Layout policy — which strategy and layout fits this provider/model?
;;
;; Decision table (mirrors hermes-agent/agent_runtime_helpers.anthropic_prompt_cache_policy
;; plus the per-provider quirks at hermes-agent/agent/transports/codex.py).
;; ---------------------------------------------------------------------------

(defn- base-url-host [base-url]
  (try
    (when (seq base-url)
      (-> base-url java.net.URI. .getHost (or "") str/lower-case))
    (catch Exception _ "")))

(defn- claude-model? [model]
  (and (string? model)
       (str/includes? (str/lower-case model) "claude")))

(defn- qwen-model? [model]
  (and (string? model)
       (str/includes? (str/lower-case model) "qwen")))

(defn- anthropic-host? [base-url]
  (let [h (base-url-host base-url)]
    (or (= h "api.anthropic.com")
        (str/ends-with? h ".anthropic.com"))))

(defn- openrouter-host? [base-url]
  (let [h (base-url-host base-url)]
    (or (= h "openrouter.ai")
        (str/ends-with? h ".openrouter.ai"))))

(defn- minimax-host? [base-url]
  (let [h (base-url-host base-url)]
    (#{"api.minimax.io" "api.minimaxi.com"} h)))

(defn- xai-host? [base-url]
  (let [h (base-url-host base-url)]
    (or (= h "api.x.ai")
        (str/ends-with? h ".x.ai"))))

(defn decide-strategy
  "Decide the caching strategy + layout for a provider/model pair.

   Returns a map:
     {:strategy :system-and-3|:prompt-key|:explicit|:cache-point|:none
      :layout   :native|:envelope|nil
      :reason   string}

   Caller intent (`request-cache`) wins when explicit:
     - :strategy :none     → no-op
     - :strategy :explicit → :explicit (caller supplies :cached-content-id)
     - :strategy :auto / nil → choose from profile/model

   This is meant to be called from each adapter's build-request after
   it has resolved the model id and base url."
  [profile model request-cache]
  (let [provider-id (:profile/id profile)
        protocol (:profile/protocol-family profile)
        base-url (:profile/base-url profile)
        intent (get request-cache :strategy :auto)]
    (cond
      (= intent :none)
      {:strategy :none :layout nil :reason "caller disabled"}

      (= intent :explicit)
      {:strategy :explicit :layout nil :reason "caller explicit"}

      ;; Native Anthropic (or hosts that speak the Messages API)
      (or (= provider-id :anthropic)
          (and (= protocol :anthropic-messages) (anthropic-host? base-url)))
      {:strategy :system-and-3 :layout :native :reason "anthropic native"}

      ;; MiniMax Anthropic-compatible endpoints
      (and (= protocol :anthropic-messages) (minimax-host? base-url))
      {:strategy :system-and-3 :layout :native :reason "minimax anthropic"}

      ;; Other anthropic_messages-wire gateways with Claude models
      (and (= protocol :anthropic-messages) (claude-model? model))
      {:strategy :system-and-3 :layout :native :reason "anthropic-compat gateway"}

      ;; OpenRouter Claude or Qwen — envelope layout
      (and (or (= provider-id :openrouter) (openrouter-host? base-url))
           (or (claude-model? model) (qwen-model? model)))
      {:strategy :system-and-3 :layout :envelope :reason "openrouter envelope"}

      ;; xAI Responses — prompt_cache_key in extra_body
      (or (= provider-id :xai) (xai-host? base-url))
      {:strategy :prompt-key :layout nil :reason "xai responses"}

      ;; Bedrock — cachePoint blocks in Converse
      (= protocol :bedrock)
      {:strategy :cache-point :layout nil :reason "bedrock converse"}

      ;; Gemini family — caching is server-side implicit on Gemini
      ;; 2.5 models. The SDK can't opt in or out: the provider
      ;; matches request prefixes automatically and surfaces hits via
      ;; usageMetadata.cachedContentTokenCount. Callers who want an
      ;; explicit pre-created CachedContent resource still get the
      ;; passthrough by setting :cached-content-id, but it's not the
      ;; default cache mechanism for either Vertex or Native Gemini.
      (#{:gemini-native :vertex-gemini} provider-id)
      (cond
        (:cached-content-id request-cache)
        {:strategy :explicit :layout nil :reason "gemini cached content reference"}

        (= provider-id :vertex-gemini)
        {:strategy :implicit :layout nil :reason "vertex gemini implicit only"}

        :else
        {:strategy :implicit :layout nil :reason "gemini native implicit only"})

      ;; OpenAI-wire (Chat or Responses) — prompt_cache_key (cache routing).
      ;; OpenRouter falls here for non-Claude / non-Qwen models so its
      ;; downstream upstreams that honour the key (OpenAI, xAI Grok)
      ;; still get a stable cache scope.
      (#{:openai-chat :codex :openrouter} protocol)
      {:strategy :prompt-key :layout nil :reason "openai cache routing"}

      :else
      {:strategy :none :layout nil :reason "no-op default"})))

;; ---------------------------------------------------------------------------
;; Cache config helpers (read :request/cache options safely)
;; ---------------------------------------------------------------------------

(defn cache-enabled?
  "Return true when the request opted in to caching. Defaults to true
   when the request carries a :request/cache map at all (callers opt
   out by passing {:enabled? false} or {:strategy :none})."
  [request]
  (let [c (:request/cache request)]
    (cond
      (nil? c) false
      (= (:enabled? c) false) false
      (= (:strategy c) :none) false
      :else true)))

(defn ttl
  "Read the cache TTL from a request, defaulting to '5m'."
  [request]
  (or (get-in request [:request/cache :ttl]) "5m"))

(defn breakpoints
  "Read the cache breakpoint count from a request, defaulting to 4."
  [request]
  (or (get-in request [:request/cache :breakpoints]) 4))

(defn scope-id
  "Read the caller-supplied scope id (cache routing key) from a request."
  [request]
  (get-in request [:request/cache :scope-id]))

(defn cached-content-id
  "Read the caller-supplied explicit cached content id."
  [request]
  (get-in request [:request/cache :cached-content-id]))

(defn tools-cache?
  "Should we cache the tools[] schema in addition to messages?"
  [request]
  (boolean (get-in request [:request/cache :tools-cache?])))
