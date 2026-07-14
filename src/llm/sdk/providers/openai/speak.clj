(ns llm.sdk.providers.openai.speak
  "OpenAI /audio/speech adapter — POST {model, voice, input, response_format}
   returns raw audio bytes."
  (:require [llm.sdk.transport.speak :as st]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]
            [llm.sdk.sse :as sse]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]))

(defn build-request
  [profile request]
  (let [model (:speak/model request)
        voice (or (:speak/voice request) "alloy")
        input (:speak/input request)
        fmt (some-> (:speak/format request) name)
        body (cond-> {:model model
                      :input input
                      :voice voice}
               fmt (assoc :response_format fmt)
               (:speak/speed request) (assoc :speed (:speak/speed request))
               (:speak/instructions request) (assoc :instructions (:speak/instructions request)))
        body (merge body (:speak/provider-options request))]
    {:method :post
     :url (str (:profile/base-url profile) "/audio/speech")
     :headers (merge (provider/default-headers profile
                                                (provider/resolve-auth-token profile))
                     {"Content-Type" "application/json"})
     :body body}))

(defn parse-response
  [_profile resp]
  (let [ct (or (get-in resp [:headers "content-type"])
               (get-in resp [:headers "Content-Type"])
               "audio/mpeg")]
    {:audio/bytes (:body resp)
     :audio/content-type ct
     :response/raw (:headers resp)}))

(defn parse-stream-event
  "Normalize current speech.audio.delta/done SSE events."
  [profile line]
  (when-let [data (sse/parse-json-data line)]
    (case (:type data)
      "speech.audio.delta"
      (stream/provider-state-event (:profile/id profile)
                                   {:speech/audio-delta (:audio data)})

      "speech.audio.done"
      (cond-> []
        (:usage data)
        (conj (stream/usage-event
               (usage/normalize-openai-usage (:usage data))))
        true
        (conj (stream/end-event :finish-reason :stop)))

      nil)))

(defn parse-error
  [profile status body]
  (errors/classify-error (Exception. "TTS API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

(defrecord OpenAISpeakTransport []
  st/SpeakTransport
  (build-speak-request [_ profile request] (build-request profile request))
  (parse-speak-response [_ profile resp] (parse-response profile resp))
  (parse-speak-error [_ profile status body] (parse-error profile status body)))

(defn make-transport [] (->OpenAISpeakTransport))

(when-let [p (provider/get-provider :openai)]
  (provider/register-provider
   (assoc p :profile/speak-transport-constructor make-transport)))
