(ns llm.sdk.providers.bedrock-image-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.image :as it]
            [llm.sdk.providers.bedrock-image :as bimage]))

(deftest test-bedrock-titan-build-request
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        built (it/build-image-request
               t profile
               {:image/model "amazon.titan-image-generator-v2:0"
                :image/prompt "a cat in space"
                :image/size "1024x1024"
                :image/n 2})]
    (is (.endsWith ^String (:url built)
                   "/model/amazon.titan-image-generator-v2:0/invoke"))
    (is (= "TEXT_IMAGE" (get-in built [:body :taskType])))
    (is (= "a cat in space" (get-in built [:body :textToImageParams :text])))
    (is (= 2 (get-in built [:body :imageGenerationConfig :numberOfImages])))
    (is (= 1024 (get-in built [:body :imageGenerationConfig :width])))
    (is (= "bedrock" (get built :llm.sdk.providers.bedrock/aws-service)))))

(deftest test-bedrock-stability-build-request
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        built (it/build-image-request
               t profile
               {:image/model "stability.stable-diffusion-xl-v1"
                :image/prompt "fox"
                :image/size "1024x1024"
                :image/n 1
                :image/provider-options
                {:bedrock {:cfg-scale 7.0 :seed 42 :steps 30}}})]
    (is (= "fox" (get-in built [:body :text_prompts 0 :text])))
    (is (= 7.0 (get-in built [:body :cfg_scale])))
    (is (= 30 (get-in built [:body :steps])))
    (is (= 42 (get-in built [:body :seed])))))

(deftest test-bedrock-parse-titan-response
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        raw {:images ["b64-image-1" "b64-image-2"]}
        parsed (it/parse-image-response t profile raw)]
    (is (= 2 (count (:image/images parsed))))
    (is (= "b64-image-1" (:image/b64 (first (:image/images parsed)))))))

(deftest test-bedrock-parse-stability-response
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        raw {:artifacts [{:base64 "abc" :finishReason "SUCCESS"}]}
        parsed (it/parse-image-response t profile raw)]
    (is (= "abc" (:image/b64 (first (:image/images parsed)))))
    (is (= "SUCCESS" (:image/finish-reason (first (:image/images parsed)))))))
