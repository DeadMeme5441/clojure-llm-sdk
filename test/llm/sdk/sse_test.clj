(ns llm.sdk.sse-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.sse :as sse]))

(deftest parse-json-data-handles-common-sse-envelope
  (testing "valid data lines parse as keywordized JSON"
    (is (= {:ok true}
           (sse/parse-json-data "data: {\"ok\":true}"))))
  (testing "terminal, blank, non-data, and malformed lines are ignored"
    (is (nil? (sse/parse-json-data "data: [DONE]")))
    (is (nil? (sse/parse-json-data "data: ")))
    (is (nil? (sse/parse-json-data "event: message")))
    (is (nil? (sse/parse-json-data "data: {")))))

(deftest event->seq-normalizes-provider-event-return
  (is (nil? (sse/event->seq nil)))
  (is (= [{:event/type :one}]
         (sse/event->seq {:event/type :one})))
  (is (= [{:event/type :one} {:event/type :two}]
         (sse/event->seq [{:event/type :one}
                          {:event/type :two}]))))
