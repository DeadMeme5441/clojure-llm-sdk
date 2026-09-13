(ns llm.sdk.providers.openrouter-embedding-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.embed :as embed]
            [llm.sdk.http :as http]
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
                  :embed/encoding-format :float
                  :embed/provider-options
                  {:provider {:order ["Google"]
                              :allow_fallbacks false
                              :require_parameters true}
                   :input-type "search_query"
                   :extra_body {"provider" {:order ["OpenAI"]}
                                "input_type" "search_document"
                                :service_tier "priority"}}}))]
    (is (= "https://openrouter.ai/api/v1/embeddings" (:url built)))
    (is (= {:model "google/gemini-embedding-001"
            :input "query phrase"
            :encoding_format "float"
            :provider {:order ["Google"]
                       :allow_fallbacks false
                       :require_parameters true}
            :input_type "search_query"
            :service_tier "priority"}
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
                 :usage {:prompt_tokens 4
                         :total_tokens 4
                         :cost 0.0001
                         :cost_details {:upstream_inference_cost 0.00008}
                         :is_byok false}})]
    (is (= :openrouter (:embed/provider parsed)))
    (is (= [[0.1 0.2 0.3]] (:embed/vectors parsed)))
    (is (= 4 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= {:prompt_tokens 4
            :total_tokens 4
            :cost 0.0001
            :cost_details {:upstream_inference_cost 0.00008}
            :is_byok false}
           (get-in parsed [:response/usage :usage/provider-raw])))
    (is (= 0.0001 (get-in parsed [:response/cost :cost/usd])))
    (is (= {:upstream_inference_cost 0.00008}
           (get-in parsed [:response/cost :cost/breakdown :cost_details])))
    (is (false? (get-in parsed
                        [:response/cost :cost/breakdown :is_byok])))))

(deftest test-openrouter-embedding-driver-preserves-reported-zero-cost
  (with-redefs [provider/resolve-auth-token (constantly "stub")
                http/request
                (fn [_]
                  {:status 200
                   :body {:object "list"
                          :model "google/gemini-embedding-001"
                          :data [{:object "embedding"
                                  :index 0
                                  :embedding [0.1 0.2]}]
                          :usage {:prompt_tokens 4
                                  :total_tokens 4
                                  :cost 0.0
                                  :cost_details {:upstream_inference_cost 0.0}
                                  :is_byok true}}})]
    (let [response (embed/embed
                    :openrouter
                    {:embed/model "google/gemini-embedding-001"
                     :embed/inputs ["query phrase"]})]
      (is (= 0.0 (get-in response [:response/cost :cost/usd])))
      (is (false? (get-in response [:response/cost :cost/estimated?])))
      (is (= {:upstream_inference_cost 0.0}
             (get-in response
                     [:response/cost :cost/breakdown :cost_details])))
      (is (true? (get-in response
                         [:response/cost :cost/breakdown :is_byok]))))))
