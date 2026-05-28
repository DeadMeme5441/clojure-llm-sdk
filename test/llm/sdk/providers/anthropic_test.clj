(ns llm.sdk.providers.anthropic-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
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

(deftest test-build-request-stop-sequence-is-not-split
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        built (transport/build-request
               t profile
               {:request/model "claude-sonnet-4-6"
                :request/messages [{:message/role :user
                                    :message/content "Hello"}]
                :request/stop "END"})]
    (is (= ["END"] (get-in built [:body :stop_sequences])))))

(deftest test-build-request-document-file-id
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        req {:request/model "claude-sonnet-4-20250514"
             :request/messages
             [{:message/role :user
               :message/content [{:part/type :file
                                  :file/id "file_abc123"
                                  :file/name "brief.pdf"
                                  :file/citations true}
                                 {:part/type :text
                                  :text "Summarize this."}]}]}
        built (transport/build-request t profile req)
        blocks (get-in built [:body :messages 0 :content])]
    (is (= "files-api-2025-04-14"
           (get-in built [:headers "anthropic-beta"])))
    (is (= {:type "document"
            :source {:type "file" :file_id "file_abc123"}
            :title "brief.pdf"
            :citations {:enabled true}}
           (first blocks)))
    (is (= {:type "text" :text "Summarize this."}
           (second blocks)))))

(deftest test-build-request-document-base64
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        req {:request/model "claude-sonnet-4-20250514"
             :request/messages
             [{:message/role :user
               :message/content [{:part/type :file
                                  :file/name "brief.pdf"
                                  :file/data "JVBERi0x"
                                  :file/mime-type "application/pdf"}]}]}
        built (transport/build-request t profile req)]
    (is (= {:type "document"
            :source {:type "base64"
                     :media_type "application/pdf"
                     :data "JVBERi0x"}
            :title "brief.pdf"}
           (get-in built [:body :messages 0 :content 0])))))

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

(deftest test-build-request-tool-loop-replay
  (let [t (anthropic/make-transport)
        profile (provider/get-provider :anthropic)
        req {:request/model "claude-sonnet-4-6"
             :request/messages [{:message/role :user
                                 :message/content "weather?"}
                                {:message/role :assistant
                                 :message/content "I'll check."
                                 :message/tool-calls [{:part/type :tool-call
                                                       :tool-call/id "toolu_1"
                                                       :tool-call/name "get_weather"
                                                       :tool-call/arguments "{\"location\":\"NYC\"}"}]}
                                {:message/role :tool
                                 :message/tool-call-id "toolu_1"
                                 :message/content "72F"}]}
        built (transport/build-request t profile req)
        messages (get-in built [:body :messages])]
    (is (= "assistant" (get-in messages [1 :role])))
    (is (= ["text" "tool_use"]
           (mapv :type (get-in messages [1 :content]))))
    (is (= {:location "NYC"}
           (get-in messages [1 :content 1 :input])))
    (is (= "user" (get-in messages [2 :role])))
    (is (= "tool_result" (get-in messages [2 :content 0 :type])))
    (is (= "toolu_1" (get-in messages [2 :content 0 :tool_use_id])))))

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

