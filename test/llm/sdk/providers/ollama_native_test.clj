(ns llm.sdk.providers.ollama-native-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.schema :as schema]
            [llm.sdk.stream :as stream]
            [llm.sdk.providers.ollama.native :as ollama]))

(deftest test-chat-build-request
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        built (transport/build-request
               t profile
               {:request/model "llama3.1"
                :request/messages [{:message/role :user :message/content "hi"}]
                :request/temperature 0.5})]
    (is (.endsWith ^String (:url built) "/api/chat"))
    (is (= "llama3.1" (get-in built [:body :model])))
    (is (= false (get-in built [:body :stream])))
    (is (= "application/json" (get-in built [:headers "Accept"])))
    (is (= 0.5 (get-in built [:body :options :temperature])))))

(deftest test-chat-builds-current-structured-thinking-and-logprob-fields
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        json-schema {:type "object"
                     :properties {:answer {:type "string"}}
                     :required ["answer"]}
        built (transport/build-request
               t profile
               {:request/model "qwen3"
                :request/messages [{:message/role :user :message/content "hi"}]
                :request/tools
                [{:type :function
                  :function {:name "lookup"
                             :description "Look up a value"
                             :strict true}}]
                :request/response-format {:type :json_schema
                                          :json-schema json-schema}
                :request/reasoning {:enabled true :effort :high}
                :request/provider-options
                {:ollama {:keep_alive 0
                          :logprobs false
                          :top_logprobs 3
                          :options {:seed 42}}}})]
    (is (= json-schema (get-in built [:body :format])))
    (is (= "high" (get-in built [:body :think])))
    (is (= {:type "function"
            :function {:name "lookup"
                       :description "Look up a value"
                       :parameters {:type "object" :properties {}}}}
           (get-in built [:body :tools 0])))
    (is (= 0 (get-in built [:body :keep_alive])))
    (is (= false (get-in built [:body :logprobs])))
    (is (= 3 (get-in built [:body :top_logprobs])))
    (is (= 42 (get-in built [:body :options :seed])))))

(deftest test-chat-maps-developer-to-system-and-supports-max-thinking
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        built (transport/build-request
               t profile
               {:request/model "gpt-oss"
                :request/stream? true
                :request/messages
                [{:message/role :developer
                  :message/content "Follow these instructions."}
                 {:message/role :user
                  :message/content "hi"}]
                :request/reasoning {:enabled true :effort :max}})]
    (is (= "system" (get-in built [:body :messages 0 :role])))
    (is (= "max" (get-in built [:body :think])))
    (is (= "application/x-ndjson"
           (get-in built [:headers "Accept"])))))

(deftest test-chat-disables-thinking-and-rejects-unsupported-effort-aliases
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        base {:request/model "qwen3"
              :request/messages [{:message/role :user
                                  :message/content "hi"}]}
        disabled (transport/build-request
                  t profile
                  (assoc base :request/reasoning
                         {:enabled false :effort :high}))
        none (transport/build-request
              t profile
              (assoc base :request/reasoning
                     {:enabled true :effort :none}))]
    (is (= false (get-in disabled [:body :think])))
    (is (= false (get-in none [:body :think])))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"does not support reasoning effort minimal"
         (transport/build-request
          t profile
          (assoc base :request/reasoning
                 {:enabled true :effort :minimal}))))))

(deftest test-chat-text-response-format-does-not-enable-json
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        built (transport/build-request
               t profile
               {:request/model "qwen3"
                :request/messages [{:message/role :user :message/content "hi"}]
                :request/response-format {:type :text}})]
    (is (not (contains? (:body built) :format)))))

