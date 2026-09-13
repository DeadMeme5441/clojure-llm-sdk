(ns llm.sdk.providers.openai-chat-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.openai.chat :as openai]))

(deftest test-build-request-basic
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]
             :request/temperature 0.5
             :request/max-tokens 100}
        built (transport/build-request t profile req)]
    (is (= "https://api.openai.com/v1/chat/completions" (:url built)))
    (is (= "gpt-4o" (get-in built [:body :model])))
    (is (= 0.5 (get-in built [:body :temperature])))
    (is (= 100 (get-in built [:body :max_completion_tokens])))
    (is (= 2 (count (get-in built [:body :messages]))))))

(deftest test-alias-profile-preserves-query-auth-parameter
  (let [profile (openai/build-alias-profile
                 {:id :query-auth
                  :base-url "https://example.test/v1"
                  :auth-strategy :api-key-query
                  :auth-query-param "key"
                  :env-var-names ["QUERY_AUTH_KEY"]})]
    (is (= "key" (:profile/auth-query-param profile)))))

(deftest test-build-request-stream-flag
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        request {:request/model "gpt-4o"
                 :request/messages [{:message/role :user :message/content "Hello"}]}
        non-stream (transport/build-request t profile request)
        stream (transport/build-request t profile
                                        (assoc request :request/stream? true))]
    (is (not (contains? (:body non-stream) :stream)))
    (is (true? (get-in stream [:body :stream])))
    (is (= {:include_usage true}
           (get-in stream [:body :stream_options])))))

(deftest test-openai-compatible-alias-wire-contracts
  (let [t (openai/make-transport)
        groq (provider/get-provider :groq)
        groq-built
        (transport/build-request
         t groq
         {:request/model "openai/gpt-oss-20b"
          :request/messages [{:message/role :user :message/content "Think"}]
          :request/max-tokens 256
          :request/reasoning {:enabled true :effort :high}
          :request/provider-options
          {:extra_body {:service_tier "auto"
                        :logprobs true
                        :logit_bias {"1" 1}
                        :top_logprobs 3}}})
        xai-built
        (transport/build-request
         t (provider/get-provider :xai)
         {:request/model "grok-4"
          :request/messages [{:message/role :user :message/content "Think"}]
          :request/reasoning {:enabled true :effort :low}
          :request/max-tokens 512
          :request/stream? true
          :request/provider-options {:extra_body {:custom_option true}}})]
    (testing "verified alias metadata"
      (is (= "https://api.deepseek.com/v1"
             (:profile/base-url (provider/get-provider :deepseek))))
      (is (contains? (:profile/capabilities (provider/get-provider :kimi))
                     :json-schema))
      (is (nil? (provider/get-provider :lambda)))
      (is (false? (:profile/supports-model-listing
                   (provider/get-provider :volcengine)))))
    (testing "Groq GPT-OSS fields are top-level and invalid fields are absent"
      (is (= 256 (get-in groq-built [:body :max_completion_tokens])))
      (is (= "high" (get-in groq-built [:body :reasoning_effort])))
      (is (not (contains? (:body groq-built) :reasoning_format)))
      (is (= "auto" (get-in groq-built [:body :service_tier])))
      (is (not (contains? (:body groq-built) :extra_body)))
      (is (not (contains? (:body groq-built) :max_tokens))))
    (testing "xAI reasoning and documented native options stay top-level"
      (is (= "low" (get-in xai-built [:body :reasoning_effort])))
      (is (= 512 (get-in xai-built [:body :max_completion_tokens])))
      (is (not (contains? (:body xai-built) :max_tokens)))
      (is (= {:include_usage true}
             (get-in xai-built [:body :stream_options])))
      (is (true? (get-in xai-built [:body :custom_option])))
      (is (not (contains? (:body xai-built) :extra_body))))))

(deftest test-groq-include-reasoning-is-mutually-exclusive-with-format
  (let [built (transport/build-request
               (openai/make-transport)
               (provider/get-provider :groq)
               {:request/model "openai/gpt-oss-20b"
                :request/messages [{:message/role :user :message/content "Think"}]
                :request/reasoning {:enabled true :effort :medium :exclude false}})]
    (is (true? (get-in built [:body :include_reasoning])))
    (is (= "medium" (get-in built [:body :reasoning_effort])))
    (is (not (contains? (:body built) :reasoning_format)))))

