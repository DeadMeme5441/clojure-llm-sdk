(ns llm.sdk.retry-complete-test
  "Coverage for the opt-in :retry kwarg on sdk/complete. No live
   network, no real sleeping — http/request is stubbed and the sleep
   indirection is replaced so retry loops complete instantly."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.registry :as registry]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "retry-test" "")]
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

(defn- okay-resp []
  {:status 200
   :body {:id "ok"
          :model "gpt-4o"
          :choices [{:index 0
                     :message {:role "assistant" :content "pong"}
                     :finish_reason "stop"}]
          :usage {:prompt_tokens 10 :completion_tokens 1 :total_tokens 11}}})

(defn- rate-limit-resp [& {:keys [retry-after]}]
  (cond-> {:status 429
           :body {:error {:type "rate_limit_error"
                          :message "rate_limited"}}}
    retry-after (assoc :headers {"retry-after" (str retry-after)})))

(defn- counting-stub
  "Build an http/request stub that returns one canned response per call
   from the given seq. Records the call count in the supplied atom.

   We only count *chat* requests (POST to /chat/completions) so any
   registry-side /models GETs that happen during cost stamping don't
   pollute the call count. Those GETs get an empty-body 200 so they
   don't surface as registry exceptions."
  [responses-seq call-count]
  (let [responses (atom responses-seq)]
    (fn [{:keys [method url] :as _req}]
      (if (and (= :post method)
               (string? url)
               (re-find #"/chat/completions" url))
        (do (swap! call-count inc)
            (let [r (first @responses)]
              (when (next @responses) (swap! responses rest))
              r))
        ;; Pass-through 500 for anything else (registry probes, etc) —
        ;; offline-safe but not counted.
        {:status 500 :body {:error "offline"}}))))

;; ---------------------------------------------------------------------------
;; Default behavior — :retry not supplied is still one-shot
;; ---------------------------------------------------------------------------

(deftest no-retry-by-default
  (let [calls (atom 0)
        stub (counting-stub [(rate-limit-resp) (okay-resp)] calls)]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [_] nil)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/complete :openai
                                 {:request/model "gpt-4o"
                                  :request/messages [{:message/role :user
                                                      :message/content "hi"}]})))
      (is (= 1 @calls) "no retry: one request, then throw"))))

;; ---------------------------------------------------------------------------
;; :retry true uses the default policy and recovers from a 429
;; ---------------------------------------------------------------------------

(deftest retry-true-uses-default-policy
  (let [calls (atom 0)
        stub (counting-stub [(rate-limit-resp) (okay-resp)] calls)]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [_] nil)]
      (let [resp (sdk/complete :openai
                               {:request/model "gpt-4o"
                                :request/messages [{:message/role :user
                                                    :message/content "hi"}]}
                               :retry true)]
        (is (= 2 @calls) "one retry after the 429")
        (is (= :openai (:response/provider resp)))
        (is (contains? resp :response/cost))))))

;; ---------------------------------------------------------------------------
;; Honors Retry-After header (max of header + computed backoff)
;; ---------------------------------------------------------------------------

(deftest retry-after-header-controls-sleep
  (let [calls (atom 0)
        slept (atom [])
        stub (counting-stub [(rate-limit-resp :retry-after 7)
                             (okay-resp)]
                            calls)]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [ms] (swap! slept conj ms))]
      (sdk/complete :openai
                    {:request/model "gpt-4o"
                     :request/messages [{:message/role :user
                                         :message/content "hi"}]}
                    :retry true)
      (is (= 1 (count @slept)))
      (is (>= (first @slept) 7000)
          "sleep is at least the Retry-After header (in ms)"))))

;; ---------------------------------------------------------------------------
;; Non-retryable errors propagate immediately
;; ---------------------------------------------------------------------------

(deftest non-retryable-errors-do-not-retry
  (let [calls (atom 0)
        stub (counting-stub
              [{:status 401
                :body {:error {:type "auth_error" :message "invalid key"}}}
               (okay-resp)]
              calls)]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [_] nil)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/complete :openai
                                 {:request/model "gpt-4o"
                                  :request/messages [{:message/role :user
                                                      :message/content "hi"}]}
                                 :retry true)))
      (is (= 1 @calls) "auth error is non-retryable: one call only"))))

;; ---------------------------------------------------------------------------
;; Caller-supplied policy overrides only the named keys
;; ---------------------------------------------------------------------------

(deftest custom-policy-overrides-defaults
  (let [calls (atom 0)
        stub (counting-stub
              (concat (repeat 4 (rate-limit-resp))
                      [(okay-resp)])
              calls)]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [_] nil)]
      (let [resp (sdk/complete :openai
                               {:request/model "gpt-4o"
                                :request/messages [{:message/role :user
                                                    :message/content "hi"}]}
                               :retry {:retry/max-attempts 5})]
        (is (= 5 @calls) "4 retries (5 total attempts) covers the 4 429s")
        (is (= :openai (:response/provider resp)))))))

(deftest retry-exhausts-and-throws
  (let [calls (atom 0)
        stub (counting-stub (repeat 10 (rate-limit-resp)) calls)]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [_] nil)]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Provider API error"
           (sdk/complete :openai
                         {:request/model "gpt-4o"
                          :request/messages [{:message/role :user
                                              :message/content "hi"}]}
                         :retry {:retry/max-attempts 3})))
      (is (= 3 @calls) "max-attempts=3 means exactly 3 calls before giving up"))))

;; ---------------------------------------------------------------------------
;; Network exception path retries too
;; ---------------------------------------------------------------------------

(deftest network-exception-is-retried
  (let [calls (atom 0)
        responses (atom [::throw (okay-resp)])
        stub (fn [{:keys [method url] :as _req}]
               (if (and (= :post method)
                        (string? url)
                        (re-find #"/chat/completions" url))
                 (do (swap! calls inc)
                     (let [r (first @responses)]
                       (swap! responses rest)
                       (if (= r ::throw)
                         (throw (java.net.ConnectException. "Connection refused"))
                         r)))
                 {:status 500 :body {:error "offline"}}))]
    (with-redefs [http/request stub
                  sdk/*retry-sleep-fn* (fn [_] nil)]
      (let [resp (sdk/complete :openai
                               {:request/model "gpt-4o"
                                :request/messages [{:message/role :user
                                                    :message/content "hi"}]}
                               :retry true)]
        (is (= 2 @calls) "network exception retried once, then success")
        (is (= :openai (:response/provider resp)))))))
