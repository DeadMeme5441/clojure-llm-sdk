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
            [llm.sdk.schema :as schema]
            [llm.sdk.errors :as errors]
            [llm.sdk.transport.speak :as st]))

(defn- http-client [{:keys [http-client connect-timeout-ms timeout-ms]}]
  (or http-client
      (hc/build-http-client {:connect-timeout (or connect-timeout-ms 30000)
                             :timeout (or timeout-ms 120000)})))

(defn- bytes-request
  "TTS responses are raw audio bytes — we read :as :byte-array and
   forward content-type so the transport can label the audio."
  [{:keys [method url headers body] :as req}]
  (let [resp (hc/request
              {:method method
               :url url
               :headers headers
               :body (when body (json/generate-string body))
               :as :byte-array
               :http-client (http-client req)
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
  [provider-id request & {:keys [config]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (schema/validate-speak-request request)
            (throw (ex-info "Invalid llm.sdk speak request"
                            {:error/type :schema/invalid-speak-request
                             :schema/explain (schema/explain-speak-request request)})))
        ctor (:profile/speak-transport-constructor profile)
        _ (when-not ctor
            (throw (ex-info "Text-to-speech not supported by provider"
                            {:provider provider-id})))
        transport (ctor)
        req (st/build-speak-request transport profile request)
        req (provider/apply-http-options profile req)
        resp (try
               (bytes-request req)
               (catch Exception e
                 (throw (ex-info "Provider TTS transport error"
                                 {:error (errors/classify-error e :provider provider-id)
                                  :provider provider-id}
                                 e))))
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
