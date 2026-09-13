(ns llm.sdk.transcribe
  "Driver for audio transcription (speech-to-text). The STT counterpart
   to sdk/complete and sdk/embed.

   Providers without a transcribe transport throw ex-info on call so
   the missing capability surfaces at the call site."
  (:require [clojure.string :as str]
            [hato.client :as hc]
            [llm.sdk.http :as http]
            [llm.sdk.operation :as operation]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.schema :as schema]
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

(defn- text-response-format? [format]
  (contains? #{:text :srt :vtt "text" "srt" "vtt"} format))

(defn- multipart-request
  "Execute a multipart upload while preserving hato's generated boundary.
   JSON is decoded opportunistically; plain text, SRT, and VTT remain strings."
  [{:keys [method url headers multipart] :as request} response-format]
  (let [response
        (hc/request
         (merge (http/request-options request)
                {:method method
                 :url url
                 :headers headers
                 :multipart multipart
                 :throw-exceptions? false}))
        status (:status response)
        raw-body (:body response)
        body (if (and (number? status)
                      (<= 200 status 299)
                      (text-response-format? response-format))
               raw-body
               (http/decode-body raw-body))]
    {:status status
     :headers (:headers response)
     :body body}))

(defn- header-value [headers header-name]
  (some (fn [[key value]]
          (when (= (str/lower-case (if (keyword? key) (name key) (str key)))
                   header-name)
            value))
        headers))

(defn- event-stream-response? [response]
  (let [content-type (header-value (:headers response) "content-type")]
    (and (string? content-type)
         (str/includes? (str/lower-case content-type) "text/event-stream"))))

(defn transcribe
  "Send a canonical TranscribeRequest and return a TranscribeResponse.

   Multipart audio input is sent through the shared HTTP client resolver.
   JSON responses are decoded regardless of Content-Type spelling; text,
   SRT, and VTT response bodies are preserved verbatim."
  [provider-id request & {:keys [config]}]
  (operation/run
   {:provider-id provider-id
    :request request
    :config config
    :validate-request schema/validate-transcribe-request
    :explain-request schema/explain-transcribe-request
    :invalid-error-type :schema/invalid-transcribe-request
    :invalid-message "Invalid llm.sdk transcribe request"
    :constructor-key :profile/transcribe-transport-constructor
    :unsupported-message "Transcription not supported by provider"
    :build-request tt/build-transcribe-request
    :request-effect
    (fn [native-request]
      (multipart-request
       native-request
       (or (:transcribe/response-format request)
           (get-in request
                   [:transcribe/provider-options :response_format])
           (get-in request
                   [:transcribe/provider-options :response-format]))))
    :parse-response
    (fn [transport profile response]
      (when (event-stream-response? response)
        (let [error {:error/reason :unsupported-parameter
                     :error/retryable false
                     :error/message
                     "Buffered transcription transport cannot consume an SSE response"}]
          (throw
           (ex-info "Provider returned a streaming transcription response"
                    {:error error
                     :error/type :transcribe/streaming-response-unsupported
                     :status (:status response)
                     :body (:body response)
                     :provider provider-id}))))
      (let [parsed (tt/parse-transcribe-response
                    transport profile (:body response))]
        (when-not (and (map? parsed)
                       (string? (:transcription/text parsed)))
          (throw
           (ex-info "Provider returned an invalid transcription response"
                    {:provider provider-id
                     :error/type :provider/invalid-transcription-response
                     :status (:status response)
                     :body (:body response)
                     :response parsed})))
        (stamp-transcription-cost provider-id request parsed)))
    :parse-error tt/parse-transcribe-error
    :transport-error-message "Provider transcribe transport error"
    :api-error-message "Provider transcribe API error"}))
