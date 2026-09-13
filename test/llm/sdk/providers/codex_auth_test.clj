(ns llm.sdk.providers.codex-auth-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk.http :as http]
            [llm.sdk.providers.codex.auth :as auth])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files LinkOption]
           [java.nio.file.attribute FileAttribute PosixFilePermission]
           [java.time Instant]
           [java.util Base64]))

(def ^:private fixed-now-ms 2000000000000)

(defn- jwt [claims]
  (let [encoder (.withoutPadding (Base64/getUrlEncoder))
        encode (fn [value]
                 (.encodeToString encoder
                                  (.getBytes (json/generate-string value)
                                             StandardCharsets/UTF_8)))]
    (str (encode {:alg "none"}) "." (encode claims) ".signature")))

(defn- access-jwt [expires-at-seconds account-id]
  (jwt {:exp expires-at-seconds
        "https://api.openai.com/auth"
        {:chatgpt_account_id account-id}}))

(defn- write-json! [file data]
  (spit file (json/generate-string data)))

(defn- read-json [file]
  (json/parse-string (slurp file) true))

(defn- auth-data
  ([access-token refresh-token account-id]
   (auth-data access-token refresh-token account-id {}))
  ([access-token refresh-token account-id extra]
   (merge {:auth_mode "chatgpt"
           :tokens {:access_token access-token
                    :refresh_token refresh-token
                    :account_id account-id}}
          extra)))

