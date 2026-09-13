(ns llm.sdk.providers.bedrock-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.bedrock :as bedrock]))

(defn- request-error [request]
  (try
    (transport/build-request (bedrock/make-transport)
                             (provider/get-provider :bedrock)
                             request)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest test-resolve-model-id-known-short
  (is (= "anthropic.claude-sonnet-4-5-20250929-v1:0"
         (bedrock/resolve-model-id "claude-sonnet-4-5")))
  (is (= "anthropic.claude-opus-4-7"
         (bedrock/resolve-model-id "claude-opus-4-7")))
  (is (= "anthropic.claude-fable-5-1"
         (bedrock/resolve-model-id "claude-fable-5-1")))
  (is (= "amazon.nova-pro-v1:0"
         (bedrock/resolve-model-id "nova-pro")))
  (is (= "cohere.command-r-plus-v1:0"
         (bedrock/resolve-model-id "command-r-plus"))))

(deftest test-resolve-model-id-passthrough
  (testing "ids that look like full Bedrock ARNs are forwarded verbatim"
    (is (= "anthropic.claude-3-5-sonnet-20241022-v2:0"
           (bedrock/resolve-model-id "anthropic.claude-3-5-sonnet-20241022-v2:0"))))
  (testing "ARNs are forwarded"
    (let [arn "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-3-5-sonnet-20241022-v2:0"]
      (is (= arn (bedrock/resolve-model-id arn)))))
  (testing "full geo inference profile ids are forwarded"
    (let [model "us.anthropic.claude-opus-4-8"]
      (is (= model (bedrock/resolve-model-id model))))))

(deftest test-build-request-basic
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]}
        built (transport/build-request t profile req)]
    (is (.endsWith ^String (:url built) "/model/anthropic.claude-3-5-sonnet-20241022-v2:0/converse"))
    (is (= "Sys" (get-in built [:body :system 0 :text])))
    (is (= 1 (count (get-in built [:body :messages]))))
    (is (= "bedrock" (get built :llm.sdk.providers.bedrock/aws-service)))))

(deftest test-build-request-stream-url
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request t profile
                                       {:request/model "nova-pro"
                                        :request/stream? true
                                        :request/messages [{:message/role :user
                                                            :message/content "hi"}]})]
    (is (.endsWith ^String (:url built) "/model/amazon.nova-pro-v1:0/converse-stream"))
    (is (= "application/vnd.amazon.eventstream" (get-in built [:headers "Accept"])))))

(deftest test-inference-config-stop-sequence-is-not-split
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "nova-pro"
                :request/messages [{:message/role :user
                                    :message/content "hi"}]
                :request/temperature 0.2
                :request/stop "END"})]
    (is (= 0.2 (get-in built [:body :inferenceConfig :temperature])))
    (is (= ["END"] (get-in built [:body :inferenceConfig :stopSequences])))))

(deftest test-build-request-document-attachment
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages
                [{:message/role :user
                  :message/content [{:part/type :text
                                     :text "Summarize."}
                                    {:part/type :file
                                     :file/name "Q1 Report"
                                     :file/format "pdf"
                                     :file/data "JVBERi0x"
                                     :file/citations true}]}]})
        content (get-in built [:body :messages 0 :content])]
    (is (= {:text "Summarize."} (first content)))
    (is (= {:document {:format "pdf"
                       :name "Q1 Report"
                       :source {:bytes "JVBERi0x"}
                       :citations {:enabled true}}}
           (second content)))))

(deftest test-build-request-document-s3-source
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages
                [{:message/role :user
                  :message/content [{:part/type :text
                                     :text "Summarize."}
                                    {:part/type :file
                                     :file/name "Report"
                                     :file/mime-type "application/pdf"
                                     :file/url "s3://example/report.pdf"}]}]})]
    (is (= {:document {:format "pdf"
                       :name "Report"
                       :source {:s3Location {:uri "s3://example/report.pdf"}}}}
           (get-in built [:body :messages 0 :content 1])))))

(deftest test-explicit-file-format-precedes-name-and-mime
  (is (= "csv"
         (transport/file-extension
          {:file/name "Report.pdf"
           :file/format "csv"
           :file/mime-type "application/pdf"}))))

