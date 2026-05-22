(ns llm.sdk.providers.cohere-chat-test
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.cohere-chat :as cohere]))

(deftest test-build-request-shape
  (let [t (cohere/make-transport)
        profile (provider/get-provider :cohere)
        req {:request/model "command-r-plus-08-2024"
             :request/messages [{:message/role :system :message/content "be helpful"}
                                {:message/role :user :message/content "hi"}]
             :request/temperature 0.3
             :request/max-tokens 200}
        built (transport/build-request t profile req)]
    (is (= "https://api.cohere.com/v2/chat" (:url built)))
    (is (= "command-r-plus-08-2024" (get-in built [:body :model])))
    (is (= "system" (get-in built [:body :messages 0 :role])))
    (is (= "user" (get-in built [:body :messages 1 :role])))
    (is (= false (get-in built [:body :stream])))
    (is (= 0.3 (get-in built [:body :temperature])))
    (is (= 200 (get-in built [:body :max_tokens])))))

(deftest test-build-request-tools
  (let [t (cohere/make-transport)
        profile (provider/get-provider :cohere)
        req {:request/model "command-r-plus"
             :request/messages [{:message/role :user :message/content "weather"}]
             :request/tools [{:type "function"
                              :function {:name "get_weather"
                                         :description "Get weather"
                                         :parameters {:type "object"
                                                      :properties {:city {:type "string"}}}}}]
             :request/tool-choice :required}
        built (transport/build-request t profile req)
        tools (get-in built [:body :tools])]
    (is (= 1 (count tools)))
    (is (= "function" (get-in tools [0 :type])))
    (is (= "get_weather" (get-in tools [0 :function :name])))
    (is (= "REQUIRED" (get-in built [:body :tool_choice])))))

(deftest test-build-request-tool-result-roundtrip
  (testing "assistant tool_calls and tool messages are mapped onto Cohere's shape"
    (let [t (cohere/make-transport)
          profile (provider/get-provider :cohere)
          req {:request/model "command-r-plus"
               :request/messages [{:message/role :user :message/content "use it"}
                                  {:message/role :assistant
                                   :message/content ""
                                   :message/tool-calls [{:tool-call/id "tc_1"
                                                         :tool-call/name "get_weather"
                                                         :tool-call/arguments "{\"city\":\"NYC\"}"}]}
                                  {:message/role :tool
                                   :message/tool-call-id "tc_1"
                                   :message/content "72F"}]}
          built (transport/build-request t profile req)
          msgs (get-in built [:body :messages])]
      (is (= 3 (count msgs)))
      (is (= [{:id "tc_1"
               :type "function"
               :function {:name "get_weather"
                          :arguments "{\"city\":\"NYC\"}"}}]
             (get-in msgs [1 :tool_calls])))
      (is (= "tool" (:role (msgs 2))))
      (is (= "tc_1" (:tool_call_id (msgs 2)))))))

(deftest test-parse-response-with-citations
  (let [t (cohere/make-transport)
        profile (provider/get-provider :cohere)
        raw {:id "resp_x"
             :model "command-r-plus"
             :finish_reason "COMPLETE"
             :message {:role "assistant"
                       :content [{:type "text"
                                  :text "Beethoven died in 1827."}]
                       :citations [{:start 16
                                    :end 25
                                    :text "1827"
                                    :sources [{:type "document"
                                               :id "doc_1"
                                               :url "https://example.com/b"
                                               :title "Bio"}]}]}
             :usage {:billed_units {:input_tokens 10 :output_tokens 7}
                     :tokens {:input_tokens 12 :output_tokens 7}}}
        parsed (transport/parse-response t profile raw)
        parts (:response/parts parsed)]
    (is (= :stop (:response/finish-reason parsed)))
    (is (= 2 (count parts)) "text + citation")
    (is (= :text (:part/type (first parts))))
    (is (= :citation (:part/type (second parts))))
    (is (= "https://example.com/b" (:citation/url (second parts))))
    (is (= 10 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 7 (get-in parsed [:response/usage :usage/output-tokens])))))

(deftest test-parse-response-tool-calls
  (let [t (cohere/make-transport)
        profile (provider/get-provider :cohere)
        raw {:id "resp"
             :model "command-r"
             :finish_reason "TOOL_CALL"
             :message {:role "assistant"
                       :content []
                       :tool_calls [{:id "tc_a"
                                     :type "function"
                                     :function {:name "lookup"
                                                :arguments "{\"q\":\"x\"}"}}]}}
        parsed (transport/parse-response t profile raw)]
    (is (= :tool-calls (:response/finish-reason parsed)))
    (is (= 1 (count (:response/tool-calls parsed))))
    (is (= "tc_a" (:tool-call/id (first (:response/tool-calls parsed)))))))

(deftest test-stream-content-delta
  (let [t (cohere/make-transport)
        profile (provider/get-provider :cohere)
        line (str "data: " (json/generate-string
                            {:type "content-delta"
                             :delta {:message {:content {:text "Hello"}}}}))
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-stream-message-end-emits-usage-then-end
  (let [t (cohere/make-transport)
        profile (provider/get-provider :cohere)
        line (str "data: "
                  (json/generate-string
                   {:type "message-end"
                    :delta {:finish_reason "COMPLETE"
                            :usage {:billed_units {:input_tokens 10
                                                   :output_tokens 5}}}}))
        events (transport/parse-stream-event t profile line)]
    (is (sequential? events) "message-end yields a vector of events")
    (is (= :stream/usage (:event/type (first events))))
    (is (= :stream/end (:event/type (last events))))
    (is (= :stop (:event/finish-reason (last events))))))
