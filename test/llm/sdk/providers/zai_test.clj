(ns llm.sdk.providers.zai-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.zai.chat :as zai]
            [llm.sdk.transport :as transport]))

(defn- build [request]
  (with-redefs [provider/resolve-auth-token (constantly "stub-token")]
    (transport/build-request (zai/make-transport)
                             (provider/get-provider :zai)
                             request)))

(deftest native-request-contract
  (let [built
        (build
         {:request/model "glm-5.3"
          :request/messages [{:message/role :user
                              :message/content "Think carefully."}]
          :request/reasoning {:enabled true :effort :high}
          :request/tools
          [{:type :function
            :function {:name "lookup"
                       :description "Look up a value"
                       :parameters {:type "object"}
                       :strict true}}]
          :request/tool-choice :auto
          :request/max-tokens 512
          :request/stop "done"
          :request/cache {:enabled? true :scope-id "unsupported-key"}
          :request/provider-options
          {:zai {:do-sample false
                 :tool-stream true
                 :request-id "request-123"}}})
        body (:body built)]
    (is (= "https://api.z.ai/api/paas/v4/chat/completions" (:url built)))
    (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
    (is (= "glm-5.3" (:model body)))
    (is (= {:type "enabled"} (:thinking body)))
    (is (= "high" (:reasoning_effort body)))
    (is (= "auto" (:tool_choice body)))
    (is (= 512 (:max_tokens body)))
    (is (= ["done"] (:stop body)))
    (is (false? (:do_sample body)))
    (is (true? (:tool_stream body)))
    (is (= "request-123" (:request_id body)))
    (is (not (contains? body :prompt_cache_key)))
    (is (not (contains? (get-in body [:tools 0 :function]) :strict)))))

(deftest reasoning-and-tool-state-round-trip
  (let [call {:part/type :tool-call
              :tool-call/id "call_1"
              :tool-call/name "lookup"
              :tool-call/arguments "{\"key\":\"value\"}"
              :tool-call/provider-data {:wire_type "function"}}
        built
        (build
         {:request/model "glm-5.3"
          :request/messages
          [{:message/role :assistant
            :message/content [{:part/type :reasoning
                               :reasoning/text "Need the lookup."}
                              call]
            :message/tool-calls [call]}
           {:message/role :tool
            :message/tool-call-id "call_1"
            :message/content "result"}]})
        response
        (transport/parse-response
         (zai/make-transport)
         (provider/get-provider :zai)
         {:id "chat-1"
          :model "glm-5.3"
          :choices [{:message {:content nil
                               :reasoning_content "Need the lookup."
                               :tool_calls
                               [{:id "call_2"
                                 :type "function"
                                 :function {:name "lookup"
                                            :arguments {:key "next"}}}]}
                     :finish_reason "tool_calls"}]
          :usage {:prompt_tokens 20
                  :completion_tokens 8
                  :total_tokens 28
                  :prompt_tokens_details {:cached_tokens 12}}})]
    (testing "reasoning and tool calls replay in the native wire shape"
      (is (= "Need the lookup."
             (get-in built [:body :messages 0 :reasoning_content])))
      (is (= false (get-in built [:body :thinking :clear_thinking])))
      (is (= "call_1" (get-in built [:body :messages 0 :tool_calls 0 :id])))
      (is (= "call_1" (get-in built [:body :messages 1 :tool_call_id]))))
    (testing "response is canonical and retains reported implicit cache usage"
      (is (= :zai (:response/provider response)))
      (is (= :tool-calls (:response/finish-reason response)))
      (is (= "{\"key\":\"next\"}"
             (get-in response [:response/tool-calls 0 :tool-call/arguments])))
      (is (= "Need the lookup."
             (get-in response [:response/parts 0 :reasoning/text])))
      (is (= 12
             (get-in response [:response/usage :usage/cached-input-tokens])))
      (is (= 8 (get-in response [:response/usage :usage/input-tokens]))))))

(deftest rejects-non-automatic-tool-choice
  (try
    (build {:request/model "glm-5.3"
            :request/messages [{:message/role :user :message/content "Hi"}]
            :request/tools
            [{:type :function
              :function {:name "lookup" :parameters {:type "object"}}}]
            :request/tool-choice :required})
    (is false "expected non-automatic tool choice rejection")
    (catch clojure.lang.ExceptionInfo e
      (is (= :provider/unsupported-tool-choice
             (:error/type (ex-data e)))))))
