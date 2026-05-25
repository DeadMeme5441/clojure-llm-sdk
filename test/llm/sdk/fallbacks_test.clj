(ns llm.sdk.fallbacks-test
  "Coverage for the sequential fallback helper.

   Uses a stub :complete-fn to drive deterministic outcomes per
   provider; no real network calls."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]))

(defn- stub-complete
  "Return a stub completion fn driven by a map of {provider-id outcome}.
   outcome can be:
     {:ok response-map}    succeed
     {:throw ex-info-map}  throw ex-info with this map as ex-data"
  [outcomes]
  (fn [provider-id _request]
    (let [outcome (get outcomes provider-id)]
      (cond
        (nil? outcome)
        (throw (ex-info "No stub outcome configured" {:provider provider-id}))

        (:ok outcome)
        (:ok outcome)

        (:throw outcome)
        (throw (ex-info (str provider-id " failed") (:throw outcome)))))))

(defn- response [provider model]
  {:response/provider provider
   :response/model model
   :response/parts [{:part/type :text :text "ok"}]
   :response/finish-reason :stop})

;; ---------------------------------------------------------------------------
;; Happy paths
;; ---------------------------------------------------------------------------

(deftest test-first-provider-succeeds
  (let [stub (stub-complete {:openai {:ok (response :openai "gpt-4o")}})
        resp (sdk/with-fallbacks
              [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
              {:request/messages [{:message/role :user :message/content "Hi"}]}
              {:complete-fn stub})]
    (is (= :openai (:response/provider resp)))
    (is (= "gpt-4o" (:response/model resp)))))

(deftest test-falls-through-to-second-on-failure
  (let [stub (stub-complete
              {:openai {:throw {:status 429 :error {:error/reason :rate-limit}}}
               :anthropic {:ok (response :anthropic "claude-haiku-4-5")}})
        resp (sdk/with-fallbacks
              [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
              {:request/messages [{:message/role :user :message/content "Hi"}]}
              {:complete-fn stub})]
    (is (= :anthropic (:response/provider resp)))))

(deftest test-falls-through-on-terminal-error-too
  (testing "auth / quota / content-filter errors also fall through — caller asked for fallback"
    (let [stub (stub-complete
                {:openai {:throw {:status 401 :error {:error/reason :auth}}}
                 :anthropic {:ok (response :anthropic "claude-haiku-4-5")}})
          resp (sdk/with-fallbacks
                [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
                {:request/messages [{:message/role :user :message/content "Hi"}]}
                {:complete-fn stub})]
      (is (= :anthropic (:response/provider resp))))))

;; ---------------------------------------------------------------------------
;; All-fail behaviour
;; ---------------------------------------------------------------------------

(deftest test-all-providers-fail-throws-with-attempts
  (let [stub (stub-complete
              {:openai {:throw {:status 429 :error {:error/reason :rate-limit}}}
               :anthropic {:throw {:status 503 :error {:error/reason :overloaded}}}})
        ex (try
             (sdk/with-fallbacks
              [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
              {:request/messages [{:message/role :user :message/content "Hi"}]}
              {:complete-fn stub})
             nil
             (catch clojure.lang.ExceptionInfo e e))
        data (ex-data ex)]
    (is (some? ex))
    (is (= 2 (count (:attempts data))))
    (is (= :openai (:provider (first (:attempts data)))))
    (is (= :anthropic (:provider (second (:attempts data)))))
    (is (= :rate-limit (:error/reason (first (:attempts data)))))
    (is (= :overloaded (:error/reason (second (:attempts data)))))))

(deftest test-empty-provider-list-throws
  (is (thrown-with-msg? Exception #"empty provider list"
        (sdk/with-fallbacks [] {:request/messages []}))))

;; ---------------------------------------------------------------------------
;; Side-effects
;; ---------------------------------------------------------------------------

(deftest test-on-attempt-callback-fires-per-failure
  (let [stub (stub-complete
              {:openai {:throw {:status 429 :error {:error/reason :rate-limit}}}
               :anthropic {:ok (response :anthropic "claude-haiku-4-5")}})
        seen (atom [])
        _ (sdk/with-fallbacks
            [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
            {:request/messages [{:message/role :user :message/content "Hi"}]}
            {:complete-fn stub
             :on-attempt (fn [m] (swap! seen conj m))})]
    (is (= 1 (count @seen)) "only one attempt was a failure")
    (is (= :openai (:provider (first @seen))))
    (is (= :rate-limit (:error/reason (first @seen))))))

(deftest test-request-model-rewritten-per-attempt
  (testing "the model id from the fallback pair overrides the request's model"
    (let [calls (atom [])
          stub (fn [provider-id request]
                 (swap! calls conj [provider-id (:request/model request)])
                 (if (= provider-id :anthropic)
                   (response :anthropic (:request/model request))
                   (throw (ex-info "down" {:status 503}))))]
      (sdk/with-fallbacks
        [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
        {:request/model "ignored-original"
         :request/messages [{:message/role :user :message/content "Hi"}]}
        {:complete-fn stub})
      (is (= [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]] @calls)))))

;; ---------------------------------------------------------------------------
;; Public API surface
;; ---------------------------------------------------------------------------

(deftest test-public-api-exposes-with-fallbacks
  (is (some? (resolve 'llm.sdk/with-fallbacks)))
  (is (fn? @(resolve 'llm.sdk/with-fallbacks))))
