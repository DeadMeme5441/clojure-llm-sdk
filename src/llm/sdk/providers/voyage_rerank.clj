(ns llm.sdk.providers.voyage-rerank
  "Compatibility shim. Implementation lives in llm.sdk.providers.voyage.rerank."
  (:require [llm.sdk.providers.voyage.rerank :as impl]))

(def make-transport impl/make-transport)
(def build-rerank-request-voyage impl/build-rerank-request-voyage)
(def parse-rerank-response-voyage impl/parse-rerank-response-voyage)
(def parse-rerank-error-voyage impl/parse-rerank-error-voyage)
