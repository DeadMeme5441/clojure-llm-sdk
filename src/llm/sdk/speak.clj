(ns llm.sdk.speak
  "Driver for text-to-speech (TTS). The TTS counterpart to
   sdk/complete and sdk/transcribe.

   Returns a SpeakResponse: {:audio/bytes byte-array
                              :audio/content-type str
                              :audio/model str?
                              :response/usage Usage?
                              :response/raw raw}.

   Providers without a speak transport throw ex-info on call."
  (:require [hato.client :as hc]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.speak :as st]))

(defn- bytes-request
  "TTS responses are raw audio bytes — we read :as :byte-array and
   forward content-type so the transport can label the audio."
  [{:keys [method url headers body]}]
  (let [resp (hc/request
              {:method method
               :url url
               :headers headers
               :body (when body (json/generate-string body))
               :as :byte-array
               :throw-exceptions? false})]
    {:status (:status resp)
     :headers (:headers resp)
     :body (:body resp)}))

(defn speak
  "Send a canonical SpeakRequest and return a SpeakResponse.

   Request keys:
     :speak/model    model id (e.g. \"tts-1\", \"eleven_multilingual_v2\")
     :speak/input    text to synthesize
     :speak/voice    voice id (e.g. \"alloy\" or an ElevenLabs voice id)
     :speak/format   :mp3|:opus|:aac|:flac|:wav|:pcm (provider-dependent)
     :speak/speed    optional [0.25, 4.0]
     :speak/instructions  optional style/affect prompt (OpenAI tts-1-hd / gpt-4o-mini-tts)
     :speak/provider-options  extra provider-specific fields"
  [provider-id request]
  (let [profile (or (provider/get-provider provider-id)
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        ctor (:profile/speak-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Text-to-speech not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (st/build-speak-request transport profile request)
        resp (bytes-request req)
        status (:status resp)]
    (if (>= status 400)
      (let [body (try (json/parse-string (String. ^bytes (:body resp)) true)
                      (catch Exception _ (:body resp)))
            err (st/parse-speak-error transport profile status body)]
        (throw (ex-info "Provider TTS API error"
                        {:error err
                         :status status
                         :body body
                         :provider provider-id})))
      (st/parse-speak-response transport profile resp))))
