(ns llm.sdk.providers.vertex-imagen-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.image :as it]
            [llm.sdk.providers.vertex-imagen :as imagen]))

(defn- with-env [bindings f]
  (let [orig (into {} (map (fn [k] [k (System/getenv k)]) (keys bindings)))]
    (try
      ;; We can't actually set env vars from JVM; emulate by passing
      ;; provider-options instead. This helper exists so the tests
      ;; document the env-var path even though they hand-feed values.
      (f orig)
      (finally nil))))

(deftest test-build-request-defaults
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        built (it/build-image-request
               t profile
               {:image/prompt "a cat"
                :image/provider-options
                {:vertex {:project "test-proj"
                          :location "us-central1"
                          :access-token "fake-token"}}})]
    (is (= "https://us-central1-aiplatform.googleapis.com/v1/projects/test-proj/locations/us-central1/publishers/google/models/imagen-3.0-generate-002:predict"
           (:url built)))
    (is (= "Bearer fake-token" (get-in built [:headers "Authorization"])))
    (is (= "a cat" (get-in built [:body :instances 0 :prompt])))
    (is (= 1 (get-in built [:body :parameters :sampleCount])))))

(deftest test-build-request-aspect-ratio
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        built (it/build-image-request
               t profile
               {:image/prompt "x"
                :image/size "1792x1024"
                :image/n 3
                :image/model "imagen-4.0-generate-001"
                :image/provider-options
                {:vertex {:project "p" :location "us-east1"
                          :access-token "tok"
                          :seed 42
                          :negative-prompt "blurry"}}})]
    (is (.contains ^String (:url built) "us-east1"))
    (is (.contains ^String (:url built) "imagen-4.0-generate-001"))
    (is (= "16:9" (get-in built [:body :parameters :aspectRatio])))
    (is (= 3 (get-in built [:body :parameters :sampleCount])))
    (is (= 42 (get-in built [:body :parameters :seed])))
    (is (= "blurry" (get-in built [:body :parameters :negativePrompt])))))

(deftest test-parse-response
  (let [t (imagen/make-transport)
        profile (provider/get-provider :vertex-imagen)
        raw {:predictions [{:bytesBase64Encoded "aGVsbG8="
                            :mimeType "image/png"}
                           {:bytesBase64Encoded "d29ybGQ="
                            :mimeType "image/png"}]}
        parsed (it/parse-image-response t profile raw)]
    (is (= 2 (count (:image/images parsed))))
    (is (= "aGVsbG8=" (:image/b64 (first (:image/images parsed)))))
    (is (= "image/png" (:image/mime-type (first (:image/images parsed)))))))
