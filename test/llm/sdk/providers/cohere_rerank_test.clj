(ns llm.sdk.providers.cohere-rerank-test
  "Adapter-level coverage for the Cohere/Jina shared rerank
   transport."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.rerank :as rt]
            [llm.sdk.providers.cohere.rerank :as ckr]))

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
    (is (= "https://api.cohere.com/v2/rerank" (:url built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (= "rerank-english-v3.0" (get-in built [:body :model])))
    (is (= "clojure programming" (get-in built [:body :query])))
    (is (= ["python" "javascript" "clojure"]
           (get-in built [:body :documents])))
    (is (= 2 (get-in built [:body :top_n])))
    (is (nil? (get-in built [:body :return_documents]))
        "Cohere v2 does not accept return_documents")))

(deftest test-extra-body-rejects-rerank-canonical-fields
  (let [transport (ckr/make-transport)
        profile (provider/get-provider :cohere)
        base-request {:rerank/model "canonical-model"
                      :rerank/query "canonical-query"
                      :rerank/documents ["canonical-document"]}]
    (doseq [[provided-key expected-field provided-value]
            [["model" :model "other-model"]
             [:query :query "other-query"]
             ["documents" :documents ["other-document"]]]]
      (let [error
            (try
              (with-redefs [provider/resolve-auth-token (constantly "stub")]
                (rt/build-rerank-request
                 transport profile
                 (assoc base-request
                        :rerank/provider-options
                        {:extra_body {provided-key provided-value}})))
              nil
              (catch clojure.lang.ExceptionInfo e e))]
        (is (= :request/protected-extra-body-override
               (:error/type (ex-data error))))
        (is (= expected-field (:field (ex-data error))))
        (is (= :cohere (:provider (ex-data error))))))))

(deftest test-cohere-rejects-structured-documents
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        error (try
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "rerank-v4.0-pro"
                  :rerank/query "q"
                  :rerank/documents ["plain" {:text "structured"}]})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :request/invalid-rerank-document
           (:error/type (ex-data error))))
    (is (= :cohere (:provider (ex-data error))))
    (is (= 1 (:document/index (ex-data error))))))

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

(deftest test-build-request-current-provider-options
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        built (with-redefs [provider/resolve-auth-token (constantly "stub")]
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "rerank-v4.0-pro"
                  :rerank/query "q"
                  :rerank/documents ["a"]
                  :rerank/provider-options {:max-tokens-per-doc 2048
                                            :priority 3}}))]
    (is (= 2048 (get-in built [:body :max_tokens_per_doc])))
    (is (= 3 (get-in built [:body :priority])))))

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
    (testing "scores and returned documents are preserved exactly"
      (is (= 0.9523 (:rerank/score (first results))))
      (is (= "Clojure is a Lisp dialect for the JVM."
             (:rerank/document (first results))))
      (is (= 2 (:rerank/index (first results)))))
    (testing "search-unit billing is exposed without invented token counts"
      (is (= 1 (get-in resp [:response/usage :usage/search-units])))
      (is (not (contains? (:response/usage resp) :usage/input-tokens)))
      (is (not (contains? (:response/usage resp) :usage/output-tokens)))
      (is (not (contains? (:response/usage resp) :usage/total-tokens)))
      (is (= (:meta raw)
             (get-in resp [:response/usage :usage/provider-raw]))))))

(deftest test-parse-cohere-v2-usage
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        resp (rt/parse-rerank-response
              t profile
              {:id "rerank-1"
               :results [{:index 0 :relevance_score 0.75}]
               :meta {:tokens {:input_tokens 14 :output_tokens 0}
                      :billed_units {:search_units 2}}})]
    (is (= "rerank-1" (:rerank/id resp)))
    (is (= 14 (get-in resp [:response/usage :usage/input-tokens])))
    (is (= 1 (get-in resp [:response/usage :usage/request-count])))))

(deftest test-missing-native-score-is-rejected
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        error (try
                (rt/parse-rerank-response
                 t profile
                 {:results [{:index 0}]
                  :meta {:billed_units {:search_units 1}}})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :response/missing-rerank-score
           (:error/type (ex-data error))))))

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
             :results [{:index 0
                        :relevance_score 0.95
                        :document {:text "match"
                                   :metadata {:source "fixture"}}
                        :embedding [0.25 -0.5 0.75]}
                       {:index 1
                        :relevance_score 0.5
                        :document {:image "https://example.test/image.png"}}]
             :usage {:total_tokens 25}}
        resp (rt/parse-rerank-response t profile raw)
        results (:rerank/results resp)]
    (is (= :jina (:rerank/provider resp)))
    (is (= "jina-reranker-v2-base-multilingual" (:rerank/model resp)))
    (testing "structured Jina documents and numeric embeddings stay canonical"
      (is (= {:text "match" :metadata {:source "fixture"}}
             (:rerank/document (first results))))
      (is (= [0.25 -0.5 0.75]
             (:rerank/embedding (first results))))
      (is (= {:image "https://example.test/image.png"}
             (:rerank/document (second results)))))
    (testing "Jina total_tokens surfaces in usage"
      (is (= 25 (get-in resp [:response/usage :usage/total-tokens]))))))

(deftest test-jina-rerank-preserves-explicit-zero-usage
  (let [response
        (rt/parse-rerank-response
         (ckr/make-transport)
         (provider/get-provider :jina)
         {:results [] :usage {:total_tokens 0}})]
    (is (= 0 (get-in response [:response/usage :usage/input-tokens])))
    (is (= 0 (get-in response [:response/usage :usage/total-tokens])))))

(deftest test-jina-request-current-options-and-structured-documents
  (let [t (ckr/make-transport)
        profile (provider/get-provider :jina)
        documents ["plain"
                   {:text "match" :metadata {:source "fixture"}}
                   {:image "https://example.test/image.png"}]
        built (with-redefs [provider/resolve-auth-token (constantly "stub")]
                (rt/build-rerank-request
                 t profile
                 {:rerank/model "jina-reranker-v3"
                  :rerank/query "q"
                  :rerank/documents documents
                  :rerank/return-documents true
                  :rerank/provider-options {:truncation true
                                            :max-doc-length 4096
                                            :return-embeddings true}}))]
    (is (= "https://api.jina.ai/v1/rerank" (:url built)))
    (is (= documents (get-in built [:body :documents])))
    (is (true? (get-in built [:body :return_documents])))
    (is (not (contains? (:body built) :truncation))
        "Jina rerank does not document a truncation request field")
    (is (= 4096 (get-in built [:body :max_doc_length])))
    (is (true? (get-in built [:body :return_embeddings])))))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (ckr/make-transport)
        profile (provider/get-provider :cohere)
        err (rt/parse-rerank-error t profile 401 {:message "bad key"})]
    (is (= :auth (:error/reason err)))))
