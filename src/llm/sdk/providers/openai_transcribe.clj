(ns llm.sdk.providers.openai-transcribe
  "OpenAI /audio/transcriptions adapter. Wire shape is shared by Groq's
   /openai/v1/audio/transcriptions endpoint (same field names, same
   verbose_json output), so the same transport class powers both
   profiles."
  (:require [clojure.java.io :as io]
            [llm.sdk.transport.transcribe :as tt]
            [llm.sdk.provider :as provider]
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
    (bytes? file) (io/input-stream file)
    (string? file) (io/file file)
    :else
    (throw (ex-info "Unsupported :transcribe/file type"
                    {:type (class file)}))))

(defn- guess-filename [file fallback]
  (cond
    (instance? java.io.File file) (.getName ^java.io.File file)
    (string? fallback) fallback
    :else "audio.wav"))

(defn build-request
  [profile request]
  (let [file (:transcribe/file request)
        fname (guess-filename file (:transcribe/filename request))
        model (:transcribe/model request)
        provider-opts (:transcribe/provider-options request)
        granularities (:transcribe/timestamp-granularities request)
        parts (cond-> [{:name "file"
                        :content (file-content file)
                        :filename fname}
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
        ;; Tack on provider-specific fields (e.g. Groq's :temperature
        ;; or :prompt) when the caller pre-shapes them as a vector of
        ;; multipart entries.
        parts (into parts (or (:multipart provider-opts) []))]
    {:method :post
     :url (str (:profile/base-url profile) "/audio/transcriptions")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :multipart parts}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn parse-response
  [_profile raw]
  (cond
    ;; verbose_json
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
     :response/raw raw}))

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
  (normalize-transcribe-usage [_ _ raw] raw))

(defn make-transport [] (->OpenAITranscribeTransport))

;; Attach to :openai (whisper-1, gpt-4o-transcribe) and :groq
;; (whisper-large-v3, distil-whisper-large-v3-en) — same wire shape.
(doseq [pid [:openai :groq]]
  (when-let [p (provider/get-provider pid)]
    (provider/register-provider
     (assoc p :profile/transcribe-transport-constructor make-transport))))
