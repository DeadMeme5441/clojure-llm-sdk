(ns llm.sdk.providers.codex-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.codex.auth :as auth]
            [llm.sdk.providers.codex.responses :as codex]))

(defn- temp-auth-file []
  (let [dir (doto (java.io.File/createTempFile "codex-auth-test" "")
              (.delete)
              (.mkdirs))
        file (java.io.File. dir "auth.json")]
    [dir file]))

(defn- write-auth!
  ([file access-token refresh-token]
   (write-auth! file access-token refresh-token nil))
  ([file access-token refresh-token account-id]
   (spit file
         (json/generate-string
          {:auth_mode "chatgpt"
           :tokens (cond-> {:access_token access-token
                            :refresh_token refresh-token}
                     account-id (assoc :account_id account-id))}))))

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
    (is (= "user" (:role (first (get-in built [:body :input])))))
    (is (not (contains? (:body built) :stream)))))

(deftest test-build-request-standard-stream-flag
  (let [built (transport/build-request
               (codex/make-transport)
               (provider/get-provider :codex)
               {:request/model "o3"
                :request/messages [{:message/role :user :message/content "Hello"}]
                :request/stream? true})]
    (is (true? (get-in built [:body :stream])))
    (is (= "https://api.openai.com/v1/responses" (:url built)))))

(deftest test-build-request-current-responses-fields
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        built (transport/build-request
               t profile
               {:request/model "gpt-5"
                :request/messages [{:message/role :system :message/content "Sys"}
                                   {:message/role :developer :message/content "Dev"}
                                   {:message/role :user :message/content "Hi"}]
                :request/temperature 0.2
                :request/top-p 0.9
                :request/tool-choice :required
                :request/response-format
                {:type :json_schema
                 :name "answer"
                 :strict true
                 :json-schema {:type "object"}}
                :request/provider-options
                {:extra_body {:service_tier "flex"
                              :truncation "auto"}}})]
    (is (= "Sys" (get-in built [:body :instructions])))
    (is (= "developer" (get-in built [:body :input 0 :role])))
    (is (= "user" (get-in built [:body :input 1 :role])))
    (is (= 0.2 (get-in built [:body :temperature])))
    (is (= 0.9 (get-in built [:body :top_p])))
    (is (= {:format {:type "json_schema"
                     :name "answer"
                     :schema {:type "object"}
                     :strict true}}
           (get-in built [:body :text])))
    (is (= "flex" (get-in built [:body :service_tier])))
    (is (= "auto" (get-in built [:body :truncation])))))

(deftest test-build-request-preserves-assistant-phase
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        built (transport/build-request
               t profile
               {:request/model "gpt-5.3-codex"
                :request/messages
                [{:message/role :assistant
                  :message/content "Working..."
                  :message/phase :commentary}
                 {:message/role :user :message/content "Continue"}]})]
    (is (= "commentary" (get-in built [:body :input 0 :phase])))))

(deftest completed-assistant-message-replays-once-with-phase
  (let [profile (provider/get-provider :codex)
        item {:type "message" :id "msg_reply" :role "assistant"
              :status "completed" :phase "final_answer"
              :content [{:type "output_text" :text "Original" :annotations []}]}
        event (codex/parse-stream-event-codex
               profile
               (str "data: " (json/generate-string
                              {:type "response.output_item.done" :output_index 1 :item item})))
        provider-data {:codex (:provider-state/data event)}
        build (fn [text]
                (:input (:body (codex/build-request-codex
                                profile
                                {:request/model "gpt-6-astra"
                                 :request/messages
                                 [{:message/role :assistant :message/content text
                                   :message/provider-data provider-data}]}))))]
    (is (= [item] (build "Original"))
        "Preserved output metadata must not duplicate canonical assistant text")
    (is (= [{:type "message" :role "assistant" :status "completed"
             :content [{:type "output_text" :text "Edited"}]}]
           (build "Edited"))
        "Editing canonical content must not silently replay stale provider text")))

(deftest test-build-request-tools
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "o3"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/tools [{:type :function
                              :function {:name "get_weather"
                                         :description "Get weather"
                                         :strict true
                                         :parameters {:type :object
                                                      :properties {:location {:type :string}}}}}]}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :tools]))))
    (is (= "auto" (get-in built [:body :tool_choice])))
    (is (= true (get-in built [:body :parallel_tool_calls])))
    (is (= "get_weather" (get-in built [:body :tools 0 :name])))
    (is (true? (get-in built [:body :tools 0 :strict])))))

