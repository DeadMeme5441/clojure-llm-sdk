(ns llm.sdk.providers.elevenlabs
  "Compatibility shim. Implementation lives in llm.sdk.providers.elevenlabs.tts."
  (:require [llm.sdk.providers.elevenlabs.tts :as impl]))

(def make-transport impl/make-transport)
(def build-request impl/build-request)
(def parse-response impl/parse-response)
(def parse-error impl/parse-error)
