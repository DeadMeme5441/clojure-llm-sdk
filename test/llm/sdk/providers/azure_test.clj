(ns llm.sdk.providers.azure-test
  "Coverage for the Azure OpenAI deployment-routing helper.

   Azure shares OpenAI's body shape but differs in URL construction
   ({endpoint}/openai/deployments/{deployment}/chat/completions
    ?api-version=YYYY-MM-DD) and standard auth header (api-key vs
   Authorization: Bearer).

   Live smoke lives in llm.sdk.live-azure-test."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai-chat :as openai]))

;; ---------------------------------------------------------------------------
;; register-azure-deployment! shape
;; ---------------------------------------------------------------------------

(defn- register-test-deployment!
  ([] (register-test-deployment! {}))
  ([overrides]
   (openai/register-azure-deployment!
    (merge {:id :azure-test-1
            :endpoint "https://test-resource.openai.azure.com"
            :deployment "gpt-4o-prod"
            :api-version "2024-08-01-preview"
            :env-var-names ["AZURE_OPENAI_API_KEY"]}
           overrides))))

(deftest test-register-azure-deployment-defaults
  (register-test-deployment!)
  (let [p (provider/get-provider :azure-test-1)]
    (is (some? p))
    (is (= :openai-chat (:profile/protocol-family p)))
    (is (= "https://test-resource.openai.azure.com" (:profile/base-url p)))
    (is (= :api-key-header (:profile/auth-strategy p)))
    (is (= "api-key" (:profile/auth-header-name p)))
    (is (= ["AZURE_OPENAI_API_KEY"] (:profile/env-var-names p)))
    (is (= "gpt-4o-prod" (:azure/deployment p)))
    (is (= "2024-08-01-preview" (:azure/api-version p)))
    (is (false? (:profile/supports-model-listing p))
        "Azure /models is per-deployment — disabled for catalog")
    (is (fn? (:profile/transport-constructor p)))
    (is (fn? (:profile/url-builder p)))))

(deftest test-register-azure-deployment-aad-bearer
  (register-test-deployment! {:id :azure-aad
                              :auth-strategy :bearer
                              :env-var-names ["AZURE_AD_TOKEN"]})
  (let [p (provider/get-provider :azure-aad)]
    (is (= :bearer (:profile/auth-strategy p)))
    (is (nil? (:profile/auth-header-name p))
        ":auth-header-name is only set for the api-key strategy")))

(deftest test-register-azure-deployment-missing-fields-throws
  (is (thrown-with-msg? Exception #"register-azure-deployment!"
        (openai/register-azure-deployment!
         {:id :azure-bad
          :endpoint "https://x.openai.azure.com"
          :deployment "d"})))) ; missing :api-version

;; ---------------------------------------------------------------------------
;; URL construction
;; ---------------------------------------------------------------------------

(deftest test-azure-url-construction
  (register-test-deployment! {:id :azure-url-test})
  (let [t (openai/make-transport)
        profile (provider/get-provider :azure-url-test)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub-token")]
                (transport/build-request
                 t profile
                 {:request/model "ignored-by-azure"
                  :request/messages [{:message/role :user
                                      :message/content "Hi"}]}))]
    (is (= (str "https://test-resource.openai.azure.com"
                "/openai/deployments/gpt-4o-prod/chat/completions"
                "?api-version=2024-08-01-preview")
           (:url built)))))

(deftest test-azure-api-key-header-auth
  (register-test-deployment! {:id :azure-apikey-auth})
  (let [t (openai/make-transport)
        profile (provider/get-provider :azure-apikey-auth)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "abc123")]
                (transport/build-request
                 t profile
                 {:request/model "ignored"
                  :request/messages [{:message/role :user :message/content "Hi"}]}))]
    (testing "Azure uses :api-key header, not Authorization: Bearer"
      (is (= "abc123" (get-in built [:headers "api-key"])))
      (is (nil? (get-in built [:headers "Authorization"]))))))

(deftest test-azure-aad-bearer-auth
  (register-test-deployment! {:id :azure-aad-auth
                              :auth-strategy :bearer
                              :env-var-names ["AZURE_AD_TOKEN"]})
  (let [t (openai/make-transport)
        profile (provider/get-provider :azure-aad-auth)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "aad-bearer-token")]
                (transport/build-request
                 t profile
                 {:request/model "ignored"
                  :request/messages [{:message/role :user :message/content "Hi"}]}))]
    (testing "AAD path uses Authorization: Bearer"
      (is (= "Bearer aad-bearer-token" (get-in built [:headers "Authorization"])))
      (is (nil? (get-in built [:headers "api-key"]))))))

;; ---------------------------------------------------------------------------
;; Body shape matches OpenAI
;; ---------------------------------------------------------------------------

(deftest test-azure-body-shape-matches-openai
  (register-test-deployment! {:id :azure-body-test})
  (let [t (openai/make-transport)
        profile (provider/get-provider :azure-body-test)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (transport/build-request
                 t profile
                 {:request/model "doesnt-matter-azure-uses-deployment"
                  :request/messages [{:message/role :system :message/content "Sys"}
                                     {:message/role :user :message/content "Hi"}]
                  :request/temperature 0.7
                  :request/max-tokens 200}))]
    (is (= 2 (count (get-in built [:body :messages]))))
    (is (= 0.7 (get-in built [:body :temperature])))
    (is (= 200 (get-in built [:body :max_tokens])))))

;; ---------------------------------------------------------------------------
;; Multiple deployments coexist
;; ---------------------------------------------------------------------------

(deftest test-multiple-azure-deployments-coexist
  (register-test-deployment! {:id :azure-gpt4o
                              :deployment "gpt-4o"})
  (register-test-deployment! {:id :azure-gpt5
                              :endpoint "https://other.openai.azure.com"
                              :deployment "gpt-5"
                              :api-version "2025-01-01-preview"})
  (let [p1 (provider/get-provider :azure-gpt4o)
        p2 (provider/get-provider :azure-gpt5)]
    (is (= "gpt-4o" (:azure/deployment p1)))
    (is (= "gpt-5" (:azure/deployment p2)))
    (is (= "https://other.openai.azure.com" (:profile/base-url p2)))
    (is (not= (:profile/base-url p1) (:profile/base-url p2)))))

;; ---------------------------------------------------------------------------
;; URL-builder hook also lets non-Azure profiles plug in.
;; ---------------------------------------------------------------------------

(deftest test-url-builder-hook-honoured
  (testing "any profile can supply a :profile/url-builder fn"
    (provider/register-provider
     {:profile/id :custom-router
      :profile/protocol-family :openai-chat
      :profile/base-url "https://router.example.com"
      :profile/auth-strategy :bearer
      :profile/env-var-names ["ROUTER_KEY"]
      :profile/capabilities #{:chat}
      :profile/supports-model-listing false
      :profile/transport-constructor openai/make-transport
      :profile/url-builder
      (fn [profile request path]
        (str (:profile/base-url profile)
             "/proxy/" (:request/model request) path))})
    (let [t (openai/make-transport)
          profile (provider/get-provider :custom-router)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "k")]
                  (transport/build-request
                   t profile
                   {:request/model "llama-3.1"
                    :request/messages [{:message/role :user
                                        :message/content "Hi"}]}))]
      (is (= "https://router.example.com/proxy/llama-3.1/chat/completions"
             (:url built))))))