(deftest test-groq-raw-reasoning-is-removed-for-tools-and-json
  (let [t (openai/make-transport)
        profile (provider/get-provider :groq)
        base {:request/model "qwen/qwen3.8-27b"
              :request/messages
              [{:message/role :user :message/content "Think"}]
              :request/reasoning {:enabled true :effort :high}}
        tool-request
        (assoc base :request/tools
               [{:type :function
                 :function {:name "lookup"
                            :parameters {:type "object"}}}])
        json-request
        (assoc base :request/response-format {:type :json_object})
        conflicting-request
        (assoc base :request/provider-options
               {:extra_body {"reasoning_format" "raw"
                             :include_reasoning true}})]
    (doseq [request [tool-request json-request]]
      (let [body (:body (transport/build-request t profile request))]
        (is (not (contains? body :reasoning_format)))
        (is (not (contains? body "reasoning_format")))))
    (let [error (try (transport/build-request t profile conflicting-request)
                     nil
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= {:provider :groq
              :field :reasoning_format
              :error/type :request/protected-extra-body-override}
             error)))))

(deftest test-build-request-tools
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/tools [{:type :function
                              :function {:name "get_weather"
                                         :description "Get weather"
                                         :parameters {:type :object
                                                      :properties {:location {:type :string}}}}}]}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :tools]))))))

(deftest test-build-request-custom-tool-definition-and-replay
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        call {:part/type :tool-call
              :tool-call/id "call_custom"
              :tool-call/name "shell"
              :tool-call/arguments "pwd"
              :tool-call/provider-data
              {:wire_type "custom"
               :custom {:name "shell" :input "stale"}
               :extra_content {:trace_id "trace-1"}}}
        built
        (transport/build-request
         t profile
         {:request/model "gpt-5"
          :request/messages
          [{:message/role :assistant
            :message/content [{:part/type :text :text "Checking"}
                              call]
            :message/tool-calls [call]}]
          :request/tools
          [{:type :custom
            :custom
            {:name "shell"
             :description "Run a shell command"
             :format {:type :grammar
                      :grammar {:definition "start: /.+/"
                                :syntax :lark}}}}]
          :request/tool-choice {:type :custom
                                :custom {:name "shell"}}})
        definition (get-in built [:body :tools 0])
        replay (get-in built [:body :messages 0 :tool_calls])]
    (is (= "custom" (:type definition)))
    (is (= "grammar" (get-in definition [:custom :format :type])))
    (is (= "lark" (get-in definition
                          [:custom :format :grammar :syntax])))
    (is (= {:type "custom" :custom {:name "shell"}}
           (get-in built [:body :tool_choice])))
    (is (= [{:id "call_custom"
             :type "custom"
             :extra_content {:trace_id "trace-1"}
             :custom {:name "shell" :input "pwd"}}]
           replay))))

(deftest test-build-request-json-schema-response-format
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o-mini"
             :request/messages [{:message/role :user
                                 :message/content "Return JSON."}]
             :request/response-format
             {:type :json_schema
              :name "ok_response"
              :description "Boolean ok envelope"
              :strict true
              :json-schema {:type "object"
                            :properties {:ok {:type "boolean"}}
                            :required ["ok"]
                            :additionalProperties false}}}
        built (transport/build-request t profile req)]
    (is (= {:type "json_schema"
            :json_schema {:name "ok_response"
                          :description "Boolean ok envelope"
                          :strict true
                          :schema {:type "object"
                                   :properties {:ok {:type "boolean"}}
                                   :required ["ok"]
                                   :additionalProperties false}}}
           (get-in built [:body :response_format])))))

(deftest test-build-request-json-schema-response-format-default-name
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o-mini"
             :request/messages [{:message/role :user :message/content "Return JSON."}]
             :request/response-format {:type :json_schema
                                       :json-schema {:type "object"}}}
        built (transport/build-request t profile req)]
    (is (= "response"
           (get-in built [:body :response_format :json_schema :name])))))

(deftest test-build-request-developer-role
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-5-codex"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (= "developer" (get-in built [:body :messages 0 :role])))))

(deftest test-build-request-current-openai-fields
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        built (transport/build-request
               t profile
               {:request/model "gpt-5"
                :request/messages [{:message/role :user :message/content "Hi"}]
                :request/reasoning {:enabled true :effort :high}
                :request/metadata {:trace "abc"}
                :request/provider-options
                {:extra_body {:verbosity "low"
                              :service_tier "flex"}}})]
    (is (= "high" (get-in built [:body :reasoning_effort])))
    (is (= {:trace "abc"} (get-in built [:body :metadata])))
    (is (= "low" (get-in built [:body :verbosity])))
    (is (= "flex" (get-in built [:body :service_tier])))
    (is (nil? (get-in built [:body :extra_body])))))

