(ns llm.sdk.providers.openai-moderation
  "Compatibility shim. Implementation lives in llm.sdk.providers.openai.moderation."
  (:require [llm.sdk.providers.openai.moderation :as impl]))

(def make-transport impl/make-transport)
(def build-moderation-request-openai impl/build-moderation-request-openai)
(def parse-moderation-response-openai impl/parse-moderation-response-openai)
(def parse-moderation-error-openai impl/parse-moderation-error-openai)
