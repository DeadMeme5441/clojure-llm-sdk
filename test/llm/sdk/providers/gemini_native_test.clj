(ns llm.sdk.providers.gemini-native-test
  (:require [cheshire.core]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.gemini-native :as gemini]))

(deftest test-build-request-basic
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        req {:request/model "gemini-2.5-flash"
             :request/messages [{:message/role :system :message/content "Sys"}
                                {:message/role :user :message/content "Hello"}]
             :request/temperature 0.5}
        built (transport/build-request t profile req)]
    (is (= "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
           (:url built)))
    (is (= "user" (get-in built [:body :contents 0 :role])))
    (is (= "Sys" (get-in built [:body :systemInstruction :parts 0 :text])))))

(deftest test-capabilities-match-implemented-request-shapes
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)]
    (doseq [capability [:json-schema :cache]]
      (is (contains? (transport/request-capabilities t) capability))
      (is (contains? (:profile/capabilities profile) capability)))))

(deftest test-build-request-generation-config-preserves-all-options
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        req {:request/model "gemini-2.5-flash"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/temperature 0.2
             :request/top-p 0.9
             :request/max-tokens 7
             :request/stop ["END"]
             :request/reasoning {:enabled true}}
        built (transport/build-request t profile req)]
    (is (= {:temperature 0.2
            :topP 0.9
            :maxOutputTokens 7
            :stopSequences ["END"]
            :thinkingConfig {:includeThoughts true}}
           (get-in built [:body :generationConfig])))))

(deftest test-build-request-preserves-zero-generation-values
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        built (transport/build-request
               t profile
               {:request/model "gemini-2.5-flash"
                :request/messages [{:message/role :user
                                    :message/content "Hi"}]
                :request/temperature 0
                :request/top-p 0.0
                :request/max-tokens 0})]
    (is (= {:temperature 0
            :topP 0.0
            :maxOutputTokens 0}
           (get-in built [:body :generationConfig])))))

(deftest test-build-request-structured-output-and-provider-options
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        schema {:type "object"
                :properties {:answer {:type "string"}}
                :required ["answer"]
                :additionalProperties false}
        built (transport/build-request
               t profile
               {:request/model "gemini-3.5-flash"
                :request/messages [{:message/role :developer
                                    :message/content "Return a record."}
                                   {:message/role :user
                                    :message/content "Hi"}]
                :request/response-format {:type :json_schema
                                          :json-schema schema}
                :request/provider-options
                {:extra_body
                 {:serviceTier "PRIORITY"
                  :store false
                  :safetySettings
                  [{:category "HARM_CATEGORY_HARASSMENT"
                    :threshold "BLOCK_ONLY_HIGH"}]
                  :generationConfig
                  {:routingConfig
                   {:autoMode {:modelRoutingPreference "BALANCED"}}}}}})
        body (:body built)]
    (is (= "Return a record."
           (get-in body [:systemInstruction :parts 0 :text])))
    (is (= 1 (count (:contents body)))
        "developer instructions are not duplicated as user content")
    (is (= "application/json"
           (get-in body [:generationConfig :responseMimeType])))
    (is (= schema
           (get-in body [:generationConfig :responseJsonSchema])))
    (is (= {:autoMode {:modelRoutingPreference "BALANCED"}}
           (get-in body [:generationConfig :routingConfig])))
    (is (= "PRIORITY" (:serviceTier body)))
    (is (false? (:store body)))
    (is (= "BLOCK_ONLY_HIGH"
           (get-in body [:safetySettings 0 :threshold])))))

(deftest test-build-request-current-thinking-controls
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        build (fn [model reasoning]
                (transport/build-request
                 t profile
                 {:request/model model
                  :request/messages [{:message/role :user
                                      :message/content "Think"}]
                  :request/reasoning reasoning}))]
    (is (= {:includeThoughts true :thinkingBudget 2048}
           (get-in (build "gemini-2.5-flash"
                          {:enabled true :budget 2048})
                   [:body :generationConfig :thinkingConfig])))
    (is (= {:includeThoughts false :thinkingBudget 0}
           (get-in (build "gemini-2.5-flash" {:enabled false})
                   [:body :generationConfig :thinkingConfig])))
    (is (= {:includeThoughts true :thinkingLevel "minimal"}
           (get-in (build "gemini-3.5-flash"
                          {:enabled true :effort :minimal})
                   [:body :generationConfig :thinkingConfig])))
    (is (= {:includeThoughts true :thinkingLevel "high"}
           (get-in (build "gemini-3.1-pro"
                          {:enabled true :effort :xhigh})
                   [:body :generationConfig :thinkingConfig])))))

