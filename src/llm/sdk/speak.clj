(ns llm.sdk.speak
  "Driver for text-to-speech (TTS). The TTS counterpart to
   sdk/complete and sdk/transcribe.

   Returns a SpeakResponse: {:audio/bytes byte-array
                              :audio/content-type str
                              :audio/model str?
                              :response/usage Usage?
                              :response/raw raw}.

   Providers without a speak transport throw ex-info on call."
  (:require [cheshire.core :as json]
            [hato.client :as hc]
            [llm.sdk.http :as http]
            [llm.sdk.operation :as operation]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.speak :as st]))

(defn- stamp-tts-cost [provider-id request parsed]
  (if (contains? parsed :response/cost)
    parsed
    (let [model (or (:audio/model parsed) (:speak/model request))
          characters (count (:speak/input request))
          pricing (pricing/get-pricing provider-id model)
          result (pricing/tts-cost {:characters characters} pricing)
          cost (pricing/cost-result->canonical
                result
                pricing
                {:characters characters})]
      (assoc parsed :response/cost cost))))

(defn- bytes-request
  "Execute a buffered request whose successful body is raw audio bytes."
  [{:keys [method url headers body] :as request}]
  (let [response
        (hc/request
         (merge (http/request-options request)
                {:method method
                 :url url
                 :headers headers
                 :body (when body (json/generate-string body))
                 :as :byte-array
                 :throw-exceptions? false}))]
    {:status (:status response)
     :headers (:headers response)
     :body (:body response)}))

(defn- decode-error-body [body]
  (try
    (http/decode-body
     (if (bytes? body)
       (String. ^bytes body "UTF-8")
       body))
    (catch Exception _ body)))

(defn- validate-provider-response! [provider-id status response parsed]
  (let [audio (:audio/bytes parsed)]
    (when-not (and (bytes? audio) (pos? (alength ^bytes audio)))
      (throw
       (ex-info "Provider returned an empty or invalid speech response"
                {:provider provider-id
                 :status status
                 :error/type :provider/invalid-speech-response
                 :response parsed
                 :body (:body response)})))))

(defn speak
  "Send a canonical SpeakRequest and return a SpeakResponse.

   The response contains nonempty :audio/bytes and its content type."
  [provider-id request & {:keys [config]}]
  (operation/run
   {:provider-id provider-id
    :request request
    :config config
    :validate-request schema/validate-speak-request
    :explain-request schema/explain-speak-request
    :invalid-error-type :schema/invalid-speak-request
    :invalid-message "Invalid llm.sdk speak request"
    :constructor-key :profile/speak-transport-constructor
    :unsupported-message "Text-to-speech not supported by provider"
    :build-request st/build-speak-request
    :request-effect bytes-request
    :parse-response
    (fn [transport profile response]
      (let [parsed (st/parse-speak-response transport profile response)]
        (validate-provider-response!
         provider-id (:status response) response parsed)
        (stamp-tts-cost provider-id request parsed)))
    :parse-error
    (fn [transport profile status body]
      (st/parse-speak-error
       transport profile status (decode-error-body body)))
    :transport-error-message "Provider TTS transport error"
    :api-error-message "Provider TTS API error"}))
