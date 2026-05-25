(ns llm.sdk.moderate-test
  "Driver + schema coverage for sdk/moderate. Adapter-specific
   request/response shape lives in
   providers/openai-moderation-test."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]))

;; ---------------------------------------------------------------------------
;; Schema round-trips
;; ---------------------------------------------------------------------------

(deftest test-moderation-request-schema
  (testing "minimal request validates"
    (is (schema/validate-moderation-request
         {:moderation/inputs ["hello"]})))
  (testing "multi-modal input validates"
    (is (schema/validate-moderation-request
         {:moderation/model "omni-moderation-latest"
          :moderation/inputs [{:type :text :text "hello"}
                              {:type :image_url
                               :image_url "https://example.com/a.png"}]})))
  (testing "non-vector inputs fails"
    (is (not (schema/validate-moderation-request
              {:moderation/inputs "hi"})))))

(deftest test-moderation-response-schema
  (let [resp {:moderation/provider :openai
              :moderation/model "omni-moderation-latest"
              :moderation/results
              [{:moderation/flagged? true
                :moderation/categories {:violence true :sexual false}
                :moderation/scores {:violence 0.87 :sexual 0.0001}}]}]
    (is (schema/validate-moderation-response resp))))

;; ---------------------------------------------------------------------------
;; Driver error paths
;; ---------------------------------------------------------------------------

(deftest test-moderate-unknown-provider
  (is (thrown-with-msg? Exception #"Unknown provider"
        (sdk/moderate :no-such-provider
                      {:moderation/inputs ["hi"]}))))

(deftest test-moderate-provider-without-moderation-support
  (testing "non-moderation provider throws a clear error"
    (is (thrown-with-msg? Exception #"Moderation not supported"
          (sdk/moderate :anthropic
                        {:moderation/inputs ["hi"]})))))

(deftest test-moderate-driver-4xx
  (with-redefs [http/request
                (fn [_]
                  {:status 400
                   :body {:error {:message "Bad request"
                                  :type "invalid_request_error"}}})]
    (let [ex (try (sdk/moderate :openai {:moderation/inputs ["hi"]})
                  nil
                  (catch Exception e e))
          data (ex-data ex)]
      (is (some? ex))
      (is (= :openai (:provider data)))
      (is (= :invalid-request (get-in data [:error :error/reason]))))))

(deftest test-moderate-driver-happy-path
  (with-redefs [http/request
                (fn [_]
                  {:status 200
                   :body {:id "modr-1"
                          :model "omni-moderation-latest"
                          :results [{:flagged false
                                     :categories {:violence false}
                                     :category_scores {:violence 0.0001}}]}})]
    (let [resp (sdk/moderate :openai {:moderation/inputs ["benign input"]})]
      (is (= :openai (:moderation/provider resp)))
      (is (= 1 (count (:moderation/results resp))))
      (is (false? (:moderation/flagged?
                   (first (:moderation/results resp)))))
      (is (schema/validate-moderation-response resp)))))

;; ---------------------------------------------------------------------------
;; Public API surface
;; ---------------------------------------------------------------------------

(deftest test-public-api-exposes-moderate
  (is (some? (resolve 'llm.sdk/moderate)))
  (is (fn? @(resolve 'llm.sdk/moderate))))

(deftest test-openai-profile-advertises-moderation-capability
  (let [profile (provider/get-provider :openai)]
    (is (contains? (:profile/capabilities profile) :moderation))
    (is (fn? (:profile/moderation-transport-constructor profile)))))