(deftest test-build-request-validates-thinking-by-model-family
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        build (fn [model reasoning]
                (transport/build-request
                 t profile
                 {:request/model model
                  :request/messages [{:message/role :user
                                      :message/content "Think"}]
                  :request/reasoning reasoning}))
        failure-reason
        (fn [model reasoning]
          (try
            (build model reasoning)
            nil
            (catch clojure.lang.ExceptionInfo e
              (:reason (ex-data e)))))]
    (is (= {:includeThoughts false :thinkingBudget 0}
           (get-in (build "gemini-2.5-flash" {:effort :none})
                   [:body :generationConfig :thinkingConfig])))
    (is (= {:includeThoughts true :thinkingLevel "high"}
           (get-in (build "gemini-3.5-flash" {:effort :max})
                   [:body :generationConfig :thinkingConfig])))
    (is (= :model-cannot-disable-thinking
           (failure-reason "gemini-2.5-pro" {:effort :none})))
    (is (= :model-cannot-disable-thinking
           (failure-reason "gemini-3.5-flash" {:enabled false})))
    (is (= :unsupported-thinking-effort
           (failure-reason "gemini-3.8-flash" {:effort :minimal})))
    (is (= :thinking-effort-requires-gemini-3
           (failure-reason "gemini-2.5-flash" {:effort :low})))
    (is (= :thinking-budget-requires-gemini-2.5
           (failure-reason "gemini-3.5-flash" {:budget 128})))
    (is (= :enabled-conflicts-with-disable
           (failure-reason "gemini-3.5-flash"
                           {:enabled true :effort :none})))))

(deftest test-build-request-replays-reasoning-signature
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        built (transport/build-request
               t profile
               {:request/model "gemini-3.5-flash"
                :request/messages
                [{:message/role :assistant
                  :message/content
                  [{:part/type :reasoning
                    :reasoning/text "summary"
                    :reasoning/signature "opaque-signature"}]}
                 {:message/role :user :message/content "Continue"}]})]
    (is (= {:text "summary"
            :thought true
            :thoughtSignature "opaque-signature"}
           (get-in built [:body :contents 0 :parts 0])))))

(deftest test-build-request-string-stop-sequence-is-not-split
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        built (transport/build-request
               t profile
               {:request/model "gemini-2.5-flash"
                :request/messages [{:message/role :user
                                    :message/content "Hello"}]
                :request/stop "END"})]
    (is (= ["END"] (get-in built [:body :generationConfig :stopSequences])))))

(deftest test-build-request-file-uri
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        built (transport/build-request
               t profile
               {:request/model "gemini-2.5-flash"
                :request/messages
                [{:message/role :user
                  :message/content [{:part/type :file
                                     :file/url "https://generativelanguage.googleapis.com/v1beta/files/abc"
                                     :file/mime-type "application/pdf"}
                                    {:part/type :text
                                     :text "Summarize this."}]}]})
        parts (get-in built [:body :contents 0 :parts])]
    (is (= {:fileData {:mimeType "application/pdf"
                       :fileUri "https://generativelanguage.googleapis.com/v1beta/files/abc"}}
           (first parts)))
    (is (= {:text "Summarize this."}
           (second parts)))))

(deftest test-build-request-inline-file-data
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        built (transport/build-request
               t profile
               {:request/model "gemini-2.5-flash"
                :request/messages
                [{:message/role :user
                  :message/content [{:part/type :file
                                     :file/data "JVBERi0x"
                                     :file/mime-type "application/pdf"}]}]})]
    (is (= {:inlineData {:mimeType "application/pdf"
                         :data "JVBERi0x"}}
           (get-in built [:body :contents 0 :parts 0])))))

