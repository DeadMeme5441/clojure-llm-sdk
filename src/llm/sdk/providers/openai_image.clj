(ns llm.sdk.providers.openai-image
  "Compatibility shim. Implementation lives in llm.sdk.providers.openai.image."
  (:require [llm.sdk.providers.openai.image :as impl]))

(def make-transport impl/make-transport)
(def build-image-request-openai impl/build-image-request-openai)
(def parse-image-response-openai impl/parse-image-response-openai)
(def parse-image-error-openai impl/parse-image-error-openai)
