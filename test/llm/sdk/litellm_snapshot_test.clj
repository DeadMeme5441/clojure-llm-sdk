(ns llm.sdk.litellm-snapshot-test
  "Coverage for llm.sdk.litellm-snapshot."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.litellm-snapshot :as lsnap]))

(deftest test-snapshot-loads
  (is (true? (lsnap/loaded?)))
  (is (seq (lsnap/known-providers))))

(deftest test-known-providers-includes-major-ones
  (let [kp (lsnap/known-providers)]
    (is (contains? kp :openai))
    (is (contains? kp :anthropic))
    (is (contains? kp :mistral))
    (is (contains? kp :perplexity))
    (is (contains? kp :bedrock))))

(deftest test-lookup-openai-gpt-4o
  (let [e (lsnap/lookup :openai "gpt-4o")]
    (is (some? e) "snapshot should know gpt-4o")
    (is (= :openai (:model/provider e)))
    (is (= "gpt-4o" (:model/id e)))
    (is (= :litellm-snapshot (:model/source e)))
    (is (pos? (:model/context-length e)))
    (is (pos? (get-in e [:model/cost :input-per-million])))))

(deftest test-lookup-anthropic-with-cache-pricing
  (let [e (lsnap/lookup :anthropic "claude-opus-4-7")]
    (is (some? e))
    (is (pos? (get-in e [:model/cost :input-per-million])))
    (testing "Anthropic snapshot carries cache-read pricing"
      (is (pos? (get-in e [:model/cost :cache-read-per-million]))))))

(deftest test-lookup-bedrock-namespaced-key
  (testing "Bedrock model ids keep their region/vendor prefix (e.g. anthropic.claude-...)"
    (let [models (lsnap/list-models :bedrock)
          example (some #(when (re-find #"^us\." (:model/id %)) %) models)]
      (is (some? example))
      (is (re-find #"\." (:model/id example))))))

(deftest test-lookup-unknown-returns-nil
  (is (nil? (lsnap/lookup :openai "not-a-real-model-id-here-9999")))
  (is (nil? (lsnap/lookup :no-such-provider "x"))))

(deftest test-perplexity-routing-curios-skipped
  (testing "perplexity/anthropic/X entries are skipped by the build script"
    (is (nil? (lsnap/lookup :perplexity "anthropic/claude-opus-4-7"))))
  (testing "real Perplexity wire models are kept"
    (is (some? (lsnap/lookup :perplexity "sonar-pro"))
        "perplexity/sonar-pro should be in the snapshot")))

(deftest test-list-models-cardinality
  (testing "OpenAI and Bedrock are the largest provider buckets"
    (is (>= (count (lsnap/list-models :openai)) 100))
    (is (>= (count (lsnap/list-models :bedrock)) 100))))