(deftest test-build-request-stream-uses-stream-endpoint
  (testing "streaming flips the URL suffix to :streamGenerateContent?alt=sse"
    (let [t (gemini/make-transport)
          profile (provider/get-provider :gemini-native)
          req {:request/model "gemini-2.5-flash"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/stream? true}
          built (transport/build-request t profile req)]
      (is (re-find #":streamGenerateContent\?alt=sse$" (:url built))
          ":streamGenerateContent suffix + ?alt=sse query")
      (is (not (re-find #":generateContent[^a-zA-Z]" (:url built)))
          "no naked :generateContent in the URL"))))

(deftest test-build-request-non-stream-still-uses-generate-content
  (testing "explicit :request/stream? false keeps the unary endpoint"
    (let [t (gemini/make-transport)
          profile (provider/get-provider :gemini-native)
          req {:request/model "gemini-2.5-flash"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/stream? false}
          built (transport/build-request t profile req)]
      (is (re-find #":generateContent$" (:url built)))
      (is (not (re-find #"streamGenerateContent" (:url built)))))))

(deftest test-parse-stream-event-multi-bundle
  (testing "a single SSE chunk carrying text + usageMetadata + finishReason emits all three events"
    (let [t (gemini/make-transport)
          line (str "data: "
                    (cheshire.core/generate-string
                     {:candidates [{:content {:parts [{:text "pong"}]}
                                    :finishReason "STOP"}]
                      :usageMetadata {:promptTokenCount 8
                                      :candidatesTokenCount 1
                                      :totalTokenCount 9}}))
          events (transport/parse-stream-event t {} line)]
      (is (sequential? events) "multi-event chunks return a sequence")
      (is (= 3 (count events)))
      (is (= :stream/content-delta (:event/type (nth events 0))))
      (is (= "pong" (:event/delta (nth events 0))))
      (is (= :stream/usage (:event/type (nth events 1))))
      (is (= 8 (get-in events [1 :usage :usage/input-tokens])))
      (is (= :stream/end (:event/type (nth events 2))))
      (is (= :stop (:event/finish-reason (nth events 2)))))))

(deftest test-parse-stream-event-text-only
  (testing "a text-only chunk emits just a content-delta event"
    (let [t (gemini/make-transport)
          line (str "data: "
                    (cheshire.core/generate-string
                     {:candidates [{:content {:parts [{:text "Hello "}]}}]}))
          events (transport/parse-stream-event t {} line)]
      (is (= 1 (count events)))
      (is (= :stream/content-delta (:event/type (first events))))
      (is (= "Hello " (:event/delta (first events)))))))

(deftest test-parse-stream-event-final-finish-only
  (testing "a finish-only chunk emits a single end event"
    (let [t (gemini/make-transport)
          line (str "data: "
                    (cheshire.core/generate-string
                     {:candidates [{:finishReason "MAX_TOKENS"}]}))
          events (transport/parse-stream-event t {} line)]
      (is (= 1 (count events)))
      (is (= :stream/end (:event/type (first events))))
      (is (= :length (:event/finish-reason (first events)))))))

(deftest test-parse-stream-event-tool-call-includes-args-and-id
  (let [t (gemini/make-transport)
        line (str "data: "
                  (cheshire.core/generate-string
                   {:candidates [{:content {:parts [{:functionCall
                                                      {:id "gemini_call_1"
                                                       :name "get_weather"
                                                       :args {:location "NYC"}}
                                                      :thoughtSignature "sig-1"}]}}]}))
        events (transport/parse-stream-event t {} line)]
    (is (= [:stream/tool-call-start
            :stream/tool-call-delta
            :stream/tool-call-end]
           (mapv :event/type events)))
    (is (= "gemini_call_1" (:tool-call/id (first events))))
    (is (= "{\"location\":\"NYC\"}"
           (:tool-call/arguments-delta (second events))))
    (is (= "sig-1"
           (get-in (first events)
                   [:tool-call/provider-data :gemini/thought-signature])))
    (is (= {:id "gemini_call_1"
            :name "get_weather"
            :args {:location "NYC"}}
           (get-in (first events)
                   [:tool-call/provider-data :gemini/function-call])))))

(deftest test-stream-response-replays-indexed-thoughts-and-multiple-tools
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        sse (fn [payload]
              (str "data: " (cheshire.core/generate-string payload)))
        chunks
        [{:candidates [{:content {:parts [{:text "Plan "
                                          :thought true}]}}]}
         {:candidates [{:content {:parts [{:text "carefully"
                                          :thought true
                                          :thoughtSignature "thought-sig"}]}}]}
         {:candidates [{:content
                        {:parts [{:functionCall {:id "call-a"
                                                 :name "first_tool"
                                                 :args {:n 1
                                                        "tenant/id" "acme"}}
                                  :thoughtSignature "call-sig-a"}]}}]}
         {:candidates [{:content
                        {:parts [{:functionCall {:id "call-b"
                                                 :name "second_tool"
                                                 :args {:n 2}}
                                  :thoughtSignature "call-sig-b"}]}
                        :finishReason "STOP"}]}]
        events (mapcat #(transport/parse-stream-event t profile (sse %))
                       chunks)
        response (stream/events->response events :gemini-native
                                         "gemini-3.5-flash")
        rebuilt (transport/build-request
                 t profile
                 {:request/model "gemini-3.5-flash"
                  :request/messages
                  [{:message/role :assistant
                    :message/content (:response/parts response)
                    :message/tool-calls (:response/tool-calls response)}
                   {:message/role :user :message/content "Continue"}]})
        replay-parts (get-in rebuilt [:body :contents 0 :parts])]
    (is (= "thought-sig"
           (get-in response [:response/parts 0 :reasoning/signature])))
    (is (= #{"call-a" "call-b"}
           (set (map :tool-call/id (:response/tool-calls response)))))
    (is (= 3 (count replay-parts))
        "tool calls present in content and message metadata are not duplicated")
    (is (= {:text "Plan carefully"
            :thought true
            :thoughtSignature "thought-sig"}
           (first replay-parts)))
    (is (= (cheshire.core/parse-string
            (cheshire.core/generate-string
             [{:text "Plan carefully"
               :thought true
               :thoughtSignature "thought-sig"}
              {:functionCall {:id "call-a"
                              :name "first_tool"
                              :args {:n 1
                                     "tenant/id" "acme"}}
               :thoughtSignature "call-sig-a"}
              {:functionCall {:id "call-b"
                              :name "second_tool"
                              :args {:n 2}}
               :thoughtSignature "call-sig-b"}]))
           (cheshire.core/parse-string
            (cheshire.core/generate-string replay-parts))))))

(deftest test-stream-response-replays-signed-output-part-from-native-state
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        line (str "data: "
                  (cheshire.core/generate-string
                   {:candidates
                    [{:content
                      {:parts [{:text "answer"
                                :thoughtSignature "output-sig"}]}}]}))
        events (transport/parse-stream-event t profile line)
        response (stream/events->response events :gemini-native
                                         "gemini-3.5-flash")
        rebuilt (transport/build-request
                 t profile
                 {:request/model "gemini-3.5-flash"
                  :request/messages
                  [{:message/role :assistant
                    :message/content (:response/parts response)
                    :message/provider-data (:response/provider-data response)}
                   {:message/role :user :message/content "Continue"}]})]
    (is (= :gemini-native
           (:provider-state/provider (second events))))
    (is (= {:text "answer" :thoughtSignature "output-sig"}
           (get-in rebuilt [:body :contents 0 :parts 0])))))

(deftest test-stream-usage-keeps-zero-and-does-not-invent-partial-counters
  (let [t (gemini/make-transport)
        line (str "data: "
                  (cheshire.core/generate-string
                   {:usageMetadata {:candidatesTokenCount 0}}))
        usage (:usage (first (transport/parse-stream-event t {} line)))]
    (is (= 0 (:usage/output-tokens usage)))
    (is (not (contains? usage :usage/input-tokens)))
    (is (not (contains? usage :usage/total-tokens)))))

(deftest test-build-request-tools
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        req {:request/model "gemini-2.5-pro"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/tools [{:type :function
                              :function
                              {:name "get_weather"
                               :parameters
                               {:type :object
                                :properties {:location {:type :string}}}}}]}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built
                            [:body :tools 0 :functionDeclarations]))))
    (is (= {:type :object
            :properties {:location {:type :string}}}
           (get-in built
                   [:body :tools 0 :functionDeclarations 0
                    :parametersJsonSchema])))
    (is (nil? (get-in built
                      [:body :tools 0 :functionDeclarations 0 :parameters])))))

(deftest test-build-request-supports-audio-and-rejects-unsupported-forms
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        base {:request/model "gemini-2.5-flash"}
        failure
        (fn [request]
          (try
            (transport/build-request t profile request)
            nil
            (catch clojure.lang.ExceptionInfo e
              (ex-data e))))
        audio (transport/build-request
               t profile
               (assoc base
                      :request/messages
                      [{:message/role :user
                        :message/content
                        [{:part/type :input-audio
                          :audio/data "UklGRg=="
                          :audio/format :wav}]}]))]
    (is (= {:inlineData {:mimeType "audio/wav" :data "UklGRg=="}}
           (get-in audio [:body :contents 0 :parts 0])))
    (is (= :gemini/unsupported-tool
           (:error/type
            (failure
             (assoc base
                    :request/messages [{:message/role :user
                                        :message/content "Hi"}]
                    :request/tools
                    [{:type :custom
                      :custom {:name "grammar"
                               :format {:type :text}}}])))))
    (is (= :gemini/unsupported-tool-choice
           (:error/type
            (failure
             (assoc base
                    :request/messages [{:message/role :user
                                        :message/content "Hi"}]
                    :request/tool-choice
                    {:type :custom :custom {:name "grammar"}})))))
    (is (= :gemini/unsupported-input
           (:error/type
            (failure
             (assoc base
                    :request/messages
                    [{:message/role :user
                      :message/content
                      [{:part/type :safety
                        :safety/category "x"
                        :safety/severity "low"
                        :safety/blocked false}]}])))))
    (is (= :gemini/unsupported-input
           (:error/type
            (failure
             (assoc base
                    :request/messages
                    [{:message/role :user
                      :message/content
                      [{:part/type :file :file/id "files/only-id"}]}])))))))

