(ns llm.sdk.providers.voyage-rerank-test
  "Adapter-level coverage for the Voyage rerank transport.
   Voyage diverges from Cohere/Jina on field names: top_k / data."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.rerank :as rt]
            [llm.sdk.providers.voyage.rerank :as vrk]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

(deftest test-voyage-carries-rerank-transport
  (let [p (provider/get-provider :voyage)]
    (is (fn? (:profile/rerank-transport-constructor p)))))

;; ---------------------------------------------------------------------------
;; Request building — top_k, not top_n
;; ---------------------------------------------------------------------------

(deftest test-build-request-uses-top_k
  (let [t (vrk/make-transport)
        profile (provider/get-provider :voyage)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "rerank-2"
                  :rerank/query "q"
                  :rerank/documents ["a" "b"]
                  :rerank/top-n 1
                  :rerank/return-documents true
                  :rerank/provider-options {:truncation false}}))]
    (is (= "https://api.voyageai.com/v1/rerank" (:url built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (= 1 (get-in built [:body :top_k])))
    (is (nil? (get-in built [:body :top_n]))
        "Voyage uses :top_k, not :top_n")
    (is (true? (get-in built [:body :return_documents])))
    (is (false? (get-in built [:body :truncation])))))

(deftest test-voyage-rejects-structured-documents
  (let [t (vrk/make-transport)
        profile (provider/get-provider :voyage)
        error (try
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "rerank-2.5"
                  :rerank/query "q"
                  :rerank/documents ["plain" {:text "structured"}]})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :request/invalid-rerank-document
           (:error/type (ex-data error))))
    (is (= :voyage (:provider (ex-data error))))
    (is (= 1 (:document/index (ex-data error))))))

;; ---------------------------------------------------------------------------
;; Response parsing — data array, document as plain string
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (vrk/make-transport)
        profile (provider/get-provider :voyage)
        raw (load-fixture "fixtures/voyage_rerank_response.json")
        resp (rt/parse-rerank-response t profile raw)
        results (:rerank/results resp)]
    (is (= :voyage (:rerank/provider resp)))
    (is (= "rerank-2" (:rerank/model resp)))
    (is (= 2 (count results)))
    (is (= 0.91 (:rerank/score (first results))))
    (is (= "Document B mentions the target concept directly."
           (:rerank/document (first results)))
        "Voyage returns :document as a plain string")
    (testing "Voyage usage surfaces as canonical input-tokens"
      (is (= 38 (get-in resp [:response/usage :usage/input-tokens])))
      (is (= 38 (get-in resp [:response/usage :usage/total-tokens]))))))

(deftest test-voyage-rerank-preserves-explicit-zero-usage
  (let [response
        (rt/parse-rerank-response
         (vrk/make-transport)
         (provider/get-provider :voyage)
         {:data [] :usage {:total_tokens 0}})]
    (is (= 0 (get-in response [:response/usage :usage/input-tokens])))
    (is (= 0 (get-in response [:response/usage :usage/total-tokens])))))

(deftest test-missing-native-score-is-rejected
  (let [t (vrk/make-transport)
        profile (provider/get-provider :voyage)
        error (try
                (rt/parse-rerank-response
                 t profile
                 {:data [{:index 0}]
                  :usage {:total_tokens 4}})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :response/missing-rerank-score
           (:error/type (ex-data error))))))

(deftest test-parse-error-401
  (let [t (vrk/make-transport)
        profile (provider/get-provider :voyage)
        err (rt/parse-rerank-error t profile 401 {:message "bad"})]
    (is (= :auth (:error/reason err)))))
