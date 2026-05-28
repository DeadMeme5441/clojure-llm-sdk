(ns llm.sdk.providers.bedrock-image
  "Compatibility shim. Implementation lives in llm.sdk.providers.bedrock.image."
  (:require [llm.sdk.providers.bedrock.image :as impl]))

(def make-transport impl/make-transport)
(def build-image-request-bedrock impl/build-image-request-bedrock)
(def parse-image-response-bedrock impl/parse-image-response-bedrock)
(def parse-image-error-bedrock impl/parse-image-error-bedrock)
