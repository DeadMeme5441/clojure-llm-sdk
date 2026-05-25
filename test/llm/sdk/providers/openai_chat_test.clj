(ns llm.sdk.providers.openai-chat-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai-chat :as openai]))

(deftest test-build-request-basic
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]
             :request/temperature 0.5
             :request/max-tokens 100}
        built (transport/build-request t profile req)]
    (is (= "https://api.openai.com/v1/chat/completions" (:url built)))
    (is (= "gpt-4o" (get-in built [:body :model])))
    (is (= 0.5 (get-in built [:body :temperature])))
    (is (= 100 (get-in built [:body :max_tokens])))
    (is (= 2 (count (get-in built [:body :messages]))))))

(deftest test-build-request-tools
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/tools [{:type :function
                              :function {:name "get_weather"
                                         :description "Get weather"
                                         :parameters {:type :object
                                                      :properties {:location {:type :string}}}}}]}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :tools]))))))

(deftest test-build-request-json-schema-response-format
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o-mini"
             :request/messages [{:message/role :user
                                 :message/content "Return JSON."}]
             :request/response-format
             {:type :json_schema
              :name "ok_response"
              :description "Boolean ok envelope"
              :strict true
              :json-schema {:type "object"
                            :properties {:ok {:type "boolean"}}
                            :required ["ok"]
                            :additionalProperties false}}}
        built (transport/build-request t profile req)]
    (is (= {:type "json_schema"
            :json_schema {:name "ok_response"
                          :description "Boolean ok envelope"
                          :strict true
                          :schema {:type "object"
                                   :properties {:ok {:type "boolean"}}
                                   :required ["ok"]
                                   :additionalProperties false}}}
           (get-in built [:body :response_format])))))

(deftest test-build-request-json-schema-response-format-default-name
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o-mini"
             :request/messages [{:message/role :user :message/content "Return JSON."}]
             :request/response-format {:type :json_schema
                                       :json-schema {:type "object"}}}
        built (transport/build-request t profile req)]
    (is (= "response"
           (get-in built [:body :response_format :json_schema :name])))))

(deftest test-build-request-developer-role
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-5-codex"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (= "developer" (get-in built [:body :messages 0 :role])))))

(deftest test-parse-response-text
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        raw {:id "chatcmpl-1"
             :model "gpt-4o"
             :choices [{:message {:content "Hello!"}
                        :finish_reason "stop"}]
             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}
        resp (transport/parse-response t profile raw)]
    (is (= :openai (:response/provider resp)))
    (is (= "gpt-4o" (:response/model resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello!"}] (:response/parts resp)))
    (is (= 10 (get-in resp [:response/usage :usage/input-tokens])))))

(deftest test-parse-response-tool-calls
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        raw {:id "chatcmpl-2"
             :model "gpt-4o"
             :choices [{:message {:content nil
                                  :tool_calls [{:id "call_1"
                                                :function {:name "get_weather"
                                                           :arguments "{\"location\":\"NYC\"}"}}]}
                        :finish_reason "tool_calls"}]
             :usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30}}
        resp (transport/parse-response t profile raw)]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather" (get-in resp [:response/tool-calls 0 :tool-call/name])))))

(deftest test-parse-stream-event-content
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-parse-stream-event-tool-call
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"get_weather\"}}]}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/tool-call-start (:event/type ev)))
    (is (= 0 (:tool-call/index ev)))
    (is (= "call_1" (:tool-call/id ev)))
    (is (= "get_weather" (:tool-call/name ev)))))

(deftest test-parse-stream-event-usage
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/usage (:event/type ev)))
    (is (= 10 (get-in ev [:usage :usage/input-tokens])))))

(deftest test-parse-stream-event-finish
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/end (:event/type ev)))
    (is (= :stop (:event/finish-reason ev)))))

;; ---------------------------------------------------------------------------
;; Caching wiring
;; ---------------------------------------------------------------------------

(deftest test-cache-prompt-cache-key-passthrough
  (testing "scope-id surfaces as body.prompt_cache_key for openai chat"
    (let [t (openai/make-transport)
          profile (provider/get-provider :openai)
          req {:request/model "gpt-4o"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "session-1234"}}
          built (transport/build-request t profile req)]
      (is (= "session-1234" (get-in built [:body :prompt_cache_key]))))))

(deftest test-cache-no-prompt-key-when-disabled
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages [{:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)]
    (is (nil? (get-in built [:body :prompt_cache_key])))))

(deftest test-cache-deepseek-passthrough
  (testing "DeepSeek accepts prompt_cache_key (server-side ignores but harmless)"
    (let [t (openai/make-transport)
          profile (provider/get-provider :deepseek)
          req {:request/model "deepseek-chat"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "ds-session"}}
          built (transport/build-request t profile req)]
      (is (= "ds-session" (get-in built [:body :prompt_cache_key]))))))