(deftest test-parse-response-text
  (let [t (gemini/make-transport)
        raw {:candidates [{:content {:parts [{:text "Hello!"}]}
                          :finishReason "STOP"}]
             :usageMetadata {:promptTokenCount 10
                             :candidatesTokenCount 5
                             :totalTokenCount 15}}
        resp (transport/parse-response t {} raw)]
    (is (= :gemini-native (:response/provider resp)))
    (is (= :stop (:response/finish-reason resp)))
    (is (= [{:part/type :text :text "Hello!"}] (:response/parts resp)))))

(deftest test-parse-response-tool-call
  (let [t (gemini/make-transport)
        raw {:candidates [{:content {:parts [{:functionCall {:name "get_weather"
                                                             :args {:location "NYC"}}}]}
                          :finishReason "STOP"}]
             :usageMetadata {:promptTokenCount 20 :candidatesTokenCount 10}}
        resp (transport/parse-response t {} raw)]
    (is (= 1 (count (:response/tool-calls resp))))
    (is (= "get_weather"
           (get-in resp [:response/tool-calls 0 :tool-call/name])))
    (is (= :tool-calls (:response/finish-reason resp)))))

(deftest test-parse-response-tool-call-preserves-provider-id
  (let [t (gemini/make-transport)
        raw {:candidates [{:content {:parts [{:functionCall {:id "call_from_provider"
                                                             :name "get_weather"
                                                             :args {:location "NYC"}}
                                             :thoughtSignature "sig-1"}]}
                          :finishReason "STOP"}]}
        resp (transport/parse-response t {} raw)]
    (is (= "call_from_provider"
           (get-in resp [:response/tool-calls 0 :tool-call/id])))
    (is (= "sig-1"
           (get-in resp [:response/tool-calls 0
                         :tool-call/provider-data
                         :gemini/thought-signature])))))

