(ns llm.sdk.transcribe
  "Driver for audio transcription (speech-to-text). The STT counterpart
   to sdk/complete and sdk/embed.

   Providers without a transcribe transport throw ex-info on call so
   the missing capability surfaces at the call site."
  (:require [hato.client :as hc]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.transcribe :as tt]))

(defn- multipart-request
  "Hato multipart upload. We bypass llm.sdk.http because the body is
   binary and the Content-Type header must be set by hato to include
   the multipart boundary."
  [{:keys [method url headers multipart]}]
  (let [resp (hc/request
              {:method method
               :url url
               :headers headers
               :multipart multipart
               :throw-exceptions? false})
        body (:body resp)
        ct (or (get-in resp [:headers "content-type"])
               (get-in resp [:headers "Content-Type"]) "")
        parsed (if (and body
                        (string? ct)
                        (.contains ct "application/json"))
                 (try (json/parse-string body true)
                      (catch Exception _ body))
                 body)]
    {:status (:status resp)
     :headers (:headers resp)
     :body parsed}))

(defn transcribe
  "Send a canonical TranscribeRequest and return a TranscribeResponse.

   Request keys:
     :transcribe/model     model id (e.g. \"whisper-1\")
     :transcribe/file      java.io.File, path string, byte array, or InputStream
     :transcribe/filename  filename hint (informs server file-type detection)
     :transcribe/language  optional ISO-639-1 hint (e.g. \"en\")
     :transcribe/prompt    optional spelling/style prompt
     :transcribe/temperature   optional [0,1]
     :transcribe/response-format  :json|:text|:srt|:verbose_json|:vtt
     :transcribe/timestamp-granularities #{:segment :word}
     :transcribe/provider-options  extra provider-specific fields

   Response:
     {:transcription/text str
      :transcription/language str?
      :transcription/segments [...]
      :transcription/words [...]
      :transcription/duration-seconds num?
      :response/usage Usage?
      :response/raw raw}"
  [provider-id request]
  (let [profile (or (provider/get-provider provider-id)
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        ctor (:profile/transcribe-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Transcription not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (tt/build-transcribe-request transport profile request)
        resp (multipart-request req)
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (tt/parse-transcribe-error transport profile status body)]
        (throw (ex-info "Provider transcribe API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (tt/parse-transcribe-response transport profile body))))
