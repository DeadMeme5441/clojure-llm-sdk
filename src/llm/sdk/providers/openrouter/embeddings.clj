(ns llm.sdk.providers.openrouter.embeddings
  "OpenRouter embeddings transport over the OpenAI-compatible /embeddings API."
  (:require [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai.embeddings :as openai]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.usage :as usage]))

(defn build-embed-request-openrouter
  [profile request]
  (let [base (openai/build-embed-request-openai profile request)
        headers (-> (:headers base)
                    (dissoc "X-Title")
                    (assoc "X-OpenRouter-Title"
                           (or (System/getenv "OPENROUTER_APP_NAME")
                               "clojure-llm-sdk")))]
    (assoc base :headers headers)))

(defrecord OpenRouterEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-openrouter profile request))
  (parse-embed-response [_ profile raw]
    (openai/parse-embed-response-openai profile raw))
  (parse-embed-error [_ profile status body]
    (openai/parse-embed-error-openai profile status body))
  (normalize-embed-usage [_ _ raw]
    (usage/normalize-embedding-usage raw)))

(defn make-transport [] (->OpenRouterEmbedTransport))

(when-let [p (provider/get-provider :openrouter)]
  (provider/register-provider
   (-> p
       (assoc :profile/embed-transport-constructor make-transport)
       (update :profile/capabilities (fnil conj #{}) :embedding))))
