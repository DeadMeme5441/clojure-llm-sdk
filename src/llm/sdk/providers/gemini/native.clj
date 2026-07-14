(ns llm.sdk.providers.gemini.native
  "Gemini Native API transport adapter.
   Handles thought signatures, streaming deltas, safety metadata.
   Preserves provider-specific replay state."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.sse :as sse]
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
   "LANGUAGE" :content-filter
   "BLOCKLIST" :content-filter
   "PROHIBITED_CONTENT" :content-filter
   "SPII" :content-filter
   "IMAGE_SAFETY" :content-filter
   "IMAGE_PROHIBITED_CONTENT" :content-filter
   "IMAGE_RECITATION" :content-filter
   "NO_IMAGE" :incomplete
   "IMAGE_OTHER" :incomplete
   "MALFORMED_FUNCTION_CALL" :incomplete
   "UNEXPECTED_TOOL_CALL" :incomplete
   "TOO_MANY_TOOL_CALLS" :incomplete
   "MISSING_THOUGHT_SIGNATURE" :incomplete
   "MALFORMED_RESPONSE" :incomplete
   "OTHER" :unknown})

;; ---------------------------------------------------------------------------
;; Message conversion
;; ---------------------------------------------------------------------------

(defn- extract-system [messages]
  (when-let [texts (seq (keep #(when (#{:system :developer} (:message/role %))
                                 (t/content->string (:message/content %)))
                               messages))]
    {:parts (mapv (fn [text] {:text text}) texts)}))

