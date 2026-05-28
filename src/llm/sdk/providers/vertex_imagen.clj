(ns llm.sdk.providers.vertex-imagen
  "Compatibility shim. Implementation lives in llm.sdk.providers.gemini.imagen."
  (:require [llm.sdk.providers.gemini.imagen :as impl]))

(def make-transport impl/make-transport)
(def build-image-request-vertex-imagen impl/build-image-request-vertex-imagen)
(def parse-image-response-vertex-imagen impl/parse-image-response-vertex-imagen)
(def parse-image-error-vertex-imagen impl/parse-image-error-vertex-imagen)
