(ns llm.sdk.gcp-auth-test
  "Unit tests for the GCP ADC chain. No live network calls — token
   endpoint and metadata server calls are stubbed via the
   *token-endpoint-fn* / *metadata-fetch-fn* / *well-known-path*
   dynamic vars in llm.sdk.gcp-auth."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.vertex-gemini :as vertex])
  (:import (java.security KeyPairGenerator)
           (java.util Base64)))

;; ---------------------------------------------------------------------------
;; Helpers — generate real SA / authorized_user JSON files in a temp dir
;; ---------------------------------------------------------------------------

(defn- pem-encode-pkcs8 [^java.security.PrivateKey pk]
  (let [b64 (.encodeToString (Base64/getEncoder) (.getEncoded pk))
        chunks (->> b64 (partition-all 64) (map #(apply str %)))]
    (str "-----BEGIN PRIVATE KEY-----\n"
         (str/join "\n" chunks)
         "\n-----END PRIVATE KEY-----\n")))

(defn- gen-sa-json-file
  "Generate a service_account JSON file with a real RSA key so the
   production code's PKCS#8 parsing path actually exercises."
  []
  (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
        kp (.generateKeyPair kpg)
        pem (pem-encode-pkcs8 (.getPrivate kp))
        sa {:type "service_account"
            :project_id "sa-project-123"
            :private_key_id "abc"
            :private_key pem
            :client_email "sa@sa-project-123.iam.gserviceaccount.com"
            :client_id "100000000000000000000"
            :token_uri "https://oauth2.googleapis.com/token"}
        tmp (java.io.File/createTempFile "sa-test" ".json")]
    (.deleteOnExit tmp)
    (spit tmp (json/generate-string sa))
    (.getAbsolutePath tmp)))

(defn- gen-authorized-user-json-file
  "Generate an authorized_user JSON file matching what
   `gcloud auth application-default login` writes."
  [& {:keys [quota-project]}]
  (let [u (cond-> {:type "authorized_user"
                   :client_id "32555940559.apps.googleusercontent.com"
                   :client_secret "fake-secret"
                   :refresh_token "1//fake-refresh"}
            quota-project (assoc :quota_project_id quota-project))
        tmp (java.io.File/createTempFile "user-test" ".json")]
    (.deleteOnExit tmp)
    (spit tmp (json/generate-string u))
    (.getAbsolutePath tmp)))

(defn- gen-external-account-json-file []
  (let [ea {:type "external_account"
            :audience "//iam.googleapis.com/projects/123/..."
            :subject_token_type "urn:ietf:params:oauth:token-type:jwt"
            :token_url "https://sts.googleapis.com/v1/token"
            :credential_source {:url "https://..."}}
        tmp (java.io.File/createTempFile "ext-test" ".json")]
    (.deleteOnExit tmp)
    (spit tmp (json/generate-string ea))
    (.getAbsolutePath tmp)))

(defn- reset-state [f]
  (gcp-auth/clear-caches!)
  (f))

(use-fixtures :each reset-state)

;; ---------------------------------------------------------------------------
;; Convenience layers (above ADC proper)
;; ---------------------------------------------------------------------------

(deftest resolve-token-prefers-provider-options-bearer
  (let [token (gcp-auth/resolve-access-token
               {:request/provider-options {:vertex {:access-token "from-opts"}}}
               {:profile/id :vertex-gemini})]
    (is (= "from-opts" token))))

;; ---------------------------------------------------------------------------
;; Proper ADC step 1: GOOGLE_APPLICATION_CREDENTIALS → SA JSON
;; ---------------------------------------------------------------------------

(deftest service-account-mints-jwt-and-exchanges
  (let [sa-path (gen-sa-json-file)
        captured (atom nil)]
    (binding [gcp-auth/*well-known-path* "/nonexistent"
              gcp-auth/*metadata-fetch-fn* (constantly nil)
              gcp-auth/*token-endpoint-fn* (fn [body]
                                             (reset! captured body)
                                             {:access_token "sa-token-abc"
                                              :expires_in 3600})]
      (let [req {:request/provider-options
                 {:vertex {:credentials-file sa-path}}}
            token (gcp-auth/resolve-access-token
                   req {:profile/id :vertex-gemini})]
        (is (= "sa-token-abc" token))
        (is (= "urn:ietf:params:oauth:grant-type:jwt-bearer"
               (:grant_type @captured))
            "SA flow uses jwt-bearer grant")
        (is (re-find #"^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$"
                     (:assertion @captured))
            "assertion is a three-part JWS")))))

;; ---------------------------------------------------------------------------
;; Proper ADC step 1 — authorized_user via GOOGLE_APPLICATION_CREDENTIALS
;; ---------------------------------------------------------------------------

(deftest authorized-user-uses-refresh-token-grant
  (let [user-path (gen-authorized-user-json-file)
        captured (atom nil)]
    (binding [gcp-auth/*well-known-path* "/nonexistent"
              gcp-auth/*metadata-fetch-fn* (constantly nil)
              gcp-auth/*token-endpoint-fn* (fn [body]
                                             (reset! captured body)
                                             {:access_token "user-token-def"
                                              :expires_in 3599})]
      (let [req {:request/provider-options
                 {:vertex {:credentials-file user-path}}}
            token (gcp-auth/resolve-access-token
                   req {:profile/id :vertex-gemini})]
        (is (= "user-token-def" token))
        (is (= "refresh_token" (:grant_type @captured))
            "authorized_user flow uses refresh_token grant")
        (is (= "1//fake-refresh" (:refresh_token @captured)))
        (is (= "32555940559.apps.googleusercontent.com" (:client_id @captured)))))))

;; ---------------------------------------------------------------------------
;; Proper ADC step 2: well-known file
;; ---------------------------------------------------------------------------

(deftest well-known-file-picked-up-when-no-env-var
  (let [user-path (gen-authorized-user-json-file)]
    (binding [gcp-auth/*well-known-path* user-path
              gcp-auth/*metadata-fetch-fn* (constantly nil)
              gcp-auth/*token-endpoint-fn* (fn [_]
                                             {:access_token "wkf-token"
                                              :expires_in 3600})]
      (when (nil? (System/getenv "GOOGLE_APPLICATION_CREDENTIALS"))
        (let [token (gcp-auth/resolve-access-token
                     {} {:profile/id :vertex-gemini})]
          (is (= "wkf-token" token)))))))

;; ---------------------------------------------------------------------------
;; Proper ADC step 3: GCE metadata server
;; ---------------------------------------------------------------------------

(deftest metadata-server-used-as-last-resort
  (binding [gcp-auth/*well-known-path* "/nonexistent"
            gcp-auth/*metadata-fetch-fn* (fn [] {:access_token "metadata-token"
                                                 :expires_in 3600
                                                 :token_type "Bearer"})
            gcp-auth/*token-endpoint-fn* (fn [_]
                                           (throw (ex-info "should not be called" {})))]
    (when (and (nil? (System/getenv "GOOGLE_APPLICATION_CREDENTIALS"))
               (nil? (System/getenv "GOOGLE_OAUTH_ACCESS_TOKEN")))
      (let [token (gcp-auth/resolve-access-token
                   {} {:profile/id :vertex-gemini})]
        (is (= "metadata-token" token))))))

;; ---------------------------------------------------------------------------
;; Error path
;; ---------------------------------------------------------------------------

(deftest missing-credentials-throws-clear-error
  (binding [gcp-auth/*well-known-path* "/nonexistent"
            gcp-auth/*metadata-fetch-fn* (constantly nil)
            gcp-auth/*token-endpoint-fn* (fn [_] (throw (ex-info "no" {})))]
    (when (and (nil? (System/getenv "GOOGLE_APPLICATION_CREDENTIALS"))
               (nil? (System/getenv "GOOGLE_OAUTH_ACCESS_TOKEN")))
      (try
        (gcp-auth/resolve-access-token {} {:profile/id :vertex-gemini})
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (let [d (ex-data e)]
            (is (= :auth/missing-credentials (:error/type d)))
            (is (re-find #"gcloud auth application-default login"
                         (ex-message e))
                "error message tells the user how to fix it")
            (is (re-find #"metadata server" (ex-message e)))))))))

(deftest external-account-throws-clear-unsupported-error
  (let [ext-path (gen-external-account-json-file)]
    (binding [gcp-auth/*well-known-path* "/nonexistent"
              gcp-auth/*metadata-fetch-fn* (constantly nil)]
      (let [req {:request/provider-options
                 {:vertex {:credentials-file ext-path}}}]
        (try
          (gcp-auth/resolve-access-token req {:profile/id :vertex-gemini})
          (is false "expected exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :auth/external-account-unsupported
                   (:error/type (ex-data e))))))))))

;; ---------------------------------------------------------------------------
;; Token cache reuse + expiry
;; ---------------------------------------------------------------------------

(deftest sa-token-cache-reuses-within-ttl
  (let [sa-path (gen-sa-json-file)
        call-count (atom 0)
        fake-now (atom 1000000)]
    (binding [gcp-auth/*well-known-path* "/nonexistent"
              gcp-auth/*metadata-fetch-fn* (constantly nil)
              gcp-auth/*now-seconds-fn* (fn [] @fake-now)
              gcp-auth/*token-endpoint-fn* (fn [_]
                                             (swap! call-count inc)
                                             {:access_token (str "sa-tok-" @call-count)
                                              :expires_in 3600})]
      (let [req {:request/provider-options
                 {:vertex {:credentials-file sa-path}}}
            t1 (gcp-auth/resolve-access-token req {:profile/id :vertex-gemini})
            t2 (gcp-auth/resolve-access-token req {:profile/id :vertex-gemini})]
        (is (= "sa-tok-1" t1))
        (is (= "sa-tok-1" t2) "second call hit cache")
        (is (= 1 @call-count))
        (reset! fake-now (+ @fake-now 3600))
        (let [t3 (gcp-auth/resolve-access-token req {:profile/id :vertex-gemini})]
          (is (= "sa-tok-2" t3) "cache evicts past expiry"))))))

(deftest user-token-cache-separate-from-sa-cache
  (let [sa-path (gen-sa-json-file)
        user-path (gen-authorized-user-json-file)]
    (binding [gcp-auth/*well-known-path* "/nonexistent"
              gcp-auth/*metadata-fetch-fn* (constantly nil)
              gcp-auth/*token-endpoint-fn*
              (fn [body]
                (if (= (:grant_type body) "refresh_token")
                  {:access_token "user-x" :expires_in 3600}
                  {:access_token "sa-x" :expires_in 3600}))]
      (let [sa-tok (gcp-auth/resolve-access-token
                    {:request/provider-options {:vertex {:credentials-file sa-path}}}
                    {:profile/id :vertex-gemini})
            user-tok (gcp-auth/resolve-access-token
                      {:request/provider-options {:vertex {:credentials-file user-path}}}
                      {:profile/id :vertex-gemini})]
        (is (= "sa-x" sa-tok))
        (is (= "user-x" user-tok))))))

;; ---------------------------------------------------------------------------
;; Project resolution
;; ---------------------------------------------------------------------------

(deftest resolve-project-prefers-request-opts
  (is (= "rq-proj"
         (gcp-auth/resolve-project
          {:request/provider-options {:vertex {:project "rq-proj"}}}
          {:profile/id :vertex-gemini}))))

(deftest resolve-project-from-profile-quirks
  (is (= "quirk-proj"
         (gcp-auth/resolve-project
          {} {:profile/id :vertex-gemini
              :profile/quirks {:vertex-project "quirk-proj"}}))))

(deftest resolve-project-reads-sa-json-project-id
  (let [sa-path (gen-sa-json-file)]
    (binding [gcp-auth/*well-known-path* "/nonexistent"]
      (when (and (nil? (System/getenv "GOOGLE_CLOUD_PROJECT"))
                 (nil? (System/getenv "GCLOUD_PROJECT")))
        (is (= "sa-project-123"
               (gcp-auth/resolve-project
                {:request/provider-options {:vertex {:credentials-file sa-path}}}
                {:profile/id :vertex-gemini})))))))

(deftest resolve-project-falls-back-to-authorized-user-quota-project
  (let [user-path (gen-authorized-user-json-file :quota-project "user-quota-proj")]
    (binding [gcp-auth/*well-known-path* "/nonexistent"]
      (when (and (nil? (System/getenv "GOOGLE_CLOUD_PROJECT"))
                 (nil? (System/getenv "GCLOUD_PROJECT")))
        (is (= "user-quota-proj"
               (gcp-auth/resolve-project
                {:request/provider-options {:vertex {:credentials-file user-path}}}
                {:profile/id :vertex-gemini})))))))

;; ---------------------------------------------------------------------------
;; Vertex-Gemini integration — the full build-request path uses these
;; ---------------------------------------------------------------------------

(deftest build-request-uses-resolved-bearer
  (let [profile (provider/get-provider :vertex-gemini)
        req {:request/model "gemini-2.5-flash"
             :request/messages [{:message/role :user :message/content "hi"}]
             :request/provider-options
             {:vertex {:project "p-1" :location "us-central1"
                       :access-token "tok-xyz"}}}
        built (vertex/build-request-vertex profile req)]
    (is (re-find #"/projects/p-1/locations/us-central1/" (:url built)))
    (is (= "Bearer tok-xyz" (get-in built [:headers "Authorization"])))))

(deftest build-request-regional-host
  (let [profile (provider/get-provider :vertex-gemini)
        req {:request/model "gemini-2.5-flash"
             :request/messages [{:message/role :user :message/content "hi"}]
             :request/provider-options
             {:vertex {:project "p-2" :location "europe-west4"
                       :access-token "tok-2"}}}
        built (vertex/build-request-vertex profile req)]
    (is (re-find #"^https://europe-west4-aiplatform\.googleapis\.com/"
                 (:url built)))))

(deftest build-request-global-host
  (let [profile (provider/get-provider :vertex-gemini)
        req {:request/model "gemini-2.5-flash"
             :request/messages [{:message/role :user :message/content "hi"}]
             :request/provider-options
             {:vertex {:project "p-3" :location "global"
                       :access-token "tok-3"}}}
        built (vertex/build-request-vertex profile req)]
    (is (re-find #"^https://aiplatform\.googleapis\.com/" (:url built)))))

(deftest build-request-throws-on-missing-project
  (let [profile (provider/get-provider :vertex-gemini)
        req {:request/model "gemini-2.5-flash"
             :request/messages [{:message/role :user :message/content "hi"}]
             :request/provider-options {:vertex {:access-token "tok-4"}}}]
    (with-redefs [gcp-auth/resolve-project (constantly nil)]
      (try
        (vertex/build-request-vertex profile req)
        (is false "expected exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= :vertex/missing-project (:error/type (ex-data e)))))))))
