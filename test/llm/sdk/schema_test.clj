(ns llm.sdk.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.schema :as schema]))

(deftest test-request-validation
  (is (schema/validate-request
       {:request/model "gpt-4o"
        :request/messages [{:message/role :user
                            :message/content "Hello"}]}))
  (is (not (schema/validate-request
            {:request/model "gpt-4o"}))))

(deftest test-response-validation
  (is (schema/validate-response
       {:response/provider :openai
        :response/model "gpt-4o"
        :response/parts [{:part/type :text :text "Hi"}]
        :response/finish-reason :stop}))
  (is (not (schema/validate-response
            {:response/provider :openai}))))

(deftest test-part-validation
  (is (schema/validate-part {:part/type :text :text "hello"}))
  (is (schema/validate-part {:part/type :tool-call
                             :tool-call/id "call_1"
                             :tool-call/name "foo"
                             :tool-call/arguments "{}"}))
  (is (schema/validate-part {:part/type :reasoning
                             :reasoning/text "thinking..."})))

(deftest test-message-validation
  (is (schema/validate-message {:message/role :user
                                :message/content "Hello"}))
  (is (schema/validate-message {:message/role :assistant
                                :message/content [{:part/type :text :text "Hi"}
                                                  {:part/type :tool-call
                                                   :tool-call/id "c1"
                                                   :tool-call/name "x"
                                                   :tool-call/arguments "{}"}]})))

(deftest test-usage-validation
  (is (schema/validate-usage {:usage/input-tokens 10
                              :usage/output-tokens 5})))

(deftest test-stream-event-validation
  (is (schema/validate-stream-event {:event/type :stream/start}))
  (is (schema/validate-stream-event {:event/type :stream/content-delta
                                     :event/delta "hello"})))
