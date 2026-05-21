(ns llm.sdk.rerank-test
  "Driver + schema coverage for sdk/rerank (T2-16). Provider-specific
   request/response shape lives in
   providers/cohere-rerank-test and providers/voyage-rerank-test."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]))

;; ---------------------------------------------------------------------------
;; Schema round-trips
;; ---------------------------------------------------------------------------

(deftest test-rerank-request-schema
  (testing "minimal request validates"
    (is (schema/validate-rerank-request
         {:rerank/model "rerank-english-v3.0"
          :rerank/query "clojure"
          :rerank/documents ["one" "two" "three"]})))
  (testing "full request validates"
    (is (schema/validate-rerank-request
         {:rerank/model "rerank-2"
          :rerank/query "search query"
          :rerank/documents ["a" "b"]
          :rerank/top-n 2
          :rerank/return-documents true
          :rerank/provider-options {:extra_body {:custom-flag true}}}))))

(deftest test-rerank-response-schema
  (let [resp {:rerank/provider :cohere
              :rerank/model "rerank-english-v3.0"
              :rerank/results [{:rerank/index 2 :rerank/score 0.95
                                :rerank/document "doc 2"}
                               {:rerank/index 0 :rerank/score 0.78
                                :rerank/document "doc 0"}]}]
    (is (schema/validate-rerank-response resp))))

;; ---------------------------------------------------------------------------
;; Driver error paths
;; ---------------------------------------------------------------------------

(deftest test-rerank-unknown-provider
  (is (thrown-with-msg? Exception #"Unknown provider"
        (sdk/rerank :no-such-provider
                    {:rerank/model "m"
                     :rerank/query "q"
                     :rerank/documents ["a"]}))))

(deftest test-rerank-provider-without-rerank-support
  (is (thrown-with-msg? Exception #"Rerank not supported"
        (sdk/rerank :openai
                    {:rerank/model "x"
                     :rerank/query "q"
                     :rerank/documents ["a"]}))))

(deftest test-rerank-driver-4xx
  (with-redefs [http/request
                (fn [_]
                  {:status 400
                   :body {:message "Bad model"}})]
    (let [ex (try (sdk/rerank :cohere
                              {:rerank/model "bogus"
                               :rerank/query "q"
                               :rerank/documents ["a"]})
                  nil
                  (catch Exception e e))
          data (ex-data ex)]
      (is (some? ex))
      (is (= :cohere (:provider data))))))

(deftest test-rerank-driver-happy-path
  (with-redefs [http/request
                (fn [_]
                  {:status 200
                   :body {:id "x"
                          :results [{:index 0 :relevance_score 0.9
                                     :document {:text "a"}}]
                          :meta {:billed_units {:search_units 1}}}})]
    (let [resp (sdk/rerank :cohere
                           {:rerank/model "rerank-english-v3.0"
                            :rerank/query "q"
                            :rerank/documents ["a"]})]
      (is (= :cohere (:rerank/provider resp)))
      (is (= 1 (count (:rerank/results resp))))
      (is (= 0.9 (:rerank/score (first (:rerank/results resp))))))))

;; ---------------------------------------------------------------------------
;; Public API surface
;; ---------------------------------------------------------------------------

(deftest test-public-api-exposes-rerank
  (is (some? (resolve 'llm.sdk/rerank)))
  (is (fn? @(resolve 'llm.sdk/rerank))))
