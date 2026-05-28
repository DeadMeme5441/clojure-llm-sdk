(ns llm.sdk.transcribe
  "Driver for audio transcription (speech-to-text). The STT counterpart
   to sdk/complete and sdk/embed.

   Providers without a transcribe transport throw ex-info on call so
   the missing capability surfaces at the call site."
  (:require [hato.client :as hc]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.errors :as errors]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.transport.transcribe :as tt]))

(defn- stamp-transcription-cost [provider-id request parsed]
  (let [model (:transcribe/model request)
        duration (:transcription/duration-seconds parsed)
        pricing (pricing/get-pricing provider-id model)
        result (pricing/transcription-cost {:duration-seconds duration} pricing)
        cost (pricing/cost-result->canonical
              result
              pricing
              (cond-> {}
                duration (assoc :duration-seconds duration)))]
    (assoc parsed :response/cost cost)))

(defn- http-client [{:keys [http-client connect-timeout-ms timeout-ms]}]
  (or http-client
      (hc/build-http-client {:connect-timeout (or connect-timeout-ms 30000)
                             :timeout (or timeout-ms 120000)})))

(defn- multipart-request
  "Hato multipart upload. We bypass llm.sdk.http because the body is
   binary and the Content-Type header must be set by hato to include
   the multipart boundary."
  [{:keys [method url headers multipart] :as req}]
  (let [resp (hc/request
              {:method method
               :url url
               :headers headers
               :multipart multipart
               :http-client (http-client req)
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
  [provider-id request & {:keys [config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (schema/validate-transcribe-request request)
            (throw (ex-info "Invalid llm.sdk transcribe request"
                            {:error/type :schema/invalid-transcribe-request
                             :schema/explain (schema/explain-transcribe-request request)})))
        ctor (:profile/transcribe-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Transcription not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (tt/build-transcribe-request transport profile request)
        req (provider/apply-http-options profile req)
        resp (try
               (multipart-request req)
               (catch Exception e
                 (throw (ex-info "Provider transcribe transport error"
                                 {:error (errors/classify-error e :provider provider-id)
                                  :provider provider-id}
                                 e))))
        status (:status resp)
        body (:body resp)]
    (if (>= status 400)
      (let [err (tt/parse-transcribe-error transport profile status body)]
        (throw (ex-info "Provider transcribe API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (stamp-transcription-cost provider-id request
                                (tt/parse-transcribe-response transport profile body)))))
