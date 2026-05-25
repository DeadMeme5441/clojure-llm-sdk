(ns llm.sdk.provider-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.provider :as provider]))

(deftest runtime-config-overrides-are-profile-local
  (let [base {:profile/id :test
              :profile/base-url "https://api.example.com/v1"
              :profile/auth-strategy :bearer
              :profile/default-headers {"X-Base" "1"}
              :profile/env-var-names ["TEST_API_KEY"]}
        configured (provider/apply-runtime-config
                    base
                    {:api-key "runtime-key"
                     :base-url "https://runtime.example/v1"
                     :headers {"X-App" "sdk"}
                     :connect-timeout-ms 123
                     :timeout-ms 456})]
    (is (= "runtime-key" (provider/resolve-auth-token configured)))
    (is (= "https://runtime.example/v1" (:profile/base-url configured)))
    (is (= {"X-Base" "1" "X-App" "sdk"}
           (:profile/default-headers configured)))
    (is (= "https://api.example.com/v1" (:profile/base-url base)))
    (is (= {:url "https://runtime.example/v1/chat/completions"
            :connect-timeout-ms 123
            :timeout-ms 456}
           (provider/apply-http-options
            configured
            {:url "https://runtime.example/v1/chat/completions"})))))
