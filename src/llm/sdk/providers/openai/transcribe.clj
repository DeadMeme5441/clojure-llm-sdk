(ns llm.sdk.providers.openai.transcribe
  "OpenAI and Groq /audio/transcriptions adapters. The endpoints share
   multipart mechanics, but provider-specific request policies are enforced
   before fields are serialized."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [llm.sdk.transport.transcribe :as tt]
            [llm.sdk.provider :as provider]
            [llm.sdk.sse :as sse]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Multipart body construction
;; ---------------------------------------------------------------------------

(defn- file-content
  "Coerce caller-provided :transcribe/file into something hato's
   multipart serializer accepts (java.io.File, InputStream, or bytes)."
  [file]
  (cond
    (instance? java.io.File file) file
    (instance? java.io.InputStream file) file
    (bytes? file) file
    (string? file) (io/file file)
    :else
    (throw (ex-info "Unsupported :transcribe/file type"
                    {:type (class file)}))))

(defn- guess-filename [file fallback]
  (cond
    (instance? java.io.File file) (.getName ^java.io.File file)
    (string? fallback) fallback
    :else "audio.wav"))

(defn- multipart-content [value]
  (cond
    (keyword? value) (name value)
    (map? value) (json/generate-string value)
    :else (str value)))

(defn- repeated-parts [field values]
  (mapv #(hash-map :name field :content (multipart-content %)) values))

(defn- provider-option-parts [options]
  (let [chunking (or (:chunking_strategy options)
                     (:chunking-strategy options))]
    (into []
          (concat
           (when (contains? options :stream)
             [{:name "stream" :content (str (boolean (:stream options)))}])
           (when (some? chunking)
             [{:name "chunking_strategy" :content (multipart-content chunking)}])
           (when-let [response-format (or (:response_format options)
                                          (:response-format options))]
             [{:name "response_format"
               :content (multipart-content response-format)}])
           (repeated-parts "include[]" (:include options))
           (repeated-parts "keywords[]" (:keywords options))
           (repeated-parts "languages[]" (:languages options))
           (repeated-parts "known_speaker_names[]"
                           (or (:known_speaker_names options)
                               (:known-speaker-names options)))
           (repeated-parts "known_speaker_references[]"
                           (or (:known_speaker_references options)
                               (:known-speaker-references options)))
           (:multipart options)))))

(def ^:private groq-response-formats #{"json" "verbose_json" "text"})

(def ^:private groq-unsupported-request-keys
  [:transcribe/stream
   :transcribe/include
   :transcribe/keywords
   :transcribe/languages
   :transcribe/chunking-strategy
   :transcribe/known-speaker-names
   :transcribe/known-speaker-references])

(def ^:private groq-provider-option-keys
  #{:response_format :response-format})

(defn- reject-option! [profile option]
  (throw (ex-info (str "Transcription option " option
                       " is not supported by " (name (:profile/id profile)))
                  {:error/type :transcribe/unsupported-option
                   :provider (:profile/id profile)
                   :option option})))

(defn- validate-request-policy! [profile request]
  (let [provider-id (:profile/id profile)
        options (or (:transcribe/provider-options request) {})
        languages (or (:transcribe/languages request) (:languages options))]
    (when (and (= :openai provider-id)
               (seq languages)
               (contains? request :transcribe/language))
      (throw (ex-info "OpenAI languages[] replaces the singular language field"
                      {:error/type :transcribe/conflicting-language-options
                       :provider provider-id
                       :options [:transcribe/language :transcribe/languages]})))
    (when (= :groq provider-id)
      (when-let [option (some #(when (contains? request %) %)
                              groq-unsupported-request-keys)]
        (reject-option! profile option))
      (let [response-format (or (:transcribe/response-format request)
                                (:response_format options)
                                (:response-format options))]
        (when (and response-format
                   (not (contains? groq-response-formats
                                   (multipart-content response-format))))
          (reject-option! profile :transcribe/response-format)))
      (when-let [option (first (remove groq-provider-option-keys
                                      (keys options)))]
        (reject-option! profile option)))))

(defn- groq-provider-option-parts [options]
  (when-let [response-format (or (:response_format options)
                                 (:response-format options))]
    [{:name "response_format"
      :content (multipart-content response-format)}]))

(defn build-request
  [profile request]
  (validate-request-policy! profile request)
  (let [file (:transcribe/file request)
        fname (guess-filename file (:transcribe/filename request))
        model (:transcribe/model request)
        canonical-opts
        (cond-> {}
          (contains? request :transcribe/stream)
          (assoc :stream (:transcribe/stream request))
          (:transcribe/include request)
          (assoc :include (:transcribe/include request))
          (:transcribe/keywords request)
          (assoc :keywords (:transcribe/keywords request))
          (:transcribe/languages request)
          (assoc :languages (:transcribe/languages request))
          (:transcribe/chunking-strategy request)
          (assoc :chunking-strategy (:transcribe/chunking-strategy request))
          (:transcribe/known-speaker-names request)
          (assoc :known-speaker-names (:transcribe/known-speaker-names request))
          (:transcribe/known-speaker-references request)
          (assoc :known-speaker-references
                 (:transcribe/known-speaker-references request)))
        provider-opts (merge (:transcribe/provider-options request)
                             canonical-opts)
        granularities (:transcribe/timestamp-granularities request)
        _ (when (boolean (:stream provider-opts))
            (throw (ex-info "Streaming transcription is not supported by the synchronous transport"
                            {:error/type :transcribe/streaming-unsupported
                             :provider (:profile/id profile)})))
        parts (cond-> [{:name "file"
                        :content (file-content file)
                        :file-name fname}
                       {:name "model" :content (str model)}]
                (:transcribe/language request)
                (conj {:name "language"
                       :content (str (:transcribe/language request))})
                (:transcribe/prompt request)
                (conj {:name "prompt"
                       :content (str (:transcribe/prompt request))})
                (:transcribe/temperature request)
                (conj {:name "temperature"
                       :content (str (:transcribe/temperature request))})
                (:transcribe/response-format request)
                (conj {:name "response_format"
                       :content (name (:transcribe/response-format request))})
                (seq granularities)
                (#(reduce (fn [acc g]
                            (conj acc {:name "timestamp_granularities[]"
                                       :content (name g)}))
                          % granularities)))
        parts (into parts
                    ((if (= :groq (:profile/id profile))
                       groq-provider-option-parts
                       provider-option-parts)
                     provider-opts))]
    {:method :post
     :url (str (:profile/base-url profile) "/audio/transcriptions")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :multipart parts}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- normalize-transcription-usage [raw]
  (case (:type raw)
    "tokens"
    (-> (usage/normalize-openai-usage
         (cond-> raw
           (:input_token_details raw)
           (assoc :input_tokens_details (:input_token_details raw))))
        (assoc :usage/provider-raw raw))

    "duration"
    (when (number? (:seconds raw))
      {:usage/duration-seconds (:seconds raw)
       :usage/provider-raw raw})

    nil))

(defn parse-response
  [_profile raw]
  (let [base
        (cond
          (and (map? raw) (:text raw))
          (cond-> {:transcription/text (:text raw)
                   :response/raw raw}
            (:language raw) (assoc :transcription/language (:language raw))
            (:languages raw) (assoc :transcription/languages (:languages raw))
            (:logprobs raw) (assoc :transcription/logprobs (:logprobs raw))
            (:segments raw) (assoc :transcription/segments (vec (:segments raw)))
            (:words raw) (assoc :transcription/words (vec (:words raw))))

          ;; plain text response (response_format=text|srt|vtt)
          (string? raw)
          {:transcription/text raw
           :response/raw raw}

          :else
          {:transcription/text ""
           :response/raw raw})
        normalized-usage (when (map? (:usage raw))
                           (normalize-transcription-usage (:usage raw)))
        duration (or (:duration raw)
                     (:usage/duration-seconds normalized-usage))]
    (cond-> base
      (some? duration)
      (assoc :transcription/duration-seconds duration)

      normalized-usage
      (assoc :response/usage normalized-usage))))

(defn parse-stream-event
  "Normalize an OpenAI transcription SSE event for integrations that already
   own a streaming HTTP connection. The public TranscribeTransport remains
   request/response-only and rejects :stream true."
  [profile line]
  (when-let [data (sse/parse-json-data line)]
    (case (:type data)
      "transcript.text.delta"
      (stream/content-delta (:delta data))

      "transcript.text.segment"
      (stream/provider-state-event (:profile/id profile)
                                   {:transcription/segment data})

      "transcript.text.done"
      (let [events (cond-> [(stream/provider-state-event
                             (:profile/id profile)
                             {:transcription/done
                              (select-keys data [:text :logprobs])})]
                     (= "tokens" (get-in data [:usage :type]))
                     (conj (stream/usage-event
                            (normalize-transcription-usage (:usage data))))
                     true
                     (conj (stream/end-event :finish-reason :stop)))]
        events)

      nil)))

(defn parse-error
  [profile status body]
  (errors/classify-error (Exception. "Transcription API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenAITranscribeTransport []
  tt/TranscribeTransport
  (build-transcribe-request [_ profile request] (build-request profile request))
  (parse-transcribe-response [_ profile raw] (parse-response profile raw))
  (parse-transcribe-error [_ profile status body] (parse-error profile status body))
  (normalize-transcribe-usage [_ _ raw] (normalize-transcription-usage raw)))

(defn make-transport [] (->OpenAITranscribeTransport))

;; Attach to :openai (whisper-1 and current gpt transcription models) and
;; :groq (whisper-large-v3 and whisper-large-v3-turbo).
(doseq [pid [:openai :groq]]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (assoc p :profile/transcribe-transport-constructor make-transport))))
