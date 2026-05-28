(ns llm.sdk.providers.cohere-rerank
  "Compatibility shim. Implementation lives in llm.sdk.providers.cohere.rerank."
  (:require [llm.sdk.providers.cohere.rerank :as impl]))

(def make-transport impl/make-transport)
(def build-rerank-request-cohere-shape impl/build-rerank-request-cohere-shape)
(def parse-rerank-response-cohere-shape impl/parse-rerank-response-cohere-shape)
(def parse-rerank-error-cohere-shape impl/parse-rerank-error-cohere-shape)