(deftest test-parse-and-replay-signature-on-ordinary-part
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        parsed (transport/parse-response
                t profile
                {:candidates
                 [{:content {:parts [{:text "final"
                                      :thoughtSignature "text-signature"}]}
                   :finishReason "STOP"}]
                 :modelVersion "gemini-3.5-flash"})
        rebuilt (transport/build-request
                 t profile
                 {:request/model "gemini-3.5-flash"
                  :request/messages
                  [{:message/role :assistant
                    :message/content (:response/parts parsed)}
                   {:message/role :user :message/content "Continue"}]})]
    (is (= :provider-state
           (get-in parsed [:response/parts 1 :part/type])))
    (is (= :gemini-native
           (get-in parsed [:response/parts 1 :provider-state/provider])))
    (is (= {:text "final" :thoughtSignature "text-signature"}
           (get-in rebuilt [:body :contents 0 :parts 0])))))

(deftest test-parse-response-preserves-citation-grounding-and-unknown-state
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        citation {:startIndex 0
                  :endIndex 4
                  :uri "https://example.test/source"
                  :license "MIT"}
        grounding {:groundingChunks [{:web {:uri "https://example.test"
                                            :title "Example"}}]}
        parsed (transport/parse-response
                t profile
                {:candidates
                 [{:content {:parts [{:text "fact"}
                                     {:executableCode
                                      {:language "PYTHON"
                                       :code "print(1)"}}]}
                   :finishReason "STOP"
                   :citationMetadata {:citationSources [citation]}
                   :groundingMetadata grounding}]})
        unknown (first (filter #(= :unknown/provider-native (:part/type %))
                               (:response/parts parsed)))
        rebuilt (transport/build-request
                 t profile
                 {:request/model "gemini-3.5-flash"
                  :request/messages
                  [{:message/role :assistant :message/content [unknown]}
                   {:message/role :user :message/content "Continue"}]})]
    (is (= {:part/type :citation
            :citation/source :gemini-native
            :citation/provider-data {:gemini/citation-source citation}
            :citation/url "https://example.test/source"
            :citation/text-range [0 4]}
           (first (filter #(= :citation (:part/type %))
                          (:response/parts parsed)))))
    (is (= grounding
           (get-in parsed
                   [:response/provider-data :gemini/grounding-metadata])))
    (is (= {:executableCode {:language "PYTHON" :code "print(1)"}}
           (get-in rebuilt [:body :contents 0 :parts 0])))))

