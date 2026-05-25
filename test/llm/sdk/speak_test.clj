(ns llm.sdk.speak-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.speak :as st]
            [llm.sdk.providers.openai-speak :as openai-spk]
            [llm.sdk.providers.elevenlabs :as eleven]))

(deftest test-openai-build-request-defaults
  (let [t (openai-spk/make-transport)
        profile (provider/get-provider :openai)
        built (st/build-speak-request t profile
                                      {:speak/model "tts-1"
                                       :speak/input "Hello"})]
    (is (.endsWith ^String (:url built) "/audio/speech"))
    (is (= "tts-1" (get-in built [:body :model])))
    (is (= "Hello" (get-in built [:body :input])))
    (is (= "alloy" (get-in built [:body :voice])) "default voice")))

(deftest test-openai-build-request-explicit-options
  (let [t (openai-spk/make-transport)
        profile (provider/get-provider :openai)
        built (st/build-speak-request t profile
                                      {:speak/model "tts-1-hd"
                                       :speak/input "Hi"
                                       :speak/voice "shimmer"
                                       :speak/format :flac
                                       :speak/speed 1.25
                                       :speak/instructions "Speak warmly."})]
    (is (= "shimmer" (get-in built [:body :voice])))
    (is (= "flac" (get-in built [:body :response_format])))
    (is (= 1.25 (get-in built [:body :speed])))
    (is (= "Speak warmly." (get-in built [:body :instructions])))))

(deftest test-elevenlabs-voice-in-url
  (let [t (eleven/make-transport)
        profile (provider/get-provider :elevenlabs)
        built (st/build-speak-request t profile
                                      {:speak/model "eleven_multilingual_v2"
                                       :speak/voice "21m00Tcm4TlvDq8ikWAM"
                                       :speak/input "hi"
                                       :speak/format :mp3})]
    (is (.contains ^String (:url built) "/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM"))
    (is (.contains ^String (:url built) "output_format=mp3_44100_128"))
    (is (= "eleven_multilingual_v2" (get-in built [:body :model_id])))
    (is (= "hi" (get-in built [:body :text])))))

(deftest test-elevenlabs-requires-voice
  (let [t (eleven/make-transport)
        profile (provider/get-provider :elevenlabs)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"voice"
          (st/build-speak-request t profile
                                  {:speak/model "x" :speak/input "y"})))))

(deftest test-parse-response-content-type
  (let [t (openai-spk/make-transport)
        profile (provider/get-provider :openai)
        resp {:status 200
              :headers {"content-type" "audio/mpeg"}
              :body (byte-array [1 2 3])}
        parsed (st/parse-speak-response t profile resp)]
    (is (= "audio/mpeg" (:audio/content-type parsed)))
    (is (= 3 (count (:audio/bytes parsed))))
    (is (schema/validate-speak-response parsed))))
