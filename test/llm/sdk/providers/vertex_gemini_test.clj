(ns llm.sdk.providers.vertex-gemini-test
  (:require [cheshire.core]
            [clojure.test :refer [deftest is]]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.gemini.vertex :as vertex]))

(defn- build [request]
  (with-redefs [gcp-auth/resolve-access-token (fn [_ _] "stub-token")]
    (transport/build-request
     (vertex/make-transport)
     (provider/get-provider :vertex-gemini)
     request)))

(deftest test-build-request-current-vertex-route-and-routing-config
  (let [built (build
               {:request/model "gemini-3.5-flash"
                :request/messages [{:message/role :user
                                    :message/content "Hello"}]
                :request/response-format
                {:type :json_schema
                 :json-schema {:type "object"
                               :properties {:answer {:type "string"}}}}
                :request/provider-options
                {:vertex {:project "project-1" :location "global"}
                 :extra_body
                 {:generationConfig
                  {:routingConfig
                   {:autoMode {:modelRoutingPreference "PRIORITIZE_QUALITY"}}}}}})]
    (is (= "https://aiplatform.googleapis.com/v1/projects/project-1/locations/global/publishers/google/models/gemini-3.5-flash:generateContent"
           (:url built)))
    (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
    (is (= "application/json"
           (get-in built [:body :generationConfig :responseMimeType])))
    (is (= {:type "object"
            :properties {:answer {:type "string"}}}
           (get-in built
                   [:body :generationConfig :responseJsonSchema])))
    (is (= "PRIORITIZE_QUALITY"
           (get-in built
                   [:body :generationConfig :routingConfig :autoMode
                    :modelRoutingPreference])))))

(deftest test-build-request-qualifies-vertex-cache-resource
  (let [built (build
               {:request/model "models/gemini-2.5-flash"
                :request/messages [{:message/role :user
                                    :message/content "Continue"}]
                :request/cache
                {:cached-content-id "cachedContents/cache-1"}
                :request/provider-options
                {:vertex {:project "p" :location "us-central1"}}})]
    (is (= "projects/p/locations/us-central1/cachedContents/cache-1"
           (get-in built [:body :cachedContent])))))

(deftest test-build-stream-request-uses-vertex-sse-endpoint
  (let [built (build
               {:request/model "gemini-2.5-flash"
                :request/messages [{:message/role :user
                                    :message/content "Hello"}]
                :request/stream? true
                :request/provider-options
                {:vertex {:project "p" :location "us-central1"}}})]
    (is (.endsWith ^String (:url built) ":streamGenerateContent?alt=sse"))))

(deftest test-parse-response-retains-vertex-provider-identity
  (let [t (vertex/make-transport)
        profile (provider/get-provider :vertex-gemini)
        parsed (transport/parse-response
                t profile
                {:candidates [{:content {:parts [{:text "ok"}]}
                               :finishReason "STOP"}]
                 :modelVersion "gemini-3.5-flash"
                 :usageMetadata {:promptTokenCount 2
                                 :candidatesTokenCount 1
                                 :totalTokenCount 3}})]
    (is (= :vertex-gemini (:response/provider parsed)))
    (is (= "gemini-3.5-flash" (:response/model parsed)))
    (is (= :stop (:response/finish-reason parsed)))))

(deftest test-vertex-full-response-replays-native-signatures-and-call-id
  (let [t (vertex/make-transport)
        profile (provider/get-provider :vertex-gemini)
        parsed
        (transport/parse-response
         t profile
         {:candidates
          [{:content
            {:parts
             [{:text "plan"
               :thought true
               :thoughtSignature "reasoning-sig"}
              {:functionCall {:id "vertex-call"
                              :name "lookup"
                              :args {:q "x"
                                     "filter.with.dot" true}}
               :thoughtSignature "tool-sig"}
              {:text "done"
               :thoughtSignature "text-sig"}]}
            :finishReason "STOP"}]})
        rebuilt
        (build
         {:request/model "gemini-3.5-flash"
          :request/messages
          [{:message/role :assistant
            :message/content (:response/parts parsed)
            :message/tool-calls (:response/tool-calls parsed)}
           {:message/role :user :message/content "Continue"}]
          :request/provider-options
          {:vertex {:project "p" :location "us-central1"}}})
        replay-parts (get-in rebuilt [:body :contents 0 :parts])]
    (is (= :tool-calls (:response/finish-reason parsed)))
    (is (= :vertex-gemini
           (get-in parsed [:response/parts 3 :provider-state/provider])))
    (is (= (cheshire.core/parse-string
            (cheshire.core/generate-string
             [{:text "plan"
               :thought true
               :thoughtSignature "reasoning-sig"}
              {:functionCall {:id "vertex-call"
                              :name "lookup"
                              :args {:q "x"
                                     "filter.with.dot" true}}
               :thoughtSignature "tool-sig"}
              {:text "done" :thoughtSignature "text-sig"}]))
           (cheshire.core/parse-string
            (cheshire.core/generate-string replay-parts))))))

(deftest test-vertex-stream-state-and-signatures-stay-vertex-native
  (let [t (vertex/make-transport)
        profile (provider/get-provider :vertex-gemini)
        sse (fn [payload]
              (str "data: " (cheshire.core/generate-string payload)))
        chunks
        [{:candidates [{:content {:parts [{:text "think"
                                           :thought true}]}}]}
         {:candidates [{:content {:parts [{:thought true
                                           :thoughtSignature "vertex-thought"}]}}]}
         {:candidates
          [{:content
            {:parts [{:functionCall {:id "vertex-stream-call"
                                     :name "lookup"
                                     :args {:q "x"}}
                      :thoughtSignature "vertex-tool"}]}
            :finishReason "STOP"
            :groundingMetadata
            {:groundingChunks [{:web {:uri "https://example.test"}}]}}]}]
        events (mapcat #(transport/parse-stream-event t profile (sse %))
                       chunks)
        response (stream/events->response events :vertex-gemini
                                          "gemini-3.5-flash")
        rebuilt
        (build
         {:request/model "gemini-3.5-flash"
          :request/messages
          [{:message/role :assistant
            :message/content (:response/parts response)
            :message/tool-calls (:response/tool-calls response)}
           {:message/role :user :message/content "Continue"}]
          :request/provider-options
          {:vertex {:project "p" :location "us-central1"}}})
        provider-state-events
        (filter #(= :stream/provider-state (:event/type %)) events)]
    (is (every? #(= :vertex-gemini (:provider-state/provider %))
                provider-state-events))
    (is (= "vertex-thought"
           (get-in response [:response/parts 0 :reasoning/signature])))
    (is (= "vertex-tool"
           (get-in response
                   [:response/tool-calls 0 :tool-call/provider-data
                    :gemini/thought-signature])))
    (is (= {:groundingChunks
            [{:web {:uri "https://example.test"}}]}
           (get-in response
                   [:response/provider-data :vertex-gemini
                    :gemini/candidate-state :groundingMetadata])))
    (is (= "vertex-thought"
           (get-in rebuilt
                   [:body :contents 0 :parts 0 :thoughtSignature])))
    (is (= "vertex-stream-call"
           (get-in rebuilt
                   [:body :contents 0 :parts 1 :functionCall :id])))
    (is (= "vertex-tool"
           (get-in rebuilt
                   [:body :contents 0 :parts 1 :thoughtSignature])))))