(defn- image-inline-data [part]
  (let [url (:image/url part)
        data (:image/data part)
        mime (or (:image/mime-type part) "image/png")]
    (cond
      (seq data)
      {:inlineData {:mimeType mime :data data}}

      (and (string? url) (str/starts-with? url "data:"))
      (let [[header encoded] (str/split url #"," 2)
            mime (or (second (re-find #"data:([^;]+)" header)) mime)]
        {:inlineData {:mimeType mime :data encoded}})

      (seq url)
      {:fileData {:mimeType mime :fileUri url}}

      :else
      {:text "[image]"})))

(defn- file-part->gemini [part]
  (let [mime (t/file-mime-type part)]
    (cond
      (:file/url part)
      {:fileData {:mimeType mime
                  :fileUri (:file/url part)}}

      (t/file-data-for-input-file part)
      {:inlineData {:mimeType mime
                    :data (t/file-data-for-input-file part)}}

      :else
      (t/missing-file-source! :gemini-native part))))

(defn- canonical-part->gemini [part]
  (case (:part/type part)
    :text {:text (:text part)}
    :image (image-inline-data part)
    :file (file-part->gemini part)
    :reasoning
    (cond-> {:text (:reasoning/text part)
             :thought true}
      (:reasoning/signature part)
      (assoc :thoughtSignature (:reasoning/signature part)))
    :unknown/provider-native
    (if (#{:gemini :gemini-native :vertex-gemini} (:unknown/provider part))
      (:unknown/data part)
      {:text (str part)})
    {:text (str part)}))

(defn- content->gemini-parts [content]
  (cond
    (nil? content) []
    (string? content) [{:text content}]
    (sequential? content)
    (reduce
     (fn [wire-parts part]
       (if (and (= :provider-state (:part/type part))
                (= :gemini (:provider-state/provider part))
                (some? (get-in part [:provider-state/data :part-index]))
                (get-in part [:provider-state/data :thoughtSignature]))
         (let [idx (get-in part [:provider-state/data :part-index])]
           (if (< idx (count wire-parts))
             (assoc-in wire-parts [idx :thoughtSignature]
                       (get-in part [:provider-state/data :thoughtSignature]))
             wire-parts))
         (conj wire-parts (canonical-part->gemini part))))
     []
     content)
    :else [{:text (str content)}]))

(defn- tool-call-signature [tc]
  (or (get-in tc [:tool-call/provider-data :gemini/thought-signature])
      (get-in tc [:tool-call/provider-data :extra_content :google :thought_signature])))

(defn- message->gemini [msg tool-name-by-id]
  (let [role (case (:message/role msg)
               (:user :tool) "user"
               (:assistant) "model"
               "user")
        content (:message/content msg)]
    (cond
      ;; Tool result
      (= (:message/role msg) :tool)
      (let [tool-call-id (:message/tool-call-id msg)
            response (cond-> {:name (or (get tool-name-by-id tool-call-id)
                                        (:message/name msg)
                                        "tool")
                              :response {:output (t/content->string content)}}
                       tool-call-id
                       (assoc :id tool-call-id))]
        {:role "user"
         :parts [{:functionResponse response}]})

      ;; Assistant with tool calls
      (seq (:message/tool-calls msg))
      {:role "model"
       :parts (concat
               (content->gemini-parts content)
               (mapv (fn [tc]
                       (let [args (try (json/parse-string (:tool-call/arguments tc))
                                       (catch Exception _ {}))
                             function-call (cond-> {:name (:tool-call/name tc)
                                                    :args args}
                                             (:tool-call/id tc)
                                             (assoc :id (:tool-call/id tc)))
                             part {:functionCall function-call}]
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

(defn- tool->gemini [tool]
  (let [fn-data (:function tool)]
    (cond-> {:name (:name fn-data)
             :description (or (:description fn-data) "")}
      (:parameters fn-data)
      (assoc :parametersJsonSchema (:parameters fn-data)))))

;; ---------------------------------------------------------------------------
;; Thinking config
;; ---------------------------------------------------------------------------

(defn- gemini-3-thinking-level [model-lower effort]
  (let [effort (if (= effort :xhigh) :high effort)]
    (cond
      (and (str/includes? model-lower "pro") (= effort :minimal)) "low"
      (and (str/includes? model-lower "flash-lite-image")
           (#{:low :medium} effort)) "minimal"
      :else (name effort))))

(defn- build-thinking-config [model reasoning]
  (let [model-lower (str/replace-first
                     (str/lower-case (or model ""))
                     #"^models/"
                     "")
        gemini-25? (str/starts-with? model-lower "gemini-2.5-")
        gemini-3? (str/starts-with? model-lower "gemini-3")
        enabled? (not= (:enabled reasoning) false)
        budget (:budget reasoning)
        effort (get reasoning :effort :medium)]
    (when reasoning
      (cond
        (not enabled?)
        (cond
          (and gemini-25? (not (str/includes? model-lower "pro")))
          {:includeThoughts false :thinkingBudget 0}

          (and gemini-3? (not (str/includes? model-lower "pro")))
          {:includeThoughts false :thinkingLevel "minimal"}

          ;; Gemini 2.5 Pro and Gemini 3 Pro cannot disable thinking.
          ;; Suppress summaries without sending an unsupported level/budget.
          :else
          {:includeThoughts false})

        gemini-25?
        (cond-> {:includeThoughts true}
          (some? budget) (assoc :thinkingBudget budget))

        gemini-3?
        {:includeThoughts true
         :thinkingLevel (gemini-3-thinking-level model-lower effort)}

        :else
        (cond-> {:includeThoughts true}
          (some? budget) (assoc :thinkingBudget budget))))))

(defn- response-format->gemini [response-format]
  (case (:type response-format)
    :text {:responseMimeType "text/plain"}
    :json_object {:responseMimeType "application/json"}
    :json_schema (cond-> {:responseMimeType "application/json"}
                   (:json-schema response-format)
                   (assoc :responseJsonSchema (:json-schema response-format)))
    nil))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-gemini
  [profile request]
  (let [model (:request/model request)
        model-norm (if (str/starts-with? (str/lower-case model) "models/")
                     model
                     (str "models/" model))
        messages (remove #(#{:system :developer} (:message/role %))
                         (:request/messages request))
        system-inst (extract-system (:request/messages request))
        contents (build-contents messages)
        tools (when (seq (:request/tools request))
                [{:functionDeclarations (mapv tool->gemini (:request/tools request))}])
        thinking (build-thinking-config model (:request/reasoning request))
        response-format (response-format->gemini (:request/response-format request))
        extra-body (or (get-in request [:request/provider-options :extra_body]) {})
        canonical-generation-config
        (cond-> {}
          (:request/temperature request)
          (assoc :temperature (:request/temperature request))
          (:request/top-p request)
          (assoc :topP (:request/top-p request))
          (:request/max-tokens request)
          (assoc :maxOutputTokens (:request/max-tokens request))
          (:request/stop request)
          (assoc :stopSequences (t/stop-sequences (:request/stop request)))
          response-format
          (merge response-format)
          thinking
          (assoc :thinkingConfig thinking))
        generation-config (merge canonical-generation-config
                                 (:generationConfig extra-body))
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
        base-body
        (merge
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
                  :allowedFunctionNames [(get-in (:request/tool-choice request)
                                                 [:function :name])]}}))}))
        body (cond-> (merge base-body (dissoc extra-body :generationConfig))
               (seq generation-config)
               (assoc :generationConfig generation-config))
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

(defn- normalize-gemini-usage [raw]
  (cond-> (usage/normalize-usage :gemini-native raw)
    (some? (:thoughtsTokenCount raw))
    (assoc :usage/reasoning-tokens (:thoughtsTokenCount raw))))

(defn- wire-part->canonical [provider-id idx part]
  (cond
    (:functionCall part)
    (let [fc (:functionCall part)
          call-id (or (:id fc) (str "gemini_call_" idx))]
      {:part/type :tool-call
       :tool-call/id call-id
       :tool-call/name (or (:name fc) "")
       :tool-call/arguments (json/generate-string (or (:args fc) {}))
       :tool-call/provider-data
       (cond-> {:gemini/function-call-id call-id}
         (:thoughtSignature part)
         (assoc :gemini/thought-signature (:thoughtSignature part)
                :extra_content
                {:google {:thought_signature (:thoughtSignature part)}}))})

    (and (:text part) (:thought part))
    (cond-> {:part/type :reasoning
             :reasoning/text (:text part)}
      (:thoughtSignature part)
      (assoc :reasoning/signature (:thoughtSignature part)))

    (:text part)
    {:part/type :text :text (:text part)}

    (:inlineData part)
    (let [{:keys [mimeType data]} (:inlineData part)]
      (if (str/starts-with? (or mimeType "") "image/")
        {:part/type :image :image/data data :image/mime-type mimeType}
        {:part/type :file :file/data data :file/mime-type mimeType}))

    (:fileData part)
    (let [{:keys [mimeType fileUri]} (:fileData part)]
      (if (str/starts-with? (or mimeType "") "image/")
        {:part/type :image :image/url fileUri :image/mime-type mimeType}
        {:part/type :file :file/url fileUri :file/mime-type mimeType}))

    :else
    {:part/type :unknown/provider-native
     :unknown/provider provider-id
     :unknown/data part}))

(defn- safety-rating->part [source blocked? rating]
  {:part/type :safety
   :safety/category (or (:category rating) "gemini-safety")
   :safety/severity (or (:probability rating) "HARM_PROBABILITY_UNSPECIFIED")
   :safety/blocked (boolean (or (:blocked rating) blocked?))
   :safety/details {:source source :rating rating}})

(defn- response-safety-parts [candidate prompt-feedback]
  (let [prompt-blocked? (some? (:blockReason prompt-feedback))
        candidate-parts (mapv #(safety-rating->part :candidate false %)
                              (:safetyRatings candidate))
        prompt-parts (mapv #(safety-rating->part :prompt prompt-blocked? %)
                           (:safetyRatings prompt-feedback))]
    (cond-> (into candidate-parts prompt-parts)
      (and prompt-blocked? (empty? prompt-parts))
      (conj {:part/type :safety
             :safety/category "gemini-prompt"
             :safety/severity (:blockReason prompt-feedback)
             :safety/blocked true
             :safety/details {:source :prompt
                              :prompt-feedback prompt-feedback}}))))

(defn parse-response-gemini
  [profile raw]
  (let [provider-id (or (:profile/id profile) :gemini-native)
        candidate (first (:candidates raw))
        parts (:parts (:content candidate))
        canonical-parts
        (->> (or parts [])
             (map-indexed
              (fn [idx part]
                (let [canonical (wire-part->canonical provider-id idx part)
                      signature (:thoughtSignature part)
                      signature-native? (#{:tool-call :reasoning
                                           :unknown/provider-native}
                                         (:part/type canonical))]
                  (cond-> [canonical]
                    (and signature (not signature-native?))
                    (conj {:part/type :provider-state
                           :provider-state/provider :gemini
                           :provider-state/data
                           {:part-index idx
                            :thoughtSignature signature}})))))
             (apply concat)
             vec)
        tool-calls (vec (filter #(= (:part/type %) :tool-call) canonical-parts))
        prompt-feedback (:promptFeedback raw)
        finish-reason (if (:blockReason prompt-feedback)
                        :content-filter
                        (get finish-reason-map (:finishReason candidate) :unknown))
        usage-raw (:usageMetadata raw)
        safety-parts (response-safety-parts candidate prompt-feedback)]
    (cond-> {:response/provider provider-id
             :response/model (:modelVersion raw)
             :response/parts (into canonical-parts safety-parts)
             :response/finish-reason finish-reason
             :response/provider-data
             (cond-> {:gemini/candidates (:candidates raw)}
               (:responseId raw) (assoc :gemini/response-id (:responseId raw))
               prompt-feedback (assoc :gemini/prompt-feedback prompt-feedback)
               (:modelStatus raw) (assoc :gemini/model-status (:modelStatus raw)))
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage-raw (assoc :response/usage (normalize-gemini-usage usage-raw)))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn parse-stream-event-gemini
  "Parse one SSE line into a vector of canonical stream events.

   Gemini's streaming chunks frequently bundle a content part *and*
   usageMetadata *and* a finishReason in the same chunk (especially
   the terminal one). Returning a vector — which sdk/complete flattens
   via `(sequential? ev)` — keeps every event from a single chunk
   addressable, instead of losing usage and finish to a `cond` that
   only picks the first match."
  [_profile line]
  (when-let [data (parse-sse-line line)]
    (let [candidate (first (:candidates data))
          parts (:parts (:content candidate))
          part-events
          (mapcat
           (fn [[idx part]]
             (let [signature-ev (when-let [sig (:thoughtSignature part)]
                                  (stream/provider-state-event
                                   :gemini
                                   {:parts {idx {:thoughtSignature sig}}}))]
               (cond
                 (:text part)
                 (remove nil?
                         [(if (:thought part)
                            (stream/reasoning-delta (:text part))
                            (stream/content-delta (:text part)))
                          signature-ev])

                 (:functionCall part)
                 (let [fc (:functionCall part)
                       call-id (or (:id fc) (str "gemini_call_" idx))
                       args (json/generate-string (or (:args fc) {}))]
                   (remove nil?
                           [(stream/tool-call-start idx call-id (or (:name fc) ""))
                            (when (seq args)
                              (stream/tool-call-delta idx args))
                            (stream/tool-call-end idx)
                            signature-ev]))

                 :else
                 [(stream/provider-state-event :gemini {:parts {idx part}})])))
           (map-indexed vector (or parts [])))
          usage-ev (when-let [u (:usageMetadata data)]
                     (stream/usage-event (normalize-gemini-usage u)))
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
  [_profile status body]
  (errors/classify-api-error :gemini-native "Gemini" status body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord GeminiNativeTransport []
  t/Transport
  (build-request [_this profile request]
    (build-request-gemini profile request))

  (parse-response [_this profile raw]
    (parse-response-gemini profile raw))

  (parse-stream-event [_this profile line]
    (parse-stream-event-gemini profile line))

  (parse-error [_this profile status body]
    (parse-error-gemini profile status body))

  (normalize-usage [_this _profile raw]
    (usage/normalize-usage :gemini-native raw))

  (request-capabilities [_]
    #{:chat :streaming :tools :multimodal :reasoning :file-attachments}))

(defn make-transport []
  (->GeminiNativeTransport))

;; Register
(when-let [p (provider/get-provider :gemini-native)]
  (provider/register-provider
   (assoc p :profile/transport-constructor make-transport)))
