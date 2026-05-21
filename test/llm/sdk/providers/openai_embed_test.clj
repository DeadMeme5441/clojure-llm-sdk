(ns llm.sdk.providers.openai-embed-test
  "Adapter-level coverage for the OpenAI embed transport (T2-01).

   Verifies request body shape (single vs multi input), URL, auth
   header, response parsing against a golden fixture, and the
   dimensions/usage round-trip."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.providers.openai-embed :as openai-embed]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(deftest test-build-request-single-input-unwraps
  (testing "a one-element :embed/inputs vector serializes as a string"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-small"
                    :embed/inputs ["hello world"]}))]
      (is (= "https://api.openai.com/v1/embeddings" (:url built)))
      (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
      (is (= "text-embedding-3-small" (get-in built [:body :model])))
      (is (= "hello world" (get-in built [:body :input]))))))

(deftest test-build-request-multi-input-stays-vector
  (testing "multi-input request serializes as a vector"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-small"
                    :embed/inputs ["a" "b" "c"]}))]
      (is (= ["a" "b" "c"] (get-in built [:body :input]))))))

(deftest test-build-request-optional-fields
  (testing "dimensions / encoding-format / user surface on the body"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-large"
                    :embed/inputs ["hi"]
                    :embed/dimensions 256
                    :embed/encoding-format :base64
                    :embed/user "u-1"}))
          body (:body built)]
      (is (= 256 (:dimensions body)))
      (is (= "base64" (:encoding_format body)))
      (is (= "u-1" (:user body))))))

(deftest test-build-request-extra-body-merges
  (testing ":embed/provider-options :extra_body keys land on the body"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-small"
                    :embed/inputs ["a"]
                    :embed/provider-options {:extra_body {:custom-flag true}}}))]
      (is (true? (get-in built [:body :custom-flag]))))))

;; ---------------------------------------------------------------------------
;; Response parsing — golden fixture
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (openai-embed/make-transport)
        profile (provider/get-provider :openai)
        raw (load-fixture "fixtures/openai_embed_response.json")
        resp (et/parse-embed-response t profile raw)]
    (is (= :openai (:embed/provider resp)))
    (is (= "text-embedding-3-small" (:embed/model resp)))
    (is (= 2 (count (:embed/vectors resp))))
    (is (every? vector? (:embed/vectors resp)))
    (is (= 8 (count (first (:embed/vectors resp)))))
    (is (= 8 (:embed/dimensions resp)))
    (is (= 7 (get-in resp [:response/usage :usage/input-tokens])))
    (is (= 7 (get-in resp [:response/usage :usage/total-tokens])))
    (is (= 0 (get-in resp [:response/usage :usage/output-tokens])))
    ;; raw preserved for callers that need provider-specific fields.
    (is (= raw (:embed/raw resp)))))

(deftest test-parse-response-sorts-by-index
  (testing "out-of-order :data is sorted before extracting vectors"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          raw {:object "list"
               :model "text-embedding-3-small"
               :data [{:index 2 :embedding [0.3 0.4]}
                      {:index 0 :embedding [0.1 0.2]}
                      {:index 1 :embedding [0.5 0.6]}]
               :usage {:prompt_tokens 10 :total_tokens 10}}
          resp (et/parse-embed-response t profile raw)]
      (is (= [[0.1 0.2] [0.5 0.6] [0.3 0.4]]
             (:embed/vectors resp))))))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (openai-embed/make-transport)
        profile (provider/get-provider :openai)
        err (et/parse-embed-error
             t profile 401
             {:error {:message "Invalid API key" :type "invalid_request_error"}})]
    (is (= :auth (:error/reason err)))
    (is (false? (:error/retryable err)))))

(deftest test-parse-error-429
  (let [t (openai-embed/make-transport)
        profile (provider/get-provider :openai)
        err (et/parse-embed-error
             t profile 429
             {:error {:message "Rate limit reached"}})]
    (is (= :rate-limit (:error/reason err)))
    (is (true? (:error/retryable err)))))