(deftest test-parse-stream-tool-input-and-finish
  (let [t (anthropic/make-transport)
        start-line (str "data: "
                        (json/generate-string
                         {:type "content_block_start"
                          :index 2
                          :content_block {:type "tool_use"
                                          :id "toolu_1"
                                          :name "get_weather"}}))
        delta-line (str "data: "
                        (json/generate-string
                         {:type "content_block_delta"
                          :index 2
                          :delta {:type "input_json_delta"
                                  :partial_json "{\"location\""}}))
        finish-line (str "data: "
                         (json/generate-string
                          {:type "message_delta"
                           :delta {:stop_reason "tool_use"}
                           :usage {:input_tokens 5
                                   :output_tokens 3}}))
        start-ev (transport/parse-stream-event t {} start-line)
        delta-ev (transport/parse-stream-event t {} delta-line)
        finish-events (transport/parse-stream-event t {} finish-line)]
    (is (= :stream/tool-call-start (:event/type start-ev)))
    (is (= 2 (:tool-call/index start-ev)))
    (is (= :stream/tool-call-delta (:event/type delta-ev)))
    (is (= "{\"location\"" (:tool-call/arguments-delta delta-ev)))
    (is (= [:stream/usage :stream/end]
           (mapv :event/type finish-events)))
    (is (= :tool-calls (:event/finish-reason (second finish-events))))))

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
                       :profile/env-var-names ["ANTHROPIC_OAUTH_TEST"])
        req {:request/model "claude-sonnet-4-6"
             :request/messages [{:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    ;; The request should build without errors.
    (is (map? built))
    (is (string? (:url built)))))

(deftest test-build-request-claude-oat-direct-wire-shape
  (testing "Claude OAT on direct Anthropic endpoint uses Claude Code bearer headers and body transforms"
    (let [t (anthropic/make-transport)
          profile (assoc (provider/get-provider :anthropic)
                         :profile/auth-token "sk-ant-oat-test-token")
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :system
                                   :message/content "You are Hermes Agent"}
                                  {:message/role :user
                                   :message/content "weather?"}
                                  {:message/role :assistant
                                   :message/content "Checking."
                                   :message/tool-calls [{:part/type :tool-call
                                                         :tool-call/id "toolu_1"
                                                         :tool-call/name "get_weather"
                                                         :tool-call/arguments "{\"city\":\"NYC\"}"}]}]
               :request/tools [{:type :function
                                :function {:name "get_weather"
                                           :description "Weather"
                                           :parameters {:type :object
                                                        :properties {:city {:type :string}}}}}]
               :request/tool-choice :required}
          built (transport/build-request t profile req)]
      (is (= "Bearer sk-ant-oat-test-token" (get-in built [:headers "Authorization"])))
      (is (nil? (get-in built [:headers "x-api-key"])))
      (is (re-find #"oauth-2025-04-20" (get-in built [:headers "anthropic-beta"])))
      (is (re-find #"claude_cli|claude-cli"
                   (or (get-in built [:headers "user-agent"]) "")))
      (is (= "cli" (get-in built [:headers "x-app"])))
      (is (nil? (get-in built [:headers "anthropic-dangerous-direct-browser-access"])))
      (is (= "claude-sonnet-4-6" (get-in built [:body :model])))
      (is (= "You are Claude Code, Anthropic's official CLI for Claude."
             (get-in built [:body :system 0 :text])))
      (is (= "You are Claude Code"
             (get-in built [:body :system 1 :text])))
      (is (= "mcp_get_weather" (get-in built [:body :tools 0 :name])))
      (is (= "mcp_get_weather"
             (get-in built [:body :messages 1 :content 1 :name])))
      (is (= {:type "any"} (get-in built [:body :tool_choice]))))))

(deftest test-claude-oat-transform-does-not-apply-to-third-party-endpoints
  (testing "OAuth-looking tokens on Anthropic-compatible gateways keep the gateway wire shape"
    (let [t (anthropic/make-transport)
          profile (assoc (provider/get-provider :anthropic)
                         :profile/base-url "https://example-gateway.test/v1"
                         :profile/auth-token "sk-ant-oat-test-token")
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :system
                                   :message/content "You are Hermes Agent"}
                                  {:message/role :user
                                   :message/content "hi"}]}
          built (transport/build-request t profile req)]
      (is (= "sk-ant-oat-test-token" (get-in built [:headers "x-api-key"])))
      (is (nil? (get-in built [:headers "Authorization"])))
      (is (= "You are Hermes Agent" (get-in built [:body :system 0 :text])))
      (is (nil? (get-in built [:headers "x-app"]))))))

(deftest test-claude-oat-transform-requires-real-anthropic-host
  (testing "host detection does not treat lookalike domains as Anthropic"
    (let [t (anthropic/make-transport)
          profile (assoc (provider/get-provider :anthropic)
                         :profile/base-url "https://proxy.anthropic.com.evil.test/v1"
                         :profile/auth-token "sk-ant-oat-test-token")
          built (transport/build-request
                 t profile
                 {:request/model "claude-sonnet-4-6"
                  :request/messages [{:message/role :system
                                      :message/content "You are Hermes Agent"}
                                     {:message/role :user
                                      :message/content "hi"}]})]
      (is (= "sk-ant-oat-test-token" (get-in built [:headers "x-api-key"])))
      (is (nil? (get-in built [:headers "Authorization"])))
      (is (= "You are Hermes Agent" (get-in built [:body :system 0 :text]))))))

(deftest test-build-request-oauth-system-prefix
  (testing "OAuth mode sanitizes system prompt product references"
    (let [sanitized (#'anthropic/sanitize-system-for-oauth
                     [{:type "text" :text "You are Hermes Agent"}])]
      (is (= "You are Claude Code" (get-in sanitized [0 :text]))))))