(defn- with-synthetic-auth [data f]
  (let [dir-path (Files/createTempDirectory "codex-auth-test-"
                                            (make-array FileAttribute 0))
        dir (.toFile dir-path)
        file (.toFile (.resolve dir-path "auth.json"))]
    (write-json! file data)
    (try
      (reset! @#'auth/codex-auth-cache nil)
      (with-redefs-fn {#'auth/codex-auth-file-path
                       (constantly (.getPath file))}
        #(f file))
      (finally
        (reset! @#'auth/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(defn- thrown-info [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      error)))

(deftest managed-auth-file-reads-are-cached-until-state-changes
  (with-synthetic-auth
    (auth-data "opaque-access-one" "refresh-one" "acct-one")
    (fn [file]
      (let [real-slurp slurp
            reads (atom 0)]
        (with-redefs [clojure.core/slurp
                      (fn [& args]
                        (swap! reads inc)
                        (apply real-slurp args))]
          (is (= "opaque-access-one" (:access-token (auth/read-codex-auth))))
          (is (= "opaque-access-one" (:access-token (auth/read-codex-auth))))
          (is (= 1 @reads))
          (write-json! file
                       (auth-data "opaque-access-two-longer"
                                  "refresh-two"
                                  "acct-one"))
          (.setLastModified file (+ 5000 (.lastModified file)))
          (is (= "opaque-access-two-longer"
                 (:access-token (auth/read-codex-auth))))
          (is (= 2 @reads)))))))

(deftest only-chatgpt-and-unambiguous-legacy-token-files-are-managed
  (with-synthetic-auth
    (auth-data "chatgpt-access" "chatgpt-refresh" "acct-one")
    (fn [file]
      (is (= "chatgpt-access" (:access-token (auth/read-codex-auth))))

      (write-json! file {:auth_mode "apikey"
                         :tokens {:access_token "must-not-use"
                                  :refresh_token "must-not-refresh"}})
      (reset! @#'auth/codex-auth-cache nil)
      (is (nil? (auth/read-codex-auth)))

      (write-json! file {:OPENAI_API_KEY "explicit-api-key"
                         :tokens {:access_token "legacy-looking-token"
                                  :refresh_token "legacy-looking-refresh"}})
      (reset! @#'auth/codex-auth-cache nil)
      (is (nil? (auth/read-codex-auth)))

      (write-json! file {:tokens {:access_token "legacy-access"
                                  :refresh_token "legacy-refresh"}})
      (reset! @#'auth/codex-auth-cache nil)
      (is (= "legacy-access" (:access-token (auth/read-codex-auth)))))))

(deftest proactive-refresh-persists-safely-and-preserves-optional-fields
  (let [now-seconds (quot fixed-now-ms 1000)
        expiring (access-jwt (+ now-seconds 60) "acct-one")
        refreshed (access-jwt (+ now-seconds 3600) "acct-one")
        id-token (jwt {"https://api.openai.com/auth"
                       {:chatgpt_account_id "acct-one"}})
        captured (atom nil)]
    (with-synthetic-auth
      {:auth_mode "chatgpt"
       :last_refresh "2030-01-01T00:00:00Z"
       :unrelated {:keep true}
       :tokens {:access_token expiring
                :refresh_token "refresh-original"
                :id_token id-token
                :account_id "acct-one"
                :unrelated_token_key "keep-too"}}
      (fn [file]
        (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                         #'http/request
                         (fn [request]
                           (reset! captured request)
                           {:status 200
                            :body {:access_token refreshed}})}
          (fn []
            (let [{:keys [headers recover!]} (auth/request-auth {})
                  stored (read-json file)
                  permissions (Files/getPosixFilePermissions
                               (.toPath file)
                               (make-array LinkOption 0))]
              (is (= (str "Bearer " refreshed) (get headers "Authorization")))
              (is (fn? recover!))
              (is (= {:client_id "app_EMoamEEZ73f0CkXaXp7hrann"
                      :grant_type "refresh_token"
                      :refresh_token "refresh-original"}
                     (:body @captured)))
              (is (= "https://auth.openai.com/oauth/token" (:url @captured)))
              (is (= :post (:method @captured)))
              (is (= 30000 (:timeout-ms @captured)))
              (is (= 10000 (:connect-timeout-ms @captured)))
              (is (= refreshed (get-in stored [:tokens :access_token])))
              (is (= "refresh-original"
                     (get-in stored [:tokens :refresh_token])))
              (is (= id-token (get-in stored [:tokens :id_token])))
              (is (= "keep-too"
                     (get-in stored [:tokens :unrelated_token_key])))
              (is (= {:keep true} (:unrelated stored)))
              (is (= (str (Instant/ofEpochMilli fixed-now-ms))
                     (:last_refresh stored)))
              (is (= #{PosixFilePermission/OWNER_READ
                       PosixFilePermission/OWNER_WRITE}
                     (set permissions)))
              (is (= ["auth.json"] (vec (sort (seq (.list (.getParentFile file))))))))))))))

(deftest stale-last-refresh-triggers-refresh-with-an-opaque-access-token
  (with-synthetic-auth
    (auth-data "opaque-access" "opaque-refresh" "acct-one"
               {:last_refresh "2030-01-01T00:00:00Z"})
    (fn [_]
      (let [requests (atom 0)]
        (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                         #'http/request
                         (fn [_]
                           (swap! requests inc)
                           {:status 200 :body {:access_token "opaque-new"}})}
          (fn []
            (is (= "Bearer opaque-new"
                   (get-in (auth/request-auth {}) [:headers "Authorization"])))
            (is (= 1 @requests))))))))

(deftest opaque-legacy-token-without-refresh-metadata-is-not-proactive
  (with-synthetic-auth
    {:tokens {:access_token "legacy-opaque"
              :refresh_token "legacy-refresh"}}
    (fn [_]
      (with-redefs [http/request
                    (fn [_]
                      (throw (AssertionError. "refresh must not be attempted")))]
        (is (= "Bearer legacy-opaque"
               (get-in (auth/request-auth {}) [:headers "Authorization"])))))))

(deftest concurrent-proactive-refresh-does-not-reuse-a-rotating-refresh-token
  (let [now-seconds (quot fixed-now-ms 1000)
        expired (access-jwt (- now-seconds 1) "acct-one")
        fresh (access-jwt (+ now-seconds 3600) "acct-one")]
    (with-synthetic-auth
      (auth-data expired "rotating-refresh-one" "acct-one")
      (fn [file]
        (let [requests (atom 0)
              entered (promise)
              release (promise)
              first-result (atom nil)
              second-result (atom nil)]
          (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                           #'http/request
                           (fn [_]
                             (swap! requests inc)
                             (deliver entered true)
                             @release
                             {:status 200
                              :body {:access_token fresh
                                     :refresh_token "rotating-refresh-two"}})}
            (fn []
              (let [first-call (future (auth/request-auth {}))]
                (try
                  (is (= true (deref entered 2000 ::timeout)))
                  (let [second-call (future (auth/request-auth {}))]
                    (deliver release true)
                    (reset! first-result (deref first-call 2000 ::timeout))
                    (reset! second-result (deref second-call 2000 ::timeout))
                    (is (not= ::timeout @first-result))
                    (is (not= ::timeout @second-result))
                    (is (= 1 @requests))
                    (is (= "rotating-refresh-two"
                           (get-in (read-json file) [:tokens :refresh_token])))
                    (is (= (str "Bearer " fresh)
                           (get-in @first-result [:headers "Authorization"])))
                    (is (= (str "Bearer " fresh)
                           (get-in @second-result [:headers "Authorization"]))))
                  (finally
                    (deliver release true)
                    (future-cancel first-call)))))))))))

(deftest in-flight-credential-rotation-is-never-overwritten
  (let [now-seconds (quot fixed-now-ms 1000)
        expired (access-jwt (- now-seconds 1) "acct-one")
        externally-refreshed (access-jwt (+ now-seconds 7200) "acct-one")
        obsolete-response (access-jwt (+ now-seconds 3600) "acct-one")]
    (with-synthetic-auth
      (auth-data expired "refresh-old" "acct-one")
      (fn [file]
        (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                         #'http/request
                         (fn [_]
                           (write-json! file
                                        (auth-data externally-refreshed
                                                   "refresh-external"
                                                   "acct-one"))
                           {:status 200
                            :body {:access_token obsolete-response
                                   :refresh_token "refresh-obsolete"}})}
          (fn []
            (is (= (str "Bearer " externally-refreshed)
                   (get-in (auth/request-auth {}) [:headers "Authorization"])))
            (is (= externally-refreshed
                   (get-in (read-json file) [:tokens :access_token])))
            (is (= "refresh-external"
                   (get-in (read-json file) [:tokens :refresh_token])))))))))

(deftest recovery-reloads-and-refuses-an-account-switch
  (let [now-seconds (quot fixed-now-ms 1000)
        account-one-token (access-jwt (+ now-seconds 3600) "acct-one")
        account-two-token (access-jwt (+ now-seconds 3600) "acct-two")]
    (with-synthetic-auth
      (auth-data account-one-token "refresh-one" "acct-one")
      (fn [file]
        (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                         #'http/request
                         (fn [_]
                           (throw (AssertionError. "authority must not be called")))}
          (fn []
            (let [recover! (:recover! (auth/request-auth {}))]
              (write-json! file
                           (auth-data account-two-token "refresh-two" "acct-two"))
              (let [error (thrown-info recover!)]
                (is (= :auth/reauthentication-required
                       (:error/type (ex-data error))))
                (is (= :account-changed (:auth/reason (ex-data error))))))))))))

(deftest recovery-uses-a-reloaded-token-once
  (let [now-seconds (quot fixed-now-ms 1000)
        first-token (access-jwt (+ now-seconds 3600) "acct-one")
        rotated-token (access-jwt (+ now-seconds 7200) "acct-one")]
    (with-synthetic-auth
      (auth-data first-token "refresh-one" "acct-one")
      (fn [file]
        (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                         #'http/request
                         (fn [_]
                           (throw (AssertionError. "authority must not be called")))}
          (fn []
            (let [recover! (:recover! (auth/request-auth {}))]
              (write-json! file
                           (auth-data rotated-token "refresh-two" "acct-one"))
              (is (= (str "Bearer " rotated-token)
                     (get (recover!) "Authorization")))
              (is (= :auth/recovery-exhausted
                     (:error/type (ex-data (thrown-info recover!))))))))))))

(deftest permanent-refresh-errors-are-sanitized-and-require-reauthentication
  (let [now-seconds (quot fixed-now-ms 1000)
        expired (access-jwt (- now-seconds 1) "acct-one")]
    (doseq [[code reason] [["refresh_token_expired" :refresh-token-expired]
                           ["refresh_token_reused" :refresh-token-reused]
                           ["refresh_token_invalidated" :refresh-token-invalidated]
                           ["invalid_grant" :invalid-grant]]]
      (testing code
        (with-synthetic-auth
          (auth-data expired "synthetic-refresh-secret" "acct-one")
          (fn [file]
            (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                             #'http/request
                             (fn [_]
                               {:status 400
                                :body {:error {:code code
                                               :message "synthetic-response-secret"}}})}
              (fn []
                (let [error (thrown-info #(auth/request-auth {}))
                      rendered (str (ex-message error) " " (pr-str (ex-data error)))]
                  (is (= :auth/reauthentication-required
                         (:error/type (ex-data error))))
                  (is (= reason (:auth/reason (ex-data error))))
                  (is (false? (:retryable? (ex-data error))))
                  (is (not (.contains rendered "synthetic-refresh-secret")))
                  (is (not (.contains rendered "synthetic-response-secret")))
                  (is (= "synthetic-refresh-secret"
                         (get-in (read-json file) [:tokens :refresh_token]))))))))))))

(deftest transient-refresh-errors-preserve-credentials-and-are-retriable
  (let [now-seconds (quot fixed-now-ms 1000)
        expired (access-jwt (- now-seconds 1) "acct-one")]
    (with-synthetic-auth
      (auth-data expired "synthetic-refresh-secret" "acct-one")
      (fn [file]
        (with-redefs-fn {#'auth/now-ms (constantly fixed-now-ms)
                         #'http/request
                         (fn [_]
                           {:status 503
                            :body {:error {:message "synthetic-response-secret"}}})}
          (fn []
            (let [error (thrown-info #(auth/request-auth {}))
                  rendered (str (ex-message error) " " (pr-str (ex-data error)))]
              (is (= :auth/refresh-failed (:error/type (ex-data error))))
              (is (= :authority-rejected (:auth/reason (ex-data error))))
              (is (true? (:retryable? (ex-data error))))
              (is (= 503 (:status (ex-data error))))
              (is (not (.contains rendered "synthetic-refresh-secret")))
              (is (not (.contains rendered "synthetic-response-secret")))
              (is (= "synthetic-refresh-secret"
                     (get-in (read-json file) [:tokens :refresh_token]))))))))))

(deftest explicit-host-credentials-bypass-file-and-refresh
  (let [external-jwt (jwt {"https://api.openai.com/auth"
                           {:chatgpt_account_id "jwt-account"
                            :chatgpt_account_is_fedramp true}})
        file-access (fn []
                      (throw (AssertionError. "auth.json must not be touched")))
        network-access (fn [_]
                         (throw (AssertionError. "refresh must not be attempted")))]
    (with-redefs-fn {#'auth/codex-auth-file-path file-access
                     #'http/request network-access}
      (fn []
        (let [resolved (auth/request-auth
                        {:profile/auth-token "profile-token"
                         :profile/account-id "profile-account"
                         :profile/default-headers
                         {"Authorization" "Bearer default-token"
                          "ChatGPT-Account-ID" "default-account"}
                         :profile/runtime-headers
                         {"authorization" "Bearer runtime-token"
                          "chatgpt-account-id" "runtime-account"}})]
          (is (= "Bearer runtime-token"
                 (get-in resolved [:headers "Authorization"])))
          (is (= "runtime-account"
                 (get-in resolved [:headers "ChatGPT-Account-ID"])))
          (is (not (contains? resolved :recover!))))

        (let [resolved (auth/request-auth {:profile/auth-token external-jwt})]
          (is (= "jwt-account"
                 (get-in resolved [:headers "ChatGPT-Account-ID"])))
          (is (= "true" (get-in resolved [:headers "X-OpenAI-Fedramp"])))
          (is (not (contains? resolved :recover!))))))))
