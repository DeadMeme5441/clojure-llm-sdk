(ns llm.sdk.providers.codex-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.codex :as codex]))

(defn- temp-auth-file []
  (let [dir (doto (java.io.File/createTempFile "codex-auth-test" "")
              (.delete)
              (.mkdirs))
        file (java.io.File. dir "auth.json")]
    [dir file]))

(defn- write-auth! [file access-token refresh-token]
  (spit file
        (json/generate-string
         {:auth_mode "chatgpt"
          :tokens {:access_token access-token
                   :refresh_token refresh-token}})))

(deftest test-build-request-basic
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "o3"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (= "https://api.openai.com/v1/responses" (:url built)))
    (is (= "o3" (get-in built [:body :model])))
    (is (= false (get-in built [:body :store])))
    (is (= "Sys" (get-in built [:body :instructions])))
    (is (sequential? (get-in built [:body :input])))
    (is (= "user" (:role (first (get-in built [:body :input])))))))

(deftest test-build-request-tools
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "o3"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/tools [{:type :function
                              :function {:name "get_weather"
                                         :description "Get weather"
                                         :parameters {:type :object
                                                      :properties {:location {:type :string}}}}}]}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :tools]))))
    (is (= "auto" (get-in built [:body :tool_choice])))
    (is (= true (get-in built [:body :parallel_tool_calls])))
    (is (= "get_weather" (get-in built [:body :tools 0 :name])))))

(deftest test-build-request-reasoning
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "o3"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/reasoning {:enabled true :effort :high}}
        built (transport/build-request t profile req)]
    (is (= "high" (get-in built [:body :reasoning :effort])))
    (is (= "auto" (get-in built [:body :reasoning :summary])))
    (is (= ["reasoning.encrypted_content"] (get-in built [:body :include])))))

(deftest test-codex-auth-file-is-cached-until-file-changes
  (let [[dir file] (temp-auth-file)
        path (.getPath file)
        original-slurp slurp
        reads (atom 0)]
    (try
      (reset! @#'codex/codex-auth-cache nil)
      (write-auth! file "tok-1" "ref-1")
      (with-redefs-fn {#'codex/codex-auth-file-path (constantly path)
                       #'clojure.core/slurp (fn [& args]
                                              (swap! reads inc)
                                              (apply original-slurp args))}
        (fn []
          (is (= "tok-1" (:access-token (codex/read-codex-auth))))
          (is (= "tok-1" (:access-token (codex/read-codex-auth))))
          (is (= 1 @reads) "stable auth file should only be read once")
          (write-auth! file "tok-2" "ref-2")
          (.setLastModified file (+ 5000 (.lastModified file)))
          (is (= "tok-2" (:access-token (codex/read-codex-auth))))
          (is (= 2 @reads) "auth file should be reread after mtime/length changes")))
      (finally
        (reset! @#'codex/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(deftest test-parse-response-text
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        raw {:id "resp_1"
             :model "o3"
             :output [{:type "message" :role "assistant" :status "completed"
                       :content [{:type "output_text" :text "Hello!"}]}]
             :status "completed"
             :usage {:input_tokens 10 :output_tokens 5}}
        resp (transport/parse-response t profile raw)]
    (is (= :codex (:response/provider resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello!"}] (:response/parts resp)))
    (is (= 10 (get-in resp [:response/usage :usage/input-tokens])))))

(deftest test-parse-response-reasoning
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        raw {:id "resp_2"
             :model "o3"
             :output [{:type "reasoning"
                       :encrypted_content "encrypted-thinking-blob"
                       :id "ri_1"}
                      {:type "message" :role "assistant" :status "completed"
                       :content [{:type "output_text" :text "Done!"}]}]
             :status "completed"
             :usage {:input_tokens 15 :output_tokens 8}}
        resp (transport/parse-response t profile raw)]
    (is (= 2 (count (:response/parts resp))))
    (is (= :text (:part/type (first (:response/parts resp)))))
    (is (= :reasoning (:part/type (second (:response/parts resp)))))
    (is (= "encrypted-thinking-blob" (:reasoning/text (second (:response/parts resp)))))
    (is (seq (get-in resp [:response/provider-data :codex_reasoning_items])))))

(deftest test-parse-response-tool-call
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        raw {:id "resp_3"
             :model "o3"
             :output [{:type "function_call"
                       :call_id "call_abc"
                       :id "fc_123"
                       :name "get_weather"
                       :arguments "{\"location\":\"NYC\"}"}]
             :status "completed"
             :usage {:input_tokens 20 :output_tokens 10}}
        resp (transport/parse-response t profile raw)]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather" (get-in resp [:response/tool-calls 0 :tool-call/name])))
    (is (= "{\"location\":\"NYC\"}" (get-in resp [:response/tool-calls 0 :tool-call/arguments])))
    (is (= "fc_123" (get-in resp [:response/tool-calls 0 :tool-call/provider-data :response_item_id])))))

(deftest test-parse-response-empty-output
  (testing "empty output with output_text synthesizes a message item"
    (let [t (codex/make-transport)
          profile (provider/get-provider :codex)
          raw {:id "resp_4"
               :model "o3"
               :output []
               :output_text "Fallback text"
               :status "completed"
               :usage {:input_tokens 10 :output_tokens 5}}
          resp (transport/parse-response t profile raw)]
      (is (= :stop (:response/finish-reason resp)))
      (is (= [{:part/type :text :text "Fallback text"}] (:response/parts resp))))))

(deftest test-parse-response-incomplete-function
  (testing "queued/in_progress function_call items are skipped"
    (let [t (codex/make-transport)
          profile (provider/get-provider :codex)
          raw {:id "resp_5"
               :model "o3"
               :output [{:type "function_call"
                         :call_id "call_1"
                         :name "get_weather"
                         :arguments "{}"
                         :status "in_progress"}
                        {:type "message" :role "assistant" :status "completed"
                         :content [{:type "output_text" :text "Done!"}]}]
               :status "completed"
               :usage {:input_tokens 10 :output_tokens 5}}
          resp (transport/parse-response t profile raw)]
      (is (= :stop (:response/finish-reason resp)))
      (is (empty? (:response/tool-calls resp)))
      (is (= 1 (count (:response/parts resp)))))))

(deftest test-parse-stream-content-delta
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        line "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello\"}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-parse-stream-reasoning-delta
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        line "data: {\"type\":\"response.reasoning.delta\",\"delta\":\"thinking...\"}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/reasoning-delta (:event/type ev)))
    (is (= "thinking..." (:event/delta ev)))
    (is (= true (:event/encrypted ev)))))