(deftest test-build-request-custom-tools-and-choice
  (let [built (transport/build-request
               (codex/make-transport)
               (provider/get-provider :codex)
               {:request/model "gpt-5"
                :request/messages [{:message/role :user
                                    :message/content "Run the parser"}]
                :request/tools
                [{:type :custom
                  :custom {:name "parser"
                           :description "Parse one expression"
                           :format {:type :grammar
                                    :grammar
                                    {:definition "start: WORD"
                                     :syntax :lark}}}}
                 {:type :custom
                  :custom {:name "shell"
                           :format {:type :text}}}]
                :request/tool-choice
                {:type :custom :custom {:name "parser"}}})
        tools (get-in built [:body :tools])]
    (is (= [{:type "custom"
             :name "parser"
             :description "Parse one expression"
             :format {:type "grammar"
                      :definition "start: WORD"
                      :syntax "lark"}}
            {:type "custom"
             :name "shell"
             :format {:type "text"}}]
           tools))
    (is (every? (comp seq :name) tools))
    (is (= {:type "custom" :name "parser"}
           (get-in built [:body :tool_choice])))))

(deftest test-build-request-file-input
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "gpt-5"
             :request/messages
             [{:message/role :user
               :message/content [{:part/type :text
                                  :text "Summarize this."}
                                 {:part/type :file
                                  :file/name "brief.pdf"
                                  :file/data "JVBERi0x"
                                  :file/mime-type "application/pdf"}]}]}
        built (transport/build-request t profile req)
        first-input (first (get-in built [:body :input]))
        content (:content first-input)]
    (is (= {:type "input_text" :text "Summarize this."}
           (first content)))
    (is (= {:type "input_file"
            :file_data "data:application/pdf;base64,JVBERi0x"
            :filename "brief.pdf"}
           (second content)))))

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
      (reset! @#'auth/codex-auth-cache nil)
      (write-auth! file "tok-1" "ref-1")
      (with-redefs-fn {#'auth/codex-auth-file-path (constantly path)
                       #'clojure.core/slurp (fn [& args]
                                              (swap! reads inc)
                                              (apply original-slurp args))}
        (fn []
          (is (= "tok-1" (:access-token (auth/read-codex-auth))))
          (is (= "tok-1" (:access-token (auth/read-codex-auth))))
          (is (= 1 @reads) "stable auth file should only be read once")
          (write-auth! file "tok-2" "ref-2")
          (.setLastModified file (+ 5000 (.lastModified file)))
          (is (= "tok-2" (:access-token (auth/read-codex-auth))))
          (is (= 2 @reads) "auth file should be reread after mtime/length changes")))
      (finally
        (reset! @#'auth/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(deftest test-codex-backend-auth-headers-include-account-id
  (let [[dir file] (temp-auth-file)
        path (.getPath file)]
    (try
      (reset! @#'auth/codex-auth-cache nil)
      (write-auth! file "tok-1" "ref-1" "acct-123")
      (with-redefs-fn {#'auth/codex-auth-file-path (constantly path)}
        (fn []
          (let [auth (auth/read-codex-auth)
                headers (auth/codex-backend-auth-headers)]
            (is (= "acct-123" (:account-id auth)))
            (is (= "Bearer tok-1" (get headers "Authorization")))
            (is (= "acct-123" (get headers "ChatGPT-Account-ID"))))))
      (finally
        (reset! @#'auth/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(deftest test-codex-auth-file-without-access-token-is-invalid
  (let [[dir file] (temp-auth-file)
        path (.getPath file)]
    (try
      (reset! @#'auth/codex-auth-cache nil)
      (spit file
            (json/generate-string
             {:auth_mode "chatgpt"
              :tokens {:refresh_token "ref-1"
                       :account_id "acct-123"}}))
      (with-redefs-fn {#'auth/codex-auth-file-path (constantly path)}
        (fn []
          (is (nil? (auth/read-codex-auth)))
          (is (nil? (auth/codex-backend-auth-headers)))
          (is (false? (auth/codex-backend-available?)))))
      (finally
        (reset! @#'auth/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(deftest test-build-request-codex-backend-requires-valid-oauth-file
  (let [[dir file] (temp-auth-file)
        path (.getPath file)
        t (codex/make-transport)
        profile (provider/get-provider :codex-backend)
        req {:request/model "gpt-5-codex"
             :request/messages [{:message/role :user
                                 :message/content "hi"}]}]
    (try
      (reset! @#'auth/codex-auth-cache nil)
      (spit file (json/generate-string {:auth_mode "chatgpt" :tokens {}}))
      (with-redefs-fn {#'auth/codex-auth-file-path (constantly path)}
        (fn []
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Codex backend OAuth credentials are unavailable"
               (transport/build-request t profile req)))))
      (finally
        (reset! @#'auth/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(deftest test-build-request-codex-backend-wire-auth-and-cache
  (let [[dir file] (temp-auth-file)
        path (.getPath file)
        t (codex/make-transport)
        profile (provider/get-provider :codex-backend)
        req {:request/model "gpt-5-codex"
             :request/messages [{:message/role :system
                                 :message/content "Sys"}
                                {:message/role :user
                                 :message/content "hi"}]
             :request/max-tokens 16
             :request/cache {:scope-id "session-123"}}]
    (try
      (reset! @#'auth/codex-auth-cache nil)
      (write-auth! file "tok-1" "ref-1" "acct-123")
      (with-redefs-fn {#'auth/codex-auth-file-path (constantly path)}
        (fn []
          (let [built (transport/build-request t profile req)]
            (is (= "https://chatgpt.com/backend-api/codex/responses" (:url built)))
            (is (= "Bearer tok-1" (get-in built [:headers "Authorization"])))
            (is (= "acct-123" (get-in built [:headers "ChatGPT-Account-ID"])))
            (is (= "text/event-stream" (get-in built [:headers "Accept"])))
            (is (= "codex_cli_rs" (get-in built [:headers "originator"])))
            (is (= "session-123" (get-in built [:headers "session-id"])))
            (is (= "session-123" (get-in built [:headers "x-client-request-id"])))
            (is (= "session-123" (get-in built [:body :prompt_cache_key])))
            (is (= true (get-in built [:body :stream])))
            (is (nil? (get-in built [:body :max_output_tokens]))
                "Codex backend rejects max_output_tokens; transport must suppress it")
            (is (= "Sys" (get-in built [:body :instructions]))))))
      (finally
        (reset! @#'auth/codex-auth-cache nil)
        (.delete file)
        (.delete dir)))))

(deftest test-build-request-codex-backend-default-instructions-keep-user-input
  (with-redefs [auth/request-auth
                (fn [_] {:headers {"Authorization" "Bearer test-token"
                                   "ChatGPT-Account-ID" "acct-123"}})]
    (let [built (transport/build-request
                 (codex/make-transport)
                 (provider/get-provider :codex-backend)
                 {:request/model "gpt-5.5"
                  :request/messages [{:message/role :user
                                      :message/content "hi"}]})]
      (is (= "You are a helpful assistant."
             (get-in built [:body :instructions])))
      (is (= "user" (get-in built [:body :input 0 :role])))
      (is (= "hi" (get-in built [:body :input 0 :content 0 :text]))))))

(deftest test-build-request-codex-backend-replays-streamed-reasoning
  (with-redefs [auth/request-auth
                (fn [_] {:headers {"Authorization" "Bearer test-token"
                                   "ChatGPT-Account-ID" "acct-123"}})]
    (let [reasoning-item {:type "reasoning"
                          :id "reasoning-item-1"
                          :encrypted_content "encrypted-thinking"}
          built (transport/build-request
                 (codex/make-transport)
                 (provider/get-provider :codex-backend)
                 {:request/model "gpt-5.5"
                  :request/messages
                  [{:message/role :assistant
                    :message/provider-data
                    {:codex-backend
                     {:codex_reasoning_items {0 reasoning-item}}}}
                   {:message/role :user
                    :message/content "Continue."}]})]
      (is (= reasoning-item
             (get-in built [:body :input 0])))
      (is (= "user" (get-in built [:body :input 1 :role]))))))

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
    (is (= :reasoning (:part/type (first (:response/parts resp)))))
    (is (= :text (:part/type (second (:response/parts resp)))))
    (is (= "encrypted-thinking-blob"
           (:reasoning/text (first (:response/parts resp)))))
    (is (seq (get-in resp [:response/provider-data :codex_reasoning_items])))))

(deftest test-parse-and-replay-response-function-call
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
        resp (transport/parse-response t profile raw)
        call (first (:response/tool-calls resp))
        replay (get-in
                (transport/build-request
                 t profile
                 {:request/model "o3"
                  :request/messages
                  [{:message/role :assistant
                    :message/tool-calls [call]}
                   {:message/role :tool
                    :message/tool-call-id "call_abc"
                    :message/content "sunny"}]})
                [:body :input])]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather" (:tool-call/name call)))
    (is (= "{\"location\":\"NYC\"}" (:tool-call/arguments call)))
    (is (= "fc_123"
           (get-in call [:tool-call/provider-data :response_item_id])))
    (is (= [{:type "function_call"
             :id "fc_123"
             :call_id "call_abc"
             :name "get_weather"
             :arguments "{\"location\":\"NYC\"}"}
            {:type "function_call_output"
             :call_id "call_abc"
             :output "sunny"}]
           replay))))

(deftest test-custom-tool-full-roundtrip-preserves-kind-order-and-ids
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        reasoning {:type "reasoning"
                   :id "rs_1"
                   :summary [{:type "summary_text"
                              :text "Checked the constraints."}]
                   :encrypted_content "opaque-reasoning"}
        message {:type "message"
                 :id "msg_1"
                 :role "assistant"
                 :status "completed"
                 :phase "commentary"
                 :content [{:type "output_text" :text "Running it."}]}
        custom-call {:type "custom_tool_call"
                     :id "ct_1"
                     :call_id "call_custom"
                     :name "shell"
                     :input "pwd"
                     :status "completed"}
        raw {:id "resp-current"
             :model "gpt-5"
             :output [reasoning message custom-call]
             :status "completed"
             :incomplete_details {:reason nil}
             :service_tier "flex"}
        resp (transport/parse-response t profile raw)
        call (first (:response/tool-calls resp))
        assistant {:message/role :assistant
                   :message/phase :commentary
                   :message/content
                   [{:part/type :text :text "Running it."} call]
                   ;; Some consumers expose calls in both canonical locations.
                   :message/tool-calls [call]
                   :message/provider-data
                   {:codex (:response/provider-data resp)}}
        replay (get-in
                (transport/build-request
                 t profile
                 {:request/model "gpt-5"
                  :request/messages
                  [assistant
                   {:message/role :tool
                    :message/tool-call-id "call_custom"
                    :message/name "shell"
                    :message/content "project-root"}]})
                [:body :input])]
    (is (= [:reasoning :text :tool-call]
           (mapv :part/type (:response/parts resp))))
    (is (= "shell" (:tool-call/name call)))
    (is (= "pwd" (:tool-call/arguments call)))
    (is (= "custom_tool_call"
           (get-in call [:tool-call/provider-data :wire_type])))
    (is (= "flex" (get-in resp [:response/provider-data :service_tier])))
    (is (= [reasoning
            message
            {:type "custom_tool_call"
             :id "ct_1"
             :call_id "call_custom"
             :name "shell"
             :input "pwd"}
            {:type "custom_tool_call_output"
             :call_id "call_custom"
             :output "project-root"}]
           replay))
    (is (= 1 (count (filter #(= "custom_tool_call" (:type %)) replay)))
        "A call supplied in content and message/tool-calls replays once")))

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

(deftest test-parse-sse-response-completed-usage
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex-backend)
        raw (str "data: "
                 (json/generate-string
                  {:type "response.created"
                   :response {:id "resp_sse" :model "gpt-5.5"}})
                 "\n\n"
                 "data: "
                 (json/generate-string
                  {:type "response.output_text.delta"
                   :delta "pong"})
                 "\n\n"
                 "data: "
                 (json/generate-string
                  {:type "response.completed"
                   :response {:id "resp_sse"
                              :model "gpt-5.5"
                              :usage {:input_tokens 23
                                      :input_tokens_details {:cached_tokens 3}
                                      :output_tokens 16
                                      :output_tokens_details {:reasoning_tokens 9}
                                      :total_tokens 39}}})
                 "\n\n")
        resp (transport/parse-response t profile raw)
        usage (:response/usage resp)]
    (is (= "resp_sse" (:response/id resp)))
    (is (= :codex-backend (:response/provider resp)))
    (is (= "gpt-5.5" (:response/model resp)))
    (is (= [{:part/type :text :text "pong"}] (:response/parts resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= 20 (:usage/input-tokens usage)))
    (is (= 3 (:usage/cached-input-tokens usage)))
    (is (= 16 (:usage/output-tokens usage)))
    (is (= 9 (:usage/reasoning-tokens usage)))
    (is (= 39 (:usage/total-tokens usage)))))

(deftest failed-responses-propagate-with-partial-output
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        json-failure
        {:id "resp_failed"
         :model "gpt-5"
         :status "failed"
         :output [{:type "message"
                   :role "assistant"
                   :status "incomplete"
                   :content [{:type "output_text" :text "partial"}]}]
         :error {:code "server_error" :message "generation failed"}}
        sse-failure
        (str "data: "
             (json/generate-string
              {:type "response.output_text.delta" :delta "partial"})
             "\n\ndata: "
             (json/generate-string
              {:type "response.failed"
               :response {:id "resp_failed"
                          :model "gpt-5"
                          :status "failed"
                          :usage {:input_tokens 3 :output_tokens 1}}})
             "\n\n")]
    (doseq [raw [json-failure sse-failure]]
      (let [failure (try
                      (transport/parse-response t profile raw)
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
        (is (some? failure))
        (is (= "partial"
               (get-in (ex-data failure)
                       [:partial-response :response/parts 0 :text])))))))

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

(deftest test-parse-stream-current-reasoning-summary-delta
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        line "data: {\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"checking\"}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/reasoning-delta (:event/type ev)))
    (is (= "checking" (:event/delta ev)))
    (is (false? (:event/encrypted ev)))))

(deftest test-parse-stream-preserves-unmapped-current-event
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        line "data: {\"type\":\"response.output_text.annotation.added\",\"annotation\":{\"type\":\"url_citation\"}}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/provider-state (:event/type ev)))
    (is (= "response.output_text.annotation.added"
           (get-in ev [:provider-state/data :responses/event :type])))))

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

(deftest test-parse-stream-current-custom-tool-call
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        start (transport/parse-stream-event
               t profile
               "data: {\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"type\":\"custom_tool_call\",\"id\":\"ct_2\",\"call_id\":\"call_2\",\"name\":\"shell\"}}")
        delta (transport/parse-stream-event
               t profile
               "data: {\"type\":\"response.custom_tool_call_input.delta\",\"output_index\":1,\"delta\":\"pwd\"}")
        done (transport/parse-stream-event
              t profile
              "data: {\"type\":\"response.custom_tool_call_input.done\",\"output_index\":1,\"input\":\"pwd\"}")
        full (transport/parse-response
              t profile
              {:id "resp_2"
               :model "gpt-5"
               :status "completed"
               :output [{:type "message"
                         :id "msg_2"
                         :role "assistant"
                         :status "completed"
                         :content []}
                        {:type "custom_tool_call"
                         :id "ct_2"
                         :call_id "call_2"
                         :name "shell"
                         :input "pwd"}]})
        full-metadata (get-in full
                              [:response/tool-calls 0
                               :tool-call/provider-data])]
    (is (= :stream/tool-call-start (:event/type start)))
    (is (= "shell" (:tool-call/name start)))
    (is (= full-metadata (:tool-call/provider-data start)))
    (is (= "custom_tool_call"
           (get-in start [:tool-call/provider-data :wire_type])))
    (is (= "pwd" (:tool-call/arguments-delta delta)))
    (is (= :stream/tool-call-end (:event/type done)))))

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

(deftest test-parse-stream-preserves-encrypted-reasoning-for-replay
  (let [event (transport/parse-stream-event
               (codex/make-transport)
               (provider/get-provider :codex-backend)
               (str "data: "
                    (json/generate-string
                     {:type "response.output_item.done"
                      :output_index 0
                      :item {:type "reasoning"
                             :id "reasoning-item-1"
                             :encrypted_content "encrypted-thinking"}})))]
    (is (= :stream/provider-state (:event/type event)))
    (is (= "encrypted-thinking"
           (get-in event
                   [:provider-state/data
                    :codex_reasoning_items
                    0
                    :encrypted_content])))))

(deftest test-parse-stream-end
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        line "data: {\"type\":\"response.completed\"}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/end (:event/type ev)))
    (is (= :stop (:event/finish-reason ev)))))

(deftest test-parse-stream-completed-emits-usage-before-end
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex-backend)
        line (str "data: "
                  (json/generate-string
                   {:type "response.completed"
                    :response {:usage {:input_tokens 23
                                       :input_tokens_details {:cached_tokens 3}
                                       :output_tokens 16
                                       :output_tokens_details {:reasoning_tokens 9}
                                       :total_tokens 39}}}))
        events (transport/parse-stream-event t profile line)]
    (is (= [:stream/usage :stream/end] (mapv :event/type events)))
    (is (= 20 (get-in (first events) [:usage :usage/input-tokens])))
    (is (= 3 (get-in (first events) [:usage :usage/cached-input-tokens])))
    (is (= 16 (get-in (first events) [:usage :usage/output-tokens])))
    (is (= 9 (get-in (first events) [:usage :usage/reasoning-tokens])))
    (is (= :stop (:event/finish-reason (second events))))))

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

(deftest test-codex-host-detection-does-not-match-lookalikes
  (let [t (codex/make-transport)
        profile (assoc (provider/get-provider :codex)
                       :profile/base-url "https://chatgpt.com.evil.test/backend-api/codex")
        built (transport/build-request
               t profile
               {:request/model "gpt-5-codex"
                :request/messages [{:message/role :user
                                    :message/content "hi"}]
                :request/max-tokens 16})]
    (is (= "https://chatgpt.com.evil.test/backend-api/codex/responses" (:url built)))
    (is (nil? (get-in built [:body :stream])))
    (is (= 16 (get-in built [:body :max_output_tokens])))))

(deftest test-cache-no-cache-key-when-disabled
  (let [t (codex/make-transport)
        profile (provider/get-provider :codex)
        req {:request/model "o3"
             :request/messages [{:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)]
    (is (nil? (get-in built [:body :prompt_cache_key])))))

(deftest typed-tool-results-preserve-function-and-custom-wire-kinds
  (doseq [custom? [false true]]
    (let [call {:part/type :tool-call :tool-call/id "call_probe"
                :tool-call/name "probe" :tool-call/arguments "{}"
                :tool-call/provider-data {:wire_type (if custom? "custom_tool_call" "function_call")}}
          built (transport/build-request
                 (codex/make-transport)
                 (assoc (provider/get-provider :codex-backend)
                        :profile/auth-token "synthetic-token")
                 {:request/model "gpt-5.6-luna"
                  :request/messages
                  [{:message/role :assistant :message/tool-calls [call]}
                   {:message/role :tool
                    :message/content [{:part/type :tool-result
                                       :tool-result/id "call_probe"
                                       :tool-result/name "probe"
                                       :tool-result/content "42"}]}]})]
      (is (= {:type (if custom? "custom_tool_call_output" "function_call_output")
              :call_id "call_probe" :output "42"}
             (last (get-in built [:body :input])))))))

(deftest codex-protects-canonical-routing-and-generation-fields
  (doseq [field [:model "input" :store "stream"]]
    (let [error (try
                  (transport/build-request
                   (codex/make-transport)
                   (assoc (provider/get-provider :codex-backend)
                          :profile/auth-token "synthetic-token")
                   {:request/model "gpt-5.6-luna"
                    :request/messages [{:message/role :user :message/content "probe"}]
                    :request/provider-options {:extra_body {field false}}})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= {:provider :codex-backend
              :field (keyword field)
              :error/type :request/protected-extra-body-override}
             error)))))

(deftest codex-inline-image-data-and-default-reasoning-remain-replayable
  (let [built (transport/build-request
               (codex/make-transport)
               (assoc (provider/get-provider :codex-backend)
                      :profile/auth-token "synthetic-token")
               {:request/model "gpt-5.6-luna"
                :request/messages [{:message/role :user
                                    :message/content [{:part/type :image
                                                       :image/data "aW1hZ2U="
                                                       :image/mime-type "image/png"}]}]
                :request/cache {:scope-id "synthetic-session"}})]
    (is (= "data:image/png;base64,aW1hZ2U="
           (get-in built [:body :input 0 :content 0 :image_url])))
    (is (= ["reasoning.encrypted_content"] (get-in built [:body :include])))
    (is (= "synthetic-session" (get-in built [:headers "session-id"])))
    (is (= "synthetic-session" (get-in built [:headers "thread-id"])))))
