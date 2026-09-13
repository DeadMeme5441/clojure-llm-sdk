(ns llm.sdk.providers.gemini-embed-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.gemini.embeddings :as gemini]
            [llm.sdk.transport.embed :as et]))

(deftest test-gemini-native-driver-wire-to-response
  (let [sent (atom nil)
        response
        (with-redefs [http/request
                      (fn [request]
                        (reset! sent request)
                        {:status 200
                         :body {:embeddings [{:values [0.1 0.2 0.3]}
                                             {:values [0.4 0.5 0.6]}]
                                :usageMetadata
                                {:promptTokenCount 7
                                 :promptTokenDetails
                                 [{:modality "TEXT" :tokenCount 7}]}}})]
          (sdk/embed
           :gemini-native
           {:embed/model "models/gemini-embedding-001"
            :embed/inputs ["first document" "second document"]
            :embed/dimensions 3
            :embed/encoding-format :float
            :embed/provider-options {:task-type :retrieval-document
                                     :title "Corpus title"}}
           :config {:api-key "runtime-key"}))]
    (is (= "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents"
           (:url @sent)))
    (is (= "runtime-key" (get-in @sent [:headers "x-goog-api-key"])))
    (is (= [{:model "models/gemini-embedding-001"
             :content {:parts [{:text "first document"}]}
             :embedContentConfig {:taskType "RETRIEVAL_DOCUMENT"
                                  :title "Corpus title"
                                  :outputDimensionality 3}}
            {:model "models/gemini-embedding-001"
             :content {:parts [{:text "second document"}]}
             :embedContentConfig {:taskType "RETRIEVAL_DOCUMENT"
                                  :title "Corpus title"
                                  :outputDimensionality 3}}]
           (get-in @sent [:body :requests])))
    (is (= [[0.1 0.2 0.3] [0.4 0.5 0.6]] (:embed/vectors response)))
    (is (= 3 (:embed/dimensions response)))
    (is (= "models/gemini-embedding-001" (:embed/model response)))
    (is (= 7 (get-in response [:response/usage :usage/input-tokens])))
    (is (= 7 (get-in response [:response/usage :usage/total-tokens])))))

(deftest test-gemini-native-rejects-base64-encoding
  (let [error
        (try
          (et/build-embed-request
           (gemini/make-transport)
           (provider/get-provider :gemini-native)
           {:embed/model "gemini-embedding-001"
            :embed/inputs ["query"]
            :embed/encoding-format :base64})
          nil
          (catch clojure.lang.ExceptionInfo error error))]
    (is (= {:provider :gemini-native
            :error/type :request/unsupported-embedding-encoding
            :encoding-format :base64}
           (ex-data error)))))