(deftest test-provider-extra-rejects-protected-fields-and-stays-flat
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        base-request
        {:request/model "gpt-5"
         :request/messages
         [{:message/role :user :message/content "Canonical"}]
         :request/stream? true}]
    (doseq [[spelling expected-field provided]
            [[:model :model "bad-keyword"]
             ["model" :model "bad-string"]
             [:messages :messages [{:role "user" :content "bad"}]]
             ["messages" :messages []]
             [:stream :stream false]
             ["stream" :stream false]]]
      (let [error
            (try
              (transport/build-request
               t profile
               (assoc-in base-request
                         [:request/provider-options :extra_body]
                         {spelling provided}))
              nil
              (catch clojure.lang.ExceptionInfo e e))]
        (is (= :request/protected-extra-body-override
               (:error/type (ex-data error))))
        (is (= expected-field (:field (ex-data error))))
        (is (= :openai (:provider (ex-data error))))))
    (let [body
          (:body
           (transport/build-request
            t profile
            (assoc base-request
                   :request/provider-options
                   {:extra_body {"service_tier" "flex"
                                 :verbosity "low"}})))]
      (is (= "flex" (:service_tier body)))
      (is (= "low" (:verbosity body)))
      (is (not (contains? body :extra_body))))))

(deftest test-build-request-file-attachment
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages
             [{:message/role :user
               :message/content [{:part/type :file
                                  :file/name "brief.pdf"
                                  :file/data "JVBERi0x"
                                  :file/mime-type "application/pdf"}]}]}
        built (transport/build-request t profile req)]
    (is (= {:type "file"
            :file {:filename "brief.pdf"
                   :file_data "data:application/pdf;base64,JVBERi0x"}}
           (get-in built [:body :messages 0 :content 0])))))

(deftest test-build-request-inline-image-and-typed-tool-result
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        built
        (transport/build-request
         t profile
         {:request/model "gpt-4o"
          :request/messages
          [{:message/role :user
            :message/content
            [{:part/type :image
              :image/data "aW1hZ2U="
              :image/mime-type "image/webp"}]}
           {:message/role :tool
            :message/content
            [{:part/type :tool-result
              :tool-result/id "call_1"
              :tool-result/name "lookup"
              :tool-result/content "{\"ok\":true}"}]}]})]
    (is (= "data:image/webp;base64,aW1hZ2U="
           (get-in built [:body :messages 0 :content 0 :image_url :url])))
    (is (= {:role "tool"
            :content "{\"ok\":true}"
            :tool_call_id "call_1"
            :name "lookup"}
           (get-in built [:body :messages 1]))))
  (testing "OpenAI rejects error status because chat-completions has no such field"
    (let [t (openai/make-transport)
          profile (provider/get-provider :openai)]
      (try
        (transport/build-request
         t profile
         {:request/model "gpt-4o"
          :request/messages
          [{:message/role :tool
            :message/content
            [{:part/type :tool-result
              :tool-result/id "call_1"
              :tool-result/name "lookup"
              :tool-result/content "failed"
              :tool-result/is-error true}]}]})
        (is false "expected unsupported error-status rejection")
        (catch clojure.lang.ExceptionInfo e
          (is (= :provider/unsupported-tool-result-error
                 (:error/type (ex-data e)))))))))

(deftest test-build-request-rejects-image-without-source
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)]
    (try
      (transport/build-request
       t profile
       {:request/model "gpt-4o"
        :request/messages
        [{:message/role :user
          :message/content [{:part/type :image}]}]})
      (is false "expected missing image source rejection")
      (catch clojure.lang.ExceptionInfo e
        (is (= :request/missing-image-source
               (:error/type (ex-data e))))))))

(deftest test-build-request-file-attachment-fails-for-alias
  (let [t (openai/make-transport)
        profile (provider/get-provider :deepseek)
        req {:request/model "deepseek-chat"
             :request/messages
             [{:message/role :user
               :message/content [{:part/type :file
                                  :file/name "brief.pdf"
                                  :file/data "JVBERi0x"}]}]}]
    (try
      (transport/build-request t profile req)
      (is false "expected file attachment rejection")
      (catch clojure.lang.ExceptionInfo e
        (is (= :provider/unsupported-file-attachment
               (:error/type (ex-data e))))
        (is (= :deepseek (:provider (ex-data e))))))))

