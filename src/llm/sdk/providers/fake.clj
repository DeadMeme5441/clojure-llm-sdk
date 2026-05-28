(ns llm.sdk.providers.fake
  "Compatibility shim. Implementation lives in llm.sdk.providers.fake.chat."
  (:require [llm.sdk.providers.fake.chat :as impl]))

(def make-fake-transport impl/make-fake-transport)
