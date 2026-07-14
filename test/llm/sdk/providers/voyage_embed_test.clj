(ns llm.sdk.providers.voyage-embed-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.voyage.embeddings :as voyage]
            [llm.sdk.transport.embed :as et]))

(deftest test-voyage-profile-uses-dedicated-transport
  (let [profile (provider/get-provider :voyage)]
    (is (fn? (:profile/embed-transport-constructor profile)))
    (is (instance? llm.sdk.providers.voyage.embeddings.VoyageEmbedTransport
                   ((:profile/embed-transport-constructor profile))))))

(deftest test-build-current-voyage-request
  (let [profile (provider/get-provider :voyage)
        built (with-redefs [provider/resolve-auth-token (constantly "stub")]
                (et/build-embed-request
                 (voyage/make-transport)
                 profile
                 {:embed/model "voyage-4-large"
                  :embed/inputs ["query"]
                  :embed/dimensions 512
                  :embed/encoding-format :float
                  :embed/provider-options {:input-type "query"
                                           :truncation false}}))]
    (is (= "https://api.voyageai.com/v1/embeddings" (:url built)))
    (is (= "query" (get-in built [:body :input])))
    (is (= "query" (get-in built [:body :input_type])))
    (is (false? (get-in built [:body :truncation])))
    (is (= 512 (get-in built [:body :output_dimension])))
    (is (= "float" (get-in built [:body :output_dtype])))
    (is (nil? (get-in built [:body :encoding_format]))
        "Voyage only accepts base64 for encoding_format")))

(deftest test-build-base64-voyage-request
  (let [built (et/build-embed-request
               (voyage/make-transport)
               (provider/get-provider :voyage)
               {:embed/model "voyage-4"
                :embed/inputs ["a" "b"]
                :embed/encoding-format :base64})]
    (is (= ["a" "b"] (get-in built [:body :input])))
    (is (= "base64" (get-in built [:body :encoding_format])))))

(deftest test-parse-current-voyage-response
  (let [parsed (et/parse-embed-response
                (voyage/make-transport)
                (provider/get-provider :voyage)
                {:object "list"
                 :model "voyage-4-large"
                 :data [{:object "embedding" :index 1
                         :embedding [3.0 4.0]}
                        {:object "embedding" :index 0
                         :embedding [1.0 2.0]}]
                 :usage {:total_tokens 11}})]
    (is (= [[1.0 2.0] [3.0 4.0]] (:embed/vectors parsed)))
    (is (= 2 (:embed/dimensions parsed)))
    (is (= 11 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 11 (get-in parsed [:response/usage :usage/total-tokens])))))

(deftest test-parse-voyage-base64-float-response
  (testing "canonical vectors remain numeric for Voyage base64 encoding"
    (let [parsed (et/parse-embed-response
                  (voyage/make-transport)
                  (provider/get-provider :voyage)
                  {:model "voyage-4"
                   :data [{:index 0 :embedding "AACAPwAAAEA="}]})]
      (is (= [[1.0 2.0]] (:embed/vectors parsed))))))