(deftest test-invalid-document-shapes-fail-before-transport
  (let [file {:part/type :file
              :file/name "brief"
              :file/format "pdf"
              :file/url "s3://bucket/brief.pdf"}
        request (fn [role content]
                  {:request/model "claude-3-5-sonnet"
                   :request/messages [{:message/role role
                                       :message/content content}]})
        cases [[:request/document-without-text
                (request :user [file])]
               [:request/invalid-document-name
                (request :user [{:part/type :text :text "Summarize."}
                                (assoc file :file/name "brief.pdf")])]
               [:request/invalid-document-name
                (request :user [{:part/type :text :text "Summarize."}
                                (assoc file
                                       :file/name
                                       (apply str (repeat 201 "a")))])]
               [:request/unsupported-document-format
                (request :user [{:part/type :text :text "Summarize."}
                                (assoc file :file/format "rtf")])]
               [:request/invalid-document-role
                (request :assistant [{:part/type :text :text "Summary."}
                                     file])]]]
    (doseq [[expected request] cases]
      (is (= expected (:error/type (request-error request)))))))

(deftest test-tool-call-shape
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages [{:message/role :user :message/content "use it"}]
                :request/tools [{:type "function"
                                 :function {:name "fetch_weather"
                                            :description "Get weather"
                                            :parameters {:type "object"
                                                         :properties {:city {:type "string"}}}}}]
                :request/tool-choice :auto})]
    (let [tools (get-in built [:body :toolConfig :tools])]
      (is (= 1 (count tools)))
      (is (= "fetch_weather" (get-in tools [0 :toolSpec :name])))
      (is (= "object" (get-in tools [0 :toolSpec :inputSchema :json :type]))))
    (is (= {:auto {}} (get-in built [:body :toolConfig :toolChoice])))))

(deftest test-assistant-tool-call-replay-keeps-text
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages [{:message/role :assistant
                                    :message/content "I'll check."
                                    :message/tool-calls [{:part/type :tool-call
                                                          :tool-call/id "tool_1"
                                                          :tool-call/name "fetch_weather"
                                                          :tool-call/arguments "{\"city\":\"NYC\"}"}]}]})
        content (get-in built [:body :messages 0 :content])]
    (is (= "I'll check." (get-in content [0 :text])))
    (is (= "tool_1" (get-in content [1 :toolUse :toolUseId])))
    (is (= {"city" "NYC"} (get-in content [1 :toolUse :input])))))

(deftest test-tool-call-native-replay-does-not-duplicate-parts
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        parsed (transport/parse-response
                t profile
                {:stopReason "tool_use"
                 :output
                 {:message
                  {:content [{:toolUse
                              {:toolUseId "tool_1"
                               :name "fetch_weather"
                               :input {:city "NYC"}}}]}}})
        tool-call (first (:response/tool-calls parsed))
        built (transport/build-request
               t profile
               {:request/model "claude-3-5-sonnet"
                :request/messages
                [{:message/role :assistant
                  :message/content (:response/parts parsed)
                  :message/tool-calls (:response/tool-calls parsed)}]})
        tool-uses (keep :toolUse
                       (get-in built [:body :messages 0 :content]))]
    (is (= {:toolUseId "tool_1"
            :name "fetch_weather"
            :input {:city "NYC"}}
           (get-in tool-call [:tool-call/provider-data
                              :bedrock/tool-use])))
    (is (= 1 (count tool-uses)))
    (is (= {:city "NYC"} (:input (first tool-uses))))))

(deftest test-developer-messages-remain-system-instructions
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "nova-pro"
                :request/messages
                [{:message/role :system :message/content "System"}
                 {:message/role :developer :message/content "Developer"}
                 {:message/role :user :message/content "Question"}]})]
    (is (= [{:text "System"} {:text "Developer"}]
           (get-in built [:body :system])))
    (is (= [{:role "user" :content [{:text "Question"}]}]
           (get-in built [:body :messages])))))

(deftest test-runtime-endpoint-controls-routing-and-signing-region
  (let [t (bedrock/make-transport)
        profile (assoc (provider/get-provider :bedrock)
                       :profile/base-url
                       "https://bedrock-runtime.eu-west-1.amazonaws.com/")
        built (transport/build-request
               t profile
               {:request/model "amazon.nova-pro-v1:0"
                :request/messages
                [{:message/role :user :message/content "Hello"}]})]
    (is (.startsWith
         ^String (:url built)
         "https://bedrock-runtime.eu-west-1.amazonaws.com/model/"))
    (is (= "eu-west-1"
           (:llm.sdk.providers.bedrock/aws-region built)))))