(deftest test-chat-history-uses-native-thinking-and-tool-fields
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        call {:part/type :tool-call
              :tool-call/id "canonical-id"
              :tool-call/name "weather"
              :tool-call/arguments "{\"city\":\"Paris\"}"}
        built (transport/build-request
               t profile
               {:request/model "qwen3"
                :request/messages
                [{:message/role :assistant
                  :message/content [{:part/type :reasoning
                                     :reasoning/text "checking"}
                                    {:part/type :text :text "calling"}
                                    call]
                  :message/tool-calls [call]}
                 {:message/role :tool
                  :message/content
                  [{:part/type :tool-result
                    :tool-result/id "canonical-id"
                    :tool-result/name "weather"
                    :tool-result/content "sunny"
                    :tool-result/is-error false}]}]})
        assistant (get-in built [:body :messages 0])
        tool-result (get-in built [:body :messages 1])]
    (is (= "checking" (:thinking assistant)))
    (is (= [{:id "canonical-id"
             :function {:index 0
                        :name "weather"
                        :arguments {:city "Paris"}}}]
           (:tool_calls assistant))
        "The canonical call in content and message/tool-calls is replayed once")
    (is (= "weather" (:tool_name tool-result)))
    (is (= "canonical-id" (:tool_call_id tool-result)))
    (is (= "sunny" (:content tool-result)))))

(deftest test-chat-vision-images-sibling
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        built (transport/build-request
               t profile
               {:request/model "llama3.2-vision"
                :request/messages [{:message/role :user
                                    :message/content
                                    [{:part/type :text :text "what's this"}
                                     {:part/type :image
                                      :image/data "base64bytes"}]}]})
        msg (get-in built [:body :messages 0])]
    (is (= "what's this" (:content msg)))
    (is (= ["base64bytes"] (:images msg))
        "Ollama native takes images as sibling :images, not as content parts")))

(deftest test-chat-stop-sequence-is-not-split
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        built (transport/build-request
               t profile
               {:request/model "llama3.1"
                :request/messages [{:message/role :user
                                    :message/content "hi"}]
                :request/stop "END"})]
    (is (= ["END"] (get-in built [:body :options :stop])))))

(deftest test-chat-vision-data-uri-strips-header
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        built (transport/build-request
               t profile
               {:request/model "llama3.2-vision"
                :request/messages [{:message/role :user
                                    :message/content
                                    [{:part/type :image
                                      :image/url "data:image/png;base64,abc123"}]}]})]
    (is (= ["abc123"] (get-in built [:body :messages 0 :images])))))

(deftest test-chat-rejects-unfetched-images-and-unsupported-native-shapes
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        base {:request/model "llama3.2-vision"}]
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"does not fetch image URLs"
      (transport/build-request
       t profile
       (assoc base
              :request/messages
              [{:message/role :user
                :message/content
                [{:part/type :image
                  :image/url "https://example.invalid/image.png"}]}]))))
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"does not support content part :file"
      (transport/build-request
       t profile
       (assoc base
              :request/messages
              [{:message/role :user
                :message/content
                [{:part/type :file
                  :file/data "not-an-image"}]}]))))
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"custom tools are unsupported"
      (transport/build-request
       t profile
       (assoc base
              :request/messages
              [{:message/role :user :message/content "hi"}]
              :request/tools
              [{:type :custom
                :custom {:name "freeform"}}]))))))

(deftest test-parse-response
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        raw {:model "llama3.1"
             :message {:role "assistant" :content "Hello there."}
             :done true
             :done_reason "stop"
             :prompt_eval_count 12
             :prompt_eval_cached_count 5
             :eval_count 3}
        parsed (transport/parse-response t profile raw)]
    (is (= :stop (:response/finish-reason parsed)))
    (is (= "Hello there." (get-in parsed [:response/parts 0 :text])))
    (is (= 7 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 5 (get-in parsed
                     [:response/usage :usage/cached-input-tokens])))
    (is (= 3 (get-in parsed [:response/usage :usage/output-tokens])))
    (is (= 15 (get-in parsed [:response/usage :usage/total-tokens])))
    (is (= {:prompt_eval_count 12
            :prompt_eval_cached_count 5
            :eval_count 3}
           (get-in parsed [:response/usage :usage/provider-raw])))))

