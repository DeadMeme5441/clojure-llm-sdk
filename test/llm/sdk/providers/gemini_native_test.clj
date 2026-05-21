(ns llm.sdk.providers.gemini-native-test
  (:require [clojure.test :refer [deftest is testing]]
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
