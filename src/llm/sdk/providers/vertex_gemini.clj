(ns llm.sdk.providers.vertex-gemini
  "Compatibility shim. Implementation lives in llm.sdk.providers.gemini.vertex."
  (:require [llm.sdk.providers.gemini.vertex :as impl]))

(def make-transport impl/make-transport)
(def build-request-vertex impl/build-request-vertex)
(def parse-response-vertex impl/parse-response-vertex)
(def parse-stream-event-vertex impl/parse-stream-event-vertex)
(def parse-error-vertex impl/parse-error-vertex)
