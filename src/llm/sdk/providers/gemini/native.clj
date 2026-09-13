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

(defn- unsupported-input!
  [provider-id part reason]
  (throw (ex-info "Gemini does not support this canonical input form"
                  {:provider provider-id
                   :part/type (:part/type part)
                   :reason reason
                   :error/type :gemini/unsupported-input})))

(defn- instruction-text [provider-id content]
  (cond
    (nil? content) nil
    (string? content) content
    (sequential? content)
    (do
      (when-let [unsupported
                 (first (remove #(= :text (:part/type %)) content))]
        (unsupported-input! provider-id unsupported
                            :system-instruction-must-be-text))
      (t/content->string content))
    :else
    (unsupported-input! provider-id {:part/type :message-content}
                        :system-instruction-must-be-text)))

(defn- extract-system [provider-id messages]
  (when-let [texts
             (seq (keep #(when (#{:system :developer} (:message/role %))
                           (instruction-text provider-id
                                             (:message/content %)))
                        messages))]
    {:parts (mapv (fn [text] {:text text}) texts)}))

(defn- image-inline-data [provider-id part]
  (let [url (:image/url part)
        data (:image/data part)
        mime (or (:image/mime-type part) "image/png")]
    (cond
      (seq data)
      {:inlineData {:mimeType mime :data data}}

      (and (string? url) (str/starts-with? url "data:"))
      (let [[header encoded] (str/split url #"," 2)
            mime (or (second (re-find #"data:([^;]+)" header)) mime)]
        (if (seq encoded)
          {:inlineData {:mimeType mime :data encoded}}
          (unsupported-input! provider-id part :malformed-data-url)))

      (seq url)
      {:fileData {:mimeType mime :fileUri url}}

      :else
      (unsupported-input! provider-id part :missing-image-source))))

(defn- file-part->gemini [provider-id part]
  (let [mime (t/file-mime-type part)]
    (cond
      (:file/url part)
      {:fileData {:mimeType mime
                  :fileUri (:file/url part)}}

      (t/file-data-for-input-file part)
      {:inlineData {:mimeType mime
                    :data (t/file-data-for-input-file part)}}

      (:file/id part)
      (unsupported-input! provider-id part :file-id)

      :else
      (t/missing-file-source! provider-id part))))

(defn- audio-part->gemini [part]
  {:inlineData
   {:mimeType (case (:audio/format part)
                :wav "audio/wav"
                :mp3 "audio/mpeg")
    :data (:audio/data part)}})

(defn- parse-tool-arguments [provider-id tool-call]
  (try
    (let [arguments (json/parse-string (:tool-call/arguments tool-call))]
      (if (map? arguments)
        arguments
        (unsupported-input! provider-id tool-call
                            :tool-call-arguments-must-be-object)))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info "Gemini tool-call arguments must be valid JSON"
                      {:provider provider-id
                       :tool-call/id (:tool-call/id tool-call)
                       :error/type :gemini/invalid-tool-call-arguments}
                      e)))))

(defn- tool-call-signature [tool-call]
  (or (get-in tool-call
              [:tool-call/provider-data :gemini/thought-signature])
      (get-in tool-call
              [:tool-call/provider-data :extra_content :google
               :thought_signature])))

(defn- tool-call->gemini [provider-id tool-call]
  (let [native-call (get-in tool-call
                            [:tool-call/provider-data :gemini/function-call])
        function-call
        (cond-> (merge (when (map? native-call) native-call)
                       {:name (:tool-call/name tool-call)
                        :args (parse-tool-arguments provider-id tool-call)})
          (:tool-call/id tool-call)
          (assoc :id (:tool-call/id tool-call)))
        part {:functionCall function-call}]
    (if-some [signature (tool-call-signature tool-call)]
      (assoc part :thoughtSignature signature)
      part)))

(defn- canonical-part->gemini [provider-id part]
  (case (:part/type part)
    :text {:text (:text part)}
    :image (image-inline-data provider-id part)
    :input-audio (audio-part->gemini part)
    :file (file-part->gemini provider-id part)
    :tool-call (tool-call->gemini provider-id part)
    :tool-result
    {:functionResponse
     (cond-> {:name (:tool-result/name part)
              :response {(if (:tool-result/is-error part) :error :output)
                         (:tool-result/content part)}}
       (:tool-result/id part) (assoc :id (:tool-result/id part)))}
    :reasoning
    (cond-> {:text (:reasoning/text part)
             :thought true}
      (some? (:reasoning/signature part))
      (assoc :thoughtSignature (:reasoning/signature part)))
    :unknown/provider-native
    (if (= provider-id (:unknown/provider part))
      (:unknown/data part)
      (unsupported-input! provider-id part :foreign-provider-native-part))
    (unsupported-input! provider-id part :unsupported-part-type)))

(defn- content->gemini-parts [provider-id content]
  (cond
    (nil? content) []
    (string? content) [{:text content}]
    (sequential? content)
    (reduce
     (fn [wire-parts part]
       (if (= :provider-state (:part/type part))
         (let [state-provider (:provider-state/provider part)
               state-data (:provider-state/data part)
               idx (:part-index state-data)
               signature (:thoughtSignature state-data)]
           (cond
             (not= provider-id state-provider)
             (unsupported-input! provider-id part :foreign-provider-state)

             (and (some? idx) (some? signature) (< idx (count wire-parts)))
             (assoc-in wire-parts [idx :thoughtSignature] signature)

             :else
             (unsupported-input! provider-id part :unsupported-provider-state)))
         (conj wire-parts (canonical-part->gemini provider-id part))))
     []
     content)
    :else
    (unsupported-input! provider-id {:part/type :message-content}
                        :content-must-be-string-or-parts)))

(defn- tool-call-key [tool-call]
  [(:tool-call/id tool-call)
   (:tool-call/name tool-call)
   (:tool-call/arguments tool-call)])

(defn- message-tool-calls [msg]
  (let [content-calls (if (sequential? (:message/content msg))
                        (filterv #(= :tool-call (:part/type %))
                                 (:message/content msg))
                        [])]
    (reduce
     (fn [calls tool-call]
       (if (some #(= (tool-call-key %) (tool-call-key tool-call)) calls)
         calls
         (conj calls tool-call)))
     content-calls
     (:message/tool-calls msg))))

(defn- apply-message-provider-state [provider-id msg wire-parts]
  (let [state (get-in msg [:message/provider-data provider-id])
        signature-parts (:parts state)]
    (reduce-kv
     (fn [parts idx part-state]
       (if-some [signature (:thoughtSignature part-state)]
         (if (and (int? idx) (<= 0 idx) (< idx (count parts)))
           (assoc-in parts [idx :thoughtSignature] signature)
           (unsupported-input!
            provider-id
            {:part/type :provider-state}
            :provider-state-part-index-out-of-range))
         parts))
     wire-parts
     (or signature-parts {}))))

(defn- message-content->gemini-parts [provider-id msg]
  (apply-message-provider-state
   provider-id
   msg
   (content->gemini-parts provider-id (:message/content msg))))

(defn- tool-message->gemini
  [provider-id msg tool-name-by-id]
  (let [content (:message/content msg)
        parts (when (sequential? content) content)
        unsupported (seq (remove #(#{:text :tool-result} (:part/type %))
                                 parts))
        tool-results (filterv #(= :tool-result (:part/type %)) parts)]
    (when unsupported
      (unsupported-input! provider-id (first unsupported)
                          :unsupported-tool-result-content))
    (when (< 1 (count tool-results))
      (unsupported-input! provider-id (second tool-results)
                          :multiple-tool-results-in-message))
    (when (and (seq tool-results)
               (some #(= :text (:part/type %)) parts))
      (unsupported-input! provider-id (first tool-results)
                          :mixed-tool-result-content))
    (let [tool-result (first tool-results)
          tool-call-id (or (:message/tool-call-id msg)
                           (:tool-result/id tool-result))
          tool-name (or (get tool-name-by-id tool-call-id)
                        (:message/name msg)
                        (:tool-result/name tool-result)
                        "tool")
          output (or (:tool-result/content tool-result)
                     (t/content->string content)
                     "")
          response (cond-> {:name tool-name
                            :response
                            {(if (:tool-result/is-error tool-result)
                               :error
                               :output)
                             output}}
                     tool-call-id (assoc :id tool-call-id))]
      {:role "user"
       :parts [{:functionResponse response}]})))

(defn- message->gemini [provider-id msg tool-name-by-id]
  (let [message-role (:message/role msg)
        role (case message-role
               (:user :tool) "user"
               :assistant "model"
               "user")
        content (:message/content msg)
        content-parts (when (sequential? content) content)
        assistant-only
        (first (filter #(#{:reasoning :provider-state
                           :unknown/provider-native}
                         (:part/type %))
                       content-parts))
        tool-result (first (filter #(= :tool-result (:part/type %))
                                   content-parts))
        tool-calls (message-tool-calls msg)]
    (when (and assistant-only (not= :assistant message-role))
      (unsupported-input! provider-id assistant-only
                          :part-requires-assistant-role))
    (when (and tool-result (not= :tool message-role))
      (unsupported-input! provider-id tool-result
                          :tool-result-requires-tool-role))
    (cond
      (= (:message/role msg) :tool)
      (tool-message->gemini provider-id msg tool-name-by-id)

      (seq tool-calls)
      (do
        (when-not (= :assistant (:message/role msg))
          (unsupported-input! provider-id (first tool-calls)
                              :tool-call-requires-assistant-role))
        (let [content-call-keys
              (set (map tool-call-key
                        (when (sequential? content)
                          (filter #(= :tool-call (:part/type %)) content))))
              additional-calls
              (remove #(contains? content-call-keys (tool-call-key %))
                      tool-calls)]
          {:role "model"
           :parts (into (message-content->gemini-parts provider-id msg)
                        (map #(tool-call->gemini provider-id %)
                             additional-calls))}))

      :else
      {:role role :parts (message-content->gemini-parts provider-id msg)})))

(defn- build-contents [provider-id messages]
  (let [tool-name-by-id
        (into {}
              (mapcat (fn [msg]
                        (map #(vector (:tool-call/id %) (:tool-call/name %))
                             (message-tool-calls msg)))
                      messages))]
    (mapv #(message->gemini provider-id % tool-name-by-id) messages)))

;; ---------------------------------------------------------------------------
;; Tool conversion
;; ---------------------------------------------------------------------------

(defn- tool->gemini [provider-id tool]
  (when-not (= :function (:type tool))
    (throw (ex-info "Gemini does not support canonical custom tools"
                    {:provider provider-id
                     :tool/type (:type tool)
                     :error/type :gemini/unsupported-tool})))
  (let [fn-data (:function tool)]
    (cond-> {:name (:name fn-data)
             :description (or (:description fn-data) "")}
      (:parameters fn-data)
      (assoc :parametersJsonSchema (:parameters fn-data)))))

;; ---------------------------------------------------------------------------
;; Thinking config
;; ---------------------------------------------------------------------------

(defn- reasoning-error!
  [provider-id model reason value]
  (throw (ex-info "Unsupported Gemini reasoning configuration"
                  {:provider provider-id
                   :model model
                   :reason reason
                   :value value
                   :error/type :gemini/unsupported-reasoning})))

(defn- normalized-thinking-effort [effort]
  (if (#{:xhigh :max} effort) :high effort))

(defn- gemini-3-supported-efforts [model-lower]
  (cond
    (re-find #"^gemini-3\.(?:8|7)-flash" model-lower)
    #{:low :medium :high}

    (str/includes? model-lower "flash-lite-image")
    #{:minimal :high}

    (str/includes? model-lower "pro")
    (if (str/starts-with? model-lower "gemini-3.1-")
      #{:low :medium :high}
      #{:low :high})

    :else
    #{:minimal :low :medium :high}))

(defn- valid-gemini-25-budget? [model-lower budget]
  (or (nil? budget)
      (= -1 budget)
      (and
       (integer? budget)
       (cond
         (str/includes? model-lower "pro")
         (<= 128 budget 32768)

         (str/includes? model-lower "flash-lite")
         (or (zero? budget) (<= 512 budget 24576))

         (str/includes? model-lower "flash")
         (<= 0 budget 24576)

         :else false))))

(defn- include-thoughts? [reasoning]
  (not (or (= true (:exclude reasoning))
           (= :none (:summary reasoning)))))

(defn- build-thinking-config [provider-id model reasoning]
  (when reasoning
    (let [model-lower (str/replace-first
                       (str/lower-case (or model ""))
                       #"^models/"
                       "")
          gemini-25? (str/starts-with? model-lower "gemini-2.5-")
          gemini-3? (str/starts-with? model-lower "gemini-3")
          enabled (:enabled reasoning)
          budget (:budget reasoning)
          effort-present? (contains? reasoning :effort)
          effort (:effort reasoning)
          normalized-effort (normalized-thinking-effort effort)
          disabled? (or (= false enabled)
                        (= :none effort)
                        (= 0 budget))]
      (when (and (= true enabled)
                 (or (= :none effort) (= 0 budget)))
        (reasoning-error! provider-id model :enabled-conflicts-with-disable
                          reasoning))
      (when (and (= false enabled)
                 (or (and effort-present? (not= :none effort))
                     (and (some? budget) (not (zero? budget)))))
        (reasoning-error! provider-id model :disabled-conflicts-with-effort
                          reasoning))
      (when (and effort-present? (some? budget))
        (reasoning-error! provider-id model :effort-conflicts-with-budget
                          reasoning))
      (cond
        gemini-25?
        (do
          (when (and effort-present? (not= :none effort))
            (reasoning-error! provider-id model
                              :thinking-effort-requires-gemini-3
                              effort))
          (when-not (valid-gemini-25-budget? model-lower budget)
            (reasoning-error! provider-id model :invalid-thinking-budget
                              budget))
          (if disabled?
            (if (str/includes? model-lower "pro")
              (reasoning-error! provider-id model
                                :model-cannot-disable-thinking reasoning)
              {:includeThoughts false :thinkingBudget 0})
            (cond-> {:includeThoughts (include-thoughts? reasoning)}
              (some? budget) (assoc :thinkingBudget budget))))

        gemini-3?
        (do
          (when (some? budget)
            (reasoning-error! provider-id model
                              :thinking-budget-requires-gemini-2.5
                              budget))
          (when disabled?
            (reasoning-error! provider-id model
                              :model-cannot-disable-thinking reasoning))
          (when (and effort-present?
                     (not (contains? (gemini-3-supported-efforts model-lower)
                                     normalized-effort)))
            (reasoning-error! provider-id model
                              :unsupported-thinking-effort effort))
          (cond-> {:includeThoughts (include-thoughts? reasoning)}
            effort-present?
            (assoc :thinkingLevel (name normalized-effort))))

        :else
        (reasoning-error! provider-id model :model-does-not-support-thinking
                          reasoning)))))

(defn- response-format->gemini [response-format]
  (case (:type response-format)
    :text {:responseMimeType "text/plain"}
    :json_object {:responseMimeType "application/json"}
    :json_schema (cond-> {:responseMimeType "application/json"}
                   (:json-schema response-format)
                   (assoc :responseJsonSchema (:json-schema response-format)))
    nil))

(defn- tool-choice->gemini [provider-id tool-choice]
  (case tool-choice
    :auto {:functionCallingConfig {:mode "AUTO"}}
    :required {:functionCallingConfig {:mode "ANY"}}
    :none {:functionCallingConfig {:mode "NONE"}}
    (if (and (map? tool-choice) (= :function (:type tool-choice)))
      {:functionCallingConfig
       {:mode "ANY"
        :allowedFunctionNames [(get-in tool-choice [:function :name])]}}
      (throw (ex-info "Gemini does not support this tool choice"
                      {:provider provider-id
                       :tool-choice tool-choice
                       :error/type :gemini/unsupported-tool-choice})))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-gemini
  [profile request]
  (let [provider-id (or (:profile/id profile) :gemini-native)
        model (:request/model request)
        model-norm (if (str/starts-with? (str/lower-case model) "models/")
                     model
                     (str "models/" model))
        messages (remove #(#{:system :developer} (:message/role %))
                         (:request/messages request))
        system-inst (extract-system provider-id (:request/messages request))
        contents (build-contents provider-id messages)
        tools (when (seq (:request/tools request))
                [{:functionDeclarations
                  (mapv #(tool->gemini provider-id %)
                        (:request/tools request))}])
        thinking (build-thinking-config provider-id model
                                        (:request/reasoning request))
        response-format (response-format->gemini (:request/response-format request))
        extra-body (or (get-in request [:request/provider-options :extra_body]) {})
        canonical-generation-config
        (cond-> {}
          (some? (:request/temperature request))
          (assoc :temperature (:request/temperature request))
          (some? (:request/top-p request))
          (assoc :topP (:request/top-p request))
          (some? (:request/max-tokens request))
          (assoc :maxOutputTokens (:request/max-tokens request))
          (some? (:request/stop request))
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
         (when-some [tool-choice (:request/tool-choice request)]
           {:toolConfig (tool-choice->gemini provider-id tool-choice)}))
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
       (cond-> {:gemini/function-call-id call-id
                :gemini/function-call fc
                :gemini/part-index idx}
         (some? (:thoughtSignature part))
         (assoc :gemini/thought-signature (:thoughtSignature part)
                :extra_content
                {:google {:thought_signature (:thoughtSignature part)}}))})

    (:thought part)
    (cond-> {:part/type :reasoning
             :reasoning/text (or (:text part) "")
             :reasoning/encrypted false}
      (some? (:thoughtSignature part))
      (assoc :reasoning/signature (:thoughtSignature part)))

    (contains? part :text)
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

(defn- citation-source->part [provider-id source]
  (cond-> {:part/type :citation
           :citation/source provider-id
           :citation/provider-data {:gemini/citation-source source}}
    (some? (:uri source)) (assoc :citation/url (:uri source))
    (and (some? (:startIndex source)) (some? (:endIndex source)))
    (assoc :citation/text-range [(:startIndex source) (:endIndex source)])))

(defn- response-citation-parts [provider-id candidate]
  (mapv #(citation-source->part provider-id %)
        (get-in candidate [:citationMetadata :citationSources])))

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
                    (and (some? signature) (not signature-native?))
                    (conj {:part/type :provider-state
                           :provider-state/provider provider-id
                           :provider-state/data
                           {:part-index idx
                            :thoughtSignature signature}})))))
             (apply concat)
             vec)
        tool-calls (vec (filter #(= (:part/type %) :tool-call) canonical-parts))
        prompt-feedback (:promptFeedback raw)
        native-finish-reason
        (if (:blockReason prompt-feedback)
          :content-filter
          (get finish-reason-map (:finishReason candidate) :unknown))
        finish-reason
        (if (and (seq tool-calls) (= :stop native-finish-reason))
          :tool-calls
          native-finish-reason)
        usage-raw (:usageMetadata raw)
        safety-parts (response-safety-parts candidate prompt-feedback)
        citation-parts (response-citation-parts provider-id candidate)]
    (cond-> {:response/provider provider-id
             :response/model (:modelVersion raw)
             :response/parts (into (into canonical-parts citation-parts)
                                   safety-parts)
             :response/finish-reason finish-reason
             :response/provider-data
             (cond-> {:gemini/candidates (:candidates raw)}
               (:citationMetadata candidate)
               (assoc :gemini/citation-metadata (:citationMetadata candidate))
               (:groundingMetadata candidate)
               (assoc :gemini/grounding-metadata
                      (:groundingMetadata candidate))
               (:urlContextMetadata candidate)
               (assoc :gemini/url-context-metadata
                      (:urlContextMetadata candidate))
               (:responseId raw) (assoc :gemini/response-id (:responseId raw))
               prompt-feedback (assoc :gemini/prompt-feedback prompt-feedback)
               (:modelStatus raw) (assoc :gemini/model-status
                                         (:modelStatus raw)))
             :response/raw raw}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      usage-raw (assoc :response/usage (normalize-gemini-usage usage-raw)))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- normalize-gemini-stream-usage [raw]
  (let [prompt (:promptTokenCount raw)
        completion (:candidatesTokenCount raw)
        total (:totalTokenCount raw)
        cached (:cachedContentTokenCount raw)
        reasoning (:thoughtsTokenCount raw)]
    (cond-> {:usage/provider-raw raw}
      (some? prompt)
      (assoc :usage/input-tokens
             (max 0 (- (usage/->int prompt)
                       (if (some? cached) (usage/->int cached) 0))))
      (some? completion)
      (assoc :usage/output-tokens (usage/->int completion))
      (some? total)
      (assoc :usage/total-tokens (usage/->int total))
      (some? cached)
      (assoc :usage/cached-input-tokens (usage/->int cached))
      (some? reasoning)
      (assoc :usage/reasoning-tokens (usage/->int reasoning)))))

(defn- citation-source->event [provider-id source]
  (stream/citation-event
   (:uri source)
   :text-range (when (and (some? (:startIndex source))
                          (some? (:endIndex source)))
                 [(:startIndex source) (:endIndex source)])
   :source provider-id
   :provider-data {:gemini/citation-source source}))

(defn- stream-tool-index [wire-index function-call]
  ;; GenerateContent has no stream-level part index: each SSE chunk starts its
  ;; local parts vector at zero. Provider call ids make indexes stable across
  ;; chunks while the wire index distinguishes parallel id-less calls.
  (if-let [call-id (:id function-call)]
    (bit-and (hash call-id) 0x7fffffff)
    (bit-and (hash [wire-index
                    (:name function-call)
                    (:args function-call)])
             0x7fffffff)))

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
    (let [provider-id (or (:profile/id profile) :gemini-native)
          candidate (first (:candidates data))
          parts (:parts (:content candidate))
          part-events
          (mapcat
           (fn [[idx part]]
             (let [signature (:thoughtSignature part)
                   signature-state
                   (when (and (some? signature)
                              (contains? part :text)
                              (not (:thought part)))
                     (stream/provider-state-event
                      provider-id
                      {:parts {idx {:thoughtSignature signature}}}))]
               (cond
                 (:functionCall part)
                 (let [fc (:functionCall part)
                       tool-index (stream-tool-index idx fc)
                       call-id (or (:id fc) (str "gemini_call_" tool-index))
                       args (json/generate-string (or (:args fc) {}))
                       provider-data
                       (cond-> {:gemini/function-call-id call-id
                                :gemini/function-call fc
                                :gemini/part-index idx}
                         (some? signature)
                         (assoc :gemini/thought-signature signature
                                :extra_content
                                {:google {:thought_signature signature}}))]
                   [(stream/tool-call-start
                     tool-index call-id (or (:name fc) "")
                     :provider-data provider-data)
                    (stream/tool-call-delta tool-index args)
                    (stream/tool-call-end tool-index)])

                 (or (:thought part)
                     (and (some? signature)
                          (not (contains? part :text))))
                 [(stream/reasoning-delta
                   (when (contains? part :text) (:text part))
                   :index idx
                   :signature signature)]

                 (contains? part :text)
                 (cond-> [(stream/content-delta (:text part))]
                   signature-state (conj signature-state))

                 :else
                 [(stream/provider-state-event provider-id
                                               {:parts {idx part}})])))
           (map-indexed vector (or parts [])))
          citation-events
          (mapv #(citation-source->event provider-id %)
                (get-in candidate [:citationMetadata :citationSources]))
          candidate-state
          (select-keys candidate
                       [:citationMetadata :groundingMetadata
                        :groundingAttributions :urlContextMetadata
                        :finishMessage :logprobsResult])
          state-data
          (cond-> {}
            (seq candidate-state) (assoc :gemini/candidate-state candidate-state)
            (:responseId data) (assoc :gemini/response-id (:responseId data))
            (:modelVersion data) (assoc :gemini/model-version
                                        (:modelVersion data))
            (:modelStatus data) (assoc :gemini/model-status (:modelStatus data))
            (:promptFeedback data) (assoc :gemini/prompt-feedback
                                          (:promptFeedback data)))
          state-event (when (seq state-data)
                        (stream/provider-state-event provider-id state-data))
          usage-event (when-let [raw-usage (:usageMetadata data)]
                        (stream/usage-event
                         (normalize-gemini-stream-usage raw-usage)))
          finish-event (when-let [finish (:finishReason candidate)]
                         (stream/end-event
                          :finish-reason
                          (get finish-reason-map finish :unknown)))
          events (cond-> (into (vec part-events) citation-events)
                   state-event (conj state-event)
                   usage-event (conj usage-event)
                   finish-event (conj finish-event))]
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
    #{:chat :streaming :tools :multimodal :reasoning :file-attachments
      :json-schema :cache}))

(defn make-transport []
  (->GeminiNativeTransport))

;; Register
(when-let [p (provider/get-provider :gemini-native)]
  (provider/register-provider
   (assoc p
          :profile/capabilities
          (conj (:profile/capabilities p) :json-schema :cache)
          :profile/transport-constructor
          make-transport)))
