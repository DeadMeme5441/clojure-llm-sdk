(ns llm.sdk.providers.codex.auth
  "Managed ChatGPT OAuth credentials for the Codex backend."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [llm.sdk.http :as http])
  (:import [java.io File FileOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file AtomicMoveNotSupportedException CopyOption Files Path
            StandardCopyOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission]
           [java.time Instant]
           [java.util Base64 HashSet]))

(def ^:private refresh-url "https://auth.openai.com/oauth/token")
(def ^:private oauth-client-id "app_EMoamEEZ73f0CkXaXp7hrann")
(def ^:private refresh-timeout-ms 30000)
(def ^:private refresh-connect-timeout-ms 10000)
(def ^:private refresh-window-ms (* 5 60 1000))
(def ^:private stale-refresh-ms (* 8 24 60 60 1000))

(defn- now-ms []
  (System/currentTimeMillis))

(defn- codex-auth-file-path []
  (let [codex-home (or (System/getenv "CODEX_HOME")
                       (str (System/getProperty "user.home") "/.codex"))]
    (str codex-home "/auth.json")))

(def ^:private codex-auth-cache
  "Cached full auth.json parse, invalidated by path, mtime, and length."
  (atom nil))

(defonce ^:private refresh-locks (atom {}))

(defn- refresh-lock [path]
  (or (get @refresh-locks path)
      (get (swap! refresh-locks
                  #(if (contains? % path) % (assoc % path (Object.))))
           path)))

(defn- non-blank-string [x]
  (when (and (string? x) (seq (str/trim x)))
    (str/trim x)))

(defn- codex-auth-file-state [path]
  (try
    (let [f (File. path)]
      (when (.isFile f)
        {:path (.getAbsolutePath f)
         :modified-ms (.lastModified f)
         :length (.length f)}))
    (catch Exception _ nil)))

(defn- jwt-claims [token]
  (when (string? token)
    (try
      (let [parts (str/split token #"\.")]
        (when (= 3 (count parts))
          (let [payload (nth parts 1)
                padding (mod (- 4 (mod (count payload) 4)) 4)
                decoded (.decode (Base64/getUrlDecoder)
                                 (str payload (apply str (repeat padding "="))))]
            (json/parse-string (String. decoded StandardCharsets/UTF_8) false))))
      (catch Exception _ nil))))

(defn- jwt-account-id [token]
  (let [claims (jwt-claims token)]
    (or (get-in claims ["https://api.openai.com/auth" "chatgpt_account_id"])
        (get claims "chatgpt_account_id"))))

(defn- jwt-fedramp? [token]
  (let [claims (jwt-claims token)]
    (true? (or (get-in claims ["https://api.openai.com/auth"
                               "chatgpt_account_is_fedramp"])
               (get claims "chatgpt_account_is_fedramp")))))

(defn- jwt-exp-ms [token]
  (when-let [exp (get (jwt-claims token) "exp")]
    (when (number? exp)
      (* 1000 (long exp)))))

(defn- instant-ms [value]
  (try
    (when-let [s (non-blank-string value)]
      (.toEpochMilli (Instant/parse s)))
    (catch Exception _ nil)))

(defn- managed-auth-data? [data]
  (let [mode (:auth_mode data)]
    (or (= "chatgpt" (some-> mode name str/lower-case))
        (and (nil? mode)
             (nil? (non-blank-string (:OPENAI_API_KEY data)))))))

(defn- parse-auth-data [data state]
  (when (and (map? data) (managed-auth-data? data))
    (when-let [tokens (when (map? (:tokens data)) (:tokens data))]
      (when-let [access-token (non-blank-string (:access_token tokens))]
        (let [refresh-token (non-blank-string (:refresh_token tokens))
              id-token (:id_token tokens)]
          {:access-token access-token
           :refresh-token refresh-token
           :account-id (or (non-blank-string (:account_id tokens))
                           (when (map? id-token)
                             (non-blank-string (:chatgpt_account_id id-token)))
                           (jwt-account-id id-token)
                           (jwt-account-id access-token))
           :account-is-fedramp? (or (when (map? id-token)
                                      (true? (:chatgpt_account_is_fedramp id-token)))
                                    (jwt-fedramp? id-token)
                                    (jwt-fedramp? access-token))
           :auth-mode (:auth_mode data)
           :last-refresh (:last_refresh data)
           :source-id-token id-token
           :data data
           :state state})))))

(defn- load-auth-snapshot [path state]
  (try
    (some-> (slurp path)
            (json/parse-string true)
            (parse-auth-data state))
    (catch Exception _ nil)))

(defn- read-auth-snapshot
  ([] (read-auth-snapshot false))
  ([fresh?]
   (let [path (codex-auth-file-path)
         state (codex-auth-file-state path)]
     (if-not state
       (do (reset! codex-auth-cache nil) nil)
       (let [cached @codex-auth-cache]
         (if (and (not fresh?) (= state (:state cached)))
           (:auth cached)
           (let [auth (load-auth-snapshot path state)]
             (reset! codex-auth-cache {:state state :auth auth})
             auth)))))))

(defn- public-auth [auth]
  (when auth
    (select-keys auth [:access-token :refresh-token :account-id
                       :account-is-fedramp? :auth-mode])))

(defn read-codex-auth
  "Read and cache managed Codex CLI credentials from CODEX_HOME/auth.json.
   Returns nil when the file is absent, malformed, or lacks an access token."
  []
  (public-auth (read-auth-snapshot)))

(defn- auth-headers [auth]
  (when auth
    (cond-> {"Authorization" (str "Bearer " (:access-token auth))
             "User-Agent" "codex_cli_rs/0.0.0 (clojure-llm-sdk)"
             "originator" "codex_cli_rs"}
      (:account-id auth) (assoc "ChatGPT-Account-ID" (:account-id auth))
      (:account-is-fedramp? auth) (assoc "X-OpenAI-Fedramp" "true"))))

(defn- header-entry [headers header-name]
  (or (some (fn [[k value]]
              (when (= (str/lower-case (name k)) header-name)
                [true value]))
            headers)
      [false nil]))

(defn- bearer-token [headers]
  (let [[_ value] (header-entry headers "authorization")]
    (when (string? value)
      (some-> (re-matches #"(?i)Bearer\s+(.+)" value)
              second
              non-blank-string))))

(defn- external-auth [profile]
  (let [default-headers (:profile/default-headers profile)
        runtime-headers (:profile/runtime-headers profile)
        access-token (or (bearer-token runtime-headers)
                         (non-blank-string (:profile/auth-token profile))
                         (bearer-token default-headers))]
    (when access-token
      (let [[runtime-account? runtime-account]
            (header-entry runtime-headers "chatgpt-account-id")
            [_ default-account] (header-entry default-headers
                                              "chatgpt-account-id")
            [runtime-fedramp? runtime-fedramp]
            (header-entry runtime-headers "x-openai-fedramp")
            [default-fedramp? default-fedramp]
            (header-entry default-headers "x-openai-fedramp")
            explicit-fedramp (cond
                               runtime-fedramp? runtime-fedramp
                               default-fedramp? default-fedramp
                               :else nil)]
        {:access-token access-token
         :account-id (or (when runtime-account?
                           (non-blank-string runtime-account))
                         (non-blank-string (:profile/account-id profile))
                         (non-blank-string default-account)
                         (jwt-account-id access-token))
         :account-is-fedramp? (if (or runtime-fedramp? default-fedramp?)
                                (= "true" (some-> explicit-fedramp
                                                  str
                                                  str/lower-case))
                                (jwt-fedramp? access-token))}))))

(defn codex-backend-auth-headers
  "Build Codex backend headers without initiating a refresh. With a profile,
   explicit host credentials take precedence over the managed auth file."
  ([] (auth-headers (read-auth-snapshot)))
  ([profile]
   (auth-headers (or (external-auth profile) (read-auth-snapshot)))))

(defn codex-backend-available?
  "Return true when the managed auth file contains an access token."
  []
  (boolean (read-codex-auth)))

(defn- missing-auth! []
  (throw (ex-info "Codex backend OAuth credentials are unavailable"
                  {:error/type :auth/missing-codex-backend-token
                   :provider :codex-backend
                   :auth/file (codex-auth-file-path)})))

(defn- reauthentication-required! [reason]
  (throw (ex-info "Codex backend OAuth credentials require reauthentication"
                  {:error/type :auth/reauthentication-required
                   :provider :codex-backend
                   :auth/reason reason
                   :retryable? false})))

(defn- refresh-failed! [reason status]
  (throw (ex-info "Codex backend OAuth token refresh failed"
                  (cond-> {:error/type :auth/refresh-failed
                           :provider :codex-backend
                           :auth/reason reason
                           :retryable? true}
                    (number? status) (assoc :status status)))))

(defn- needs-refresh? [auth]
  (let [now (now-ms)
        expires-at (jwt-exp-ms (:access-token auth))
        refreshed-at (instant-ms (:last-refresh auth))]
    (or (and expires-at (<= expires-at (+ now refresh-window-ms)))
        (and refreshed-at (< refreshed-at (- now stale-refresh-ms))))))

(defn- error-code [body]
  (let [error (when (map? body) (or (:error body) (get body "error")))
        code (cond
               (map? error) (or (:code error) (get error "code"))
               (string? error) error
               (map? body) (or (:code body) (get body "code"))
               :else nil)]
    (some-> code str str/lower-case)))

(defn- classify-refresh-error! [{:keys [status body]}]
  (let [code (error-code body)
        known-reason ({"refresh_token_expired" :refresh-token-expired
                       "refresh_token_reused" :refresh-token-reused
                       "refresh_token_invalidated" :refresh-token-invalidated}
                      code)]
    (cond
      known-reason (reauthentication-required! known-reason)
      (= "invalid_grant" code) (reauthentication-required! :invalid-grant)
      (= 401 status) (reauthentication-required! :unauthorized)
      :else (refresh-failed! :authority-rejected status))))

(defn- refresh-request [profile refresh-token]
  (let [request (cond-> {:method :post
                         :url refresh-url
                         :headers {"Content-Type" "application/json"
                                   "Accept" "application/json"}
                         :body {:client_id oauth-client-id
                                :grant_type "refresh_token"
                                :refresh_token refresh-token}
                         :connect-timeout-ms refresh-connect-timeout-ms
                         :timeout-ms refresh-timeout-ms}
                  (:profile/http-client profile)
                  (assoc :http-client (:profile/http-client profile)))]
    (try
      (http/request request)
      (catch Exception _
        (refresh-failed! :transport nil)))))

(def ^:private owner-permissions
  (HashSet. [PosixFilePermission/OWNER_READ PosixFilePermission/OWNER_WRITE]))

(defn- set-owner-only! [^Path path]
  (try
    (Files/setPosixFilePermissions path owner-permissions)
    (catch UnsupportedOperationException _
      (let [file (.toFile path)]
        (.setReadable file false false)
        (.setWritable file false false)
        (.setExecutable file false false)
        (when-not (and (.setReadable file true true)
                       (.setWritable file true true))
          (throw (java.io.IOException.
                  "Could not restrict Codex auth file permissions")))))))

(defn- atomic-write-auth! [path data]
  (let [target (.toAbsolutePath (.toPath (File. path)))
        parent (.getParent target)
        _ (Files/createDirectories parent (make-array FileAttribute 0))
        temp (Files/createTempFile parent ".auth-" ".tmp"
                                   (make-array FileAttribute 0))
        bytes (.getBytes (json/generate-string data) StandardCharsets/UTF_8)]
    (try
      (set-owner-only! temp)
      (with-open [out (FileOutputStream. (.toFile temp))]
        (.write out bytes)
        (.flush out)
        (.sync (.getFD out)))
      (try
        (Files/move temp target
                    (into-array CopyOption
                                [StandardCopyOption/ATOMIC_MOVE
                                 StandardCopyOption/REPLACE_EXISTING]))
        (catch AtomicMoveNotSupportedException _
          (Files/move temp target
                      (into-array CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING]))))
      (set-owner-only! target)
      (finally
        (Files/deleteIfExists temp)))))

(defn- same-account! [expected actual]
  (when-not (= expected actual)
    (reauthentication-required! :account-changed)))

(defn- credential-identity [auth]
  (select-keys auth [:access-token :refresh-token :account-id :source-id-token]))

(defn- response-entry [body key]
  (let [string-key (name key)]
    (cond
      (contains? body key) [true (get body key)]
      (contains? body string-key) [true (get body string-key)]
      :else [false nil])))

(defn- refresh-response-account-id [body]
  (let [[_ id-token] (response-entry body :id_token)
        [_ access-token] (response-entry body :access_token)]
    (or (jwt-account-id id-token)
        (jwt-account-id access-token))))

(defn- merge-refresh-response [data body]
  (let [token-keys [[:id_token :id_token]
                    [:access_token :access_token]
                    [:refresh_token :refresh_token]]]
    (reduce (fn [result [response-key stored-key]]
              (let [[present? value] (response-entry body response-key)]
                (if-let [token (and present? (non-blank-string value))]
                  (assoc-in result [:tokens stored-key] token)
                  result)))
            data token-keys)))

(defn- persist-refresh! [attempted response-body]
  ;; Reload after the authority call and never overwrite credentials that
  ;; changed while the refresh request was in flight.
  (when-let [returned-account (refresh-response-account-id response-body)]
    (same-account! (:account-id attempted) returned-account))
  (let [current (or (read-auth-snapshot true) (missing-auth!))]
    (same-account! (:account-id attempted) (:account-id current))
    (if (not= (credential-identity attempted) (credential-identity current))
      current
      (let [updated (-> (merge-refresh-response (:data current) response-body)
                        (assoc :last_refresh
                               (str (Instant/ofEpochMilli (now-ms)))))
            path (codex-auth-file-path)]
        (try
          (atomic-write-auth! path updated)
          (catch Exception _
            (refresh-failed! :persistence nil)))
        (reset! codex-auth-cache nil)
        (or (read-auth-snapshot true)
            (refresh-failed! :persistence nil))))))

(defn- refresh-from-authority! [auth profile]
  (let [refresh-token (:refresh-token auth)]
    (when-not refresh-token
      (reauthentication-required! :missing-refresh-token))
    (let [{:keys [status body] :as response}
          (refresh-request profile refresh-token)]
      (if (and (number? status) (<= 200 status 299) (map? body))
        (persist-refresh! auth body)
        (if (and (number? status) (<= 200 status 299))
          (refresh-failed! :invalid-response status)
          (classify-refresh-error! response))))))

(defn- managed-request-auth [profile]
  (let [initial (or (read-auth-snapshot) (missing-auth!))
        shared-lock (refresh-lock (codex-auth-file-path))
        current (if (needs-refresh? initial)
                  (locking shared-lock
                    (let [fresh (or (read-auth-snapshot true) (missing-auth!))]
                      (same-account! (:account-id initial) (:account-id fresh))
                      (if (needs-refresh? fresh)
                        (refresh-from-authority! fresh profile)
                        fresh)))
                  initial)
        failed-identity (credential-identity current)
        used? (atom false)
        recover! (fn []
                   (when-not (compare-and-set! used? false true)
                     (throw (ex-info "Codex backend OAuth recovery was already attempted"
                                     {:error/type :auth/recovery-exhausted
                                      :provider :codex-backend
                                      :retryable? false})))
                   (locking shared-lock
                     (let [fresh (or (read-auth-snapshot true) (missing-auth!))]
                       (same-account! (:account-id failed-identity)
                                      (:account-id fresh))
                       (auth-headers
                        (if (not= (:access-token failed-identity)
                                  (:access-token fresh))
                          fresh
                          (refresh-from-authority! fresh profile))))))]
    {:headers (auth-headers current)
     :recover! recover!}))

(defn request-auth
  "Resolve authentication for one native Codex request.

   Explicit :profile/auth-token credentials are host-managed and never touch or
   refresh auth.json. Managed file credentials refresh proactively when their
   access JWT expires within five minutes or last_refresh is over eight days
   old. The returned one-shot :recover! callback reloads the file before a 401
   recovery and returns fresh headers."
  [profile]
  (if-let [auth (external-auth profile)]
    {:headers (auth-headers auth)}
    (managed-request-auth profile)))
