(ns llm.sdk.live-embed-test
  "Live smoke for the OpenAI embed adapter.

   text-embedding-3-small bills at $0.02 / 1M tokens; a single 3-token
   smoke is ~$0.00000006 per run - fractions of a cent. Still gated on
   OPENAI_API_KEY for the offline default.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-embed-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(deftest ^:live live-openai-embed
  (when (has-creds? "OPENAI_API_KEY")
    (testing "OpenAI text-embedding-3-small live"
      (let [resp (sdk/embed
                  :openai
                  {:embed/model "text-embedding-3-small"
                   :embed/inputs ["clojure-llm-sdk live embed smoke"]})]
        (is (= :openai (:embed/provider resp)))
        (is (= 1 (count (:embed/vectors resp))))
        (is (pos? (:embed/dimensions resp)))
        (is (every? number? (first (:embed/vectors resp))))
        (is (pos? (get-in resp [:response/usage :usage/input-tokens])))
        (is (schema/validate-embed-response resp))))))

(deftest ^:live live-openai-embed-multi-input
  (when (has-creds? "OPENAI_API_KEY")
    (testing "multi-input embed returns one vector per input"
      (let [resp (sdk/embed
                  :openai
                  {:embed/model "text-embedding-3-small"
                   :embed/inputs ["alpha" "beta" "gamma"]})]
        (is (= 3 (count (:embed/vectors resp))))))))

(deftest ^:live live-openai-embed-with-dimensions
  (when (has-creds? "OPENAI_API_KEY")
    (testing "dimensions override truncates the returned vector"
      (let [resp (sdk/embed
                  :openai
                  {:embed/model "text-embedding-3-small"
                   :embed/inputs ["dimensions test"]
                   :embed/dimensions 128})]
        (is (= 128 (:embed/dimensions resp)))
        (is (= 128 (count (first (:embed/vectors resp)))))))))

;; ---------------------------------------------------------------------------
;; Cohere/Voyage/Mistral/Together/Jina
;; ---------------------------------------------------------------------------

(defn- smoke-embed
  [provider-id model & {:keys [extra-opts]}]
  (let [resp (sdk/embed
              provider-id
              (merge {:embed/model model
                      :embed/inputs ["clojure-llm-sdk live embed smoke"]}
                     extra-opts))]
    (is (= provider-id (:embed/provider resp)))
    (is (= 1 (count (:embed/vectors resp))))
    (is (pos? (:embed/dimensions resp)))
    (is (every? number? (first (:embed/vectors resp))))
    (is (schema/validate-embed-response resp))))

(deftest ^:live live-cohere-embed
  (when (has-creds? "COHERE_API_KEY")
    (testing "Cohere embed-english-v3.0 live"
      (smoke-embed :cohere "embed-english-v3.0"
                   :extra-opts {:embed/provider-options
                                {:input-type "search_document"}}))))

(deftest ^:live live-voyage-embed
  (when (has-creds? "VOYAGE_API_KEY")
    (testing "Voyage voyage-3 live"
      (smoke-embed :voyage "voyage-3"))))

(deftest ^:live live-mistral-embed
  (when (has-creds? "MISTRAL_API_KEY")
    (testing "Mistral mistral-embed live"
      (smoke-embed :mistral "mistral-embed"))))

(deftest ^:live live-together-embed
  (when (has-creds? "TOGETHER_API_KEY")
    (testing "Together togethercomputer/m2-bert-80M-8k-retrieval live"
      (smoke-embed :together "togethercomputer/m2-bert-80M-8k-retrieval"))))

(deftest ^:live live-jina-embed
  (when (has-creds? "JINA_API_KEY")
    (testing "Jina jina-embeddings-v3 live"
      (smoke-embed :jina "jina-embeddings-v3"))))

(deftest ^:live live-openrouter-embed
  (when (has-creds? "OPENROUTER_API_KEY")
    (testing "OpenRouter google/gemini-embedding-001 live"
      (smoke-embed :openrouter "google/gemini-embedding-001"))))
