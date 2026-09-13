(ns llm.sdk.providers.openrouter-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openrouter.chat :as openrouter]
            [llm.sdk.providers.openrouter.image :as openrouter-image]))

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

(deftest test-build-request-provider-routing-stays-top-level-and-authoritative
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        req {:request/model "anthropic/claude-sonnet-4"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/provider-options
             {:provider {:order ["together" "fireworks"]
                         :allow_fallbacks false
                         :require_parameters true}
              :extra_body {"provider" {:order ["openai"]}
                           "service_tier" "priority"}}}
        body (:body (transport/build-request t profile req))]
    (is (= {:order ["together" "fireworks"]
            :allow_fallbacks false
            :require_parameters true}
           (:provider body)))
    (is (= "priority" (:service_tier body)))
    (is (not (contains? body :extra_body)))
    (is (not (contains? body "provider")))))

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
              :extra_body {:plugins [{:id "response-healing"}]
                           :service_tier "priority"}}}
        built (transport/build-request t profile req)
        body (:body built)]
    (is (= 100 (:max_completion_tokens body)))
    (is (not (contains? body :max_tokens)))
    (is (true? (:stream body)))
    (is (= {:session "abc"} (:metadata body)))
    (is (= [{:id "response-healing"}] (:plugins body)))
    (is (= "priority" (:service_tier body)))
    (is (= "enabled"
           (get-in built [:headers "X-OpenRouter-Metadata"])))))

(deftest test-parse-response-preserves-routing-cache-write-and-authoritative-billing
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        routing {:attempt 1
                 :requested "anthropic/claude-sonnet-4"
                 :strategy "direct"
                 :region "iad"
                 :summary "available=2, selected=Anthropic"
                 :is_byok false
                 :endpoints
                 {:total 2
                  :available
                  [{:model "anthropic/claude-sonnet-4"
                    :provider "Anthropic"
                    :selected true}
                   {:model "anthropic/claude-sonnet-4"
                    :provider "Google"
                    :selected false}]}}
        raw {:id "chatcmpl-or-1"
             :model "anthropic/claude-sonnet-4"
             :choices [{:message {:content "Hello from OpenRouter!"
                                  :reasoning_details
                                  [{:type "reasoning.summary"
                                    :summary "Checked primary sources"
                                    :index 0}]}
                        :finish_reason "stop"
                        :native_finish_reason "end_turn"}]
             :openrouter_metadata routing
             :service_tier "default"
             :usage {:prompt_tokens 10
                     :completion_tokens 5
                     :total_tokens 15
                     :prompt_tokens_details
                     {:cached_tokens 2
                      :cache_write_tokens 4}
                     :cost 0.00014
                     :cost_details
                     {:upstream_inference_cost 0.00012}
                     :is_byok false
                     :server_tool_use_details {:web_search 2}}}
        resp (transport/parse-response t profile raw)]
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello from OpenRouter!"}]
           (:response/parts resp)))
    (is (= "end_turn"
           (get-in resp [:response/provider-data :native_finish_reason])))
    (is (= routing
           (get-in resp [:response/provider-data :openrouter_metadata])))
    (is (= 4 (get-in resp [:response/usage :usage/input-tokens])))
    (is (= 2 (get-in resp
                     [:response/usage :usage/cached-input-tokens])))
    (is (= 4 (get-in resp
                     [:response/usage :usage/cache-write-tokens])))
    (is (= 0.00014 (get-in resp [:response/cost :cost/usd])))
    (is (false? (get-in resp [:response/cost :cost/estimated?])))
    (is (= {:upstream_inference_cost 0.00012}
           (get-in resp [:response/cost :cost/breakdown :cost_details])))
    (is (false? (get-in resp [:response/cost :cost/breakdown :is_byok])))
    (is (= [{:type "reasoning.summary"
             :summary "Checked primary sources"
             :index 0}]
           (get-in resp [:response/provider-data :reasoning_details])))
    (is (= {:web_search 2}
           (get-in resp
                   [:response/cost :cost/breakdown
                    :server_tool_use_details])))))

(deftest test-replays-exact-reasoning-details-and-tool-call-on-follow-up
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        reasoning-details
        [{:type "reasoning.encrypted"
          :data "opaque-reasoning"
          :id "reasoning-1"
          :index 0}]
        wire-tool-call
        {:id "call_weather"
         :type "function"
         :function {:name "weather"
                    :arguments "{\"city\":\"Paris\"}"}
         :extra_content
         {:google {:thought_signature "opaque-tool-signature"}}}
        response
        (transport/parse-response
         t profile
         {:id "chatcmpl-or-replay"
          :model "google/gemini-2.5-pro"
          :choices [{:message {:content "I'll check."
                               :reasoning_details reasoning-details
                               :tool_calls [wire-tool-call]}
                     :finish_reason "tool_calls"}]})
        request
        {:request/model "google/gemini-2.5-pro"
         :request/messages
         [{:message/role :assistant
           :message/content (:response/parts response)
           :message/tool-calls (:response/tool-calls response)
           :message/provider-data (:response/provider-data response)}
          {:message/role :tool
           :message/tool-call-id "call_weather"
           :message/content "{\"temperature\":18}"}]}
        assistant-message
        (get-in (transport/build-request t profile request)
                [:body :messages 0])]
    (is (= reasoning-details (:reasoning_details assistant-message)))
    (is (= [wire-tool-call] (:tool_calls assistant-message)))))

(deftest test-parse-image-response-preserves-media-type-and-cost-details
  (let [profile (provider/get-provider :openrouter)
        raw {:data [{:b64_json "encoded-webp"
                     :media_type "image/webp"}
                    {:b64_json "encoded-unspecified"}]
             :usage {:cost 0.002
                     :server_tool_use_details {:web_search 1}}}
        resp (openrouter-image/parse-image-response-openrouter profile raw)]
    (is (= {:image/b64 "encoded-webp"
            :image/mime-type "image/webp"}
           (first (:image/images resp))))
    (is (= {:image/b64 "encoded-unspecified"}
           (second (:image/images resp))))
    (is (= {:web_search 1}
           (get-in resp
                   [:response/cost :cost/breakdown
                    :server_tool_use_details])))))

(deftest test-parse-stream-delegate
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        line "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-parse-stream-preserves-structured-reasoning-details
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        line (str "data: {\"choices\":[{\"delta\":{\"reasoning_details\":["
                  "{\"type\":\"reasoning.summary\","
                  "\"summary\":\"Checked primary sources\",\"index\":0}]}}]}")
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/provider-state (:event/type ev)))
    (is (= :openrouter (:provider-state/provider ev)))
    (is (= [{:type "reasoning.summary"
             :summary "Checked primary sources"
             :index 0}]
           (get-in ev
                   [:provider-state/data :chat-completion/delta
                    :reasoning_details])))))

(deftest test-parse-stream-preserves-reported-cost
  (let [t (openrouter/make-transport)
        profile (provider/get-provider :openrouter)
        line (str "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                  "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":8,"
                  "\"total_tokens\":13,\"cost\":0.00014,"
                  "\"server_tool_use_details\":{\"web_search\":2}}}")
        events (transport/parse-stream-event t profile line)
        usage-event (first (filter #(= :stream/usage (:event/type %)) events))]
    (is (= 0.00014 (get-in usage-event [:cost :cost/usd])))
    (is (false? (get-in usage-event [:cost :cost/estimated?])))
    (is (= {:web_search 2}
           (get-in usage-event
                   [:cost :cost/breakdown :server_tool_use_details])))))

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
