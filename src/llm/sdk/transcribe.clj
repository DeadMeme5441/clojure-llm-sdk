(ns llm.sdk.transcribe
  "Driver for audio transcription (speech-to-text). The STT counterpart
   to sdk/complete and sdk/embed.

   Providers without a transcribe transport throw ex-info on call so
   the missing capability surfaces at the call site."
  (:require [hato.client :as hc]
            [cheshire.core :as json]
            [clojure.string :as str]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.errors :as errors]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.transport.transcribe :as tt]))

(defn- stamp-transcription-cost [provider-id request parsed]
  (if (contains? parsed :response/cost)
    parsed
    (let [model (:transcribe/model request)
          usage (:response/usage parsed)
          duration (or (:usage/duration-seconds usage)
                       (:transcription/duration-seconds parsed))
          pricing (pricing/get-pricing provider-id model)
          token-usage?
          (and usage
               (some #(contains? usage %)
                     [:usage/input-tokens :usage/output-tokens
                      :usage/audio-tokens]))
          cost
          (if token-usage?
            (pricing/canonical-cost
             provider-id model usage
             {:input-modality :audio :output-modality :text})
            (let [result (pricing/transcription-cost
                          {:usage usage :duration-seconds duration}
                          pricing)]
              (pricing/cost-result->canonical
               result
               pricing
               (cond-> {}
                 (some? duration) (assoc :duration-seconds duration)
                 (:transcription-cost-per-minute pricing)
                 (assoc :transcription-cost-per-minute
                        (:transcription-cost-per-minute pricing))))))]
      (assoc parsed :response/cost cost))))

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

(defn- event-stream-response? [response]
  (let [content-type (or (get-in response [:headers "content-type"])
                         (get-in response [:headers "Content-Type"]))]
    (and (string? content-type)
         (str/includes? (str/lower-case content-type) "text/event-stream"))))

(defn transcribe
  "Send a canonical TranscribeRequest and return a TranscribeResponse.

   Request keys:
     :transcribe/model     model id (e.g. \"whisper-1\")
     :transcribe/file      java.io.File, path string, byte array, or InputStream
     :transcribe/filename  filename hint (informs server file-type detection)
     :transcribe/language  optional ISO-639-1 hint (e.g. \"en\")
     :transcribe/languages optional language hints for gpt-transcribe
     :transcribe/keywords  optional literal context terms for gpt-transcribe
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
    (cond
      (>= status 400)
      (let [err (tt/parse-transcribe-error transport profile status body)]
        (throw (ex-info "Provider transcribe API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))

      (event-stream-response? resp)
      (let [err {:error/reason :unsupported-parameter
                 :error/retryable false
                 :error/message
                 "Buffered transcription transport cannot consume an SSE response"}]
        (throw (ex-info "Provider returned a streaming transcription response"
                        {:error err
                         :error/type :transcribe/streaming-response-unsupported
                         :status status
                         :body body
                         :provider provider-id})))

      :else
      (stamp-transcription-cost
       provider-id request
       (tt/parse-transcribe-response transport profile body)))))
