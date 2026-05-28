(ns llm.sdk.providers.openai-transcribe
  "Compatibility shim. Implementation lives in llm.sdk.providers.openai.transcribe."
  (:require [llm.sdk.providers.openai.transcribe :as impl]))

(def make-transport impl/make-transport)
(def build-request impl/build-request)
(def parse-response impl/parse-response)
(def parse-error impl/parse-error)
