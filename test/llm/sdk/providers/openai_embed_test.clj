(ns llm.sdk.providers.openai-embed-test
  "Adapter-level coverage for the OpenAI embed transport (T2-01).

   Verifies request body shape (single vs multi input), URL, auth
   header, response parsing against a golden fixture, and the
   dimensions/usage round-trip."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.providers.openai-embed :as openai-embed]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(deftest test-build-request-single-input-unwraps
  (testing "a one-element :embed/inputs vector serializes as a string"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-small"
                    :embed/inputs ["hello world"]}))]
      (is (= "https://api.openai.com/v1/embeddings" (:url built)))
      (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
      (is (= "text-embedding-3-small" (get-in built [:body :model])))
      (is (= "hello world" (get-in built [:body :input]))))))

(deftest test-build-request-multi-input-stays-vector
  (testing "multi-input request serializes as a vector"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-small"
                    :embed/inputs ["a" "b" "c"]}))]
      (is (= ["a" "b" "c"] (get-in built [:body :input]))))))

(deftest test-build-request-optional-fields
  (testing "dimensions / encoding-format / user surface on the body"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-large"
                    :embed/inputs ["hi"]
                    :embed/dimensions 256
                    :embed/encoding-format :base64
                    :embed/user "u-1"}))
          body (:body built)]
      (is (= 256 (:dimensions body)))
      (is (= "base64" (:encoding_format body)))
      (is (= "u-1" (:user body))))))

(deftest test-build-request-extra-body-merges
  (testing ":embed/provider-options :extra_body keys land on the body"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub-token")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "text-embedding-3-small"
                    :embed/inputs ["a"]
                    :embed/provider-options {:extra_body {:custom-flag true}}}))]
      (is (true? (get-in built [:body :custom-flag]))))))

;; ---------------------------------------------------------------------------
;; Response parsing — golden fixture
;; ---------------------------------------------------------------------------

(deftest test-parse-response-from-fixture
  (let [t (openai-embed/make-transport)
        profile (provider/get-provider :openai)
        raw (load-fixture "fixtures/openai_embed_response.json")
        resp (et/parse-embed-response t profile raw)]
    (is (= :openai (:embed/provider resp)))
    (is (= "text-embedding-3-small" (:embed/model resp)))
    (is (= 2 (count (:embed/vectors resp))))
    (is (every? vector? (:embed/vectors resp)))
    (is (= 8 (count (first (:embed/vectors resp)))))
    (is (= 8 (:embed/dimensions resp)))
    (is (= 7 (get-in resp [:response/usage :usage/input-tokens])))
    (is (= 7 (get-in resp [:response/usage :usage/total-tokens])))
    (is (= 0 (get-in resp [:response/usage :usage/output-tokens])))
    ;; raw preserved for callers that need provider-specific fields.
    (is (= raw (:embed/raw resp)))))

(deftest test-parse-response-sorts-by-index
  (testing "out-of-order :data is sorted before extracting vectors"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :openai)
          raw {:object "list"
               :model "text-embedding-3-small"
               :data [{:index 2 :embedding [0.3 0.4]}
                      {:index 0 :embedding [0.1 0.2]}
                      {:index 1 :embedding [0.5 0.6]}]
               :usage {:prompt_tokens 10 :total_tokens 10}}
          resp (et/parse-embed-response t profile raw)]
      (is (= [[0.1 0.2] [0.5 0.6] [0.3 0.4]]
             (:embed/vectors resp))))))

;; ---------------------------------------------------------------------------
;; Error classification
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (openai-embed/make-transport)
        profile (provider/get-provider :openai)
        err (et/parse-embed-error
             t profile 401
             {:error {:message "Invalid API key" :type "invalid_request_error"}})]
    (is (= :auth (:error/reason err)))
    (is (false? (:error/retryable err)))))

(deftest test-parse-error-429
  (let [t (openai-embed/make-transport)
        profile (provider/get-provider :openai)
        err (et/parse-embed-error
             t profile 429
             {:error {:message "Rate limit reached"}})]
    (is (= :rate-limit (:error/reason err)))
    (is (true? (:error/retryable err)))))

;; ---------------------------------------------------------------------------
;; T2-07: extra OpenAI-shape embed providers attached to the same transport
;; ---------------------------------------------------------------------------

(def ^:private openai-shape-embed-providers
  [{:id :voyage    :base "https://api.voyageai.com/v1"   :env "VOYAGE_API_KEY"}
   {:id :jina      :base "https://api.jina.ai/v1"        :env "JINA_API_KEY"}
   {:id :mistral   :base "https://api.mistral.ai/v1"     :env "MISTRAL_API_KEY"}
   {:id :together  :base "https://api.together.xyz/v1"   :env "TOGETHER_API_KEY"}])

(deftest test-openai-shape-embed-providers-registered
  (doseq [{:keys [id base env]} openai-shape-embed-providers]
    (let [p (provider/get-provider id)]
      (is (some? p) (str id " profile registered"))
      (is (= base (:profile/base-url p)) (str id " base-url"))
      (is (= [env] (:profile/env-var-names p)) (str id " env-var"))
      (is (fn? (:profile/embed-transport-constructor p))
          (str id " carries an embed-transport-constructor"))
      (is (contains? (:profile/capabilities p) :embedding)
          (str id " capabilities include :embedding")))))

(deftest test-openai-shape-embed-providers-share-transport
  (testing "all OpenAI-shape embed providers reuse OpenAIEmbedTransport"
    (doseq [{:keys [id]} openai-shape-embed-providers]
      (let [profile (provider/get-provider id)
            transport ((:profile/embed-transport-constructor profile))]
        (is (instance? llm.sdk.providers.openai_embed.OpenAIEmbedTransport
                       transport)
            (str id))))))

(deftest test-voyage-build-request-includes-extra-body
  (testing "Voyage-specific :input-type lands on the body via :extra_body"
    (let [t (openai-embed/make-transport)
          profile (provider/get-provider :voyage)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (et/build-embed-request
                   t profile
                   {:embed/model "voyage-3"
                    :embed/inputs ["query phrase"]
                    :embed/provider-options
                    {:extra_body {:input_type "query"}}}))]
      (is (= "https://api.voyageai.com/v1/embeddings" (:url built)))
      (is (= "query phrase" (get-in built [:body :input])))
      (is (= "query" (get-in built [:body :input_type]))))))
