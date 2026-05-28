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
                       {:index 1 :relevanceScore 0.41}]
             :usage {:inputTokens 20 :outputTokens 4}}
        parsed (rt/parse-rerank-response t profile raw)]
    (is (= :bedrock (:rerank/provider parsed)))
    (is (= [{:rerank/index 0 :rerank/score 0.73}
            {:rerank/index 1 :rerank/score 0.41}]
           (:rerank/results parsed)))
    (is (= 20 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 4 (get-in parsed [:response/usage :usage/output-tokens])))
    (is (= 24 (get-in parsed [:response/usage :usage/total-tokens])))))

(deftest test-bedrock-profile-has-rerank-transport
  (let [profile (provider/get-provider :bedrock)]
    (is (fn? (:profile/rerank-transport-constructor profile)))
    (is (contains? (:profile/capabilities profile) :rerank))))
