(ns llm.sdk.providers.vertex-imagen-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.image :as it]
            [llm.sdk.providers.gemini.vertex :as vertex]
            [llm.sdk.providers.gemini.imagen :as imagen]))

(deftest test-build-request-uses-gemini-image-generate-content
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        built (it/build-image-request
               t profile
               {:image/prompt "a cat"
                :image/provider-options
                {:vertex {:project "test-proj"
                          :location "global"
                          :access-token "fake-token"}}})]
    (is (= "https://aiplatform.googleapis.com/v1/projects/test-proj/locations/global/publishers/google/models/gemini-2.5-flash-image:generateContent"
           (:url built)))
    (is (= "Bearer fake-token" (get-in built [:headers "Authorization"])))
    (is (= {:role "user" :parts [{:text "a cat"}]}
           (get-in built [:body :contents 0])))
    (is (= ["IMAGE"]
           (get-in built [:body :generationConfig :responseModalities])))
    (is (= 1 (get-in built [:body :generationConfig :candidateCount])))
    (is (= :gemini-native (:profile/protocol-family profile)))))

(deftest test-extra-body-rejects-vertex-image-identity-and-prompt
  (doseq [[provided-key expected-field provided-value]
          [[:model :model "other-model"]
           ["contents" :contents
            [{:role "user" :parts [{:text "other prompt"}]}]]
           [:generationConfig :candidateCount {"candidateCount" 9}]]]
    (let [error
          (try
            (it/build-image-request
             (imagen/make-transport)
             (provider/get-provider :vertex-imagen)
             {:image/model "gemini-2.5-flash-image"
              :image/prompt "canonical prompt"
              :image/provider-options
              {:vertex {:project "test-proj"
                        :location "global"
                        :access-token "fake-token"}
               :extra_body {provided-key provided-value}}})
            nil
            (catch clojure.lang.ExceptionInfo e e))]
      (is (= :request/protected-extra-body-override
             (:error/type (ex-data error))))
      (is (= expected-field (:field (ex-data error))))
      (is (= :vertex-imagen (:provider (ex-data error)))))))

(deftest test-vertex-gemini-capability-and-env-metadata
  (let [t (vertex/make-transport)
        profile (provider/get-provider :vertex-gemini)]
    (doseq [capability [:json-schema :cache]]
      (is (contains? (transport/request-capabilities t) capability))
      (is (contains? (:profile/capabilities profile) capability)))
    (doseq [env-name ["GOOGLE_CLOUD_PROJECT" "GOOGLE_CLOUD_LOCATION"]]
      (is (contains? (set (:profile/env-var-names profile)) env-name)))))

(deftest test-discontinued-imagen-endpoint-families-are-rejected
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)]
    (doseq [model ["imagen-4.0-generate-001"
                   "imagegeneration@006"
                   "imagetext@001"]]
      (let [error (try
                    (it/build-image-request
                     t profile
                     {:image/model model
                      :image/prompt "x"})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :vertex-imagen/discontinued-model
               (:error/type (ex-data error))))
        (is (= "gemini-2.5-flash-image"
               (:replacement (ex-data error))))))))

(deftest test-build-request-image-config-and-provider-options
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        built (it/build-image-request
               t profile
               {:image/prompt "x"
                :image/size "1536x1024"
                :image/n 3
                :image/model "gemini-3.1-flash-image"
                :image/provider-options
                {:vertex {:project "p"
                          :location "us-east1"
                          :access-token "tok"}
                 :gemini {:image-size "2K"}
                 :extra_body
                 {:safetySettings
                  [{:category "HARM_CATEGORY_DANGEROUS_CONTENT"
                    :threshold "BLOCK_MEDIUM_AND_ABOVE"}]
                  :generationConfig {:temperature 0.7}}}})]
    (is (.contains ^String (:url built) "us-east1"))
    (is (.contains ^String (:url built) "gemini-3.1-flash-image"))
    (is (= "3:2"
           (get-in built [:body :generationConfig :imageConfig :aspectRatio])))
    (is (= "2K"
           (get-in built [:body :generationConfig :imageConfig :imageSize])))
    (is (= 3 (get-in built [:body :generationConfig :candidateCount])))
    (is (= 0.7 (get-in built [:body :generationConfig :temperature])))
    (is (= "BLOCK_MEDIUM_AND_ABOVE"
           (get-in built [:body :safetySettings 0 :threshold])))))

