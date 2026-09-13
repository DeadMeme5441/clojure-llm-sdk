(ns llm.sdk.live-perplexity-test
  "Env-gated live smoke for the Perplexity Agent API.

   Verifies that model-based Agent requests surface grounded web-search
   citations in full and typed-stream responses. These tests make paid network
   calls only when explicitly run with PERPLEXITY_API_KEY present.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-perplexity-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]
            [llm.sdk.stream :as stream]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(deftest ^:live live-perplexity-agent-citations
  (when (has-creds? "PERPLEXITY_API_KEY")
    (testing "Agent API model request returns web-search citations"
      (let [response
            (sdk/complete
             :perplexity
             {:request/model "perplexity/sonar"
              :request/messages
              [{:message/role :user
                :message/content
                "Who created the Clojure programming language? One sentence."}]
              :request/max-tokens 100
              :request/provider-options
              {:perplexity
               {:web-search
                {:filters {:search-domain-filter ["clojure.org"]}
                 :max-results 5}}}})
            parts (:response/parts response)
            text-parts (filter #(= :text (:part/type %)) parts)
            citation-parts (filter #(= :citation (:part/type %)) parts)]
        (is (= :perplexity (:response/provider response)))
        (is (= "perplexity/sonar" (:response/model response)))
        (is (seq text-parts))
        (is (seq citation-parts))
        (is (every? string? (map :citation/url citation-parts)))
        (is (schema/validate-response response))))))

(deftest ^:live live-perplexity-agent-stream-citations
  (when (has-creds? "PERPLEXITY_API_KEY")
    (testing "typed Agent SSE folds search results into citation parts"
      (let [events
            (sdk/complete
             :perplexity
             {:request/model "perplexity/sonar"
              :request/messages
              [{:message/role :user
                :message/content
                "What is the latest stable Clojure release? One short answer."}]
              :request/max-tokens 80}
             :stream? true)
            response (stream/events->response
                      events :perplexity "perplexity/sonar")
            citation-parts (filter #(= :citation (:part/type %))
                                   (:response/parts response))]
        (is (seq citation-parts))
        (is (= :stop (:response/finish-reason response)))
        (is (schema/validate-response response))))))