(deftest test-parse-response-text
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        raw {:id "chatcmpl-1"
             :model "gpt-4o"
             :choices [{:message {:content "Hello!"}
                        :finish_reason "stop"}]
             :usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}}
        resp (transport/parse-response t profile raw)]
    (is (= :openai (:response/provider resp)))
    (is (= "gpt-4o" (:response/model resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello!"}] (:response/parts resp)))
    (is (= 10 (get-in resp [:response/usage :usage/input-tokens])))))

(deftest test-parse-response-tool-calls
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        raw {:id "chatcmpl-2"
             :model "gpt-4o"
             :choices [{:message {:content nil
                                  :tool_calls [{:id "call_1"
                                                :function {:name "get_weather"
                                                           :arguments "{\"location\":\"NYC\"}"}}]}
                        :finish_reason "tool_calls"}]
             :usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30}}
        resp (transport/parse-response t profile raw)]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather" (get-in resp [:response/tool-calls 0 :tool-call/name])))))

(deftest test-parse-response-legacy-function-call
  (let [resp (transport/parse-response
              (openai/make-transport)
              (provider/get-provider :openai)
              {:id "chatcmpl-legacy"
               :model "gpt-4o"
               :choices [{:message {:content nil
                                    :function_call
                                    {:name "get_weather"
                                     :arguments "{\"location\":\"NYC\"}"}}
                          :finish_reason "function_call"}]})]
    (is (= :tool-calls (:response/finish-reason resp)))
    (is (= [{:part/type :tool-call
             :tool-call/id "tool_call_0"
             :tool-call/name "get_weather"
             :tool-call/arguments "{\"location\":\"NYC\"}"
             :tool-call/provider-data {:wire_type "function"}}]
           (:response/tool-calls resp)))
    (is (= (:response/tool-calls resp)
           (filterv #(= :tool-call (:part/type %)) (:response/parts resp))))))

(deftest test-parse-mistral-sequential-content
  (let [profile (provider/get-provider :mistral)
        t (openai/make-transport)
        resp
        (transport/parse-response
         t profile
         {:id "mistral-1"
          :model "mistral-small-latest"
          :choices
          [{:message
            {:content
             [{:type "thinking"
               :thinking [{:type "text" :text "Check "}
                          {:type "text" :text "carefully"}]}
              {:type "text" :text "First"}
              {:type "citation" :url "https://example.com"}
              " second"]}
            :finish_reason "stop"}]})
        events
        (transport/parse-stream-event
         t profile
         (str "data: {\"choices\":[{\"delta\":{\"content\":["
              "{\"type\":\"thinking\",\"thinking\":["
              "{\"type\":\"text\",\"text\":\"Check \"},"
              "{\"type\":\"text\",\"text\":\"carefully\"}]},"
              "{\"type\":\"text\",\"text\":\"First\"},"
              "{\"type\":\"citation\",\"url\":\"https://example.com\"},"
              "\" second\"]}}]}"))]
    (is (= [{:part/type :reasoning
             :reasoning/text "Check carefully"}
            {:part/type :text :text "First"}
            {:part/type :text :text " second"}]
           (:response/parts resp)))
    (is (= [{:type "citation" :url "https://example.com"}]
           (get-in resp [:response/provider-data :content_chunks])))
    (is (= [:stream/reasoning-delta
            :stream/content-delta
            :stream/provider-state
            :stream/content-delta]
           (mapv :event/type events)))
    (is (= "Check carefully" (:event/delta (first events))))
    (is (= {:type "citation" :url "https://example.com"}
           (get-in events [2 :provider-state/data
                           :chat-completion/content-chunk])))))

(deftest test-parse-deepseek-insufficient-resource-finish
  (let [t (openai/make-transport)
        profile (provider/get-provider :deepseek)
        resp (transport/parse-response
              t profile
              {:id "deepseek-1"
               :model "deepseek-v4-pro"
               :choices [{:message {:content ""}
                          :finish_reason "insufficient_system_resource"}]})
        event (transport/parse-stream-event
               t profile
               "data: {\"choices\":[{\"finish_reason\":\"insufficient_system_resource\"}]}")]
    (is (= :incomplete (:response/finish-reason resp)))
    (is (= :incomplete (:event/finish-reason event)))))

(deftest test-reasoning-content-is-canonical-and-replayed-exactly
  (doseq [[provider-id model]
          [[:deepseek "deepseek-v4-pro"]
           [:kimi "kimi-k2.6"]
           [:kimi-code "kimi-for-coding"]]]
    (let [t (openai/make-transport)
          profile (provider/get-provider provider-id)
          parsed
          (transport/parse-response
           t profile
           {:id "reasoning-turn"
            :model model
            :choices
            [{:message
              {:content ""
               :reasoning_content "exact private reasoning"
               :tool_calls
               [{:id "call_1"
                 :type "function"
                 :function {:name "lookup" :arguments "{}"}}]}
              :finish_reason "tool_calls"}]})
          built
          (transport/build-request
           t profile
           {:request/model model
            :request/messages
            [{:message/role :assistant
              :message/content (:response/parts parsed)
              :message/tool-calls (:response/tool-calls parsed)
              :message/provider-data (:response/provider-data parsed)}
             {:message/role :tool
              :message/content "result"
              :message/tool-call-id "call_1"}]
            :request/tools
            [{:type :function
              :function {:name "lookup"
                         :parameters {:type "object"}}}]})
          assistant-message (get-in built [:body :messages 0])]
      (is (= {:part/type :reasoning
              :reasoning/text "exact private reasoning"}
             (first (filter #(= :reasoning (:part/type %))
                            (:response/parts parsed))))
          (str provider-id " canonical reasoning"))
      (is (= "exact private reasoning"
             (:reasoning_content assistant-message))
          (str provider-id " reasoning replay"))
      (is (= 1 (count (:tool_calls assistant-message)))
          (str provider-id " de-duplicates tool calls")))))

(deftest test-parse-response-current-custom-tool-and-message-state
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        raw {:id "chatcmpl-custom"
             :model "gpt-5"
             :choices [{:message
                        {:content nil
                         :refusal "I cannot do that."
                         :audio {:id "audio-1" :transcript "No."}
                         :moderation {:output {:flagged false}}
                         :tool_calls [{:id "call_custom"
                                       :type "custom"
                                       :custom {:name "shell"
                                                :input "pwd"}}]}
                        :finish_reason "tool_calls"}]}
        resp (transport/parse-response t profile raw)
        replay
        (transport/build-request
         t profile
         {:request/model "gpt-5"
          :request/messages
          [{:message/role :assistant
            :message/content (:response/parts resp)
            :message/tool-calls (:response/tool-calls resp)
            :message/provider-data (:response/provider-data resp)}]})
        replay-call (get-in replay [:body :messages 0 :tool_calls 0])]
    (is (= "shell" (get-in resp [:response/tool-calls 0 :tool-call/name])))
    (is (= "pwd" (get-in resp [:response/tool-calls 0 :tool-call/arguments])))
    (is (= "custom"
           (get-in resp [:response/tool-calls 0 :tool-call/provider-data :wire_type])))
    (is (= "I cannot do that."
           (get-in resp [:response/provider-data :refusal])))
    (is (= "audio-1"
           (get-in resp [:response/provider-data :audio :id])))
    (is (= {:id "call_custom"
            :type "custom"
            :custom {:name "shell" :input "pwd"}}
           replay-call))
    (is (not (contains? (get-in replay [:body :messages 0]) :content)))))

(deftest test-parse-stream-event-content
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-parse-stream-event-tool-call
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"get_weather\"}}]}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/tool-call-start (:event/type ev)))
    (is (= 0 (:tool-call/index ev)))
    (is (= "call_1" (:tool-call/id ev)))
    (is (= "get_weather" (:tool-call/name ev)))))

(deftest test-parse-stream-event-current-custom-tool-call
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        lines
        [(str "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
              "{\"index\":0,\"id\":\"call_1\",\"type\":\"custom\","
              "\"call_id\":\"native-call\",\"extra_content\":{\"trace\":\"t1\"},"
              "\"custom\":{\"name\":\"shell\",\"input\":\"p\"}}]}}]}")
         (str "data: {\"choices\":[{\"delta\":{\"tool_calls\":["
              "{\"index\":0,\"custom\":{\"input\":\"wd\"}}]}}]}")]
        events
        (->> lines
             (mapcat
              (fn [line]
                (let [parsed (transport/parse-stream-event t profile line)]
                  (if (sequential? parsed) parsed [parsed]))))
             vec)
        response (stream/events->response events :openai "gpt-5")
        replay
        (transport/build-request
         t profile
         {:request/model "gpt-5"
          :request/messages
          [{:message/role :assistant
            :message/content (:response/parts response)
            :message/tool-calls (:response/tool-calls response)}]})
        replay-call (get-in replay [:body :messages 0 :tool_calls 0])]
    (is (= [:stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-delta]
           (mapv :event/type events)))
    (is (= "custom"
           (get-in events [0 :tool-call/provider-data :wire_type])))
    (is (= {:trace "t1"}
           (get-in events [0 :tool-call/provider-data :extra_content])))
    (is (= "shell" (:tool-call/name (first events))))
    (is (= ["p" "wd"]
           (mapv :tool-call/arguments-delta (rest events))))
    (is (= "custom" (:type replay-call)))
    (is (= "native-call" (:call_id replay-call)))
    (is (= {:trace "t1"} (:extra_content replay-call)))
    (is (not (contains? (get-in replay [:body :messages 0]) :content)))
    (is (= {:name "shell" :input "pwd"} (:custom replay-call)))))

