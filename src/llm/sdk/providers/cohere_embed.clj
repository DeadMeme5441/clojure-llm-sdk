(ns llm.sdk.providers.cohere-embed
  "Compatibility shim. Implementation lives in llm.sdk.providers.cohere.embeddings."
  (:require [llm.sdk.providers.cohere.embeddings :as impl]))

(def make-transport impl/make-transport)
(def normalize-cohere-embedding-usage impl/normalize-cohere-embedding-usage)
(def build-embed-request-cohere impl/build-embed-request-cohere)
(def parse-embed-response-cohere impl/parse-embed-response-cohere)
(def parse-embed-error-cohere impl/parse-embed-error-cohere)