(deftest test-parse-response-preserves-native-tool-state-for-replay
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        native-call {:type "function"
                     :id "native-call-7"
                     :function {:index 7
                                :name "lookup"
                                :arguments {:id 1}}}
        raw {:model "qwen3"
             :message {:role "assistant"
                       :thinking "reasoning"
                       :content "answer"
                       :images ["generated-image"]
                       :tool_calls [native-call]}
             :done true
             :done_reason "tool_calls"}
        parsed (transport/parse-response t profile raw)
        replay (transport/build-request
                t profile
                {:request/model "qwen3"
                 :request/messages
                 [{:message/role :assistant
                   :message/content (:response/parts parsed)
                   :message/tool-calls (:response/tool-calls parsed)}
                  {:message/role :tool
                   :message/tool-call-id "native-call-7"
                   :message/content "found"}]})]
    (is (= [:reasoning :text :image :tool-call]
           (mapv :part/type (:response/parts parsed))))
    (is (= "reasoning"
           (get-in parsed [:response/parts 0 :reasoning/text])))
    (is (= "generated-image"
           (get-in parsed [:response/parts 2 :image/data])))
    (is (= "native-call-7"
           (get-in parsed [:response/tool-calls 0 :tool-call/id])))
    (is (= native-call
           (get-in parsed
                   [:response/tool-calls 0
                    :tool-call/provider-data :ollama/tool-call])))
    (is (= "reasoning"
           (get-in replay [:body :messages 0 :thinking])))
    (is (= [native-call]
           (get-in replay [:body :messages 0 :tool_calls]))
        "Duplicate canonical tool-call surfaces replay once")
    (is (= {:role "tool"
            :content "found"
            :tool_name "lookup"
            :tool_call_id "native-call-7"}
           (get-in replay [:body :messages 1])))
    (is (= :tool-calls (:response/finish-reason parsed)))
    (is (schema/validate-response parsed))))

(deftest test-stream-line-content
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string {:model "llama3.1"
                                    :message {:role "assistant" :content "hi"}
                                    :done false})
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "hi" (:event/delta ev)))))

(deftest test-stream-midstream-error-remains-visible-and-fails-accumulation
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        error-raw {:error "model runner failed"}
        error-event (transport/parse-stream-event
                     t profile (json/generate-string error-raw))
        failure (try
                  (stream/events->response
                   [(stream/content-delta "partial")
                    error-event]
                   :ollama-native
                   "qwen3")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
    (is (= :stream/error (:event/type error-event)))
    (is (= "model runner failed"
           (get-in error-event [:error/error :error/message])))
    (is (= error-raw
           (get-in error-event [:error/error :error/raw])))
    (is (instance? clojure.lang.ExceptionInfo failure))
    (is (= "partial"
           (get-in (ex-data failure)
                   [:partial-response :response/parts 0 :text])))))

(deftest test-stream-final-line-emits-usage-and-end
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string {:model "llama3.1"
                                    :message {:role "assistant" :content ""}
                                    :done true
                                    :done_reason "stop"
                                    :prompt_eval_count 10
                                    :prompt_eval_cached_count 4
                                    :eval_count 5})
        evs (transport/parse-stream-event t profile line)
        usage (:usage (first evs))
        response (stream/events->response evs :ollama-native "llama3.1")]
    (is (sequential? evs))
    (is (= :stream/usage (:event/type (first evs))))
    (is (= 6 (:usage/input-tokens usage)))
    (is (= 4 (:usage/cached-input-tokens usage)))
    (is (= 5 (:usage/output-tokens usage)))
    (is (not (contains? usage :usage/total-tokens))
        "The adapter leaves partial stream totals for the reducer to derive")
    (is (= 15 (get-in response [:response/usage :usage/total-tokens])))
    (is (= :stream/end (:event/type (last evs))))
    (is (= :stop (:event/finish-reason (last evs))))))

(deftest test-stream-usage-keeps-zero-cache-count-without-inventing-counters
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string
              {:model "llama3.1"
               :message {:role "assistant" :content ""}
               :done true
               :done_reason "stop"
               :prompt_eval_cached_count 0})
        events (transport/parse-stream-event t profile line)]
    (is (= {:usage/request-count 1
            :usage/cached-input-tokens 0
            :usage/provider-raw {:prompt_eval_cached_count 0}}
           (:usage (first events))))))

