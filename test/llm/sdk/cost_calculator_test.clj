(ns llm.sdk.cost-calculator-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.provider :as provider]
            ;; Ensure :perplexity is registered
            [llm.sdk.providers.perplexity]))

(deftest test-embedding-cost
  (let [p (pricing/pricing-entry :input 0.02 :output 0)
        result (pricing/embedding-cost
                {:usage/input-tokens 1000000}
                p)]
    (is (= 0.02M (:cost/amount-usd result)))
    (is (= :actual (:cost/status result)))))

(deftest test-image-cost-per-image
  (let [p (pricing/pricing-entry :image-per-image 0.04)
        r (pricing/image-cost {:n-images 3} p)]
    (is (= 0.12M (:cost/amount-usd r)))))

(deftest test-image-cost-per-megapixel
  (let [p (pricing/pricing-entry :image-per-megapixel 0.02)
        r (pricing/image-cost {:n-images 2 :width 1024 :height 1024} p)]
    ;; 2 images * 1.048576 MP * $0.02
    (is (some? (:cost/amount-usd r)))
    (is (= :actual (:cost/status r)))))

(deftest test-transcription-cost
  (let [p (pricing/pricing-entry :transcription-per-minute 0.006)
        r (pricing/transcription-cost {:duration-seconds 60} p)]
    (is (= 0.006M (:cost/amount-usd r)))))

(deftest test-tts-cost
  (let [p (pricing/pricing-entry :tts-per-million-chars 15)
        r (pricing/tts-cost {:characters 1000000} p)]
    (is (= 15.000000M (:cost/amount-usd r)))))

(deftest test-perplexity-custom-calculator
  (testing "Perplexity adds per-search-query cost on top of token cost"
    ;; Inject test pricing for a Perplexity Sonar model
    (pricing/register-pricing :perplexity "sonar-pro"
                              {:input 3.0      ; $3 / M input
                               :output 15.0    ; $15 / M output
                               :request-cost 0.005
                               :search-per-call 0.005})
    ;; The default calculator handles token + request. Perplexity adds
    ;; a search-query line item on top when pricing carries it.
    (let [profile (provider/get-provider :perplexity)
          calc (:profile/cost-calculator profile)
          pricing (pricing/pricing-entry :input 3.0 :output 15.0
                                          :search-per-call 0.005)
          usage {:usage/input-tokens 1000
                 :usage/output-tokens 500
                 :usage/search-queries 4}
          result (calc {:provider :perplexity
                        :model "sonar-pro"
                        :usage usage
                        :pricing pricing})]
      (is (some? calc) "profile carries a cost-calculator")
      ;; 1000*$3/M + 500*$15/M + 4*$0.005 = $0.003 + $0.0075 + $0.02 = $0.0305
      (is (some? (:cost/amount-usd result)))
      (is (.contains ^String (str (:cost/notes result))
                     "Perplexity search queries: 4")))))

(deftest test-perplexity-custom-calculator-search-only
  (testing "known search pricing still produces a cost when token rates are absent"
    (let [profile (provider/get-provider :perplexity)
          calc (:profile/cost-calculator profile)
          pricing (pricing/pricing-entry :search-per-call 0.005)
          usage {:usage/input-tokens 0
                 :usage/output-tokens 0
                 :usage/search-queries 4}
          result (calc {:provider :perplexity
                        :model "search-metered"
                        :usage usage
                        :pricing pricing})]
      (is (= :actual (:cost/status result)))
      (is (= 0.020M (:cost/amount-usd result))))))

(deftest test-default-calculator-no-search-cost
  (testing "Perplexity calc returns base token cost when no search-cost-per-call set"
    (let [profile (provider/get-provider :perplexity)
          calc (:profile/cost-calculator profile)
          pricing (pricing/pricing-entry :input 5.0 :output 5.0)
          usage {:usage/input-tokens 1000 :usage/output-tokens 1000}
          result (calc {:provider :perplexity :model "sonar"
                        :usage usage :pricing pricing})]
      (is (some? (:cost/amount-usd result))))))