(deftest test-parse-stream-tool-call
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        start-line "data: {\"type\":\"response.output_item.added\",\"item\":{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"get_weather\"}}"
        delta-line "data: {\"type\":\"response.function_call_arguments.delta\",\"delta\":\"{\\\"loc\\\"\"}"
        end-line "data: {\"type\":\"response.function_call_arguments.done\"}"
        start-ev (transport/parse-stream-event t profile start-line)
        delta-ev (transport/parse-stream-event t profile delta-line)
        end-ev (transport/parse-stream-event t profile end-line)]
    (is (= :stream/tool-call-start (:event/type start-ev)))
    (is (= "call_1" (:tool-call/id start-ev)))
    (is (= "get_weather" (:tool-call/name start-ev)))
    (is (= :stream/tool-call-delta (:event/type delta-ev)))
    (is (= "{\"loc\"" (:tool-call/arguments-delta delta-ev)))
    (is (= :stream/tool-call-end (:event/type end-ev)))))

(deftest test-parse-stream-tool-call-index
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        start-line "data: {\"type\":\"response.output_item.added\",\"output_index\":2,\"item\":{\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"get_time\"}}"
        delta-line "data: {\"type\":\"response.function_call_arguments.delta\",\"output_index\":2,\"delta\":\"{}\"}"
        end-line "data: {\"type\":\"response.function_call_arguments.done\",\"output_index\":2}"
        start-ev (transport/parse-stream-event t profile start-line)
        delta-ev (transport/parse-stream-event t profile delta-line)
        end-ev (transport/parse-stream-event t profile end-line)]
    (is (= 2 (:tool-call/index start-ev)))
    (is (= 2 (:tool-call/index delta-ev)))
    (is (= 2 (:tool-call/index end-ev)))))

(deftest test-parse-stream-end
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        line "data: {\"type\":\"response.completed\"}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/end (:event/type ev)))
    (is (= :stop (:event/finish-reason ev)))))

(deftest test-deterministic-call-id
  (let [id1 (#'codex/deterministic-call-id "get_weather" "{}" 0)
        id2 (#'codex/deterministic-call-id "get_weather" "{}" 0)]
    (is (= id1 id2))
    (is (str/starts-with? id1 "call_"))))

(deftest test-derive-responses-function-call-id
  (is (= "fc_abc" (#'codex/derive-responses-function-call-id "call_abc" nil)))
  (is (= "fc_abc" (#'codex/derive-responses-function-call-id nil "fc_abc")))
  (is (= "fc_123" (#'codex/derive-responses-function-call-id "call_123" "other")))
  (is (str/starts-with? (#'codex/derive-responses-function-call-id nil nil) "fc_")))

;; ---------------------------------------------------------------------------
;; Caching wiring
;; ---------------------------------------------------------------------------

(deftest test-cache-prompt-cache-key-toplevel-on-openai
  (testing "standard OpenAI Responses gets top-level prompt_cache_key"
    (let [t (codex/make-transport)
          profile (provider/get-provider :codex)
          req {:request/model "o3"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "session-abc"}}
          built (transport/build-request t profile req)]
      (is (= "session-abc" (get-in built [:body :prompt_cache_key])))
      (is (nil? (get-in built [:body :extra_body :prompt_cache_key]))))))

(deftest test-cache-prompt-cache-key-xai-extra-body
  (testing "xAI host moves prompt_cache_key into extra_body + sets grok conv header"
    (let [t (codex/make-transport)
          ;; Synthesize an xAI Responses profile so we don't touch the registry.
          xai-profile {:profile/id :codex
                       :profile/protocol-family :codex
                       :profile/base-url "https://api.x.ai/v1"
                       :profile/auth-strategy :bearer
                       :profile/env-var-names []}
          req {:request/model "grok-4"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "conv-xai"}}
          built (transport/build-request t xai-profile req)]
      (is (nil? (get-in built [:body :prompt_cache_key])))
      (is (= "conv-xai" (get-in built [:body :extra_body :prompt_cache_key])))
      (is (= "conv-xai" (get-in built [:headers "x-grok-conv-id"]))))))

(deftest test-cache-no-cache-key-when-disabled
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "o3"
             :request/messages [{:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)]
    (is (nil? (get-in built [:body :prompt_cache_key])))))
