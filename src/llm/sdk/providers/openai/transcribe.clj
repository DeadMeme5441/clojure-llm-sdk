(ns llm.sdk.providers.openai.transcribe
  "OpenAI /audio/transcriptions adapter. Wire shape is shared by Groq's
   /openai/v1/audio/transcriptions endpoint (same field names, same
   verbose_json output), so the same transport class powers both
   profiles."
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
           (repeated-parts "known_speaker_names[]"
                           (or (:known_speaker_names options)
                               (:known-speaker-names options)))
           (repeated-parts "known_speaker_references[]"
                           (or (:known_speaker_references options)
                               (:known-speaker-references options)))
           (:multipart options)))))

(defn build-request
  [profile request]
  (let [file (:transcribe/file request)
        fname (guess-filename file (:transcribe/filename request))
        model (:transcribe/model request)
        canonical-opts
        (cond-> {}
          (contains? request :transcribe/stream)
          (assoc :stream (:transcribe/stream request))
          (:transcribe/include request)
          (assoc :include (:transcribe/include request))
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
        ;; Current OpenAI-only multipart fields are surfaced through the
        ;; provider-options escape hatch until they have canonical keys.
        parts (into parts (provider-option-parts provider-opts))]
    {:method :post
     :url (str (:profile/base-url profile) "/audio/transcriptions")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :multipart parts}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- normalize-transcription-usage [raw]
  (if (= "tokens" (:type raw))
    (usage/normalize-openai-usage raw)
    raw))

(defn parse-response
  [_profile raw]
  (let [base
        (cond
          ;; verbose_json and diarized_json
          (and (map? raw) (or (:segments raw) (:words raw) (:language raw)))
          (cond-> {:transcription/text (:text raw)
                   :response/raw raw}
            (:language raw) (assoc :transcription/language (:language raw))
            (:duration raw) (assoc :transcription/duration-seconds (:duration raw))
            (:segments raw) (assoc :transcription/segments (vec (:segments raw)))
            (:words raw) (assoc :transcription/words (vec (:words raw))))

          ;; default json {"text": "..."}
          (and (map? raw) (:text raw))
          {:transcription/text (:text raw)
           :response/raw raw}

          ;; plain text response (response_format=text|srt|vtt)
          (string? raw)
          {:transcription/text raw
           :response/raw raw}

          :else
          {:transcription/text ""
           :response/raw raw})]
    (if (= "tokens" (get-in raw [:usage :type]))
      (assoc base :response/usage
             (normalize-transcription-usage (:usage raw)))
      base)))

(defn parse-stream-event
  "Parse current OpenAI transcription SSE events. This is exported even
   though TranscribeTransport is request/response-only, so callers using the
   provider escape hatch `{:stream true}` can normalize the event stream."
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

;; Attach to :openai (whisper-1, gpt-4o-transcribe) and :groq
;; (whisper-large-v3, distil-whisper-large-v3-en) — same wire shape.
(doseq [pid [:openai :groq]]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (assoc p :profile/transcribe-transport-constructor make-transport))))
