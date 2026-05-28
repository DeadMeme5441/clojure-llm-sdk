(ns llm.sdk.providers.openai-chat
  "Compatibility shim. Implementation lives in llm.sdk.providers.openai.chat."
  (:require [llm.sdk.providers.openai.chat :as impl]))

(def make-transport impl/make-transport)
(def build-request-openai impl/build-request-openai)
(def parse-response-openai impl/parse-response-openai)
(def parse-stream-event-openai impl/parse-stream-event-openai)
(def parse-error-openai impl/parse-error-openai)
(def build-alias-profile impl/build-alias-profile)
(def register-alias! impl/register-alias!)
(def azure-url-builder impl/azure-url-builder)
(def register-azure-deployment! impl/register-azure-deployment!)
