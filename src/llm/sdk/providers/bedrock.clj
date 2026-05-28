(ns llm.sdk.providers.bedrock
  "Compatibility shim. Implementation lives in llm.sdk.providers.bedrock.converse."
  (:require [llm.sdk.providers.bedrock.converse :as impl]))

(def make-transport impl/make-transport)
(def model-id-mapping impl/model-id-mapping)
(def resolve-model-id impl/resolve-model-id)
(def build-request-bedrock impl/build-request-bedrock)
(def parse-response-bedrock impl/parse-response-bedrock)
(def parse-stream-event-bedrock impl/parse-stream-event-bedrock)
(def parse-error-bedrock impl/parse-error-bedrock)
