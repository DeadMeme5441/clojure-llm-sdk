(ns llm.sdk.providers.bedrock-rerank-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.bedrock.rerank :as bedrock-rerank]
            [llm.sdk.transport.rerank :as rt]))

(deftest test-build-rerank-request-shape
  (let [t (bedrock-rerank/make-transport)
        profile (provider/get-provider :bedrock)
        built (rt/build-rerank-request
               t profile
               {:rerank/model "arn:aws:bedrock:us-east-1::foundation-model/cohere.rerank-v3-5:0"
                :rerank/query "best clojure sdk"
                :rerank/documents ["doc one" "doc two"]
                :rerank/top-n 1})
        body (:body built)]
    (is (= :post (:method built)))
    (is (.contains ^String (:url built) "bedrock-agent-runtime."))
    (is (.endsWith ^String (:url built) "/rerank"))
    (is (= "bedrock" (get built :llm.sdk.providers.bedrock/aws-service)))
    (is (= [{:type "TEXT"
             :textQuery {:text "best clojure sdk"}}]
           (:queries body)))
    (is (= 1 (get-in body [:rerankingConfiguration
                           :bedrockRerankingConfiguration
                           :numberOfResults])))
    (is (= "doc one"
           (get-in body [:sources 0 :inlineDocumentSource :textDocument :text])))))

(deftest test-build-rerank-request-default-top-n
  (testing "numberOfResults defaults to document count"
    (let [t (bedrock-rerank/make-transport)
          profile (provider/get-provider :bedrock)
          built (rt/build-rerank-request
                 t profile
                 {:rerank/model "model-arn"
                  :rerank/query "q"
                  :rerank/documents ["a" "b" "c"]})]
      (is (= 3 (get-in built [:body
                              :rerankingConfiguration
                              :bedrockRerankingConfiguration
                              :numberOfResults]))))))

(deftest test-parse-rerank-response-shape
  (let [t (bedrock-rerank/make-transport)
        profile (provider/get-provider :bedrock)
        raw {:results [{:index 0 :relevanceScore 0.73}
                       {:index 1 :relevanceScore 0.41}]}
        parsed (rt/parse-rerank-response t profile raw)]
    (is (= :bedrock (:rerank/provider parsed)))
    (is (= [{:rerank/index 0 :rerank/score 0.73}
            {:rerank/index 1 :rerank/score 0.41}]
           (:rerank/results parsed)))))

(deftest test-bedrock-profile-has-rerank-transport
  (let [profile (provider/get-provider :bedrock)]
    (is (fn? (:profile/rerank-transport-constructor profile)))
    (is (contains? (:profile/capabilities profile) :rerank))))

(deftest test-build-rerank-provider-fields
  (let [t (bedrock-rerank/make-transport)
        profile (provider/get-provider :bedrock)
        built (rt/build-rerank-request
               t profile
               {:rerank/model "model-arn"
                :rerank/query "query"
                :rerank/documents ["one"]
                :rerank/provider-options
                {:bedrock
                 {:next-token "page-2"
                  :additional-model-request-fields
                  {:max_chunks_per_doc 3}}}})]
    (is (= "page-2" (get-in built [:body :nextToken])))
    (is (= {:max_chunks_per_doc 3}
           (get-in built
                   [:body :rerankingConfiguration
                    :bedrockRerankingConfiguration
                    :modelConfiguration
                    :additionalModelRequestFields])))))

(deftest test-parse-rerank-returned-document
  (let [t (bedrock-rerank/make-transport)
        profile (provider/get-provider :bedrock)
        parsed (rt/parse-rerank-response
                t profile
                {:nextToken "page-2"
                 :results
                 [{:index 0
                   :relevanceScore 0.9
                   :document {:type "TEXT"
                              :textDocument {:text "returned document"}}}]})]
    (is (= "returned document"
           (get-in parsed [:rerank/results 0 :rerank/document])))
    (is (= "page-2" (get-in parsed [:rerank/raw :nextToken])))))
