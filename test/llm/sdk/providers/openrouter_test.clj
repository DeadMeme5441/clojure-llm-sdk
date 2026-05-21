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
    (is (map? (get-in built [:body :extra_body])))
    (is (string? (get-in built [:headers "HTTP-Referer"])))
    (is (string? (get-in built [:headers "X-Title"])))))

(deftest test-build-request-provider-preferences
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "anthropic/claude-sonnet-4"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/provider-options {:provider {:order ["Together" "Fireworks"]}}}
        built (transport/build-request t profile req)]
    (is (= ["Together" "Fireworks"] (get-in built [:body :extra_body :provider :order])))))

(deftest test-build-request-pareto-router
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "openrouter/pareto-code"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/provider-options {:pareto {:min-coding-score 0.8}}}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :extra_body :plugins]))))
    (is (= "pareto-router" (get-in built [:body :extra_body :plugins 0 :id])))
    (is (= 0.8 (get-in built [:body :extra_body :plugins 0 :min_coding_score])))))

(deftest test-build-request-reasoning
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "anthropic/claude-sonnet-4"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/reasoning {:enabled true :effort :high}}
        built (transport/build-request t profile req)]
    (is (= "high" (get-in built [:body :extra_body :reasoning :effort])))
    (is (= true (get-in built [:body :extra_body :reasoning :enabled])))))

(deftest test-parse-response-delegate
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        raw {:id "chatcmpl-or-1"
             :model "anthropic/claude-sonnet-4"
             :choices [{:message {:content "Hello from OpenRouter!"}
                        :finish_reason "stop"}]
             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}
        resp (transport/parse-response t profile raw)]
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello from OpenRouter!"}] (:response/parts resp)))))

(deftest test-parse-stream-delegate
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        line "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

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
      (is (= {:type "ephemeral"} (:cache_control (first msgs))))
      ;; tail messages marked
      (is (= {:type "ephemeral"} (:cache_control (last msgs)))))))

(deftest test-cache-prompt-key-in-extra-body
  (testing "scope-id surfaces under extra_body.prompt_cache_key for OpenRouter passthrough"
    (let [t (openrouter/make-transport)
          profile (provider/get-provider :openrouter)
          req {:request/model "anthropic/claude-sonnet-4"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "session-42"}}
          built (transport/build-request t profile req)]
      (is (= "session-42" (get-in built [:body :extra_body :prompt_cache_key]))))))

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
      (is (nil? (get-in built [:body :extra_body :prompt_cache_key]))))))