(deftest test-cache-point-default-on-when-cache-enabled
  (testing "Bedrock injects cachePoint sentinel after system and final user content"
    (let [t (bedrock/make-transport)
          profile (provider/get-provider :bedrock)
          req {:request/model "claude-3-5-sonnet"
               :request/messages [{:message/role :system :message/content "Sys"}
                                  {:message/role :user :message/content "Hi"}]
               :request/cache {}}
          built (transport/build-request t profile req)
          sys (get-in built [:body :system])
          last-msg-content (get-in built [:body :messages 0 :content])]
      (is (= {:type "default"} (:cachePoint (last sys)))
          "trailing system cachePoint should be present")
      (is (= {:type "default"} (:cachePoint (last last-msg-content)))
          "trailing user-message cachePoint should be present"))))

(deftest test-cache-ttl-validation
  (let [base {:request/model "claude-sonnet-4-5"
              :request/messages
              [{:message/role :user :message/content "Hi"}]}
        valid (transport/build-request
               (bedrock/make-transport)
               (provider/get-provider :bedrock)
               (assoc base :request/cache {:ttl "5m"}))]
    (is (= {:type "default" :ttl "5m"}
           (get-in valid [:body :messages 0 :content 1 :cachePoint])))
    (is (= :request/unsupported-cache-ttl
           (:error/type
            (request-error
             (assoc base :request/cache {:ttl "30m"})))))))

(deftest test-cache-point-suppressed-when-strategy-none
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hi"}]
             :request/cache {:strategy :none}}
        built (transport/build-request t profile req)
        sys (get-in built [:body :system])
        last-msg-content (get-in built [:body :messages 0 :content])]
    (is (nil? (:cachePoint (last sys))))
    (is (nil? (:cachePoint (last last-msg-content))))))

(deftest test-cache-point-default-off-when-cache-omitted
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        req {:request/model "claude-3-5-sonnet"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)
        sys (get-in built [:body :system])
        last-msg-content (get-in built [:body :messages 0 :content])]
    (is (nil? (:cachePoint (last sys))))
    (is (nil? (:cachePoint (last last-msg-content))))))

;; ---------------------------------------------------------------------------
;; Stream parsing — frame shape produced by aws-eventstream/frame->json
;; ---------------------------------------------------------------------------

(deftest test-parse-frame-content-delta
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frame {:event-type "contentBlockDelta"
               :data {:contentBlockIndex 0
                      :delta {:text "Hello"}}}
        ev (transport/parse-stream-event t profile frame)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hello" (:event/delta ev)))))

(deftest test-legacy-frame-map-and-line-use-the-same-parser
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        legacy {:type "contentBlockDelta"
                :contentBlockDelta
                {:contentBlockIndex 0 :delta {:text "Hello"}}}
        as-map (transport/parse-stream-event t profile legacy)
        as-line (transport/parse-stream-event
                 t profile
                 "{\"type\":\"contentBlockDelta\",\"contentBlockDelta\":{\"contentBlockIndex\":0,\"delta\":{\"text\":\"Hello\"}}}")]
    (is (= as-map as-line))
    (is (= :stream/content-delta (:event/type as-line)))))

(deftest test-parse-frame-message-stop
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frame {:event-type "messageStop"
               :data {:stopReason "end_turn"}}
        ev (transport/parse-stream-event t profile frame)]
    (is (= :stream/end (:event/type ev)))
    (is (= :stop (:event/finish-reason ev)))))

(deftest test-parse-frame-metadata
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frame {:event-type "metadata"
               :data {:usage {:inputTokens 17 :outputTokens 9 :totalTokens 26}}}
        ev (transport/parse-stream-event t profile frame)]
    (is (= :stream/usage (:event/type ev)))
    (is (= 17 (get-in ev [:usage :usage/input-tokens])))
    (is (= 9 (get-in ev [:usage :usage/output-tokens])))
    (is (not (contains? (:usage ev) :usage/cached-input-tokens)))))

(deftest test-parse-response-cache-tokens
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        raw {:modelId "anthropic.claude-3-5-sonnet-20241022-v2:0"
             :stopReason "end_turn"
             :output {:message {:content [{:text "hi back"}]}}
             :usage {:inputTokens 10
                     :outputTokens 5
                     :totalTokens 15
                     :cacheReadInputTokens 8
                     :cacheWriteInputTokens 2}}
        parsed (transport/parse-response t profile raw)]
    (is (= [{:part/type :text :text "hi back"}] (:response/parts parsed)))
    (is (= 10 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 8 (get-in parsed [:response/usage :usage/cached-input-tokens])))
    (is (= 2 (get-in parsed [:response/usage :usage/cache-write-tokens])))))

