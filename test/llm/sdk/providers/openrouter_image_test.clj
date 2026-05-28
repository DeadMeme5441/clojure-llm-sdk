(ns llm.sdk.providers.openrouter-image-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openrouter.image :as openrouter-image]
            [llm.sdk.transport.image :as it]))

(deftest test-build-image-request-uses-chat-completions
  (let [t (openrouter-image/make-transport)
        profile (provider/get-provider :openrouter)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub")]
                (it/build-image-request
                 t profile
                 {:image/model "google/gemini-2.5-flash-image"
                  :image/prompt "make a small icon"
                  :image/n 1
                  :image/size "1536x1024"
                  :image/quality :high}))
        body (:body built)]
    (is (= "https://openrouter.ai/api/v1/chat/completions" (:url built)))
    (is (= "Bearer stub" (get-in built [:headers "Authorization"])))
    (is (string? (get-in built [:headers "HTTP-Referer"])))
    (is (string? (get-in built [:headers "X-Title"])))
    (is (= "google/gemini-2.5-flash-image" (:model body)))
    (is (= [{:role "user" :content "make a small icon"}]
           (:messages body)))
    (is (= {:aspect_ratio "3:2" :image_size "4K"}
           (:image_config body)))))

(deftest test-build-image-request-extra-body
  (testing "provider options merge into the OpenRouter chat body"
    (let [t (openrouter-image/make-transport)
          profile (provider/get-provider :openrouter)
          built (with-redefs [provider/resolve-auth-token
                              (constantly "stub")]
                  (it/build-image-request
                   t profile
                   {:image/model "google/gemini-2.5-flash-image"
                    :image/prompt "icon"
                    :image/provider-options
                    {:extra_body {:modalities ["image" "text"]}}}))]
      (is (= ["image" "text"] (get-in built [:body :modalities]))))))

(deftest test-parse-image-response
  (let [t (openrouter-image/make-transport)
        profile (provider/get-provider :openrouter)
        raw {:model "google/gemini-2.5-flash-image"
             :choices [{:message
                        {:content "done"
                         :images [{:type "image_url"
                                   :index 0
                                   :image_url
                                   {:url "data:image/png;base64,abc123"}}
                                  {:type "image_url"
                                   :index 1
                                   :image_url
                                   {:url "https://example.com/image.png"}}]}}]
             :usage {:prompt_tokens 6
                     :completion_tokens 10
                     :completion_tokens_details {:image_tokens 8}
                     :total_tokens 16}}
        parsed (it/parse-image-response t profile raw)]
    (is (= :openrouter (:image/provider parsed)))
    (is (= "google/gemini-2.5-flash-image" (:image/model parsed)))
    (is (= [{:image/b64 "abc123" :image/mime-type "image/png"}
            {:image/url "https://example.com/image.png"}]
           (:image/images parsed)))
    (is (= 8 (get-in parsed [:response/usage :usage/image-tokens])))))

(deftest test-openrouter-profile-has-image-transport
  (let [profile (provider/get-provider :openrouter)]
    (is (fn? (:profile/image-transport-constructor profile)))
    (is (contains? (:profile/capabilities profile) :image-generation))))
