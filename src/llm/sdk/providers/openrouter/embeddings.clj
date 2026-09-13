(ns llm.sdk.providers.openrouter.embeddings
  "OpenRouter embeddings transport over the OpenAI-compatible /embeddings API."
  (:require [llm.sdk.providers.openai.embeddings :as openai]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.usage :as usage]))

(defn build-embed-request-openrouter
  [profile request]
  (let [base (openai/build-embed-request-openai profile request)
        opts (:embed/provider-options request)
        body (cond-> (:body base)
               (contains? opts :provider)
               (-> (dissoc "provider")
                   (assoc :provider (:provider opts)))
               (contains? opts :input-type)
               (-> (dissoc "input_type")
                   (assoc :input_type (:input-type opts))))
        headers (-> (:headers base)
                    (dissoc "X-Title")
                    (assoc "X-OpenRouter-Title"
                           (or (System/getenv "OPENROUTER_APP_NAME")
                               "clojure-llm-sdk")))]
    (assoc base :body body :headers headers)))

(defn- reported-cost [usage-raw]
  (when (number? (:cost usage-raw))
    {:cost/usd (:cost usage-raw)
     :cost/estimated? false
     :cost/pricing-source :openrouter-reported
     :cost/source-url
     "https://openrouter.ai/docs/api/api-reference/embeddings/submit-an-embedding-request"
     :cost/breakdown
     (select-keys usage-raw [:cost :cost_details :is_byok])}))

(defn parse-embed-response-openrouter
  [profile raw]
  (let [parsed (openai/parse-embed-response-openai profile raw)
        cost (reported-cost (:usage raw))]
    (cond-> parsed
      cost (assoc :response/cost cost))))

(defrecord OpenRouterEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-openrouter profile request))
  (parse-embed-response [_ profile raw]
    (parse-embed-response-openrouter profile raw))
  (parse-embed-error [_ profile status body]
    (openai/parse-embed-error-openai profile status body))
  (normalize-embed-usage [_ _ raw]
    (usage/normalize-embedding-usage raw)))

(defn make-transport [] (->OpenRouterEmbedTransport))

