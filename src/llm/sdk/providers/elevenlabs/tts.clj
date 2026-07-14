(ns llm.sdk.providers.elevenlabs.tts
  "ElevenLabs TTS adapter — POST /v1/text-to-speech/:voice_id with
   xi-api-key header. Voice id is part of the URL; model id and
   text live in the JSON body. Returns audio bytes (mp3 by default).

   Reference: https://elevenlabs.io/docs/api-reference/text-to-speech/convert"
  (:require [clojure.string :as str]
            [llm.sdk.transport.speak :as st]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors])
  (:import [java.net URLEncoder]))

(defn- output-format [format]
  (case format
    :mp3 "mp3_44100_128"
    :opus "opus_48000_96"
    :pcm "pcm_44100"
    :wav "wav_44100"
    (:aac :flac)
    (throw (ex-info (str "ElevenLabs does not support " (name format)
                         " output")
                    {:provider :elevenlabs
                     :format format
                     :supported-formats #{:mp3 :opus :pcm :wav}}))
    nil))

(defn- query-string [pairs]
  (when (seq pairs)
    (str "?"
         (str/join
          "&"
          (map (fn [[k v]]
                 (str (name k) "="
                      (URLEncoder/encode (str v) "UTF-8")))
               pairs)))))

(defn build-request
  [profile request]
  (let [voice (or (:speak/voice request)
                  (throw (ex-info "ElevenLabs requires :speak/voice (voice id)"
                                  {:provider :elevenlabs})))
        model (or (:speak/model request) "eleven_multilingual_v2")
        input (:speak/input request)
        options (or (:speak/provider-options request) {})
        output-fmt (or (:output_format options)
                       (output-format (:speak/format request)))
        query (cond-> []
                output-fmt (conj [:output_format output-fmt])
                (some? (:enable_logging options))
                (conj [:enable_logging (:enable_logging options)])
                (some? (:optimize_streaming_latency options))
                (conj [:optimize_streaming_latency
                       (:optimize_streaming_latency options)]))
        voice-settings (cond-> (:voice_settings options)
                         (contains? request :speak/speed)
                         (assoc :speed (:speak/speed request)))
        body-options (dissoc options
                             :output_format
                             :enable_logging
                             :optimize_streaming_latency
                             :voice_settings)
        body (cond-> (merge {:text input
                             :model_id model}
                            body-options)
               (or (contains? options :voice_settings)
                   (contains? request :speak/speed))
               (assoc :voice_settings voice-settings))
        url (str (:profile/base-url profile)
                 "/v1/text-to-speech/" voice
                 (query-string query))]
    {:method :post
     :url url
     :headers (merge {"xi-api-key" (provider/resolve-auth-token profile)
                      "Content-Type" "application/json"}
                     (:profile/default-headers profile {}))
     :body body}))

(defn parse-response
  [_profile resp]
  (let [ct (or (get-in resp [:headers "content-type"])
               (get-in resp [:headers "Content-Type"])
               "application/octet-stream")]
    {:audio/bytes (:body resp)
     :audio/content-type ct
     :response/raw (:headers resp)}))

(defn parse-error
  [_profile status body]
  (errors/classify-error (Exception. "ElevenLabs API error")
                         :status status
                         :body body
                         :provider :elevenlabs))

(defrecord ElevenLabsSpeakTransport []
  st/SpeakTransport
  (build-speak-request [_ profile request] (build-request profile request))
  (parse-speak-response [_ profile resp] (parse-response profile resp))
  (parse-speak-error [_ profile status body] (parse-error profile status body)))

(defn make-transport [] (->ElevenLabsSpeakTransport))

(provider/register-provider
 {:profile/id :elevenlabs
  :profile/protocol-family :elevenlabs
  :profile/base-url "https://api.elevenlabs.io"
  :profile/auth-strategy :api-key-header
  :profile/auth-header-name "xi-api-key"
  :profile/supports-model-listing false
  :profile/capabilities #{:tts}
  :profile/env-var-names ["ELEVENLABS_API_KEY"]
  :profile/speak-transport-constructor make-transport})