(deftest test-stream-line-preserves-interleaved-thinking-content-and-final-metrics
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string
              {:model "qwen3"
               :message {:role "assistant"
                         :thinking "work"
                         :content "answer"}
               :done true
               :done_reason "stop"
               :total_duration 100
               :load_duration 10
               :prompt_eval_count 4
               :prompt_eval_duration 20
               :eval_count 2
               :eval_duration 30})
        events (transport/parse-stream-event t profile line)]
    (is (= [:stream/reasoning-delta
            :stream/content-delta
            :stream/usage
            :stream/end]
           (mapv :event/type events)))
    (is (= {:total_duration 100
            :load_duration 10
            :prompt_eval_count 4
            :prompt_eval_duration 20
            :eval_count 2
            :eval_duration 30}
           (get-in events [2 :usage :usage/provider-raw])))))

(deftest test-stream-tool-calls-preserve-each-index
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string
              {:model "llama3.1"
               :message {:role "assistant"
                         :tool_calls [{:id "native-a"
                                       :function {:index 2
                                                  :name "a"
                                                  :arguments {:x 1}}}
                                      {:id "native-b"
                                       :function {:index 5
                                                  :name "b"
                                                  :arguments {:y 2}}}]}
               :done false})
        events (transport/parse-stream-event t profile line)]
    (is (= [:stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-end
            :stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-end]
           (mapv :event/type events)))
    (is (= [2 2 2 5 5 5]
           (mapv :tool-call/index events)))
    (is (= ["native-a" "native-b"]
           (->> events
                (filter #(= :stream/tool-call-start (:event/type %)))
                (mapv :tool-call/id))))
    (is (= {:id "native-a"
            :function {:index 2 :name "a" :arguments {:x 1}}}
           (get-in events
                   [0 :tool-call/provider-data :ollama/tool-call])))))

(deftest test-embed-build-request
  (let [profile (provider/get-provider :ollama-native)
        ctor (:profile/embed-transport-constructor profile)
        t (ctor)
        built (et/build-embed-request
               t profile
               {:embed/model "nomic-embed-text"
                :embed/inputs ["hello" "world"]
                :embed/dimensions 128
                :embed/provider-options
                {:ollama {:truncate false
                          :keep_alive "10m"
                          :options {:num_ctx 2048}}}})]
    (is (.endsWith ^String (:url built) "/api/embed"))
    (is (= {:model "nomic-embed-text"
            :input ["hello" "world"]
            :dimensions 128
            :truncate false
            :keep_alive "10m"
            :options {:num_ctx 2048}}
           (:body built)))))

(deftest test-embed-parse-response
  (let [profile (provider/get-provider :ollama-native)
        ctor (:profile/embed-transport-constructor profile)
        t (ctor)
        raw {:model "nomic-embed-text"
             :embeddings [[0.1 0.2 0.3]
                          [0.4 0.5 0.6]]
             :total_duration 200
             :load_duration 25
             :prompt_eval_count 8}
        parsed (et/parse-embed-response t profile raw)]
    (is (= [[0.1 0.2 0.3] [0.4 0.5 0.6]]
           (:embed/vectors parsed)))
    (is (= 3 (:embed/dimensions parsed)))
    (is (= 8 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= {:prompt_eval_count 8
            :total_duration 200
            :load_duration 25}
           (get-in parsed [:response/usage :usage/provider-raw])))
    (is (= raw (:embed/raw parsed)))
    (is (schema/validate-embed-response parsed))))

(deftest test-embed-duration-only-usage-does-not-invent-token-counts
  (let [profile (provider/get-provider :ollama-native)
        t ((:profile/embed-transport-constructor profile))
        parsed (et/parse-embed-response
                t profile
                {:model "nomic-embed-text"
                 :embeddings [[0.1]]
                 :total_duration 200})
        usage (:response/usage parsed)]
    (is (= {:usage/request-count 1
            :usage/provider-raw {:total_duration 200}}
           usage))
    (is (not (contains? usage :usage/input-tokens)))
    (is (not (contains? usage :usage/output-tokens)))
    (is (not (contains? usage :usage/total-tokens)))))
