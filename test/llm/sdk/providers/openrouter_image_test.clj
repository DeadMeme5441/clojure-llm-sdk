(ns llm.sdk.providers.openrouter-image-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openrouter.image :as openrouter-image]
            [llm.sdk.transport.image :as it]))

(deftest test-build-image-request-uses-native-images-endpoint
  (let [t (openrouter-image/make-transport)
        profile (provider/get-provider :openrouter)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (it/build-image-request
                 t profile
                 {:image/model "bytedance-seed/seedream-4.5"
                  :image/prompt "make a small icon"
                  :image/n 1
                  :image/size "1536x1024"
                  :image/quality :high}))
        body (:body built)]
    (is (= "https://openrouter.ai/api/v1/images" (:url built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (string? (get-in built [:headers "HTTP-Referer"])))
    (is (string? (get-in built [:headers "X-OpenRouter-Title"])))
    (is (= {:model "bytedance-seed/seedream-4.5"
            :prompt "make a small icon"
            :n 1
            :size "1536x1024"
            :quality "high"}
           body))))

(deftest test-build-image-request-extra-body
  (testing "provider options flatten into the OpenRouter /images body"
    (let [t (openrouter-image/make-transport)
          profile (provider/get-provider :openrouter)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (it/build-image-request
                   t profile
                   {:image/model "bytedance-seed/seedream-4.5"
                    :image/prompt "icon"
                    :image/provider-options
                    {:extra_body
                     {:output_format "webp"
                      :aspect_ratio "3:2"
                      :provider {:only ["bytedance"]}}}}))]
      (is (= "webp" (get-in built [:body :output_format])))
      (is (= "3:2" (get-in built [:body :aspect_ratio])))
      (is (= ["bytedance"]
             (get-in built [:body :provider :only]))))))

(deftest test-parse-image-response
  (let [t (openrouter-image/make-transport)
        profile (provider/get-provider :openrouter)
        raw {:created 1748372400
             :data [{:b64_json "abc123" :media_type "image/png"}
                    {:b64_json "def456" :media_type "image/webp"}]
             :usage {:prompt_tokens 6
                     :completion_tokens 10
                     :completion_tokens_details {:image_tokens 8}
                     :total_tokens 16
                     :cost 0.04}}
        parsed (it/parse-image-response t profile raw)]
    (is (= :openrouter (:image/provider parsed)))
    (is (= 1748372400 (:image/created parsed)))
    (is (= [{:image/b64 "abc123"
             :image/mime-type "image/png"}
            {:image/b64 "def456"
             :image/mime-type "image/webp"}]
           (:image/images parsed)))
    (is (= 8 (get-in parsed [:response/usage :usage/image-tokens])))
    (is (= 0.04 (get-in parsed [:response/cost :cost/usd])))
    (is (false? (get-in parsed [:response/cost :cost/estimated?])))))

(deftest test-openrouter-profile-has-image-transport
  (let [profile (provider/get-provider :openrouter)]
    (is (fn? (:profile/image-transport-constructor profile)))
    (is (contains? (:profile/capabilities profile) :image-generation))))
