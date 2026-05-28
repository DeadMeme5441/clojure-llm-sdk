(ns llm.sdk.providers.openai-speak
  "Compatibility shim. Implementation lives in llm.sdk.providers.openai.speak."
  (:require [llm.sdk.providers.openai.speak :as impl]))

(def make-transport impl/make-transport)
(def build-request impl/build-request)
(def parse-response impl/parse-response)
(def parse-error impl/parse-error)