(deftest test-mcp-prefix-tools
  (testing "OAuth mode prefixes tool names with mcp_"
    (let [tools [{:type :function :function {:name "get_weather" :description "Weather"}}]
          prefixed (#'anthropic/mcp-prefix-tools tools)]
      (is (= "mcp_get_weather" (get-in prefixed [0 :function :name])))
      ;; Already-prefixed names should not be double-prefixed
      (is (= "mcp_get_weather" (get-in (#'anthropic/mcp-prefix-tools prefixed) [0 :function :name]))))))

(deftest test-mcp-prefix-tool-names-in-messages
  (testing "OAuth mode prefixes tool names in message history"
    (let [messages [{:role "assistant"
                     :content [{:type "tool_use" :name "get_weather" :id "tu_1"}]}]
          prefixed (#'anthropic/mcp-prefix-tool-names-in-messages messages)]
      (is (= "mcp_get_weather" (get-in prefixed [0 :content 0 :name]))))))

;; ---------------------------------------------------------------------------
;; Caching
;; ---------------------------------------------------------------------------

(deftest test-cache-system-block-marker
  (testing "system prompt blocks get cache_control on last block"
    (let [t (anthropic/make-transport)
          profile (provider/get-provider :anthropic)
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :system :message/content "Big system prompt"}
                                  {:message/role :user :message/content "Hi"}]
               :request/cache {}}
          built (transport/build-request t profile req)
          sys (get-in built [:body :system])]
      (is (= {:type "ephemeral"} (get-in (last sys) [:cache_control]))))))

(deftest test-cache-system-and-tail-native-layout
  (testing "system + last 3 messages get inner-block cache_control"
    (let [t (anthropic/make-transport)
          profile (provider/get-provider :anthropic)
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "u1"}
                                  {:message/role :assistant :message/content "a1"}
                                  {:message/role :user :message/content "u2"}
                                  {:message/role :assistant :message/content "a2"}
                                  {:message/role :user :message/content "u3"}]
               :request/cache {:breakpoints 4}}
          built (transport/build-request t profile req)
          msgs (get-in built [:body :messages])]
      ;; 5 non-system messages → system + last 3 marked (4 bps total).
      ;; system is extracted to :system field, so messages array has 5 items.
      (is (= 5 (count msgs)))
      ;; messages[0] = "u1" — not marked
      (is (not (some :cache_control (get-in msgs [0 :content]))))
      ;; messages[2,3,4] (a1 dropped from marking; last 3 are u2, a2, u3) — marked
      (doseq [i [2 3 4]]
        (is (= {:type "ephemeral"}
               (get-in msgs [i :content (-> msgs (nth i) :content count dec) :cache_control]))
            (str "expected marker on message " i))))))

(deftest test-cache-1h-ttl
  (testing "ttl=1h propagates to markers"
    (let [t (anthropic/make-transport)
          profile (provider/get-provider :anthropic)
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "u1"}]
               :request/cache {:ttl "1h"}}
          built (transport/build-request t profile req)
          sys (get-in built [:body :system])]
      (is (= {:type "ephemeral" :ttl "1h"} (get-in (last sys) [:cache_control]))))))

(deftest test-cache-disabled-no-markers
  (testing "without :request/cache, no markers anywhere"
    (let [t (anthropic/make-transport)
          profile (provider/get-provider :anthropic)
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "u1"}]}
          built (transport/build-request t profile req)]
      (is (not (some :cache_control (get-in built [:body :system]))))
      (is (not (some :cache_control (mapcat (comp #(if (sequential? %) % []) :content)
                                            (get-in built [:body :messages]))))))))

(deftest test-cache-tools-cache-flag
  (testing "tools-cache? marks last tool"
    (let [t (anthropic/make-transport)
          profile (provider/get-provider :anthropic)
          req {:request/model "claude-sonnet-4-6"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/tools [{:type :function :function {:name "a" :description "A"}}
                                {:type :function :function {:name "b" :description "B"}}]
               :request/cache {:tools-cache? true}}
          built (transport/build-request t profile req)
          tools (get-in built [:body :tools])]
      (is (= 2 (count tools)))
      (is (nil? (:cache_control (first tools))))
      (is (= {:type "ephemeral"} (:cache_control (last tools)))))))
