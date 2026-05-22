(ns llm.sdk.providers.elevenlabs
  "ElevenLabs TTS adapter — POST /v1/text-to-speech/:voice_id with
   xi-api-key header. Voice id is part of the URL; model id and
   text live in the JSON body. Returns audio bytes (mp3 by default).

   Reference: litellm-ref/llms/elevenlabs/ + ElevenLabs API docs."
  (:require [llm.sdk.transport.speak :as st]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]))

(defn build-request
  [profile request]
  (let [voice (or (:speak/voice request)
                  (throw (ex-info "ElevenLabs requires :speak/voice (voice id)"
                                  {:provider :elevenlabs})))
        model (or (:speak/model request) "eleven_multilingual_v2")
        input (:speak/input request)
        fmt (some-> (:speak/format request) name)
        ;; output_format is a query parameter, not a body field
        output-fmt (case fmt
                     "mp3" "mp3_44100_128"
                     "opus" "opus_48000_96"
                     "pcm" "pcm_44100"
                     "wav" "pcm_44100"
                     nil)
        url (str (:profile/base-url profile)
                 "/v1/text-to-speech/" voice
                 (when output-fmt (str "?output_format=" output-fmt)))
        body (cond-> {:text input
                      :model_id model}
               (:speak/instructions request)
               (assoc :voice_settings
                      (merge {:stability 0.5 :similarity_boost 0.75}
                             (get-in request [:speak/provider-options :voice_settings])))
               (get-in request [:speak/provider-options :voice_settings])
               (assoc :voice_settings
                      (get-in request [:speak/provider-options :voice_settings])))
        body (merge body (dissoc (:speak/provider-options request) :voice_settings))]
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
               "audio/mpeg")]
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
