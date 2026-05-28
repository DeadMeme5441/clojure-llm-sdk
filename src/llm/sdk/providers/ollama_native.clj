(ns llm.sdk.providers.ollama-native
  "Compatibility shim. Implementation lives in llm.sdk.providers.ollama.native."
  (:require [llm.sdk.providers.ollama.native :as impl]))

(def make-transport impl/make-transport)
(def make-embed-transport impl/make-embed-transport)
(def build-request-ollama impl/build-request-ollama)
(def parse-response-ollama impl/parse-response-ollama)
(def parse-stream-event-ollama impl/parse-stream-event-ollama)
(def parse-error-ollama impl/parse-error-ollama)
(def build-embed-request-ollama impl/build-embed-request-ollama)
(def parse-embed-response-ollama impl/parse-embed-response-ollama)
(def parse-embed-error-ollama impl/parse-embed-error-ollama)
