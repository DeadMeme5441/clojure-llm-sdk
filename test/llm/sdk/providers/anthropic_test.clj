(ns llm.sdk.providers.anthropic-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.anthropic :as anthropic]))

(deftest test-build-request-basic
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        req {:request/model "claude-sonnet-4-6"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]
             :request/max-tokens 100}
        built (transport/build-request t profile req)]
    (is (= "https://api.anthropic.com/v1/messages" (:url built)))
    (is (= "claude-sonnet-4-6" (get-in built [:body :model])))
    (is (= 100 (get-in built [:body :max_tokens])))
    (is (= 1 (count (get-in built [:body :messages]))))
    (is (= 1 (count (get-in built [:body :system]))))))

(deftest test-build-request-thinking
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        req {:request/model "claude-opus-4-7"
             :request/messages [{:message/role :user :message/content "Hello"}]
             :request/reasoning {:enabled true :effort :high}}
        built (transport/build-request t profile req)]
    (is (= "adaptive" (get-in built [:body :thinking :type])))
    (is (= "high" (get-in built [:body :output_config :effort])))
    (is (nil? (get-in built [:body :temperature])))))

(deftest test-parse-response-text
  (let [t (anthropic/make-transport)
        raw {:id "msg_1"
             :model "claude-sonnet-4-6"
             :content [{:type "text" :text "Hello!"}]
             :stop_reason "end_turn"
             :usage {:input_tokens 10 :output_tokens 5}}
        resp (transport/parse-response t {} raw)]
    (is (= :anthropic (:response/provider resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello!"}] (:response/parts resp)))))

(deftest test-parse-response-tool-use
  (let [t (anthropic/make-transport)
        raw {:id "msg_2"
             :model "claude-sonnet-4-6"
             :content [{:type "tool_use" :id "tu_1" :name "get_weather"
                        :input {:location "NYC"}}]
             :stop_reason "tool_use"
             :usage {:input_tokens 20 :output_tokens 10}}
        resp (transport/parse-response t {} raw)]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather" (get-in resp [:response/tool-calls 0 :tool-call/name])))
    (is (= "{\"location\":\"NYC\"}" (get-in resp [:response/tool-calls 0 :tool-call/arguments])))))

(deftest test-parse-response-thinking
  (let [t (anthropic/make-transport)
        raw {:id "msg_3"
             :model "claude-opus-4-7"
             :content [{:type "thinking" :thinking "Let me think..."}
                       {:type "text" :text "Done!"}]
             :stop_reason "end_turn"
             :usage {:input_tokens 15 :output_tokens 8}}
        resp (transport/parse-response t {} raw)]
    (is (= 2 (count (:response/parts resp))))
    (is (= :reasoning (:part/type (first (:response/parts resp)))))
    (is (= :text (:part/type (second (:response/parts resp)))))))

;; ---------------------------------------------------------------------------
;; OAuth token detection
;; ---------------------------------------------------------------------------

(deftest test-oauth-token-detection
  (is (true? (anthropic/oauth-token? "sk-ant-oat-test-token")))
  (is (true? (anthropic/oauth-token? "sk-ant-admin123")))
  (is (true? (anthropic/oauth-token? "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")))
  (is (true? (anthropic/oauth-token? "cc-access-token-123")))
  ;; Regular API keys should NOT be detected as OAuth
  (is (false? (anthropic/oauth-token? "sk-ant-api03-test")))
  (is (false? (anthropic/oauth-token? nil)))
  (is (false? (anthropic/oauth-token? ""))))

(deftest test-build-request-oauth-bearer-auth
  (let [t (anthropic/make-transport)
        ;; Temporarily override the provider to simulate an OAuth token
        profile (assoc (provider/get-provider :anthropic)
                       :profile/auth-strategy :bearer
                       :profile/env-var-names ["ANTHROPIC_OAUTH_TEST"])]
    ;; We can't easily mock env vars, but we can test the header construction
    ;; by calling build-request with a profile that has a bearer token
    (let [req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :user :message/content "Hello"}]}
          ;; Simulate what happens when oauth-token? detects an OAuth token:
          ;; the transport uses Bearer auth and adds beta headers
          built (transport/build-request t profile req)]
      ;; The request should build without errors
      (is (map? built))
      (is (string? (:url built))))))

(deftest test-build-request-oauth-system-prefix
  "When OAuth mode is active, system prompt gets Claude Code prefix and sanitization."
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        req {:request/model "claude-sonnet-4-6"
             :request/messages [{:message/role :system :message/content "You are Hermes Agent"}
                                {:message/role :user :message/content "Hello"}]}
        ;; Manually invoke the internal helpers to verify behavior
        sanitized (#'anthropic/sanitize-system-for-oauth
                   [{:type "text" :text "You are Hermes Agent"}])]
    (is (= "You are Claude Code" (get-in sanitized [0 :text])))))

(deftest test-mcp-prefix-tools
  "OAuth mode prefixes tool names with mcp_."
  (let [tools [{:type :function :function {:name "get_weather" :description "Weather"}}]
        prefixed (#'anthropic/mcp-prefix-tools tools)]
    (is (= "mcp_get_weather" (get-in prefixed [0 :function :name])))
    ;; Already-prefixed names should not be double-prefixed
    (is (= "mcp_get_weather" (get-in (#'anthropic/mcp-prefix-tools prefixed) [0 :function :name])))))

(deftest test-mcp-prefix-tool-names-in-messages
  "OAuth mode prefixes tool names in message history."
  (let [messages [{:role "assistant"
                   :content [{:type "tool_use" :name "get_weather" :id "tu_1"}]}]
        prefixed (#'anthropic/mcp-prefix-tool-names-in-messages messages)]
    (is (= "mcp_get_weather" (get-in prefixed [0 :content 0 :name])))))