(deftest test-parse-response-current-parts-safety-usage-and-finish
  (let [t (gemini/make-transport)
        raw {:candidates
             [{:content
               {:parts
                [{:inlineData {:mimeType "image/png" :data "iVBORw=="}}
                 {:executableCode {:language "PYTHON" :code "print(1)"}}]}
               :finishReason "MALFORMED_RESPONSE"
               :safetyRatings
               [{:category "HARM_CATEGORY_HARASSMENT"
                 :probability "LOW"
                 :blocked false}]}]
             :usageMetadata {:promptTokenCount 10
                             :candidatesTokenCount 4
                             :thoughtsTokenCount 3
                             :totalTokenCount 17}
             :modelVersion "gemini-3.5-flash"
             :responseId "response-1"}
        resp (transport/parse-response t {} raw)]
    (is (= :incomplete (:response/finish-reason resp)))
    (is (= {:part/type :image
            :image/data "iVBORw=="
            :image/mime-type "image/png"}
           (first (:response/parts resp))))
    (is (= :unknown/provider-native
           (get-in resp [:response/parts 1 :part/type])))
    (is (= :gemini-native
           (get-in resp [:response/parts 1 :unknown/provider])))
    (is (= "HARM_CATEGORY_HARASSMENT"
           (get-in resp [:response/parts 2 :safety/category])))
    (is (schema/validate-response resp))
    (is (= 3 (get-in resp [:response/usage :usage/reasoning-tokens])))
    (is (= "response-1"
           (get-in resp [:response/provider-data :gemini/response-id])))))

(deftest test-parse-response-prompt-block-without-candidate
  (let [t (gemini/make-transport)
        raw {:promptFeedback
             {:blockReason "PROHIBITED_CONTENT"
              :safetyRatings
              [{:category "HARM_CATEGORY_DANGEROUS_CONTENT"
                :probability "MEDIUM"
                :blocked true}]}}
        resp (transport/parse-response t {} raw)]
    (is (= :content-filter (:response/finish-reason resp)))
    (is (true? (get-in resp [:response/parts 0 :safety/blocked])))
    (is (= :prompt
           (get-in resp [:response/parts 0 :safety/details :source])))))

;; ---------------------------------------------------------------------------
;; Caching wiring (explicit cachedContent)
;; ---------------------------------------------------------------------------

(deftest test-cache-explicit-cached-content
  (testing "Gemini receives cachedContent reference when :cached-content-id supplied"
    (let [t (gemini/make-transport)
          profile (provider/get-provider :gemini-native)
          req {:request/model "gemini-2.5-pro"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/cache {:cached-content-id "cachedContents/abc123"}}
          built (transport/build-request t profile req)]
      (is (= "cachedContents/abc123" (get-in built [:body :cachedContent]))))))

(deftest test-cache-no-cachedContent-when-disabled
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        req {:request/model "gemini-2.5-pro"
             :request/messages [{:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)]
    (is (nil? (get-in built [:body :cachedContent])))))
