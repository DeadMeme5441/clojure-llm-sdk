(ns llm.sdk.providers.ollama-native-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.providers.ollama-native :as ollama]))

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
    (is (= 0.5 (get-in built [:body :options :temperature])))))

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
                                      :image/url "base64bytes"}]}]})
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

(deftest test-parse-response
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        raw {:model "llama3.1"
             :message {:role "assistant" :content "Hello there."}
             :done true
             :done_reason "stop"
             :prompt_eval_count 12
             :eval_count 3}
        parsed (transport/parse-response t profile raw)]
    (is (= :stop (:response/finish-reason parsed)))
    (is (= "Hello there." (get-in parsed [:response/parts 0 :text])))
    (is (= 12 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 3 (get-in parsed [:response/usage :usage/output-tokens])))))

(deftest test-stream-line-content
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string {:model "llama3.1"
                                    :message {:role "assistant" :content "hi"}
                                    :done false})
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "hi" (:event/delta ev)))))

(deftest test-stream-final-line-emits-usage-and-end
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string {:model "llama3.1"
                                    :message {:role "assistant" :content ""}
                                    :done true
                                    :done_reason "stop"
                                    :prompt_eval_count 10
                                    :eval_count 5})
        evs (transport/parse-stream-event t profile line)]
    (is (sequential? evs))
    (is (= :stream/usage (:event/type (first evs))))
    (is (= :stream/end (:event/type (last evs))))
    (is (= :stop (:event/finish-reason (last evs))))))

(deftest test-stream-tool-calls-preserve-each-index
  (let [t (ollama/make-transport)
        profile (provider/get-provider :ollama-native)
        line (json/generate-string
              {:model "llama3.1"
               :message {:role "assistant"
                         :tool_calls [{:id "call_1"
                                       :function {:name "a" :arguments {:x 1}}}
                                      {:id "call_2"
                                       :function {:name "b" :arguments {:y 2}}}]}
               :done false})
        events (transport/parse-stream-event t profile line)]
    (is (= [:stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-end
            :stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-end]
           (mapv :event/type events)))
    (is (= [0 0 0 1 1 1]
           (mapv :tool-call/index events)))))

(deftest test-embed-build-request
  (let [profile (provider/get-provider :ollama-native)
        ctor (:profile/embed-transport-constructor profile)
        t (ctor)
        built (et/build-embed-request t profile
                                       {:embed/model "nomic-embed-text"
                                        :embed/inputs ["hello" "world"]})]
    (is (.endsWith ^String (:url built) "/api/embed"))
    (is (= "nomic-embed-text" (get-in built [:body :model])))
    (is (= ["hello" "world"] (get-in built [:body :input])))))

(deftest test-embed-parse-response
  (let [profile (provider/get-provider :ollama-native)
        ctor (:profile/embed-transport-constructor profile)
        t (ctor)
        raw {:model "nomic-embed-text"
             :embeddings [[0.1 0.2 0.3]
                          [0.4 0.5 0.6]]}
        parsed (et/parse-embed-response t profile raw)]
    (is (= 2 (count (:embed/vectors parsed))))
    (is (= [0.1 0.2 0.3] (:embed/vector (first (:embed/vectors parsed)))))))
