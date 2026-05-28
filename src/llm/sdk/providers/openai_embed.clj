(ns llm.sdk.providers.openai-embed
  "Compatibility shim. Implementation lives in llm.sdk.providers.openai.embeddings."
  (:require [llm.sdk.providers.openai.embeddings :as impl]))

(def make-transport impl/make-transport)
(def build-embed-request-openai impl/build-embed-request-openai)
(def parse-embed-response-openai impl/parse-embed-response-openai)
(def parse-embed-error-openai impl/parse-embed-error-openai)
