(ns llm.sdk.golden-test
  "Golden fixture tests — verify parsing against sanitized real-world responses."
  (:require [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]
            [llm.sdk.providers.openai-chat :as openai]
            [llm.sdk.transport :as transport]
            [llm.sdk.stream :as stream]))

(defn- load-fixture [name]
  (-> (str "test-resources/fixtures/" name)
      slurp
      (json/parse-string true)))

(deftest test-openai-plain-response
  (let [fixture (load-fixture "openai_chat_plain.json")
        t (openai/make-transport)
        resp (transport/parse-response t {:profile/id :openai} (:response fixture))]
    (is (= :stop (:response/finish-reason resp)))
    (is (= "Hello! How can I help you today?"
           (->> (:response/parts resp)
                (filter #(= (:part/type %) :text))
                first
                :text)))
    (is (= 20 (get-in resp [:response/usage :usage/input-tokens])))))

(deftest test-openai-tool-response
  (let [fixture (load-fixture "openai_chat_tool.json")
        t (openai/make-transport)
        resp (transport/parse-response t {:profile/id :openai} (:response fixture))]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather" (get-in resp [:response/tool-calls 0 :tool-call/name])))
    (is (= "{\"location\":\"NYC\"}" (get-in resp [:response/tool-calls 0 :tool-call/arguments])))))

(deftest test-openai-stream-events
  (let [fixture (load-fixture "openai_chat_stream.json")
        t (openai/make-transport)
        profile {:profile/id :openai}
        events (keep #(transport/parse-stream-event t profile %) (:events fixture))
        resp (stream/events->response events :openai (:model fixture))]
    (is (= [{:part/type :text :text "Hello there"}] (:response/parts resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= 10 (get-in resp [:response/usage :usage/input-tokens])))))

(deftest test-openai-error-classification
  (let [fixture (load-fixture "openai_chat_error.json")
        t (openai/make-transport)
        err (transport/parse-error t {:profile/id :openai}
                                   (:status fixture) (:body fixture))]
    (is (= :auth (:error/reason err)))
    (is (not (:error/retryable err)))))
