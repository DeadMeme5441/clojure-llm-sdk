(ns llm.sdk.provider.registry
  "Validated provider values, published atomically."
  (:require [clojure.set :as set]
            [llm.sdk.schema :as schema]))

(defonce ^:private registry (atom {}))

(def ^:private operation-capabilities
  {:profile/transport-constructor :chat
   :profile/embed-transport-constructor :embedding
   :profile/moderation-transport-constructor :moderation
   :profile/rerank-transport-constructor :rerank
   :profile/image-transport-constructor :image-generation
   :profile/transcribe-transport-constructor :transcription
   :profile/speak-transport-constructor :tts})

(defn- checked-profile [profile]
  (when-not (schema/validate-provider-profile profile)
    (throw (ex-info "Invalid provider profile"
                    {:error/type :provider/invalid-profile
                     :provider (:profile/id profile)})))
  (when (and (= :api-key-query (:profile/auth-strategy profile))
             (not (seq (:profile/auth-query-param profile))))
    (throw (ex-info "Query authentication requires :profile/auth-query-param"
                    {:error/type :provider/invalid-profile
                     :provider (:profile/id profile)})))
  (let [operations (into #{} (keep (fn [[ctor capability]]
                                     (when (get profile ctor) capability)))
                         operation-capabilities)]
    (when (empty? operations)
      (throw (ex-info "Provider profile requires at least one operation"
                      {:error/type :provider/invalid-profile
                       :provider (:profile/id profile)})))
    (update profile :profile/capabilities
            #(into (set/difference (or % #{})
                                   (set (vals operation-capabilities)))
                   operations))))

(defonce ^:private initialized
  (delay
    (let [profiles ((requiring-resolve 'llm.sdk.provider.builtins/profiles))
          builtins (into {} (map (fn [profile]
                                   [(:profile/id profile) (checked-profile profile)]))
                         profiles)]
      ;; A caller may register an override before the first built-in lookup.
      (swap! registry #(merge builtins %)))))

(defn register-provider
  "Register a complete provider value. Operation capabilities are derived."
  [profile]
  (let [profile (checked-profile profile)]
    (swap! registry assoc (:profile/id profile) profile)))

(defn get-provider
  "Look up a provider profile by id. Returns nil if not found."
  [provider-id]
  @initialized
  (get @registry provider-id))

(defn list-providers
  "Return a seq of all registered provider ids."
  []
  @initialized
  (keys @registry))

(defn provider-ids
  "Return set of registered provider ids."
  []
  @initialized
  (set (keys @registry)))
