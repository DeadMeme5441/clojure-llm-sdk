(ns llm.sdk.provider.registry
  "Provider profile registry ownership.")

(defonce ^:private registry (atom {}))

(defn register-provider
  "Register a provider profile. Profile must be a map with :profile/id."
  [profile]
  (swap! registry assoc (:profile/id profile) profile))

(defn get-provider
  "Look up a provider profile by id. Returns nil if not found."
  [provider-id]
  (get @registry provider-id))

(defn list-providers
  "Return a seq of all registered provider ids."
  []
  (keys @registry))

(defn provider-ids
  "Return set of registered provider ids."
  []
  (set (keys @registry)))
