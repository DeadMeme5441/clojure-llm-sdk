(ns llm.sdk.schema-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]))

(deftest test-request-validation
  (is (schema/validate-request
       {:request/model "gpt-4o"
        :request/messages [{:message/role :user
                            :message/content "Hello"}]}))
  (is (not (schema/validate-request
            {:request/model "gpt-4o"
             :request/messages [{:message/role :user
                                 :message/content "Hello"}]
             :request/temprature 0.2})))
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
  (is (schema/validate-part {:part/type :image
                             :image/data "base64"
                             :image/mime-type "image/png"}))
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
                                                   :tool-call/arguments "{}"}]}))
  (is (schema/validate-message {:message/role :tool
                                :message/tool-call-id "c1"
                                :message/content "result"}))
  (is (not (schema/validate-message {:message/role :user
                                     :message/content "Hello"
                                     :message/contnet "typo"}))))

(deftest test-usage-validation
  (is (schema/validate-usage {:usage/input-tokens 10
                              :usage/output-tokens 5})))

(deftest test-stream-event-validation
  (is (schema/validate-stream-event {:event/type :stream/start}))
  (is (schema/validate-stream-event {:event/type :stream/content-delta
                                     :event/delta "hello"})))

(deftest test-json-schema-response-format-validation
  (is (schema/validate-request
       {:request/model "gpt-4o-mini"
        :request/messages [{:message/role :user :message/content "json"}]
        :request/response-format {:type :json_schema
                                  :name "ok_response"
                                  :strict true
                                  :json-schema {:type "object"}}})))

(deftest test-transcribe-and-speak-validation
  (is (schema/validate-transcribe-request
       {:transcribe/model "gpt-4o-transcribe"
        :transcribe/file "audio.wav"
        :transcribe/response-format :json}))
  (is (not (schema/validate-transcribe-request
            {:transcribe/file "audio.wav"})))
  (is (schema/validate-transcribe-response
       {:transcription/text "hello"}))
  (is (schema/validate-speak-request
       {:speak/model "gpt-4o-mini-tts"
        :speak/input "hello"
        :speak/format :mp3}))
  (is (not (schema/validate-speak-request
            {:speak/model "gpt-4o-mini-tts"})))
  (is (schema/validate-speak-response
       {:audio/bytes (.getBytes "bytes")
        :audio/content-type "audio/mpeg"})))

(deftest test-registered-provider-profiles-validate
  (doseq [provider-id (sdk/list-providers)
          :let [profile (sdk/provider-profile provider-id)]]
    (is (schema/validate-provider-profile profile)
        (str "profile should validate: " provider-id))))
