(ns llm.sdk.providers.openrouter-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openrouter :as openrouter]))

(deftest test-build-request-basic
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "anthropic/claude-sonnet-4"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (= "https://openrouter.ai/api/v1/chat/completions" (:url built)))
    (is (= "anthropic/claude-sonnet-4" (get-in built [:body :model])))
    (is (not (contains? (:body built) :extra_body)))
    (is (string? (get-in built [:headers "HTTP-Referer"])))
    (is (string? (get-in built [:headers "X-OpenRouter-Title"])))))

(deftest test-build-request-provider-preferences
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "anthropic/claude-sonnet-4"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/provider-options
             {:provider {:order ["together" "fireworks"]}}}
        built (transport/build-request t profile req)]
    (is (= ["together" "fireworks"]
           (get-in built [:body :provider :order])))
    (is (not (contains? (:body built) :extra_body)))))

(deftest test-build-request-pareto-router
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "openrouter/pareto-code"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/provider-options {:pareto {:min-coding-score 0.8}}}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :plugins]))))
    (is (= "pareto-router" (get-in built [:body :plugins 0 :id])))
    (is (= 0.8 (get-in built [:body :plugins 0 :min_coding_score])))))

(deftest test-build-request-reasoning
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "anthropic/claude-sonnet-4"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/reasoning
             {:enabled true :effort :high :budget 2048 :exclude true}}
        built (transport/build-request t profile req)]
    (is (= {:enabled true :effort "high" :max_tokens 2048
            :exclude true}
           (get-in built [:body :reasoning])))))

(deftest test-build-request-flattens-openrouter-fields
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "openai/gpt-5"
             :request/messages
             [{:message/role :user :message/content "Hi"}]
             :request/max-tokens 100
             :request/stream? true
             :request/metadata {:session "abc"}
             :request/provider-options
             {:metadata-level :enabled
              :extra_body {:plugins [{:id "response-healing"}]}}}
        built (transport/build-request t profile req)
        body (:body built)]
    (is (= 100 (:max_completion_tokens body)))
    (is (not (contains? body :max_tokens)))
    (is (true? (:stream body)))
    (is (= {:session "abc"} (:metadata body)))
    (is (= [{:id "response-healing"}] (:plugins body)))
    (is (= "enabled"
           (get-in built [:headers "X-OpenRouter-Metadata"])))))

(deftest test-parse-response-delegate
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        raw {:id "chatcmpl-or-1"
             :model "anthropic/claude-sonnet-4"
             :choices [{:message {:content "Hello from OpenRouter!"}
                        :finish_reason "stop"
                        :native_finish_reason "end_turn"}]
             :openrouter_metadata {:attempt 1}
             :service_tier "default"
             :usage {:prompt_tokens 10
                     :completion_tokens 5
                     :total_tokens 15
                     :cost 0.00014
                     :is_byok false}}
        resp (transport/parse-response t profile raw)]
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello from OpenRouter!"}]
           (:response/parts resp)))
    (is (= "end_turn"
           (get-in resp [:response/provider-data :native_finish_reason])))
    (is (= {:attempt 1}
           (get-in resp [:response/provider-data :openrouter_metadata])))
    (is (= 0.00014 (get-in resp [:response/cost :cost/usd])))
    (is (false? (get-in resp [:response/cost :cost/estimated?])))))

(deftest test-parse-stream-delegate
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        line "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-parse-stream-preserves-reported-cost
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        line (str "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                  "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":8,"
                  "\"total_tokens\":13,\"cost\":0.00014}}")
        events (transport/parse-stream-event t profile line)
        usage-event (first (filter #(= :stream/usage (:event/type %)) events))]
    (is (= 0.00014 (get-in usage-event [:cost :cost/usd])))
    (is (false? (get-in usage-event [:cost :cost/estimated?])))))

;; ---------------------------------------------------------------------------
;; Caching wiring
;; ---------------------------------------------------------------------------

(deftest test-cache-envelope-on-claude
  (testing "Claude on OpenRouter gets envelope-layout cache_control on system + tail"
    (let [t (openrouter/make-transport)
          profile (provider/get-provider :openrouter)
          req {:request/model "anthropic/claude-sonnet-4"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "u1"}
                                  {:message/role :assistant :message/content "a1"}
                                  {:message/role :user :message/content "u2"}]
               :request/cache {:ttl "5m"}}
          built (transport/build-request t profile req)
          msgs (get-in built [:body :messages])]
      (is (= {:type "ephemeral" :ttl "5m"} (:cache_control (first msgs))))
      ;; tail messages marked
      (is (= {:type "ephemeral" :ttl "5m"} (:cache_control (last msgs)))))))

(deftest test-cache-session-routing
  (testing "cache scope-id maps to OpenRouter's documented session_id"
    (let [t (openrouter/make-transport)
          profile (provider/get-provider :openrouter)
          req {:request/model "anthropic/claude-sonnet-4"
               :request/messages
               [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "session-42"}}
          built (transport/build-request t profile req)]
      (is (= "session-42" (get-in built [:body :session_id])))
      (is (nil? (get-in built [:body :prompt_cache_key]))))))

(deftest test-cache-disabled-when-omitted
  (testing "no :request/cache → no markers, no prompt_cache_key"
    (let [t (openrouter/make-transport)
          profile (provider/get-provider :openrouter)
          req {:request/model "anthropic/claude-sonnet-4"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "Hi"}]}
          built (transport/build-request t profile req)
          msgs (get-in built [:body :messages])]
      (is (nil? (:cache_control (first msgs))))
      (is (nil? (:cache_control (last msgs))))
      (is (nil? (get-in built [:body :session_id]))))))
