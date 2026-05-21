(ns llm.sdk.providers.cohere-embed-test
  "Cohere /embed adapter coverage (T2-07). Cohere is the only embed
   provider in the T2-07 batch with a divergent wire shape; the other
   four (Voyage, Mistral, Together, Jina) ride the OpenAI embed
   adapter and are covered alongside it under
   openai_embed_aliases_test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.providers.cohere-embed :as cohere-embed]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Profile registration
;; ---------------------------------------------------------------------------

(deftest test-cohere-profile-carries-embed-transport
  (let [p (provider/get-provider :cohere)]
    (is (some? p))
    (is (= "https://api.cohere.com/v1" (:profile/base-url p)))
    (is (= ["COHERE_API_KEY"] (:profile/env-var-names p)))
    (is (fn? (:profile/embed-transport-constructor p)))
    (is (contains? (:profile/capabilities p) :embedding))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(deftest test-build-request-default-input-type
  (testing "missing :input-type defaults to search_document"
    (let [t (cohere-embed/make-transport)
          profile (provider/get-provider :cohere)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "embed-english-v3.0"
                    :embed/inputs ["a" "b"]}))]
      (is (= "https://api.cohere.com/v1/embed" (:url built)))
      (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
      (is (= "embed-english-v3.0" (get-in built [:body :model])))
      (is (= ["a" "b"] (get-in built [:body :texts]))
          "Cohere uses :texts, not :input")
      (is (= "search_document" (get-in built [:body :input_type]))))))

(deftest test-build-request-custom-input-type
  (let [t (cohere-embed/make-transport)
        profile (provider/get-provider :cohere)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (et/build-embed-request
                 t profile
                 {:embed/model "embed-english-v3.0"
                  :embed/inputs ["query phrase"]
                  :embed/provider-options {:input-type "search_query"
                                           :truncate "END"}}))]
    (is (= "search_query" (get-in built [:body :input_type])))
    (is (= "END" (get-in built [:body :truncate])))))

(deftest test-build-request-encoding-format-maps-to-embedding-types
  (testing ":embed/encoding-format becomes Cohere's embedding_types vector"
    (let [t (cohere-embed/make-transport)
          profile (provider/get-provider :cohere)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "embed-english-v3.0"
                    :embed/inputs ["a"]
                    :embed/encoding-format :float}))]
      (is (= ["float"] (get-in built [:body :embedding_types]))))))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (cohere-embed/make-transport)
        profile (provider/get-provider :cohere)
        raw (load-fixture "fixtures/cohere_embed_response.json")
        resp (et/parse-embed-response t profile raw)]
    (is (= :cohere (:embed/provider resp)))
    (is (= 2 (count (:embed/vectors resp))))
    (is (= 8 (count (first (:embed/vectors resp)))))
    (is (= 8 (:embed/dimensions resp)))
    (is (= 5 (get-in resp [:response/usage :usage/input-tokens])))
    (is (= 5 (get-in resp [:response/usage :usage/total-tokens])))))

(deftest test-parse-response-legacy-array-embeddings
  (testing "older Cohere API returns :embeddings as a top-level vector"
    (let [t (cohere-embed/make-transport)
          profile (provider/get-provider :cohere)
          raw {:texts ["a"]
               :embeddings [[0.1 0.2 0.3]]
               :meta {:billed_units {:input_tokens 2}}}
          resp (et/parse-embed-response t profile raw)]
      (is (= [[0.1 0.2 0.3]] (:embed/vectors resp)))
      (is (= 3 (:embed/dimensions resp))))))

(deftest test-parse-response-missing-billed-units
  (testing "Cohere occasionally returns no meta — should not throw"
    (let [t (cohere-embed/make-transport)
          profile (provider/get-provider :cohere)
          raw {:embeddings {:float [[0.1 0.2]]}}
          resp (et/parse-embed-response t profile raw)]
      (is (= [[0.1 0.2]] (:embed/vectors resp)))
      (is (nil? (:response/usage resp))))))

;; ---------------------------------------------------------------------------
;; Driver path
;; ---------------------------------------------------------------------------

(deftest test-embed-via-cohere-driver-routes-to-cohere-transport
  (testing "sdk/embed picks up :cohere's embed transport constructor"
    (let [profile (provider/get-provider :cohere)
          transport ((:profile/embed-transport-constructor profile))]
      (is (instance? llm.sdk.providers.cohere_embed.CohereEmbedTransport
                     transport)))))
