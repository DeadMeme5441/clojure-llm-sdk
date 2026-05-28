(ns llm.sdk.image-test
  "Driver + schema coverage for sdk/generate-image. Adapter-
   specific shape lives in providers/openai-image-test."
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]))

;; ---------------------------------------------------------------------------
;; Schema round-trips
;; ---------------------------------------------------------------------------

(deftest test-image-gen-request-schema
  (testing "minimal request validates"
    (is (schema/validate-image-gen-request
         {:image/prompt "a cat"})))
  (testing "full request validates"
    (is (schema/validate-image-gen-request
         {:image/model "dall-e-3"
          :image/prompt "a cat"
          :image/n 1
          :image/size "1024x1024"
          :image/quality :hd
          :image/style :vivid
          :image/response-format :b64_json
          :image/user "u-1"
          :image/provider-options {:extra_body {:foo :bar}}})))
  (testing "missing prompt fails"
    (is (not (schema/validate-image-gen-request {:image/model "dall-e-3"})))))

(deftest test-image-gen-response-schema
  (is (schema/validate-image-gen-response
       {:image/provider :openai
        :image/model "dall-e-3"
        :image/images [{:image/url "https://example.com/img.png"
                        :image/revised-prompt "revised"}]
        :image/created 1736500000})))

;; ---------------------------------------------------------------------------
;; Driver error paths
;; ---------------------------------------------------------------------------

(deftest test-generate-image-unknown-provider
  (is (thrown-with-msg? Exception #"Unknown provider"
        (sdk/generate-image :no-such-provider {:image/prompt "x"}))))

(deftest test-generate-image-provider-without-image-support
  (is (thrown-with-msg? Exception #"Image generation not supported"
        (sdk/generate-image :anthropic {:image/prompt "x"}))))

(deftest test-generate-image-driver-4xx
  (with-redefs [http/request
                (fn [_]
                  {:status 400 :body {:error {:message "Bad prompt"}}})]
    (let [ex (try (sdk/generate-image :openai {:image/prompt "x"})
                  nil
                  (catch Exception e e))
          data (ex-data ex)]
      (is (some? ex))
      (is (= :openai (:provider data))))))

(deftest test-generate-image-driver-happy-path
  (with-redefs [http/request
                (fn [_]
                  {:status 200
                   :body {:created 1736500000
                          :data [{:url "https://example.com/img.png"
                                  :revised_prompt "revised"}]}})]
    (let [resp (sdk/generate-image :openai {:image/prompt "a cat"})]
      (is (= :openai (:image/provider resp)))
      (is (= 1 (count (:image/images resp))))
      (is (= "https://example.com/img.png"
             (:image/url (first (:image/images resp)))))
      (is (schema/validate-image-gen-response resp)))))

(deftest test-generate-image-stamps-token-cost-when-usage-present
  (with-redefs [http/request
                (fn [_]
                  {:status 200
                   :body {:created 1736500000
                          :data [{:b64_json "abc"}]
                          :usage {:input_tokens 10
                                  :output_tokens 100
                                  :total_tokens 110}}})]
    (let [resp (sdk/generate-image
                :openai
                {:image/model "gpt-image-1-mini"
                 :image/prompt "a cat"})]
      (is (= "gpt-image-1-mini" (:image/model resp)))
      (is (= 10 (get-in resp [:response/usage :usage/input-tokens])))
      (is (= 100 (get-in resp [:response/usage :usage/output-tokens])))
      (is (number? (get-in resp [:response/cost :cost/usd])))
      (is (= "openai-pricing-page"
             (get-in resp [:response/cost :cost/pricing-source])))
      (is (schema/validate-image-gen-response resp)))))

;; ---------------------------------------------------------------------------
;; Public API surface
;; ---------------------------------------------------------------------------

(deftest test-public-api-exposes-generate-image
  (is (some? (resolve 'llm.sdk/generate-image)))
  (is (fn? @(resolve 'llm.sdk/generate-image))))

(deftest test-openai-profile-advertises-image-capability
  (let [profile (provider/get-provider :openai)]
    (is (contains? (:profile/capabilities profile) :image-generation))
    (is (fn? (:profile/image-transport-constructor profile)))))