(deftest test-build-request-picks-model-supported-aspect-ratio
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        build-ratio
        (fn [model size]
          (get-in
           (it/build-image-request
            t profile
            {:image/model model
             :image/prompt "x"
             :image/size size
             :image/provider-options
             {:vertex {:project "p" :location "global"
                       :access-token "tok"}}})
           [:body :generationConfig :imageConfig :aspectRatio]))]
    (is (= "1:1" (build-ratio "gemini-2.5-flash-image" "1024x1024")))
    (is (= "9:16" (build-ratio "gemini-2.5-flash-image" "576x1024")))
    (is (= "4:3" (build-ratio "gemini-2.5-flash-image" "1024x768")))
    (is (= "9:21"
           (build-ratio "gemini-3.1-flash-image" "900x2100")))))

(deftest test-build-request-validates-model-options
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        unexpected-auth
        (fn [& _]
          (throw (ex-info "Unexpected auth resolution"
                          {:error/type ::unexpected-auth})))
        build-error
        (fn [request]
          (try
            (it/build-image-request t profile
                                    (merge {:image/prompt "x"} request))
            nil
            (catch clojure.lang.ExceptionInfo e e)))]
    (with-redefs [gcp-auth/resolve-project unexpected-auth
                  gcp-auth/resolve-access-token unexpected-auth]
      (is (= :request/unsupported-model
             (:error/type
              (ex-data (build-error {:image/model "gemini-3.1-flash"})))))
      (is (= :provider/unsupported-option
             (:error/type
              (ex-data
               (build-error
                {:image/model "gemini-2.5-flash-image"
                 :image/provider-options {:gemini {:image-size "2K"}}})))))
      (is (= :provider/unsupported-option
             (:error/type
              (ex-data
               (build-error
                {:image/model "gemini-3.1-flash-image"
                 :image/quality :high}))))))))

(deftest test-parse-gemini-image-response
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        raw {:candidates
             [{:content
               {:parts [{:text "generated"}
                        {:inlineData {:data "aGVsbG8="
                                      :mimeType "image/png"}}
                        {:fileData {:fileUri "gs://bucket/image.png"
                                    :mimeType "image/png"}}]}}]
             :modelVersion "gemini-2.5-flash-image"
             :usageMetadata {:promptTokenCount 10
                             :candidatesTokenCount 20
                             :thoughtsTokenCount 2
                             :totalTokenCount 32}}
        parsed (it/parse-image-response t profile raw)]
    (testing "only image parts become canonical images"
      (is (= [{:image/b64 "aGVsbG8="
               :image/mime-type "image/png"}
              {:image/url "gs://bucket/image.png"
               :image/mime-type "image/png"}]
             (:image/images parsed))))
    (is (= "gemini-2.5-flash-image" (:image/model parsed)))
    (is (schema/validate-image-gen-response parsed))
    (is (= 2 (get-in parsed [:response/usage :usage/reasoning-tokens])))
    (is (= raw (:image/raw parsed)))))

(deftest test-filtered-response-is-not-success
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        raw {:promptFeedback {:blockReason "SAFETY"}
             :candidates [{:finishReason "IMAGE_SAFETY"}]
             :usageMetadata {:promptTokenCount 4
                             :totalTokenCount 4}}
        error (try
                (it/parse-image-response t profile raw)
                nil
                (catch clojure.lang.ExceptionInfo e e))
        data (ex-data error)]
    (is (= :provider/invalid-image-response (:error/type data)))
    (is (= {:blockReason "SAFETY"} (:prompt-feedback data)))
    (is (= ["IMAGE_SAFETY"] (:finish-reasons data)))
    (is (= 4 (get-in data
                     [:response :response/usage :usage/total-tokens])))))
