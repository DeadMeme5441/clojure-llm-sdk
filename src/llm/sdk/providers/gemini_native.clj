(ns llm.sdk.providers.gemini-native
  "Compatibility shim. Implementation lives in llm.sdk.providers.gemini.native."
  (:require [llm.sdk.providers.gemini.native :as impl]))

(def make-transport impl/make-transport)
(def build-request-gemini impl/build-request-gemini)
(def parse-response-gemini impl/parse-response-gemini)
(def parse-stream-event-gemini impl/parse-stream-event-gemini)
(def parse-error-gemini impl/parse-error-gemini)