(deftest test-parse-stream-event-legacy-function-call
  (let [events (transport/parse-stream-event
                (openai/make-transport)
                (provider/get-provider :openai)
                "data: {\"choices\":[{\"delta\":{\"function_call\":{\"name\":\"get_weather\",\"arguments\":\"{\\\"city\\\":\"}},\"finish_reason\":\"function_call\"}]}")]
    (is (= [:stream/tool-call-start :stream/tool-call-delta :stream/end]
           (mapv :event/type events)))
    (is (= "tool_call_0" (get-in events [0 :tool-call/id])))
    (is (= "get_weather" (get-in events [0 :tool-call/name])))
    (is (= "{\"city\":" (get-in events [1 :tool-call/arguments-delta])))
    (is (= :tool-calls (get-in events [2 :event/finish-reason])))))

(deftest test-parse-stream-event-preserves-audio-state
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"delta\":{\"audio\":{\"id\":\"audio-1\",\"data\":\"AAAA\"}}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/provider-state (:event/type ev)))
    (is (= "audio-1"
           (get-in ev [:provider-state/data
                       :chat-completion/delta :audio :id])))))

(deftest test-parse-stream-event-multiple-tool-deltas
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}},{\"index\":1,\"id\":\"call_2\",\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
        events (transport/parse-stream-event t profile line)]
    (is (sequential? events))
    (is (= [:stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-start
            :stream/tool-call-delta
            :stream/usage
            :stream/end]
           (mapv :event/type events)))
    (is (= [0 0 1 1]
           (mapv :tool-call/index (take 4 events))))
    (is (= :tool-calls (:event/finish-reason (last events))))))