(deftest test-build-current-converse-fields
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-opus-4-6"
                :request/messages [{:message/role :user
                                    :message/content "Answer as JSON"}]
                :request/tools [{:type :function
                                 :function {:name "lookup"
                                            :parameters {:type "object"}}}]
                :request/reasoning {:enabled true :effort :xhigh}
                :request/cache {:ttl "1h" :tools-cache? true}
                :request/response-format {:type :json_schema
                                          :name "answer"
                                          :json-schema {:type "object"}}
                :request/metadata {:trace-id 42}
                :request/provider-options
                {:bedrock
                 {:additional-model-response-field-paths ["/stop_sequence"]
                  :guardrail-config {:guardrailIdentifier "guardrail-id"
                                     :guardrailVersion "1"
                                     :trace "enabled"}
                  :service-tier {:type "priority"}}}})
        body (:body built)]
    (is (= {:type "adaptive"}
           (get-in body [:additionalModelRequestFields :thinking])))
    (is (= "xhigh"
           (get-in body [:additionalModelRequestFields :output_config :effort])))
    (is (= "json_schema" (get-in body [:outputConfig :textFormat :type])))
    (is (= "{\"type\":\"object\"}"
           (get-in body [:outputConfig :textFormat :structure :jsonSchema :schema])))
    (is (= {"trace-id" "42"} (:requestMetadata body)))
    (is (= "guardrail-id"
           (get-in body [:guardrailConfig :guardrailIdentifier])))
    (is (= ["/stop_sequence"] (:additionalModelResponseFieldPaths body)))
    (is (= {:type "default" :ttl "1h"}
           (get-in body [:messages 0 :content 1 :cachePoint])))
    (is (= {:type "default" :ttl "1h"}
           (get-in body [:toolConfig :tools 1 :cachePoint])))))

(deftest test-documented-claude-and-nova-reasoning-shapes
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        build (fn [model reasoning]
                (:body
                 (transport/build-request
                  t profile
                  {:request/model model
                   :request/messages
                   [{:message/role :user :message/content "Think"}]
                   :request/max-tokens 4096
                   :request/reasoning reasoning})))
        adaptive (build "claude-fable-5-1"
                        {:enabled true :effort :high})
        manual (build "claude-sonnet-4-5"
                      {:enabled true :budget 2048})
        disabled-with-effort (build "claude-opus-5"
                                    {:enabled false :effort :high})
        nova (build "nova-lite" {:enabled true :effort :medium})
        nova-disabled (build "nova-pro" {:enabled false})]
    (is (= {:type "adaptive"}
           (get-in adaptive [:additionalModelRequestFields :thinking])))
    (is (= "high"
           (get-in adaptive
                   [:additionalModelRequestFields :output_config :effort])))
    (is (= {:type "enabled" :budget_tokens 2048}
           (get-in manual [:additionalModelRequestFields :thinking])))
    (is (= {:type "disabled"}
           (get-in disabled-with-effort
                   [:additionalModelRequestFields :thinking])))
    (is (= "high"
           (get-in disabled-with-effort
                   [:additionalModelRequestFields :output_config :effort])))
    (is (= {:type "enabled" :maxReasoningEffort "medium"}
           (get-in nova
                   [:additionalModelRequestFields :reasoningConfig])))
    (is (= {:type "disabled"}
           (get-in nova-disabled
                   [:additionalModelRequestFields :reasoningConfig])))))

