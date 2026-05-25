(ns llm.sdk.gcp-auth
  "GCP Application Default Credentials (ADC) resolution for Vertex AI.

   Mirrors the order documented at
   https://cloud.google.com/docs/authentication/application-default-credentials
   and implemented by the official google-auth client libraries:

     1. GOOGLE_APPLICATION_CREDENTIALS env var → credentials file
     2. Well-known file at
        ~/.config/gcloud/application_default_credentials.json
        (set by `gcloud auth application-default login`)
     3. GCE / Cloud Run / GKE metadata server (when running on GCP)

   Credentials files come in two flavours we support:
     :service_account  — has :private_key + :client_email; we RS256-
                         sign a JWT and exchange it at
                         oauth2.googleapis.com/token for an access
                         token (jwt-bearer grant).
     :authorized_user  — has :client_id, :client_secret, :refresh_token;
                         we POST a refresh_token grant to the same
                         endpoint. This is the format
                         `gcloud auth application-default login` writes.

   External account (workload identity federation) is not yet supported
   — those credentials require an STS exchange that varies by source
   (AWS, Azure, OIDC). Throw a clear error if encountered.

   Two convenience layers sit *above* the proper ADC chain:
     - request opts :vertex :access-token (caller override)
     - GOOGLE_OAUTH_ACCESS_TOKEN env (pre-resolved bearer)

   These are documented escape hatches; they do not replace ADC.

   When none of the layers yield a token, raises ex-info
   {:error/type :auth/missing-credentials :attempted [...]}
   naming every source the SDK tried, in order."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hato.client :as hc])
  (:import (java.net URLEncoder)
           (java.security KeyFactory Signature)
           (java.security.spec PKCS8EncodedKeySpec)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Base64url + URL encoding helpers
;; ---------------------------------------------------------------------------

(def ^:private url-encoder (.. Base64 getUrlEncoder withoutPadding))
(def ^:private std-decoder (Base64/getDecoder))

(defn- b64url-bytes [^bytes b]
  (.encodeToString url-encoder b))

(defn- b64url-string [^String s]
  (b64url-bytes (.getBytes s "UTF-8")))

(defn- form-encode [m]
  (->> m
       (map (fn [[k v]]
              (str (URLEncoder/encode (name k) "UTF-8")
                   "="
                   (URLEncoder/encode (str v) "UTF-8"))))
       (str/join "&")))

;; ---------------------------------------------------------------------------
;; PEM → RSA private key
;; ---------------------------------------------------------------------------

(defn- pem->pkcs8-bytes [^String pem]
  (let [stripped (-> pem
                     (str/replace #"-----BEGIN [A-Z ]+-----" "")
                     (str/replace #"-----END [A-Z ]+-----" "")
                     (str/replace #"\s+" ""))]
    (.decode std-decoder stripped)))

(defn- load-rsa-private-key ^java.security.PrivateKey [pem-string]
  (let [kf (KeyFactory/getInstance "RSA")
        spec (PKCS8EncodedKeySpec. (pem->pkcs8-bytes pem-string))]
    (.generatePrivate kf spec)))

;; ---------------------------------------------------------------------------
;; Time + clock indirection
;; ---------------------------------------------------------------------------

(def ^:dynamic *now-seconds-fn*
  "Indirection for testability — bind to drive cache expiry."
  (fn [] (long (/ (System/currentTimeMillis) 1000))))

;; ---------------------------------------------------------------------------
;; Credentials file: parse + cache (by path)
;; ---------------------------------------------------------------------------

(defonce ^:private cred-cache (atom {}))

(defn- detect-cred-type [parsed]
  (or (some-> parsed :type str/lower-case keyword)
      ;; Fall back to inspection for malformed/old files
      (cond
        (:private_key parsed) :service_account
        (:refresh_token parsed) :authorized_user
        :else :unknown)))

(defn- read-cred-file
  "Load + parse a credentials file. Caches the parsed map (and, for
   service accounts, the materialized private key) by absolute path.
   Returns nil when path is nil/missing/unreadable."
  [path]
  (when (and path (try (.exists (io/file path)) (catch Exception _ false)))
    (or (get @cred-cache path)
        (when-let [parsed (try (json/parse-string (slurp path) true)
                               (catch Exception _ nil))]
          (let [type-kw (detect-cred-type parsed)
                pk (when (and (= :service_account type-kw)
                              (:private_key parsed))
                     (try (load-rsa-private-key (:private_key parsed))
                          (catch Exception _ nil)))
                entry (cond-> {:path path :json parsed :type type-kw}
                        pk (assoc :private-key pk))]
            (swap! cred-cache assoc path entry)
            entry)))))

;; ---------------------------------------------------------------------------
;; Access-token cache (keyed by credential identity)
;; ---------------------------------------------------------------------------

(defonce ^:private token-cache (atom {}))

(defn- cache-key-for [entry]
  (case (:type entry)
    :service_account (str "sa:" (get-in entry [:json :client_email]))
    :authorized_user (str "user:" (get-in entry [:json :client_id]))
    (str "other:" (:path entry))))

(defn- cached-or [entry produce-fn]
  (let [k (cache-key-for entry)
        cached (get @token-cache k)
        now (*now-seconds-fn*)]
    (if (and cached (< now (- (:expires-at cached) 60)))
      (:access-token cached)
      (let [{:keys [access-token expires-in]} (produce-fn)]
        (swap! token-cache assoc k
               {:access-token access-token
                :expires-at (+ now (or expires-in 3600))})
        access-token))))

;; ---------------------------------------------------------------------------
;; HTTP — token endpoint + metadata server
;; ---------------------------------------------------------------------------

(def ^:private oauth-token-url "https://oauth2.googleapis.com/token")
(def ^:private vertex-scope "https://www.googleapis.com/auth/cloud-platform")
(def ^:private metadata-token-url
  "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token")

(def ^:dynamic *token-endpoint-fn*
  "Indirection for testability. Default POSTs `body-map` to
   oauth2.googleapis.com/token as application/x-www-form-urlencoded
   and returns the parsed JSON body. Bind in tests to stub."
  nil)

(def ^:dynamic *metadata-fetch-fn*
  "Indirection for testability. Default does a short-timeout GET to
   metadata.google.internal; returns the parsed JSON body on 200,
   nil otherwise. Bind in tests to stub."
  nil)

(defn- default-token-endpoint-call [body-map]
  (let [resp (hc/request
              {:method :post
               :url oauth-token-url
               :headers {"Content-Type" "application/x-www-form-urlencoded"
                         "Accept" "application/json"}
               :body (form-encode body-map)
               :throw-exceptions? false
               :connect-timeout 10000
               :timeout 30000})
        status (:status resp)
        body (try (json/parse-string (:body resp) true)
                  (catch Exception _ (:body resp)))]
    (if (and (number? status) (< status 400))
      body
      (throw (ex-info "GCP token endpoint returned error"
                      {:error/type :auth/token-exchange-failed
                       :status status
                       :body body})))))

(defn- default-metadata-fetch []
  ;; metadata.google.internal won't resolve outside GCP; we use a tight
  ;; connect-timeout so non-GCP hosts fail fast.
  (try
    (let [resp (hc/request
                {:method :get
                 :url metadata-token-url
                 :headers {"Metadata-Flavor" "Google"}
                 :throw-exceptions? false
                 :connect-timeout 500
                 :timeout 1500})
          status (:status resp)]
      (when (and (number? status) (< status 400))
        (try (json/parse-string (:body resp) true)
             (catch Exception _ nil))))
    (catch Exception _ nil)))

(defn- call-token-endpoint [body]
  ((or *token-endpoint-fn* default-token-endpoint-call) body))

(defn- fetch-metadata []
  ((or *metadata-fetch-fn* default-metadata-fetch)))

;; ---------------------------------------------------------------------------
;; Token producers per credential type
;; ---------------------------------------------------------------------------

(defn- sa-jwt-token [entry]
  (let [sa (:json entry)
        pk (:private-key entry)
        client-email (:client_email sa)
        token-uri (or (:token_uri sa) oauth-token-url)
        now (*now-seconds-fn*)
        header {:alg "RS256" :typ "JWT"}
        claims {:iss client-email
                :scope vertex-scope
                :aud token-uri
                :iat now
                :exp (+ now 3600)}
        encoded-h (b64url-string (json/generate-string header))
        encoded-c (b64url-string (json/generate-string claims))
        signing-input (str encoded-h "." encoded-c)
        sig (doto (Signature/getInstance "SHA256withRSA")
              (.initSign pk)
              (.update (.getBytes signing-input "UTF-8")))
        assertion (str signing-input "." (b64url-bytes (.sign sig)))
        body (call-token-endpoint
              {:grant_type "urn:ietf:params:oauth:grant-type:jwt-bearer"
               :assertion assertion})]
    {:access-token (:access_token body)
     :expires-in (or (:expires_in body) 3600)}))

(defn- authorized-user-token [entry]
  (let [u (:json entry)
        body (call-token-endpoint
              {:grant_type "refresh_token"
               :refresh_token (:refresh_token u)
               :client_id (:client_id u)
               :client_secret (:client_secret u)})]
    {:access-token (:access_token body)
     :expires-in (or (:expires_in body) 3600)}))

(defn- token-from-cred-file [entry]
  (case (:type entry)
    :service_account (cached-or entry #(sa-jwt-token entry))
    :authorized_user (cached-or entry #(authorized-user-token entry))
    :external_account
    (throw (ex-info
            (str "External-account (workload identity federation) "
                 "credentials are not yet supported by this SDK. "
                 "Use a service-account JSON or run "
                 "`gcloud auth application-default login`.")
            {:error/type :auth/external-account-unsupported
             :path (:path entry)}))
    (throw (ex-info (str "Unrecognized credential type: " (:type entry))
                    {:error/type :auth/unknown-credential-type
                     :type (:type entry)
                     :path (:path entry)}))))

;; ---------------------------------------------------------------------------
;; Well-known ADC file
;; ---------------------------------------------------------------------------

(def ^:dynamic *well-known-path*
  "Default ADC well-known file path. Bound for testability."
  (str (System/getProperty "user.home")
       "/.config/gcloud/application_default_credentials.json"))

;; ---------------------------------------------------------------------------
;; Public surface — resolve-access-token + resolve-project
;; ---------------------------------------------------------------------------

(defn- explicit-cred-path [request profile]
  (or (get-in request [:request/provider-options :vertex :credentials-file])
      (get-in profile [:profile/quirks :vertex-credentials-file])
      (System/getenv "GOOGLE_APPLICATION_CREDENTIALS")))

(defn resolve-access-token
  "Resolve a GCP OAuth access token using the ADC chain. Returns the
   token string on success. Throws ex-info with
   :error/type :auth/missing-credentials when no source yields a token.

   Failures *within* a step (broken SA JSON, unsupported credential type,
   refused refresh-token grant) surface their own ex-info directly —
   the chain only falls through when a step legitimately has no
   credentials to offer (no env var set, no file at that path, no
   metadata server reachable)."
  [request profile]
  (or
   ;; Convenience layer 1: caller passed a bearer directly.
   (get-in request [:request/provider-options :vertex :access-token])

   ;; Convenience layer 2: pre-resolved bearer in env.
   (System/getenv "GOOGLE_OAUTH_ACCESS_TOKEN")

   ;; Proper ADC step 1: GOOGLE_APPLICATION_CREDENTIALS env var.
   ;; If the file is missing, fall through. If it exists but is
   ;; broken or unsupported, propagate the underlying error.
   (when-let [entry (read-cred-file (explicit-cred-path request profile))]
     (token-from-cred-file entry))

   ;; Proper ADC step 2: well-known file written by
   ;; `gcloud auth application-default login`.
   (when-let [entry (read-cred-file *well-known-path*)]
     (token-from-cred-file entry))

   ;; Proper ADC step 3: GCE / Cloud Run / GKE metadata server.
   ;; default-metadata-fetch already swallows network errors so
   ;; non-GCP hosts fall through fast.
   (when-let [meta (fetch-metadata)]
     (:access_token meta))

   (throw (ex-info
           (str "GCP credentials not found. Tried (in order): "
                "request opts :vertex :access-token, "
                "GOOGLE_OAUTH_ACCESS_TOKEN env, "
                "GOOGLE_APPLICATION_CREDENTIALS env var, "
                "well-known file at " *well-known-path* ", "
                "GCE/Cloud Run metadata server. "
                "For local dev, run `gcloud auth application-default login`.")
           {:error/type :auth/missing-credentials
            :attempted [:provider-options
                        :GOOGLE_OAUTH_ACCESS_TOKEN
                        :GOOGLE_APPLICATION_CREDENTIALS
                        :well-known-file
                        :metadata-server]
            :provider (:profile/id profile)}))))

(defn resolve-project
  "Resolve the GCP project id. Returns nil when no source provides one
   — callers are responsible for raising a clear error in that case."
  [request profile]
  (or (get-in request [:request/provider-options :vertex :project])
      (get-in profile [:profile/quirks :vertex-project])
      (System/getenv "GOOGLE_CLOUD_PROJECT")
      (System/getenv "GCLOUD_PROJECT")
      ;; Last resort — peek at any loaded credential file. SA JSON
      ;; usually has :project_id; authorized_user files written by
      ;; `gcloud auth application-default login --quota-project=...`
      ;; carry :quota_project_id.
      (when-let [entry (or (read-cred-file (explicit-cred-path request profile))
                           (read-cred-file *well-known-path*))]
        (or (get-in entry [:json :project_id])
            (get-in entry [:json :quota_project_id])))))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn clear-caches!
  "Wipe all in-memory caches. Tests call this between fixtures so
   leftover state from one test doesn't bleed into another."
  []
  (reset! cred-cache {})
  (reset! token-cache {}))
