(ns llm.sdk.cache
  "Compatibility aggregate for context caching helpers.

   Implementation ownership lives in llm.sdk.cache.markers,
   llm.sdk.cache.policy, and llm.sdk.cache.request."
  (:require [llm.sdk.cache.markers :as markers]
            [llm.sdk.cache.policy :as policy]
            [llm.sdk.cache.request :as request]))

(def marker markers/marker)
(def apply-marker-to-message markers/apply-marker-to-message)
(def apply-system-and-3 markers/apply-system-and-3)
(def apply-system-blocks-cache markers/apply-system-blocks-cache)
(def apply-tools-cache markers/apply-tools-cache)

(def decide-strategy policy/decide-strategy)

(def cache-enabled? request/cache-enabled?)
(def ttl request/ttl)
(def breakpoints request/breakpoints)
(def scope-id request/scope-id)
(def cached-content-id request/cached-content-id)
(def tools-cache? request/tools-cache?)
