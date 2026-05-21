(ns llm.sdk.providers.cohere-rerank-test
  "Adapter-level coverage for the Cohere/Jina shared rerank
   transport (T2-16)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.rerank :as rt]
            [llm.sdk.providers.cohere-rerank :as ckr]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Profile registration
;; ---------------------------------------------------------------------------

(deftest test-cohere-and-jina-carry-rerank-transport
  (doseq [pid [:cohere :jina]]
    (let [p (provider/get-provider pid)]
      (is (some? p))
      (is (fn? (:profile/rerank-transport-constructor p))
          (str pid " has a rerank transport")))))

;; ---------------------------------------------------------------------------
;; Request building (Cohere shape: top_n, return_documents)
;; ---------------------------------------------------------------------------

(deftest test-build-request-shape
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "rerank-english-v3.0"
                  :rerank/query "clojure programming"
                  :rerank/documents ["python" "javascript" "clojure"]
                  :rerank/top-n 2
                  :rerank/return-documents true}))]
    (is (= "https://api.cohere.com/v1/rerank" (:url built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (= "rerank-english-v3.0" (get-in built [:body :model])))
    (is (= "clojure programming" (get-in built [:body :query])))
    (is (= ["python" "javascript" "clojure"]
           (get-in built [:body :documents])))
    (is (= 2 (get-in built [:body :top_n])))
    (is (true? (get-in built [:body :return_documents])))))

(deftest test-build-request-extra-body-merges
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "rerank-multilingual-v3.0"
                  :rerank/query "q"
                  :rerank/documents ["a"]
                  :rerank/provider-options {:extra_body {:rank_fields ["title"]}}}))]
    (is (= ["title"] (get-in built [:body :rank_fields])))))

;; ---------------------------------------------------------------------------
;; Response parsing — fixture
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        raw (load-fixture "fixtures/cohere_rerank_response.json")
        resp (rt/parse-rerank-response t profile raw)
        results (:rerank/results resp)]
    (is (= :cohere (:rerank/provider resp)))
    (is (= 3 (count results)))
    (testing "scores preserved, document texts extracted from {:text ...} wrapper"
      (is (= 0.9523 (:rerank/score (first results))))
      (is (= "Clojure is a Lisp dialect for the JVM."
             (:rerank/document (first results))))
      (is (= 2 (:rerank/index (first results)))))
    (testing "Cohere billed_units surfaces in usage"
      (is (= 1 (get-in resp [:response/usage :usage/request-count]))))))

(deftest test-parse-response-document-as-string
  (testing "document field already as a string also works"
    (let [t (ckr/make-transport)
          profile (provider/get-provider :cohere)
          raw {:results [{:index 0 :relevance_score 0.5 :document "plain text"}]
               :meta {:billed_units {:search_units 1}}}
          resp (rt/parse-rerank-response t profile raw)]
      (is (= "plain text" (:rerank/document (first (:rerank/results resp))))))))

;; ---------------------------------------------------------------------------
;; Same transport, Jina profile — provider id is :jina in response
;; ---------------------------------------------------------------------------

(deftest test-jina-response-tags-provider-jina
  (let [t (ckr/make-transport)
        profile (provider/get-provider :jina)
        raw {:model "jina-reranker-v2-base-multilingual"
             :results [{:index 0 :relevance_score 0.95
                        :document {:text "match"}}]
             :usage {:total_tokens 25}}
        resp (rt/parse-rerank-response t profile raw)]
    (is (= :jina (:rerank/provider resp)))
    (is (= "jina-reranker-v2-base-multilingual" (:rerank/model resp)))
    (testing "Jina total_tokens surfaces in usage"
      (is (= 25 (get-in resp [:response/usage :usage/total-tokens]))))))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        err (rt/parse-rerank-error t profile 401 {:message "bad key"})]
    (is (= :auth (:error/reason err)))))
