(ns llm.sdk.providers.openai-moderation-test
  "Adapter-level coverage for the OpenAI Moderations transport (T2-13)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.moderate :as mt]
            [llm.sdk.providers.openai-moderation :as oai-mod]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(deftest test-build-request-default-model-and-single-input
  (testing "missing :moderation/model defaults to omni-moderation-latest; single string collapses to a string"
    (let [t (oai-mod/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (mt/build-moderation-request
                   t profile {:moderation/inputs ["hi"]}))]
      (is (= "https://api.openai.com/v1/moderations" (:url built)))
      (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
      (is (= "omni-moderation-latest" (get-in built [:body :model])))
      (is (= "hi" (get-in built [:body :input]))))))

(deftest test-build-request-multi-input-stays-vector
  (let [t (oai-mod/make-transport)
        profile (provider/get-provider :openai)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (mt/build-moderation-request
                 t profile {:moderation/model "text-moderation-stable"
                            :moderation/inputs ["a" "b" "c"]}))]
    (is (= "text-moderation-stable" (get-in built [:body :model])))
    (is (= ["a" "b" "c"] (get-in built [:body :input])))))

(deftest test-build-request-multi-modal-input
  (let [t (oai-mod/make-transport)
        profile (provider/get-provider :openai)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (mt/build-moderation-request
                 t profile
                 {:moderation/inputs
                  [{:type :text :text "look at this"}
                   {:type :image_url
                    :image_url "https://example.com/img.png"}]}))]
    (is (= [{:type "text" :text "look at this"}
            {:type "image_url"
             :image_url {:url "https://example.com/img.png"}}]
           (get-in built [:body :input])))))

;; ---------------------------------------------------------------------------
;; Response parsing — golden fixture
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (oai-mod/make-transport)
        profile (provider/get-provider :openai)
        raw (load-fixture "fixtures/openai_moderation_response.json")
        resp (mt/parse-moderation-response t profile raw)
        result (first (:moderation/results resp))]
    (is (= :openai (:moderation/provider resp)))
    (is (= "omni-moderation-latest" (:moderation/model resp)))
    (is (= 1 (count (:moderation/results resp))))
    (is (true? (:moderation/flagged? result)))
    (testing "categories keywordized; slash-key normalised to dash"
      (is (true? (get-in result [:moderation/categories :violence])))
      (is (false? (get-in result [:moderation/categories :sexual])))
      ;; "violence/graphic" → :violence-graphic
      (is (false? (get-in result [:moderation/categories :violence-graphic]))))
    (testing "scores keep float precision"
      (is (= 0.87654 (get-in result [:moderation/scores :violence]))))
    (testing "applied input types come through as keyword vectors"
      (is (= [:text :image]
             (get-in result [:moderation/categories-applied :violence]))))
    (is (= raw (:moderation/raw resp)))))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (oai-mod/make-transport)
        profile (provider/get-provider :openai)
        err (mt/parse-moderation-error
             t profile 401 {:error {:message "Bad key"}})]
    (is (= :auth (:error/reason err)))))
