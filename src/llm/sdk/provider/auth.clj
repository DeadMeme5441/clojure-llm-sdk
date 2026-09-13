(ns llm.sdk.provider.auth
  "Provider auth and runtime profile configuration."
  (:require [clojure.string :as str]))

(defn merge-headers
  "Merge HTTP headers case-insensitively, keeping the last spelling and value."
  [& maps]
  (reduce (fn [headers entries]
            (reduce-kv
             (fn [headers k v]
               (let [normalized (str/lower-case (name k))
                     previous (some #(when (= normalized (str/lower-case (name %))) %)
                                    (keys headers))]
                 (assoc (if previous (dissoc headers previous) headers) k v)))
             headers (or entries {})))
          {} maps))

(defn replace-auth-headers
  "Replace an OAuth credential set without retaining a previous account claim."
  [headers fresh]
  (merge-headers
   (into {} (remove (fn [[key _]]
                      (contains? #{"authorization" "chatgpt-account-id" "x-openai-fedramp"}
                                 (str/lower-case (name key)))))
         headers)
   fresh))

(defn resolve-auth-token
  "Resolve an auth token for a provider profile.
   Per-call/profile overrides win, then env vars are checked in order."
  [profile]
  (or (:profile/auth-token profile)
      (let [env-vars (:profile/env-var-names profile)]
        (some #(System/getenv %) env-vars))))

(defn auth-headers
  "Build auth headers for a provider profile given a token."
  [profile token]
  (when token
    (case (:profile/auth-strategy profile)
      :bearer {"Authorization" (str "Bearer " token)}
      :api-key-header {(:profile/auth-header-name profile "x-api-key") token}
      :api-key-query {}
      :gcp-oauth {"Authorization" (str "Bearer " token)}
      {})))

(defn default-headers
  "Merge provider default headers with auth headers."
  [profile token]
  (merge-headers (:profile/default-headers profile)
                 (auth-headers profile token)))

(defn apply-runtime-config
  "Apply per-call SDK runtime configuration to a provider profile.

   This returns a transient profile value; it does not mutate the
   global provider registry."
  [profile config]
  (let [token (or (:auth-token config) (:api-key config))]
    (cond-> profile
      token
      (assoc :profile/auth-token token)

      (:account-id config)
      (assoc :profile/account-id (:account-id config))

      (:base-url config)
      (assoc :profile/base-url (:base-url config))

      (:headers config)
      (assoc :profile/default-headers
             (merge-headers (:profile/default-headers profile) (:headers config))
             :profile/runtime-headers (:headers config))

      (:http-client config)
      (assoc :profile/http-client (:http-client config))

      (:connect-timeout-ms config)
      (assoc :profile/connect-timeout-ms (:connect-timeout-ms config))

      (:timeout-ms config)
      (assoc :profile/timeout-ms (:timeout-ms config)))))

(defn apply-http-options
  "Apply shared request configuration before signing or execution.
   Adapter headers override defaults; explicit runtime headers win last."
  [profile req]
  (let [headers (merge-headers (:profile/default-headers profile)
                               (:headers req)
                               (auth-headers profile (:profile/auth-token profile))
                               (:profile/runtime-headers profile))
        query-token (when (= :api-key-query (:profile/auth-strategy profile))
                      (resolve-auth-token profile))]
    (cond-> req
      (seq headers)
      (assoc :headers headers)

      query-token
      (assoc-in [:query-params (:profile/auth-query-param profile)] query-token)

      (:profile/http-client profile)
      (assoc :http-client (:profile/http-client profile))

      (:profile/connect-timeout-ms profile)
      (assoc :connect-timeout-ms (:profile/connect-timeout-ms profile))

      (:profile/timeout-ms profile)
      (assoc :timeout-ms (:profile/timeout-ms profile)))))
