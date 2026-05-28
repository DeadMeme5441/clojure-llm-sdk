(ns llm.sdk.pricing-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [llm.sdk.http :as http]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.registry :as registry]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "pricing-test" "")]
    (.delete d) (.mkdirs d) d))

(defn isolate [f]
  (let [d (temp-dir)]
    (binding [mdev/*cache-dir* (.getPath d)]
      (mdev/reset-cache!)
      (registry/clear-live!)
      (registry/clear-overrides!)
      (try (f)
           (finally
             (doseq [c (.listFiles d)] (.delete c))
             (.delete d))))))

(use-fixtures :each isolate)

(defn- offline [f]
  (with-redefs [http/request (fn [_] {:status 500 :body {:error "offline"}})]
    (f)))

;; ---------------------------------------------------------------------------
;; get-pricing — registry-backed
;; ---------------------------------------------------------------------------

(deftest get-pricing-from-bundled-snapshot
  (offline
   (fn []
     (let [p (pricing/get-pricing :openai "gpt-4o")]
       (is (some? p))
       (is (= 2.5 (:input-cost-per-million p)))
       (is (= 10.0 (double (:output-cost-per-million p))))
       (is (= 1.25 (:cache-read-cost-per-million p)))))))

(deftest get-pricing-for-anthropic-includes-cache-write
  (offline
   (fn []
     (let [p (pricing/get-pricing :anthropic "claude-opus-4-7")]
       (is (some? p))
       (is (= 5.0 (double (:input-cost-per-million p))))
       (is (= 25.0 (double (:output-cost-per-million p))))
       (is (= 0.5 (double (:cache-read-cost-per-million p))))
       (is (= 6.25 (double (:cache-write-cost-per-million p))))))))

(deftest get-pricing-for-codex-backend-reuses-openai-catalog
  (offline
   (fn []
     (let [p (pricing/get-pricing :codex-backend "gpt-5-codex")]
       (is (some? p))
       (is (pos? (:input-cost-per-million p)))
       (is (pos? (:output-cost-per-million p)))))))

(deftest get-pricing-preserves-non-token-fields
  (offline
   (fn []
     (let [image (pricing/get-pricing :openai "dall-e-2")
           request (pricing/get-pricing :perplexity "pplx-7b-online")]
       (is (pos? (:image-cost-per-image image)))
       (is (pos? (:request-cost request)))))))

(deftest get-pricing-returns-nil-for-unknown
  (offline
   (fn []
     (is (nil? (pricing/get-pricing :openai "this-does-not-exist"))))))

;; ---------------------------------------------------------------------------
;; estimate-cost — pure function, kept stable
;; ---------------------------------------------------------------------------

(defn- bd≈ [expected actual]
  (zero? (.compareTo (bigdec expected) (bigdec actual))))

(deftest estimate-cost-multiplies-input-output
  (let [pe (pricing/pricing-entry :input 2.5 :output 10.0)
        usage {:usage/input-tokens 1000 :usage/output-tokens 500}
        result (pricing/estimate-cost usage pe)]
    (is (= :actual (:cost/status result)))
    (is (bd≈ 0.0075M (:cost/amount-usd result)))))

(deftest estimate-cost-handles-cache-tokens
  (let [pe (pricing/pricing-entry :input 3.0 :output 15.0
                                  :cache-read 0.3 :cache-write 3.75)
        usage {:usage/input-tokens 1000
               :usage/output-tokens 500
               :usage/cached-input-tokens 2000
               :usage/cache-write-tokens 100}
        result (pricing/estimate-cost usage pe)]
    (is (= :actual (:cost/status result)))
    ;; :usage/input-tokens is the uncached input count. Cached reads and
    ;; writes are separate billable lines, not added back into input.
    ;; 1000*3.0 + 500*15.0 + 2000*0.3 + 100*3.75 = all /1M
    ;; = 0.003 + 0.0075 + 0.0006 + 0.000375 = 0.011475
    (is (bd≈ 0.011475M (:cost/amount-usd result)))))

(deftest estimate-cost-handles-request-only-pricing
  (let [pe (pricing/pricing-entry :request-cost 0.005)
        usage {:usage/input-tokens 1000
               :usage/output-tokens 500
               :usage/request-count 3}
        result (pricing/estimate-cost usage pe)]
    (is (= :actual (:cost/status result)))
    (is (bd≈ 0.015M (:cost/amount-usd result)))))

(deftest estimate-cost-nil-pricing-returns-unknown
  (let [result (pricing/estimate-cost {:usage/input-tokens 1} nil)]
    (is (= :unknown (:cost/status result)))
    (is (nil? (:cost/amount-usd result)))))

;; ---------------------------------------------------------------------------
;; estimate-cost-for-model — full registry → cost path
;; ---------------------------------------------------------------------------

(deftest estimate-cost-for-model-resolves-from-registry
  (offline
   (fn []
     (let [usage {:usage/input-tokens 1000 :usage/output-tokens 500}
           r (pricing/estimate-cost-for-model :openai "gpt-4o" usage)]
       (is (= :actual (:cost/status r)))
       (is (pos? (:cost/amount-usd r)))))))

(deftest estimate-cost-for-codex-backend-resolves-from-registry
  (offline
   (fn []
     (let [usage {:usage/input-tokens 1000 :usage/output-tokens 500}
           r (pricing/estimate-cost-for-model :codex-backend "gpt-5-codex" usage)]
       (is (= :actual (:cost/status r)))
       (is (pos? (:cost/amount-usd r)))))))

(deftest estimate-cost-for-model-unknown-returns-unknown
  (offline
   (fn []
     (let [r (pricing/estimate-cost-for-model :openai "unknown-model"
                                              {:usage/input-tokens 100})]
       (is (= :unknown (:cost/status r)))))))

;; ---------------------------------------------------------------------------
;; register-pricing — pushes into registry override tier
;; ---------------------------------------------------------------------------

(deftest register-pricing-overrides-bundled-data
  (offline
   (fn []
     (pricing/register-pricing :openai "gpt-4o"
                               {:input 99.0 :output 999.0})
     (let [p (pricing/get-pricing :openai "gpt-4o")]
       (is (= 99.0 (:input-cost-per-million p)))
       (is (= 999.0 (:output-cost-per-million p)))))))

(deftest register-pricing-accepts-legacy-shape
  (offline
   (fn []
     (pricing/register-pricing :openai "gpt-4o"
                               (pricing/pricing-entry :input 1.5 :output 6.0
                                                      :source :custom))
     (let [p (pricing/get-pricing :openai "gpt-4o")]
       (is (= 1.5 (:input-cost-per-million p)))
       (is (= 6.0 (:output-cost-per-million p)))))))

(deftest register-pricing-works-for-custom-provider
  (offline
   (fn []
     (pricing/register-pricing :custom-corp "magic-7"
                               {:input 0.001 :output 0.005})
     (let [p (pricing/get-pricing :custom-corp "magic-7")]
       (is (some? p))
       (is (= 0.001 (:input-cost-per-million p)))))))

(deftest register-pricing-works-for-request-only-pricing
  (offline
   (fn []
     (pricing/register-pricing :custom-corp "metered"
                               {:request-cost 0.01})
     (let [p (pricing/get-pricing :custom-corp "metered")
           c (pricing/canonical-cost :custom-corp "metered"
                                     {:usage/input-tokens 10
                                      :usage/output-tokens 1
                                      :usage/request-count 2})]
       (is (= 0.01 (:request-cost p)))
       (is (bd≈ 0.02M (:cost/usd c)))
       (is (= 0.01 (get-in c [:cost/breakdown :request-cost])))
       (is (= 2 (get-in c [:cost/breakdown :request-count])))))))

;; ---------------------------------------------------------------------------
;; fetch-pricing! — delegates to registry/refresh!
;; ---------------------------------------------------------------------------

(deftest fetch-pricing-delegates-to-registry-refresh
  (let [refresh-calls (atom 0)]
    (with-redefs [registry/refresh!
                  (fn [pid]
                    (swap! refresh-calls inc)
                    [{:model/id "fake" :model/provider pid
                      :model/source :live-models-api}])]
      (let [n (pricing/fetch-pricing!
               (pricing/resolve-billing-route "gpt-4o" :provider :openai))]
        (is (= 1 n))
        (is (= 1 @refresh-calls))))))

(deftest fetch-pricing-returns-zero-on-error
  (with-redefs [registry/refresh! (fn [_] (throw (ex-info "boom" {})))]
    (let [n (pricing/fetch-pricing!
             (pricing/resolve-billing-route "gpt-4o" :provider :openai))]
      (is (zero? n) "exceptions surface as 0 entries"))))

(deftest fetch-openrouter-pricing-refreshes-openrouter-provider
  (let [seen (atom nil)]
    (with-redefs [registry/refresh!
                  (fn [pid]
                    (reset! seen pid)
                    [{:model/id "openai/gpt-4o"
                      :model/provider :openrouter
                      :model/source :live-models-api}])]
      (is (= 1 (pricing/fetch-openrouter-pricing!)))
      (is (= :openrouter @seen)))))

(deftest openrouter-pricing-is-looked-up-as-openrouter-billing-route
  (offline
   (fn []
     (pricing/register-pricing :openrouter "openai/gpt-4o"
                               {:input 2.5 :output 10.0})
     (let [p (pricing/get-openrouter-pricing :openai "gpt-4o")
           c (pricing/estimate-openrouter-cost
              :openai "gpt-4o"
              {:usage/input-tokens 1000 :usage/output-tokens 500})]
       (is (= 2.5 (:input-cost-per-million p)))
       (is (= 10.0 (:output-cost-per-million p)))
       (is (= :actual (:cost/status c)))
       (is (bd≈ 0.0075M (:cost/amount-usd c)))))))

(deftest official-openai-image-pricing-fallback
  (let [p (pricing/get-pricing :openai "gpt-image-1-mini")]
    (is (= :openai-pricing-page (:source p)))
    (is (= 2.0 (:input-cost-per-million p)))
    (is (= 8.0 (:output-cost-per-million p)))))

;; ---------------------------------------------------------------------------
;; resolve-billing-route — pure metadata
;; ---------------------------------------------------------------------------

(deftest resolve-billing-route-detects-openrouter
  (let [r (pricing/resolve-billing-route "openai/gpt-4o"
                                         :provider :openrouter
                                         :base-url "https://openrouter.ai/api/v1")]
    (is (= :openrouter (:billing-route/mode r)))))

(deftest resolve-billing-route-detects-local
  (let [r (pricing/resolve-billing-route "llama"
                                         :provider :local
                                         :base-url "http://localhost:11434/v1")]
    (is (= :local (:billing-route/mode r)))))

(deftest resolve-billing-route-direct-by-default
  (let [r (pricing/resolve-billing-route "gpt-4o" :provider :openai)]
    (is (= :direct (:billing-route/mode r)))))
