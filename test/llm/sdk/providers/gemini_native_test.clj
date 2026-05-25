(ns llm.sdk.providers.gemini-native-test
  (:require [cheshire.core]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
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
            :stream/tool-call-end
            :stream/provider-state]
           (mapv :event/type events)))
    (is (= "gemini_call_1" (:tool-call/id (first events))))
    (is (= "{\"location\":\"NYC\"}"
           (:tool-call/arguments-delta (second events))))
    (is (= "sig-1"
           (get-in (last events) [:provider-state/data :parts 0 :thoughtSignature])))))

(deftest test-build-request-tools
  (let [t (gemini/make-transport)
        profile (provider/get-provider :gemini-native)
        req {:request/model "gemini-2.5-pro"
             :request/messages [{:message/role :user :message/content "Hi"}]
             :request/tools [{:type :function
                              :function {:name "get_weather"
                                         :parameters {:type :object
                                                      :properties {:location {:type :string}}}}}]}
        built (transport/build-request t profile req)]
    (is (= 1 (count (get-in built [:body :tools 0 :functionDeclarations]))))))

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
    (is (= "get_weather" (get-in resp [:response/tool-calls 0 :tool-call/name])))))

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
