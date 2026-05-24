(ns llm.sdk.cost-cache-stamping-test
  "Coverage for the canonical-cost / canonical-cache stamping that
   sdk/complete applies after parse-response. The honesty rule under
   test: never substitute 0 for unknown cache, never substitute $0 for
   unknown cost."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.registry :as registry]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "stamping-test" "")]
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
;; canonical-cache — honest unknowns
;; ---------------------------------------------------------------------------

(deftest cache-status-hit-when-provider-reports-positive
  (let [c (pricing/canonical-cache {:usage/cached-input-tokens 42})]
    (is (= :hit (:cache/status c)))
    (is (= 42 (:cache/cached-tokens c)))))

(deftest cache-status-miss-when-provider-explicitly-zero
  (let [c (pricing/canonical-cache {:usage/cached-input-tokens 0})]
    (is (= :miss (:cache/status c)))
    (is (= 0 (:cache/cached-tokens c)))))

(deftest cache-status-unknown-when-provider-silent
  (testing "absence of cached-input-tokens means provider did not report cache"
    (let [c (pricing/canonical-cache {:usage/input-tokens 100
                                      :usage/output-tokens 50})]
      (is (= :unknown (:cache/status c)))
      (is (= :unknown (:cache/cached-tokens c)))
      ;; Honesty rule under test:
      (is (not= 0 (:cache/cached-tokens c))
          "absent cache MUST NOT collapse to 0"))))

(deftest cache-status-unknown-from-empty-usage
  (let [c (pricing/canonical-cache {})]
    (is (= :unknown (:cache/status c)))
    (is (= :unknown (:cache/cached-tokens c)))))

;; ---------------------------------------------------------------------------
;; canonical-cost — honest unknowns
;; ---------------------------------------------------------------------------

(deftest cost-marked-estimated-and-priced-when-pricing-known
  (offline
   (fn []
     (let [usage {:usage/input-tokens 1000 :usage/output-tokens 500}
           c (pricing/canonical-cost :openai "gpt-4o" usage)]
       (is (number? (:cost/usd c)))
       (is (true? (:cost/estimated? c)))
       (is (pos? (:cost/usd c)))
       (is (some? (:cost/pricing-source c)))
       (is (= 1000 (get-in c [:cost/breakdown :input-tokens])))
       (is (= 500 (get-in c [:cost/breakdown :output-tokens])))))))

(deftest cost-unknown-when-no-pricing-for-model
  (offline
   (fn []
     (let [usage {:usage/input-tokens 1000 :usage/output-tokens 500}
           c (pricing/canonical-cost :openai "model-that-does-not-exist" usage)]
       (is (= :unknown (:cost/usd c)))
       (is (true? (:cost/estimated? c)))
       (is (string? (:cost/reason c)))
       (is (not= 0 (:cost/usd c))
           "missing pricing MUST NOT collapse to $0")
       (is (not= 0M (:cost/usd c)))))))

(deftest cost-nil-for-nil-usage
  (is (nil? (pricing/canonical-cost :openai "gpt-4o" nil))))

;; ---------------------------------------------------------------------------
;; stamp-response-cost-and-cache — integration with response shape
;; ---------------------------------------------------------------------------

(deftest stamp-adds-both-cost-and-cache-when-usage-present
  (offline
   (fn []
     (let [resp {:response/provider :openai
                 :response/model "gpt-4o"
                 :response/parts [{:part/type :text :text "hi"}]
                 :response/finish-reason :stop
                 :response/usage {:usage/input-tokens 1000
                                  :usage/output-tokens 500
                                  :usage/cached-input-tokens 200}}
           stamped (pricing/stamp-response-cost-and-cache resp :openai "gpt-4o")]
       (is (contains? stamped :response/cost))
       (is (contains? stamped :response/cache))
       (is (= :hit (get-in stamped [:response/cache :cache/status])))
       (is (= 200 (get-in stamped [:response/cache :cache/cached-tokens])))
       (is (number? (get-in stamped [:response/cost :cost/usd])))))))

(deftest stamp-preserves-unknowns-when-usage-says-nothing-about-cache
  (offline
   (fn []
     (let [resp {:response/provider :openai
                 :response/model "gpt-4o"
                 :response/parts [{:part/type :text :text "hi"}]
                 :response/finish-reason :stop
                 :response/usage {:usage/input-tokens 1000
                                  :usage/output-tokens 500}}
           stamped (pricing/stamp-response-cost-and-cache resp :openai "gpt-4o")]
       (is (= :unknown (get-in stamped [:response/cache :cache/status])))
       (is (= :unknown (get-in stamped [:response/cache :cache/cached-tokens])))
       ;; cost should still resolve because pricing is known
       (is (number? (get-in stamped [:response/cost :cost/usd])))))))

;; ---------------------------------------------------------------------------
;; sdk/complete — end-to-end stamping through the public surface
;; ---------------------------------------------------------------------------

(defn- stub-openai-response
  "Return the canned response shape http/request returns on a 200, with
   the given usage embedded."
  [usage]
  {:status 200
   :body {:id "chatcmpl-stub"
          :model "gpt-4o"
          :choices [{:index 0
                     :message {:role "assistant" :content "pong"}
                     :finish_reason "stop"}]
          :usage usage}})

(deftest complete-stamps-cost-and-cache-on-openai
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  (stub-openai-response
                                   {:prompt_tokens 1000
                                    :completion_tokens 500
                                    :total_tokens 1500
                                    :prompt_tokens_details {:cached_tokens 250}}))]
       (let [resp (sdk/complete :openai
                                {:request/model "gpt-4o"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :openai (:response/provider resp)))
         (is (contains? resp :response/cost))
         (is (contains? resp :response/cache))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 250 (get-in resp [:response/cache :cache/cached-tokens])))
         (is (number? (get-in resp [:response/cost :cost/usd])))
         (is (true? (get-in resp [:response/cost :cost/estimated?]))))))))

(deftest complete-stamps-honest-unknown-cache-when-provider-silent
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  (stub-openai-response
                                   {:prompt_tokens 100
                                    :completion_tokens 20
                                    :total_tokens 120}))]
       (let [resp (sdk/complete :openai
                                {:request/model "gpt-4o"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :unknown (get-in resp [:response/cache :cache/status])))
         (is (= :unknown (get-in resp [:response/cache :cache/cached-tokens])))
         (is (not= 0 (get-in resp [:response/cache :cache/cached-tokens]))))))))

(deftest complete-stamps-honest-unknown-cost-when-pricing-missing
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  (-> (stub-openai-response
                                       {:prompt_tokens 100
                                        :completion_tokens 20
                                        :total_tokens 120})
                                      (assoc-in [:body :model] "some-unknown-model")))]
       (let [resp (sdk/complete :openai
                                {:request/model "some-unknown-model"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :unknown (get-in resp [:response/cost :cost/usd])))
         (is (true? (get-in resp [:response/cost :cost/estimated?])))
         (is (string? (get-in resp [:response/cost :cost/reason]))))))))
