(ns llm.sdk.providers.openrouter
  "Compatibility shim. Implementation lives in llm.sdk.providers.openrouter.chat."
  (:require [llm.sdk.providers.openrouter.chat :as impl]))

(def make-transport impl/make-transport)
(def build-request-openrouter impl/build-request-openrouter)
(def parse-response-openrouter impl/parse-response-openrouter)
(def parse-stream-event-openrouter impl/parse-stream-event-openrouter)
(def parse-error-openrouter impl/parse-error-openrouter)
