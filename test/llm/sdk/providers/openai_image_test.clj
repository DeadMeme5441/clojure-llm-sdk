(ns llm.sdk.providers.openai-image-test
  "Adapter-level coverage for the OpenAI image generation transport (T2-10)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.image :as it]
            [llm.sdk.providers.openai-image :as oai-img]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(deftest test-build-request-defaults-and-minimal
  (let [t (oai-img/make-transport)
        profile (provider/get-provider :openai)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (it/build-image-request
                 t profile {:image/prompt "a cat"}))]
    (is (= "https://api.openai.com/v1/images/generations" (:url built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (= "dall-e-3" (get-in built [:body :model])))
    (is (= "a cat" (get-in built [:body :prompt])))))

(deftest test-build-request-all-options
  (let [t (oai-img/make-transport)
        profile (provider/get-provider :openai)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (it/build-image-request
                 t profile
                 {:image/model "gpt-image-1"
                  :image/prompt "a cat"
                  :image/n 2
                  :image/size "1024x1024"
                  :image/quality :high
                  :image/style :vivid
                  :image/response-format :b64_json
                  :image/user "u-1"}))
        body (:body built)]
    (is (= "gpt-image-1" (:model body)))
    (is (= 2 (:n body)))
    (is (= "1024x1024" (:size body)))
    (is (= "high" (:quality body)))
    (is (= "vivid" (:style body)))
    (is (= "b64_json" (:response_format body)))
    (is (= "u-1" (:user body)))))

(deftest test-build-request-extra-body-merges
  (let [t (oai-img/make-transport)
        profile (provider/get-provider :openai)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (it/build-image-request
                 t profile
                 {:image/prompt "a cat"
                  :image/provider-options
                  {:extra_body {:background "transparent"}}}))]
    (is (= "transparent" (get-in built [:body :background])))))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (oai-img/make-transport)
        profile (provider/get-provider :openai)
        raw (load-fixture "fixtures/openai_image_response.json")
        resp (it/parse-image-response t profile raw)
        img (first (:image/images resp))]
    (is (= :openai (:image/provider resp)))
    (is (= 1736500000 (:image/created resp)))
    (is (= 1 (count (:image/images resp))))
    (is (re-find #"^https://" (:image/url img)))
    (is (string? (:image/revised-prompt img)))))

(deftest test-parse-response-b64-only
  (testing "b64_json shape with no url field"
    (let [t (oai-img/make-transport)
          profile (provider/get-provider :openai)
          raw {:created 0
               :data [{:b64_json "iVBORw0KGgoAAAANSUhEUgAA..."}]}
          resp (it/parse-image-response t profile raw)
          img (first (:image/images resp))]
      (is (string? (:image/b64 img)))
      (is (nil? (:image/url img))))))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (oai-img/make-transport)
        profile (provider/get-provider :openai)
        err (it/parse-image-error t profile 401 {:error {:message "Bad key"}})]
    (is (= :auth (:error/reason err)))))
