(ns llm.sdk.live-perplexity-test
  "Live smoke for the Perplexity adapter (T2-04).

   Verifies the adapter surfaces citation parts on a real
   web-search-backed response. Sonar is the cheapest search model
   (~$1 per 1K requests as of 2026); the smoke uses 100 max-tokens
   to keep token cost negligible.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-perplexity-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(deftest ^:live live-perplexity-chat-citations
  (when (has-creds? "PERPLEXITY_API_KEY")
    (testing "Perplexity Sonar returns citation parts"
      (let [resp (sdk/complete
                  :perplexity
                  {:request/model "sonar"
                   :request/messages
                   [{:message/role :user
                     :message/content "Who created the Clojure programming language? One sentence."}]
                   :request/max-tokens 100})
            parts (:response/parts resp)
            text-parts (filter #(= :text (:part/type %)) parts)
            citation-parts (filter #(= :citation (:part/type %)) parts)]
        (is (= :perplexity (:response/provider resp)))
        (is (seq text-parts))
        (is (seq citation-parts)
            "Sonar's web search should yield at least one citation")
        (is (every? string? (map :citation/url citation-parts)))
        (is (schema/validate-response resp))))))

(deftest ^:live live-perplexity-stream-citations
  (when (has-creds? "PERPLEXITY_API_KEY")
    (testing "stream mode also surfaces citation parts in Response.parts"
      (let [events (sdk/complete
                    :perplexity
                    {:request/model "sonar"
                     :request/messages
                     [{:message/role :user
                       :message/content "What's the latest Clojure version? One short answer."}]
                     :request/max-tokens 80}
                    :stream? true)
            resp (llm.sdk.stream/events->response events :perplexity "sonar")
            citation-parts (filter #(= :citation (:part/type %)) (:response/parts resp))]
        (is (seq citation-parts)
            "stream-mode citations should land in Response.parts via the reducer")))))
