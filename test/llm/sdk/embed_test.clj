(ns llm.sdk.embed-test
  "Driver + schema coverage for sdk/embed.

   Tests here cover the protocol contract and driver error paths.
   Adapter-specific request/response shape lives in
   providers/openai_embed_test."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]
            [llm.sdk.provider :as provider]
            [llm.sdk.http :as http]
            [llm.sdk.usage :as usage]))

;; ---------------------------------------------------------------------------
;; Schema round-trips
;; ---------------------------------------------------------------------------

(deftest test-embed-request-schema
  (testing "minimal request validates"
    (is (schema/validate-embed-request
         {:embed/model "text-embedding-3-small"
          :embed/inputs ["hello"]})))
  (testing "full request with every optional field"
    (is (schema/validate-embed-request
         {:embed/model "text-embedding-3-large"
          :embed/inputs ["a" "b" "c"]
          :embed/dimensions 512
          :embed/encoding-format :base64
          :embed/user "user-42"
          :embed/provider-options {:extra_body {:foo 1}}})))
  (testing "missing :embed/model fails"
    (is (not (schema/validate-embed-request
              {:embed/inputs ["hi"]}))))
  (testing "non-vector inputs fails"
    (is (not (schema/validate-embed-request
              {:embed/model "m" :embed/inputs "hi"})))))

(deftest test-embed-response-schema
  (let [resp {:embed/provider :openai
              :embed/model "text-embedding-3-small"
              :embed/vectors [[0.1 0.2 0.3] [0.4 0.5 0.6]]
              :embed/dimensions 3
              :response/usage {:usage/input-tokens 5
                               :usage/output-tokens 0
                               :usage/total-tokens 5}}]
    (is (schema/validate-embed-response resp))))

;; ---------------------------------------------------------------------------
;; Driver error paths
;; ---------------------------------------------------------------------------

(deftest test-embed-unknown-provider
  (is (thrown-with-msg? Exception #"Unknown provider"
        (sdk/embed :no-such-provider
                   {:embed/model "x" :embed/inputs ["a"]}))))

(deftest test-embed-provider-without-embed-support
  (testing "non-embed provider throws a clear error"
    ;; :anthropic has no embed-transport-constructor.
    (is (thrown-with-msg? Exception #"Embedding not supported"
          (sdk/embed :anthropic
                     {:embed/model "x" :embed/inputs ["a"]})))))

(deftest test-embed-driver-translates-4xx
  (testing "4xx HTTP response surfaces as ex-info with classified error"
    (with-redefs [http/request
                  (fn [_]
                    {:status 400
                     :body {:error {:message "Bad model"
                                    :type "invalid_request_error"}}})]
      (let [ex (try
                 (sdk/embed :openai
                            {:embed/model "bogus"
                             :embed/inputs ["hi"]})
                 (catch Exception e e))]
        (is (some? ex))
        (let [data (ex-data ex)]
          (is (= 400 (:status data)))
          (is (= :openai (:provider data)))
          (is (= :invalid-request (get-in data [:error :error/reason]))))))))

(deftest test-embed-driver-200-path
  (testing "happy path returns canonical response"
    (with-redefs [http/request
                  (fn [_]
                    {:status 200
                     :body {:object "list"
                            :model "text-embedding-3-small"
                            :data [{:embedding [0.1 0.2] :index 0}]
                            :usage {:prompt_tokens 3 :total_tokens 3}}})]
      (let [resp (sdk/embed :openai
                            {:embed/model "text-embedding-3-small"
                             :embed/inputs ["hi"]})]
        (is (= :openai (:embed/provider resp)))
        (is (= "text-embedding-3-small" (:embed/model resp)))
        (is (= [[0.1 0.2]] (:embed/vectors resp)))
        (is (= 2 (:embed/dimensions resp)))
        (is (= 3 (get-in resp [:response/usage :usage/input-tokens])))))))

;; ---------------------------------------------------------------------------
;; Usage normalization
;; ---------------------------------------------------------------------------

(deftest test-normalize-embedding-usage
  (let [u (usage/normalize-embedding-usage
           {:prompt_tokens 11 :total_tokens 11})]
    (is (= 11 (:usage/input-tokens u)))
    (is (= 0 (:usage/output-tokens u)))
    (is (= 11 (:usage/total-tokens u)))
    (is (= 1 (:usage/request-count u))))
  (testing "missing total_tokens falls back to prompt_tokens"
    (let [u (usage/normalize-embedding-usage {:prompt_tokens 7})]
      (is (= 7 (:usage/total-tokens u))))))

;; ---------------------------------------------------------------------------
;; Public API exposes embed
;; ---------------------------------------------------------------------------

(deftest test-public-api-exposes-embed
  (is (some? (resolve 'llm.sdk/embed)))
  (is (fn? @(resolve 'llm.sdk/embed))))

(deftest test-openai-profile-advertises-embedding-capability
  (let [profile (provider/get-provider :openai)]
    (is (contains? (:profile/capabilities profile) :embedding))
    (is (fn? (:profile/embed-transport-constructor profile)))))
