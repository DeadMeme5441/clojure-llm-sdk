(ns llm.sdk.providers.gemini-native
  "Gemini Native API transport adapter.
   Handles thought signatures, streaming deltas, safety metadata.
   Preserves provider-specific replay state."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.cache :as cache]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Finish reason mapping
;; ---------------------------------------------------------------------------

(def ^:private finish-reason-map
  {"STOP" :stop
   "MAX_TOKENS" :length
   "SAFETY" :content-filter
   "RECITATION" :content-filter
   "OTHER" :stop})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- extract-system [messages]
  (when-let [texts (seq (keep #(when (= (:message/role %) :system)
                                 (t/content->string (:message/content %)))
                               messages))]
    {:parts (mapv (fn [t] {:text t}) texts)}))

(defn- content->gemini-parts [content]
  (cond
    (string? content) [{:text content}]
    (sequential? content)
    (mapv (fn [part]
            (case (:part/type part)
              :text {:text (:text part)}
              :image (let [url (:image/url part)]
                       (if (str/starts-with? url "data:")
                         (let [[header encoded] (str/split url #"," 2)
                               mime (second (re-find #"data:([^;]+)" header))]
                           {:inlineData {:mimeType mime :data encoded}})
                         {:text (str "[image: " url "]")}))
              {:text (str part)}))
          content)
    :else [{:text (str content)}]))

(defn- tool-call-signature [tc]
  (get-in tc [:tool-call/provider-data :extra_content :google :thought_signature]))

(defn- message->gemini [msg tool-name-by-id]
  (let [role (case (:message/role msg)
               (:user :tool) "user"
               (:assistant) "model"
               "user")
        content (:message/content msg)]
    (cond
      ;; Tool result
      (= (:message/role msg) :tool)
      {:role "user"
       :parts [{:functionResponse
                {:name (or (get tool-name-by-id (:message/tool-call-id msg))
                           (:message/name msg)
                           "tool")
                 :response {:output (t/content->string content)}}}]}

      ;; Assistant with tool calls
      (seq (:message/tool-calls msg))
      {:role "model"
       :parts (concat
               (content->gemini-parts content)
               (mapv (fn [tc]
                       (let [args (try (json/parse-string (:tool-call/arguments tc))
                                       (catch Exception _ {}))
                             part {:functionCall {:name (:tool-call/name tc)
                                                  :args args}}]
                         (if-let [sig (tool-call-signature tc)]
                           (assoc part :thoughtSignature sig)
                           part)))
                     (:message/tool-calls msg)))}

      ;; Regular message
      :else
      {:role role :parts (content->gemini-parts content)})))

(defn- build-contents [messages]
  (let [tool-name-by-id (into {}
                              (mapcat (fn [msg]
                                        (when (seq (:message/tool-calls msg))
                                          (map #(vector (:tool-call/id %) (:tool-call/name %))
                                               (:message/tool-calls msg))))
                                      messages))]
    (mapv #(message->gemini % tool-name-by-id) messages)))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- sanitize-gemini-schema [schema]
  (let [schema (dissoc schema :$schema :additionalProperties)]
    (if (and (map? schema) (= (:type schema) "integer") (:enum schema))
      (dissoc schema :enum)
      schema)))

(defn- tool->gemini [tool]
  (let [fn-data (:function tool)]
    {:name (:name fn-data)
     :description (or (:description fn-data) "")
     :parameters (sanitize-gemini-schema (or (:parameters fn-data) {:type "object"}))}))

;; ---------------------------------------------------------------------------
;; Thinking config
;; ---------------------------------------------------------------------------

(defn- build-thinking-config [model reasoning]
  (let [model-lower (str/lower-case (or model ""))]
    (when (and reasoning (str/starts-with? model-lower "gemini"))
      (if (= (:enabled reasoning) false)
        {:thinkingConfig {:includeThoughts false}}
        (cond
          (str/starts-with? model-lower "gemini-2.5-")
          {:thinkingConfig {:includeThoughts true}}

          (or (str/starts-with? model-lower "gemini-3")
              (str/starts-with? model-lower "gemini-3.1"))
          (let [effort (name (get reasoning :effort :medium))
                level (if (str/includes? model-lower "flash")
                        (case effort ("minimal" "low") "low" ("high" "xhigh") "high" "medium")
                        (case effort ("high" "xhigh") "high" "low"))]
            {:thinkingConfig {:includeThoughts true :thinkingLevel level}})

          :else
          {:thinkingConfig {:includeThoughts true}})))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-gemini
  [profile request]
  (let [model (:request/model request)
        model-norm (if (str/starts-with? (str/lower-case model) "models/")
                     model
                     (str "models/" model))
        messages (remove #(= (:message/role %) :system) (:request/messages request))
        system-inst (extract-system (:request/messages request))
        contents (build-contents messages)
        tools (when (seq (:request/tools request))
                [{:functionDeclarations (mapv tool->gemini (:request/tools request))}])
        thinking (build-thinking-config model (:request/reasoning request))
        ;; Gemini caching is "explicit only" from the SDK's
        ;; perspective: the caller pre-creates a CachedContent
        ;; resource (via cachedContents.create or the genai SDK) and
        ;; passes the resource name (e.g. "cachedContents/abc123")
        ;; here. The model + system prompt + tool definitions are
        ;; sourced from the cached content; only the new turn flows
        ;; through `contents`. Implicit prefix caching is automatic
        ;; on Gemini 2.5 and not under our control.
        cached-content (when (cache/cache-enabled? request)
                         (cache/cached-content-id request))
        body (merge
              {:contents contents}
              (when system-inst
                {:systemInstruction system-inst})
              (when tools
                {:tools tools})
              (when cached-content
                {:cachedContent cached-content})
              (when (:request/tool-choice request)
                {:toolConfig
                 (case (:request/tool-choice request)
                   :auto {:functionCallingConfig {:mode "AUTO"}}
                   :required {:functionCallingConfig {:mode "ANY"}}
                   :none {:functionCallingConfig {:mode "NONE"}}
                   (when (map? (:request/tool-choice request))
                     {:functionCallingConfig
                      {:mode "ANY"
                       :allowedFunctionNames [(get-in (:request/tool-choice request) [:function :name])]}}))})
              (when (:request/temperature request)
                {:generationConfig {:temperature (:request/temperature request)}})
              (when (:request/top-p request)
                {:generationConfig {:topP (:request/top-p request)}})
              (when (:request/max-tokens request)
                {:generationConfig {:maxOutputTokens (:request/max-tokens request)}})
              (when (:request/stop request)
                {:generationConfig {:stopSequences (if (string? (:request/stop request))
                                                     [(:request/stop request)]
                                                     (:request/stop request))}})
              thinking)
        ;; Gemini uses a different endpoint suffix + ?alt=sse for
        ;; SSE-formatted streams. Without alt=sse the streaming
        ;; endpoint returns a JSON array of chunks instead, which the
        ;; SSE parser in parse-stream-event-gemini can't consume.
        stream? (boolean (:request/stream? request))
        suffix (if stream? ":streamGenerateContent?alt=sse" ":generateContent")]
    {:method :post
     :url (str (:profile/base-url profile) "/" model-norm suffix)
     :headers (merge (provider/default-headers profile
                                               (provider/resolve-auth-token profile))
                     {"Content-Type" "application/json"})
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn parse-response-gemini
  [profile raw]
  (let [candidate (first (:candidates raw))
        parts (:parts (:content candidate))
        text-parts (vec (keep #(when (:text %) (:text %)) parts))
        thought-parts (vec (keep #(when (and (:text %) (:thought %)) (:text %)) parts))
        tool-calls (vec (keep #(when (:functionCall %)
                            (let [fc (:functionCall %)]
                              {:part/type :tool-call
                               :tool-call/id (str (java.util.UUID/randomUUID))
                               :tool-call/name (:name fc)
                               :tool-call/arguments (json/generate-string (:args fc))
                               :tool-call/provider-data
                               (cond-> {}
                                 (:thoughtSignature %) (assoc :extra_content
                                                              {:google {:thought_signature (:thoughtSignature %)}}))}))
                          parts))
        finish-reason (get finish-reason-map (:finishReason candidate) :unknown)
        usage-raw (:usageMetadata raw)
        safety (when (seq (:safetyRatings candidate))
                 {:part/type :safety
                  :safety/category "gemini-safety"
                  :safety/severity (str (:safetyRatings candidate))
                  :safety/blocked (= finish-reason :content-filter)})]
    {:response/provider (or (:profile/id profile) :gemini-native)
     :response/model (:modelVersion raw)
     :response/parts (into []
                           (concat
                            (map #(hash-map :part/type :text :text %) text-parts)
                            (map #(hash-map :part/type :reasoning :reasoning/text %) thought-parts)
                            tool-calls
                            (when safety [safety])))
     :response/tool-calls (not-empty tool-calls)
     :response/finish-reason finish-reason
     :response/usage (when usage-raw
                       (usage/normalize-usage :gemini-native usage-raw))
     :response/provider-data {:gemini/candidates (:candidates raw)}
     :response/raw raw}))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= payload "[DONE]")
        (try (json/parse-string payload true)
             (catch Exception _ nil))))))

(defn parse-stream-event-gemini
  "Parse one SSE line into a vector of canonical stream events.

   Gemini's streaming chunks frequently bundle a content part *and*
   usageMetadata *and* a finishReason in the same chunk (especially
   the terminal one). Returning a vector — which sdk/complete flattens
   via `(sequential? ev)` — keeps every event from a single chunk
   addressable, instead of losing usage and finish to a `cond` that
   only picks the first match."
  [profile line]
  (when-let [data (parse-sse-line line)]
    (let [candidate (first (:candidates data))
          parts (:parts (:content candidate))
          part-events (keep (fn [part]
                              (cond
                                (:text part)
                                (if (:thought part)
                                  (stream/reasoning-delta (:text part))
                                  (stream/content-delta (:text part)))

                                (:functionCall part)
                                (let [fc (:functionCall part)]
                                  (stream/tool-call-start
                                   0
                                   (str (java.util.UUID/randomUUID))
                                   (:name fc)))

                                :else nil))
                            parts)
          usage-ev (when-let [u (:usageMetadata data)]
                     (stream/usage-event
                      (usage/normalize-usage :gemini-native u)))
          finish-ev (when-let [fr (:finishReason candidate)]
                      (stream/end-event
                       :finish-reason (get finish-reason-map fr :unknown)))
          events (cond-> (vec part-events)
                   usage-ev (conj usage-ev)
                   finish-ev (conj finish-ev))]
      (not-empty events))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-gemini
  [profile status body]
  (errors/classify-error (Exception. "Gemini API error")
                         :status status
                         :body body
                         :provider :gemini-native))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord GeminiNativeTransport []
  t/Transport
  (build-request [this profile request]
    (build-request-gemini profile request))

  (parse-response [this profile raw]
    (parse-response-gemini profile raw))

  (parse-stream-event [this profile line]
    (parse-stream-event-gemini profile line))

  (parse-error [this profile status body]
    (parse-error-gemini profile status body))

  (normalize-usage [this profile raw]
    (usage/normalize-usage :gemini-native raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :multimodal :reasoning}))

(defn make-transport []
  (->GeminiNativeTransport))

;; Register
(when-let [p (provider/get-provider :gemini-native)]
  (provider/register-provider
   (assoc p :profile/transport-constructor make-transport)))
