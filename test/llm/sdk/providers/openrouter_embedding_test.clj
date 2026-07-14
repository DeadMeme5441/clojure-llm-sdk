(ns llm.sdk.providers.openrouter-embedding-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openrouter.embeddings :as embeddings]
            [llm.sdk.transport.embed :as et]))

(deftest test-openrouter-embedding-request-wire-shape
  (let [profile (provider/get-provider :openrouter)
        transport (embeddings/make-transport)
        built (with-redefs [provider/resolve-auth-token (constantly "stub")]
                (et/build-embed-request
                 transport profile
                 {:embed/model "openrouter/google/gemini-embedding-001"
                  :embed/inputs ["query phrase"]
                  :embed/encoding-format :float}))]
    (is (= "https://openrouter.ai/api/v1/embeddings" (:url built)))
    (is (= {:model "google/gemini-embedding-001"
            :input "query phrase"
            :encoding_format "float"}
           (:body built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (string? (get-in built [:headers "HTTP-Referer"])))
    (is (string? (get-in built [:headers "X-OpenRouter-Title"])))
    (is (nil? (get-in built [:headers "X-Title"])))))

(deftest test-openrouter-embedding-response-wire-shape
  (let [profile (provider/get-provider :openrouter)
        parsed (et/parse-embed-response
                (embeddings/make-transport)
                profile
                {:object "list"
                 :model "google/gemini-embedding-001"
                 :data [{:object "embedding"
                         :index 0
                         :embedding [0.1 0.2 0.3]}]
                 :usage {:prompt_tokens 4 :total_tokens 4}})]
    (is (= :openrouter (:embed/provider parsed)))
    (is (= [[0.1 0.2 0.3]] (:embed/vectors parsed)))
    (is (= 4 (get-in parsed [:response/usage :usage/input-tokens])))))
