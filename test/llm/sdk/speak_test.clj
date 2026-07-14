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

(deftest test-openai-build-request-current-custom-voice-and-sse-format
  (let [t (openai-spk/make-transport)
        profile (provider/get-provider :openai)
        built (st/build-speak-request
               t profile
               {:speak/model "gpt-4o-mini-tts"
                :speak/input "Hi"
                :speak/provider-options
                {:voice {:id "voice_1234"}
                 :stream_format "sse"}})]
    (is (= {:id "voice_1234"} (get-in built [:body :voice])))
    (is (= "sse" (get-in built [:body :stream_format])))))

(deftest test-openai-current-speech-stream-events
  (let [profile (provider/get-provider :openai)
        delta (openai-spk/parse-stream-event
               profile
               "data: {\"type\":\"speech.audio.delta\",\"audio\":\"AAAA\"}")
        done (openai-spk/parse-stream-event
              profile
              "data: {\"type\":\"speech.audio.done\",\"usage\":{\"input_tokens\":3,\"output_tokens\":7,\"total_tokens\":10}}")]
    (is (= :stream/provider-state (:event/type delta)))
    (is (= "AAAA"
           (get-in delta [:provider-state/data :speech/audio-delta])))
    (is (= [:stream/usage :stream/end] (mapv :event/type done)))
    (is (= 10 (get-in (first done) [:usage :usage/total-tokens])))))

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

(deftest test-elevenlabs-current-voice-settings-and-query-fields
  (let [t (eleven/make-transport)
        profile (provider/get-provider :elevenlabs)
        built (st/build-speak-request
               t profile
               {:speak/model "eleven_v3"
                :speak/voice "voice-id"
                :speak/input "hello"
                :speak/format :wav
                :speak/speed 1.2
                :speak/provider-options
                {:enable_logging false
                 :optimize_streaming_latency 3
                 :language_code "en"
                 :seed 42
                 :voice_settings {:stability 0.4
                                  :similarity_boost 0.8
                                  :style 0.2
                                  :use_speaker_boost true}}})]
    (is (.contains ^String (:url built) "output_format=wav_44100"))
    (is (.contains ^String (:url built) "enable_logging=false"))
    (is (.contains ^String (:url built) "optimize_streaming_latency=3"))
    (is (= {:stability 0.4
            :similarity_boost 0.8
            :style 0.2
            :use_speaker_boost true
            :speed 1.2}
           (get-in built [:body :voice_settings])))
    (is (= "en" (get-in built [:body :language_code])))
    (is (= 42 (get-in built [:body :seed])))
    (is (not (contains? (:body built) :output_format)))
    (is (not (contains? (:body built) :enable_logging)))))

(deftest test-elevenlabs-exact-output-format-provider-option-wins
  (let [t (eleven/make-transport)
        profile (provider/get-provider :elevenlabs)
        built (st/build-speak-request
               t profile
               {:speak/model "eleven_multilingual_v2"
                :speak/voice "voice-id"
                :speak/input "hello"
                :speak/format :mp3
                :speak/provider-options {:output_format "ulaw_8000"}})]
    (is (.contains ^String (:url built) "output_format=ulaw_8000"))))

(deftest test-elevenlabs-rejects-canonical-formats-the-api-does-not-support
  (let [t (eleven/make-transport)
        profile (provider/get-provider :elevenlabs)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"does not support flac"
         (st/build-speak-request
          t profile
          {:speak/model "eleven_multilingual_v2"
           :speak/voice "voice-id"
           :speak/input "hello"
           :speak/format :flac})))))

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

(deftest test-elevenlabs-parse-binary-response
  (let [t (eleven/make-transport)
        profile (provider/get-provider :elevenlabs)
        headers {"content-type" "audio/wav"
                 "request-id" "request-1"}
        parsed (st/parse-speak-response
                t profile
                {:status 200
                 :headers headers
                 :body (byte-array [1 2 3 4])})
        without-header (st/parse-speak-response
                        t profile
                        {:status 200
                         :headers {}
                         :body (byte-array [1])})]
    (is (= "audio/wav" (:audio/content-type parsed)))
    (is (= "application/octet-stream"
           (:audio/content-type without-header)))
    (is (= 4 (count (:audio/bytes parsed))))
    (is (= headers (:response/raw parsed)))
    (is (schema/validate-speak-response parsed))))
