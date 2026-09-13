(ns llm.sdk.stream-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.schema :as schema]
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
    (is (= [{:part/type :tool-call
             :tool-call/id "call_1"
             :tool-call/name "get_weather"
             :tool-call/arguments "{\"location\": \"NYC\"}"
             :tool-call/provider-data {:stream/index 0}}]
           (:parts acc)))))

(deftest test-tool-call-parts-preserve-stream-order
  (let [events [(stream/start-event)
                (stream/content-delta "Use ")
                (stream/tool-call-start 0 "call_1" "get_weather")
                (stream/tool-call-delta 0 "{\"location\":\"NYC\"}")
                (stream/content-delta " after")
                (stream/end-event :finish-reason :tool-calls)]
        resp (stream/events->response events :openai "gpt-4o")]
    (is (= [:text :tool-call :text]
           (mapv :part/type (:response/parts resp))))
    (is (= "call_1" (get-in resp [:response/tool-calls 0 :tool-call/id])))
    (is (= "{\"location\":\"NYC\"}"
           (get-in resp [:response/tool-calls 0 :tool-call/arguments])))))

(deftest test-tool-calls-override-generic-stop-finish
  (let [response (stream/events->response
                  [(stream/tool-call-start 0 "call_1" "lookup")
                   (stream/tool-call-delta 0 "{}")
                   (stream/tool-call-end 0)
                   (stream/end-event :finish-reason :stop)]
                  :codex-backend
                  "gpt-5.5")]
    (is (= :tool-calls (:response/finish-reason response)))))


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

(deftest test-stream-usage-preserves-provider-reported-cost
  (let [cost {:cost/usd 0.003
              :cost/estimated? false
              :cost/pricing-source :provider-reported}
        response (stream/events->response
                  [(stream/usage-event
                    {:usage/input-tokens 5 :usage/output-tokens 8}
                    :cost cost)
                   (stream/end-event :finish-reason :stop)]
                  :openrouter
                  "model")]
    (is (= cost (:response/cost response)))))

(deftest indexed-reasoning-retains-separate-blocks-and-signatures
  (let [signature-event
        (stream/reasoning-delta nil :index 1 :signature "sig-1")
        response
        (stream/events->response
         [(stream/reasoning-delta "first " :index 0)
          (stream/reasoning-delta "second" :index 1 :encrypted true)
          (stream/reasoning-delta "block" :index 0)
          signature-event
          (stream/end-event :finish-reason :stop)]
         :anthropic
         "model")]
    (is (schema/validate-stream-event signature-event))
    (is (= [{:part/type :reasoning
             :reasoning/text "first block"
             :reasoning/encrypted false}
            {:part/type :reasoning
             :reasoning/text "second"
             :reasoning/encrypted true
             :reasoning/signature "sig-1"}]
           (:response/parts response)))))

(deftest tool-call-start-provider-data-survives-accumulation
  (let [response
        (stream/events->response
         [(stream/tool-call-start
           2 "call_2" "lookup"
           :provider-data {:provider/item-id "item_2"
                           :provider/nested {:kind "function"}})
          (stream/tool-call-start
           2 "call_2" "lookup"
           :provider-data {:provider/nested {:status "completed"}})
          (stream/tool-call-delta 2 "{}")
          (stream/end-event :finish-reason :tool-calls)]
         :openai
         "model")]
    (is (= {:stream/index 2
            :provider/item-id "item_2"
            :provider/nested {:kind "function" :status "completed"}}
           (get-in response
                   [:response/tool-calls 0 :tool-call/provider-data])))
    (is (= (first (:response/tool-calls response))
           (first (filter #(= :tool-call (:part/type %))
                          (:response/parts response)))))))

(deftest explicit-provider-usage-total-is-authoritative
  (let [usage {:usage/input-tokens 20
               :usage/output-tokens 16
               :usage/cached-input-tokens 3
               :usage/total-tokens 42}
        response
        (stream/events->response
         [(stream/usage-event usage)
          (stream/end-event :finish-reason :stop)]
         :openai
         "model")]
    (is (= usage (:response/usage response)))))

(deftest derived-usage-total-includes-cached-input-without-recounting-reasoning
  (let [response
        (stream/events->response
         [(stream/usage-event
           {:usage/input-tokens 20
            :usage/output-tokens 16
            :usage/cached-input-tokens 3
            :usage/reasoning-tokens 9})
          (stream/end-event :finish-reason :stop)]
         :openai
         "model")]
    (is (= {:usage/input-tokens 20
            :usage/output-tokens 16
            :usage/cached-input-tokens 3
            :usage/reasoning-tokens 9
            :usage/total-tokens 39}
           (:response/usage response)))))

(deftest partial-cumulative-usage-merges-without-clobbering-or-summing
  (let [usage-events
        [(stream/usage-event
          {:usage/input-tokens 25
           :usage/provider-raw {:input_details {:uncached 25}}})
         (stream/usage-event
          {:usage/output-tokens 15
           :usage/provider-raw {:output_details {:reasoning 4}}})]
        response (stream/events->response
                  (conj usage-events
                        (stream/end-event :finish-reason :stop))
                  :anthropic
                  "model")
        revised-response
        (stream/events->response
         (conj usage-events
               (stream/usage-event {:usage/output-tokens 16})
               (stream/end-event :finish-reason :stop))
         :anthropic
         "model")]
    (is (= {:usage/input-tokens 25
            :usage/output-tokens 15
            :usage/total-tokens 40
            :usage/provider-raw
            {:input_details {:uncached 25}
             :output_details {:reasoning 4}}}
           (:response/usage response)))
    (is (= 16 (get-in revised-response
                      [:response/usage :usage/output-tokens])))
    (is (= 41 (get-in revised-response
                      [:response/usage :usage/total-tokens])))))

(deftest accumulated-stream-errors-throw-with-partial-response
  (let [exception
        (try
          (stream/events->response
           [(stream/content-delta "partial")
            (stream/error-event
             {:error/message "Rate limit exceeded"
              :error/raw {:request-id "req_1"}})
            (stream/end-event :finish-reason :unknown)]
           :openai
           "model")
          nil
          (catch clojure.lang.ExceptionInfo e e))
        data (ex-data exception)]
    (is (some? exception))
    (is (= :rate-limit (get-in data [:error :error/reason])))
    (is (= {:error/message "Rate limit exceeded"
            :error/raw {:request-id "req_1"}}
           (:stream/error data)))
    (is (= "partial"
           (get-in data [:partial-response :response/parts 0 :text])))))

(deftest non-url-citation-events-retain-source-metadata
  (let [event (stream/citation-event
               nil
               :title "Internal document"
               :source-id "doc-7"
               :text-range [3 9]
               :provider-data {:page 4})
        response (stream/events->response
                  [event (stream/end-event :finish-reason :stop)]
                  :anthropic
                  "model")]
    (is (schema/validate-stream-event event))
    (is (= {:part/type :citation
            :citation/title "Internal document"
            :citation/source-id "doc-7"
            :citation/text-range [3 9]
            :citation/provider-data {:page 4}}
           (first (:response/parts response))))))