(deftest test-parse-stream-event-usage
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/usage (:event/type ev)))
    (is (= 10 (get-in ev [:usage :usage/input-tokens])))))

(deftest test-parse-stream-event-finish
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        line "data: {\"choices\":[{\"finish_reason\":\"stop\"}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/end (:event/type ev)))
    (is (= :stop (:event/finish-reason ev)))))

;; ---------------------------------------------------------------------------
;; Caching wiring
;; ---------------------------------------------------------------------------

(deftest test-cache-prompt-cache-key-passthrough
  (testing "scope-id surfaces as body.prompt_cache_key for openai chat"
    (let [t (openai/make-transport)
          profile (provider/get-provider :openai)
          req {:request/model "gpt-4o"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "session-1234"}}
          built (transport/build-request t profile req)]
      (is (= "session-1234" (get-in built [:body :prompt_cache_key]))))))

(deftest test-cache-no-prompt-key-when-disabled
  (let [t (openai/make-transport)
        profile (provider/get-provider :openai)
        req {:request/model "gpt-4o"
             :request/messages [{:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)]
    (is (nil? (get-in built [:body :prompt_cache_key])))))

(deftest test-cache-deepseek-passthrough
  (testing "DeepSeek accepts prompt_cache_key (server-side ignores but harmless)"
    (let [t (openai/make-transport)
          profile (provider/get-provider :deepseek)
          req {:request/model "deepseek-chat"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:scope-id "ds-session"}}
          built (transport/build-request t profile req)]
      (is (= "ds-session" (get-in built [:body :prompt_cache_key]))))))
