(ns llm.sdk.providers.cohere-chat
  "Compatibility shim. Implementation lives in llm.sdk.providers.cohere.chat."
  (:require [llm.sdk.providers.cohere.chat :as impl]))

(def make-transport impl/make-transport)
(def build-request-cohere impl/build-request-cohere)
(def parse-response-cohere impl/parse-response-cohere)
(def parse-stream-event-cohere impl/parse-stream-event-cohere)
(def parse-error-cohere impl/parse-error-cohere)
