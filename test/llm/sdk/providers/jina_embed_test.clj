(ns llm.sdk.providers.jina-embed-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.jina.embeddings :as jina]
            [llm.sdk.transport.embed :as et]))

(deftest test-jina-profile-uses-dedicated-transport
  (let [profile (provider/get-provider :jina)]
    (is (fn? (:profile/embed-transport-constructor profile)))
    (is (instance? llm.sdk.providers.jina.embeddings.JinaEmbedTransport
                   ((:profile/embed-transport-constructor profile))))))

(deftest test-build-current-jina-request
  (let [profile (provider/get-provider :jina)
        built (with-redefs [provider/resolve-auth-token (constantly "stub")]
                (et/build-embed-request
                 (jina/make-transport)
                 profile
                 {:embed/model "jina-embeddings-v5-text-small"
                  :embed/inputs ["query"]
                  :embed/dimensions 512
                  :embed/encoding-format :float
                  :embed/provider-options {:task "retrieval.query"
                                           :normalized true
                                           :truncate false}}))]
    (is (= "https://api.jina.ai/v1/embeddings" (:url built)))
    (is (= "query" (get-in built [:body :input])))
    (is (= 512 (get-in built [:body :dimensions])))
    (is (= "float" (get-in built [:body :embedding_type])))
    (is (nil? (get-in built [:body :encoding_format])))
    (is (= "retrieval.query" (get-in built [:body :task])))
    (is (true? (get-in built [:body :normalized])))
    (is (false? (get-in built [:body :truncate])))))

(deftest test-build-jina-v4-options
  (let [built (et/build-embed-request
               (jina/make-transport)
               (provider/get-provider :jina)
               {:embed/model "jina-embeddings-v4"
                :embed/inputs ["a" "b"]
                :embed/encoding-format :base64
                :embed/provider-options {:late-chunking true
                                         :return-multivector false
                                         :return-tokenized-input false}})]
    (is (= ["a" "b"] (get-in built [:body :input])))
    (is (= "base64" (get-in built [:body :embedding_type])))
    (is (true? (get-in built [:body :late_chunking])))
    (is (false? (get-in built [:body :return_multivector])))
    (is (false? (get-in built [:body :return_tokenized_input])))))

(deftest test-parse-current-jina-response
  (let [parsed (et/parse-embed-response
                (jina/make-transport)
                (provider/get-provider :jina)
                {:model "jina-embeddings-v5-text-small"
                 :object "list"
                 :usage {:prompt_tokens 8
                         :total_tokens 12
                         :image_tokens 4}
                 :data [{:object "embedding" :index 1
                         :embedding [3.0 4.0]}
                        {:object "embedding" :index 0
                         :embedding [1.0 2.0]}]})]
    (is (= :jina (:embed/provider parsed)))
    (is (= [[1.0 2.0] [3.0 4.0]] (:embed/vectors parsed)))
    (is (= 2 (:embed/dimensions parsed)))
    (is (= 8 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 12 (get-in parsed [:response/usage :usage/total-tokens])))
    (is (= 4 (get-in parsed [:response/usage :usage/image-tokens])))))

(deftest test-parse-jina-base64-float-response
  (let [parsed (et/parse-embed-response
                (jina/make-transport)
                (provider/get-provider :jina)
                {:model "jina-embeddings-v5-text-small"
                 :usage {:prompt_tokens 1 :total_tokens 1}
                 :data [{:index 0 :embedding "AACAPwAAAEA="}]})]
    (is (= [[1.0 2.0]] (:embed/vectors parsed)))))
