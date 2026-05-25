(ns llm.sdk.stream-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.stream :as stream]))

(deftest test-reduce-content-deltas
  (let [events [(stream/start-event)
                (stream/content-delta "Hello")
                (stream/content-delta " world")
                (stream/end-event :finish-reason :stop)]
        acc (stream/reduce-events events)]
    (is (= [{:part/type :text :text "Hello world"}] (:parts acc)))
    (is (= :stop (:finish-reason acc)))))

(deftest test-reduce-tool-calls
  (let [events [(stream/start-event)
                (stream/tool-call-start 0 "call_1" "get_weather")
                (stream/tool-call-delta 0 "{\"location\": \"NYC\"}")
                (stream/tool-call-end 0)
                (stream/end-event :finish-reason :tool-calls)]
        acc (stream/reduce-events events)]
    (is (= 1 (count (:tool-calls-indexed acc))))
    (is (= "get_weather" (get-in acc [:tool-calls-indexed 0 :tool-call/name])))
    (is (= "{\"location\": \"NYC\"}" (get-in acc [:tool-calls-indexed 0 :tool-call/arguments])))
    (is (empty? (:parts acc)))))

(deftest test-events->response
  (let [events [(stream/start-event)
                (stream/content-delta "The answer")
                (stream/content-delta " is 42.")
                (stream/end-event :finish-reason :stop)]
        resp (stream/events->response events :openai "gpt-4o")]
    (is (= :openai (:response/provider resp)))
    (is (= "gpt-4o" (:response/model resp)))
    (is (= [{:part/type :text :text "The answer is 42."}]
           (:response/parts resp)))
    (is (= :stop (:response/finish-reason resp)))))

(deftest test-reasoning-delta
  (let [events [(stream/start-event)
                (stream/reasoning-delta "Let me think..." :encrypted true)
                (stream/content-delta "Done")
                (stream/end-event :finish-reason :stop)]
        resp (stream/events->response events :openai "o3")]
    (is (= 2 (count (:response/parts resp))))
    (is (= :reasoning (:part/type (first (:response/parts resp)))))
    (is (= "Let me think..." (:reasoning/text (first (:response/parts resp)))))))
