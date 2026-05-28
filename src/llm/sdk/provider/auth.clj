(ns llm.sdk.provider.auth
  "Provider auth and runtime profile configuration.")

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
  (merge (:profile/default-headers profile {})
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

      (:base-url config)
      (assoc :profile/base-url (:base-url config))

      (:headers config)
      (update :profile/default-headers merge (:headers config))

      (:http-client config)
      (assoc :profile/http-client (:http-client config))

      (:connect-timeout-ms config)
      (assoc :profile/connect-timeout-ms (:connect-timeout-ms config))

      (:timeout-ms config)
      (assoc :profile/timeout-ms (:timeout-ms config)))))

(defn apply-http-options
  "Copy runtime HTTP client/timeout options from a provider profile onto
   a native request map consumed by llm.sdk.http or modality drivers."
  [profile req]
  (cond-> req
    (:profile/http-client profile)
    (assoc :http-client (:profile/http-client profile))

    (:profile/connect-timeout-ms profile)
    (assoc :connect-timeout-ms (:profile/connect-timeout-ms profile))

    (:profile/timeout-ms profile)
    (assoc :timeout-ms (:profile/timeout-ms profile))))
