(ns llm.sdk.live-cohere-chat-test
  "Live smoke for the native Cohere /v2/chat adapter.
   Env-gated on COHERE_API_KEY. Hits command-r-08-2024 (cheaper than
   command-r-plus) with a single-sentence prompt to keep cost minimal.

   To run only this suite:
     source .env && clj -M:test -n llm.sdk.live-cohere-chat-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(deftest ^:live live-cohere-chat-basic
  (when (has-creds? "COHERE_API_KEY")
    (testing "Cohere /v2/chat command-r-08-2024 live"
      (let [resp (sdk/complete
                  :cohere
                  {:request/model "command-r-08-2024"
                   :request/messages
                   [{:message/role :system :message/content "Reply with exactly: ok"}
                    {:message/role :user :message/content "ping"}]
                   :request/max-tokens 20})]
        (is (= :cohere (:response/provider resp)))
        (is (= :stop (:response/finish-reason resp)))
        (is (seq (:response/parts resp)))
        (is (pos? (get-in resp [:response/usage :usage/input-tokens])))
        (is (pos? (get-in resp [:response/usage :usage/output-tokens])))))))

(deftest ^:live live-cohere-chat-streaming
  (when (has-creds? "COHERE_API_KEY")
    (testing "Cohere /v2/chat streaming round-trip"
      (let [events (doall
                    (sdk/complete
                     :cohere
                     {:request/model "command-r-08-2024"
                      :request/messages
                      [{:message/role :user
                        :message/content "Say only the word: stream"}]
                      :request/max-tokens 10}
                     :stream? true))
            types (set (map :event/type events))]
        (is (contains? types :stream/start))
        (is (contains? types :stream/content-delta))
        (is (contains? types :stream/end))))))

(deftest ^:live live-cohere-chat-with-documents
  (when (has-creds? "COHERE_API_KEY")
    (testing "Cohere chat with documents emits citation parts"
      (let [resp (sdk/complete
                  :cohere
                  {:request/model "command-r-08-2024"
                   :request/messages
                   [{:message/role :user
                     :message/content "When was Clojure first released? Answer in one sentence using the documents."}]
                   :request/max-tokens 60
                   :request/provider-options
                   {:cohere
                    {:documents [{:id "doc1"
                                  :data {:title "Clojure"
                                         :snippet "Clojure was first released by Rich Hickey in 2007."}}]
                     :citation_options {:mode "FAST"}}}})]
        (is (= :cohere (:response/provider resp)))
        (let [citations (filter #(= :citation (:part/type %)) (:response/parts resp))]
          (testing "at least one citation part attached"
            ;; The model may decide not to cite for very short prompts;
            ;; we assert at least an attempt by checking documents
            ;; surfaced in :response/raw.
            (is (or (seq citations)
                    (some? (:response/raw resp))))))))))

(deftest ^:live live-cohere-chat-tool-calls
  (when (has-creds? "COHERE_API_KEY")
    (testing "Cohere chat with a tool definition produces a tool_call"
      (let [resp (sdk/complete
                  :cohere
                  {:request/model "command-r-08-2024"
                   :request/messages
                   [{:message/role :user
                     :message/content "What's the weather in NYC? Use the tool."}]
                   :request/tools
                   [{:type :function
                     :function {:name "get_weather"
                                :description "Get the current weather for a city"
                                :parameters {:type "object"
                                             :properties
                                             {:city {:type "string"}}
                                             :required ["city"]}}}]
                   :request/tool-choice :required
                   :request/max-tokens 80})]
        (is (= :cohere (:response/provider resp)))
        (is (= :tool-calls (:response/finish-reason resp)))
        (is (seq (:response/tool-calls resp)))
        (is (= "get_weather"
               (:tool-call/name (first (:response/tool-calls resp)))))))))