(deftest test-invalid-reasoning-controls-fail-before-transport
  (let [base {:request/model "claude-opus-4-6"
              :request/messages
              [{:message/role :user :message/content "Think"}]
              :request/reasoning {:enabled true :effort :high}}
        cases [[:request/incompatible-reasoning
                (assoc base :request/temperature 0)]
               [:request/incompatible-reasoning
                (assoc base
                       :request/tools
                       [{:type :function
                         :function {:name "lookup"
                                    :parameters {:type "object"}}}]
                       :request/tool-choice :required)]
               [:request/invalid-reasoning
                (assoc base
                       :request/model "claude-fable-5"
                       :request/reasoning {:enabled false})]
               [:request/invalid-reasoning
                (assoc base
                       :request/model "claude-fable-5-1"
                       :request/reasoning {:enabled true :budget 2048})]
               [:request/unsupported-reasoning-effort
                (assoc base
                       :request/model "nova-pro"
                       :request/reasoning {:enabled true :effort :xhigh})]
               [:request/unsupported-reasoning-effort
                (assoc base
                       :request/model "claude-opus-5"
                       :request/reasoning {:enabled false :effort :xhigh})]
               [:request/invalid-reasoning
                (assoc base
                       :request/model "nova-lite"
                       :request/reasoning {:enabled true :budget 2048})]
               [:request/unsupported-reasoning
                (assoc base
                       :request/model "nova-premier"
                       :request/reasoning {:enabled true :effort :high})]]]
    (doseq [[expected request] cases]
      (is (= expected (:error/type (request-error request)))))))

(deftest test-build-converse-multimodal-and-replay-content
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "claude-3-7-sonnet"
                :request/messages
                [{:message/role :user
                  :message/content
                  [{:part/type :image
                    :image/url "s3://bucket/photo.webp"
                    :image/mime-type "image/webp"}
                   {:part/type :image
                    :image/url "data:image/png;base64,aW1hZ2U="
                    :image/mime-type "image/png"}]}
                 {:message/role :assistant
                  :message/content
                  [{:part/type :reasoning
                    :reasoning/text "checked"
                    :reasoning/signature "signed"}]}]})
        messages (get-in built [:body :messages])]
    (is (= {:s3Location {:uri "s3://bucket/photo.webp"}}
           (get-in messages [0 :content 0 :image :source])))
    (is (= {:bytes "aW1hZ2U="}
           (get-in messages [0 :content 1 :image :source])))
    (is (= {:text "checked" :signature "signed"}
           (get-in messages [1 :content 0 :reasoningContent :reasoningText])))))

(deftest test-build-tool-result-and-guard-content
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        built (transport/build-request
               t profile
               {:request/model "nova-pro"
                :request/messages
                [{:message/role :user
                  :message/content
                  [{:part/type :tool-result
                    :tool-result/id "tool-1"
                    :tool-result/name "lookup"
                    :tool-result/content "failed"
                    :tool-result/is-error true}
                   {:part/type :unknown/provider-native
                    :unknown/provider :bedrock
                    :unknown/data
                    {:guardContent {:text {:text "guard only this"}}}}]}]})
        content (get-in built [:body :messages 0 :content])]
    (is (= "error" (get-in content [0 :toolResult :status])))
    (is (= "failed" (get-in content [0 :toolResult :content 0 :text])))
    (is (= "guard only this"
           (get-in content [1 :guardContent :text :text])))))

(deftest test-parse-current-converse-content
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        parsed (transport/parse-response
                t profile
                {:stopReason "malformed_tool_use"
                 :output
                 {:message
                  {:content
                   [{:reasoningContent
                     {:reasoningText {:text "analysis" :signature "sig"}}}
                    {:reasoningContent {:redactedContent "cmVkYWN0ZWQ="}}
                    {:citationsContent
                     {:content [{:text "cited answer"}]
                      :citations
                      [{:source "doc-1"
                        :title "Document"
                        :sourceContent [{:text "supporting passage"}]
                        :location
                        {:documentChar
                         {:documentIndex 0 :start 4 :end 22}}}]}}]}}})]
    (is (= :incomplete (:response/finish-reason parsed)))
    (is (= {:part/type :reasoning
            :reasoning/text "analysis"
            :reasoning/signature "sig"}
           (first (:response/parts parsed))))
    (is (= {:part/type :reasoning
            :reasoning/text "cmVkYWN0ZWQ="
            :reasoning/encrypted true}
           (second (:response/parts parsed))))
    (is (= {:part/type :text :text "cited answer"}
           (nth (:response/parts parsed) 2)))
    (is (= {:part/type :citation
            :citation/source-id "doc-1"
            :citation/title "Document"
            :citation/snippet "supporting passage"
            :citation/text-range [4 22]
            :citation/provider-data
            {:bedrock/citation
             {:source "doc-1"
              :title "Document"
              :sourceContent [{:text "supporting passage"}]
              :location
              {:documentChar
               {:documentIndex 0 :start 4 :end 22}}}}}
           (nth (:response/parts parsed) 3)))))

