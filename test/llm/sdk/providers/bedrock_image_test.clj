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
    (is (= "SUCCESS" (get-in parsed [:image/raw :artifacts 0 :finishReason])))))

(deftest test-bedrock-nova-canvas-build-request
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        built (it/build-image-request
               t profile
               {:image/model "nova-canvas"
                :image/prompt "a lighthouse"
                :image/size "1024x1024"
                :image/quality :high
                :image/provider-options
                {:bedrock {:negative-prompt "fog"
                           :seed 12}}})]
    (is (.endsWith ^String (:url built)
                   "/model/amazon.nova-canvas-v1:0/invoke"))
    (is (= "fog" (get-in built [:body :textToImageParams :negativeText])))
    (is (= 6.5 (get-in built [:body :imageGenerationConfig :cfgScale])))
    (is (= "premium" (get-in built [:body :imageGenerationConfig :quality])))
    (is (= 12 (get-in built [:body :imageGenerationConfig :seed])))))

(deftest test-bedrock-modern-stability-build-and-parse
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        built (it/build-image-request
               t profile
               {:image/model "stability.sd3-5-large-v1:0"
                :image/prompt "a vegetable car"
                :image/size "1536x1024"
                :image/provider-options
                {:bedrock {:negative-prompt "blur"
                           :output-format :jpeg
                           :seed 7}}})
        raw {:seeds [7]
             :finish_reasons [nil]
             :images ["modern-b64"]}
        parsed (it/parse-image-response t profile raw)]
    (is (= {:prompt "a vegetable car"
            :aspect_ratio "3:2"
            :output_format "jpeg"
            :seed 7
            :negative_prompt "blur"}
           (:body built)))
    (is (= [{:image/b64 "modern-b64"}] (:image/images parsed)))
    (is (= [nil] (get-in parsed [:image/raw :finish_reasons])))))

(deftest test-bedrock-modern-stability-validates-family-options
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        invalid-cases
        [{:label "image-to-image strength"
          :model "stability.stable-image-ultra-v1:1"
          :options {:image "source-b64" :strength 1.1}
          :expected-option :strength}
         {:label "seed range"
          :model "stability.sd3-5-large-v1:0"
          :options {:seed -1}
          :expected-option :seed}
         {:label "supported aspect ratios"
          :model "stability.sd3-5-large-v1:0"
          :options {:aspect-ratio "4:3"}
          :expected-option :aspect-ratio}
         {:label "family-specific output formats"
          :model "stability.stable-image-core-v1:1"
          :options {:output-format :webp}
          :expected-option :output-format}]]
    (doseq [{:keys [label model options expected-option]} invalid-cases]
      (testing label
        (let [error (try
                      (it/build-image-request
                       t profile
                       {:image/model model
                        :image/prompt "a test image"
                        :image/provider-options {:bedrock options}})
                      nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? error))
          (is (= :request/invalid-image-option
                 (:error/type (ex-data error))))
          (is (= expected-option (:option (ex-data error)))))))
    (testing "SD3.5 retains its documented WebP support"
      (let [built (it/build-image-request
                   t profile
                   {:image/model "stability.sd3-5-large-v1:0"
                    :image/prompt "a test image"
                    :image/provider-options
                    {:bedrock {:output-format :webp}}})]
        (is (= "webp" (get-in built [:body :output_format])))))))

(deftest test-bedrock-stability-preserves-response-metadata-in-raw
  (let [t (bimage/make-transport)
        profile (provider/get-provider :bedrock)
        raw {:images ["generated-b64"]
             :seeds [4294967294]
             :finish_reasons [nil]}
        parsed (it/parse-image-response t profile raw)]
    (is (= [{:image/b64 "generated-b64"}] (:image/images parsed)))
    (is (= {:seeds [4294967294]
            :finish_reasons [nil]}
           (select-keys (:image/raw parsed) [:seeds :finish_reasons])))))
