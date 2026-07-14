(ns llm.sdk.providers.vertex-gemini-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.vertex-gemini :as vertex]))

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
                :request/response-format {:type :json_object}
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
