(ns llm.sdk.live-rerank-test
  "Live smokes for the T2-16 rerank adapters (Cohere/Voyage/Jina).
   All env-gated; smokes use 3-document inputs to stay cheap.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-rerank-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(defn- smoke-rerank [provider-id model]
  (let [resp (sdk/rerank
              provider-id
              {:rerank/model model
               :rerank/query "what is clojure?"
               :rerank/documents
               ["Python is a general-purpose programming language."
                "JavaScript runs in browsers."
                "Clojure is a Lisp dialect for the JVM."]
               :rerank/top-n 3
               :rerank/return-documents true})]
    (is (= provider-id (:rerank/provider resp)))
    (is (= 3 (count (:rerank/results resp))))
    (is (every? #(>= (:rerank/score %) 0) (:rerank/results resp)))
    (is (schema/validate-rerank-response resp))))

(deftest ^:live live-cohere-rerank
  (when (has-creds? "COHERE_API_KEY")
    (testing "Cohere rerank-english-v3.0 live"
      (smoke-rerank :cohere "rerank-english-v3.0"))))

(deftest ^:live live-voyage-rerank
  (when (has-creds? "VOYAGE_API_KEY")
    (testing "Voyage rerank-2 live"
      (smoke-rerank :voyage "rerank-2"))))

(deftest ^:live live-jina-rerank
  (when (has-creds? "JINA_API_KEY")
    (testing "Jina jina-reranker-v2-base-multilingual live"
      (smoke-rerank :jina "jina-reranker-v2-base-multilingual"))))