(deftest test-parse-current-stream-events
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        reasoning (transport/parse-stream-event
                   t profile
                   {:event-type "contentBlockDelta"
                    :data {:contentBlockIndex 2
                           :delta {:reasoningContent {:text "analysis"}}}})
        signature (transport/parse-stream-event
                   t profile
                   {:event-type "contentBlockDelta"
                    :data {:contentBlockIndex 2
                           :delta {:reasoningContent {:signature "sig"}}}})
        redacted (transport/parse-stream-event
                  t profile
                  {:event-type "contentBlockDelta"
                   :data {:contentBlockIndex 3
                          :delta {:reasoningContent
                                  {:redactedContent "cmVkYWN0ZWQ="}}}})
        citation (transport/parse-stream-event
                  t profile
                  {:event-type "contentBlockDelta"
                   :data {:contentBlockIndex 4
                          :delta
                          {:citation
                           {:source "doc-1"
                            :title "Document"
                            :sourceContent [{:text "support"}]
                            :location {:documentChar {:start 2 :end 9}}}}}})
        exception (transport/parse-stream-event
                   t profile
                   {:event-type nil
                    :headers {":message-type" "exception"
                              ":exception-type" "validationException"}
                    :data {:message "bad request"}})
        response (stream/events->response [reasoning signature]
                                          :bedrock
                                          "claude-opus-4-6")
        replay (transport/build-request
                t profile
                {:request/model "claude-opus-4-6"
                 :request/messages
                 [{:message/role :assistant
                   :message/content (:response/parts response)}]})]
    (is (= :stream/reasoning-delta (:event/type signature)))
    (is (= 2 (:event/index signature)))
    (is (= "sig" (:reasoning/signature signature)))
    (is (= {:text "analysis" :signature "sig"}
           (get-in replay
                   [:body :messages 0 :content 0
                    :reasoningContent :reasoningText])))
    (is (= :stream/reasoning-delta (:event/type redacted)))
    (is (= 3 (:event/index redacted)))
    (is (true? (:event/encrypted redacted)))
    (is (= :stream/citation (:event/type citation)))
    (is (= "doc-1" (:citation/source-id citation)))
    (is (= [2 9] (:citation/text-range citation)))
    (is (= :stream/error (:event/type exception)))
    (is (= "validationException" (get-in exception [:error/error :type])))))

(deftest test-official-message-stop-then-metadata-retains-trailers
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        frames [{:event-type "messageStop"
                 :data {:stopReason "end_turn"
                        :additionalModelResponseFields
                        {:stop_sequence "END"}}}
                {:event-type "metadata"
                 :data {:usage {:inputTokens 17
                                :outputTokens 9
                                :totalTokens 26}
                        :metrics {:latencyMs 123}}}]
        events (mapcat
                (fn [frame]
                  (let [parsed (transport/parse-stream-event
                                t profile frame)]
                    (if (sequential? parsed) parsed [parsed])))
                frames)
        response (stream/events->response events :bedrock "nova-pro")]
    (is (= [:stream/provider-state :stream/end
            :stream/usage :stream/provider-state]
           (mapv :event/type events)))
    (is (= :stop (:response/finish-reason response)))
    (is (= 26 (get-in response
                      [:response/usage :usage/total-tokens])))
    (is (= {:stop_sequence "END"}
           (get-in response
                   [:response/provider-data :bedrock
                    :additionalModelResponseFields])))
    (is (= {:latencyMs 123}
           (get-in response
                   [:response/provider-data :bedrock :metrics])))))

(deftest test-parse-converse-preserves-provider-response-metadata
  (let [t (bedrock/make-transport)
        profile (provider/get-provider :bedrock)
        metadata {:additionalModelResponseFields {:reasoning "enabled"}
                  :metrics {:latencyMs 123}
                  :performanceConfig {:latency "optimized"}
                  :serviceTier {:type "priority"}
                  :trace {:guardrail {:action "NONE"}}}
        parsed (transport/parse-response
                t profile
                (merge {:modelId "anthropic.claude-sonnet-4-20250514-v1:0"
                        :stopReason "end_turn"
                        :output {:message {:content [{:text "hello"}]}}
                        :usage {:inputTokens 1
                                :outputTokens 1
                                :totalTokens 2}}
                       metadata))]
    (is (= metadata (:response/provider-data parsed)))))
