(ns llm.sdk.live-moderation-test
  "Live smoke for OpenAI Moderations. The endpoint is free —
   OpenAI does not bill for moderation calls.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-moderation-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(deftest ^:live live-openai-moderation-benign
  (when (has-creds? "OPENAI_API_KEY")
    (testing "benign input is not flagged"
      (let [resp (sdk/moderate
                  :openai
                  {:moderation/inputs ["Reply with the word: ok"]})
            result (first (:moderation/results resp))]
        (is (= :openai (:moderation/provider resp)))
        (is (false? (:moderation/flagged? result)))
        (is (schema/validate-moderation-response resp))))))

(deftest ^:live live-openai-moderation-multi-input
  (when (has-creds? "OPENAI_API_KEY")
    (testing "multiple inputs yield multiple results"
      (let [resp (sdk/moderate
                  :openai
                  {:moderation/inputs ["benign text" "another benign text"]})]
        (is (= 2 (count (:moderation/results resp))))))))
