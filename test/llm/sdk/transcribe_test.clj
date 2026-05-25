(ns llm.sdk.transcribe-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.transcribe :as tt]
            [llm.sdk.providers.openai-transcribe :as openai-tx]))

(deftest test-openai-build-request-shape
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        tmp (java.io.File/createTempFile "speech" ".wav")
        _ (spit tmp "fake audio")
        req {:transcribe/file tmp
             :transcribe/model "whisper-1"
             :transcribe/language "en"
             :transcribe/response-format :verbose_json
             :transcribe/timestamp-granularities #{:segment}}
        built (tt/build-transcribe-request t profile req)
        parts (:multipart built)
        name->part (into {} (map (juxt :name identity) parts))]
    (is (.endsWith ^String (:url built) "/audio/transcriptions"))
    (is (= "whisper-1" (:content (name->part "model"))))
    (is (= "en" (:content (name->part "language"))))
    (is (= "verbose_json" (:content (name->part "response_format"))))
    (is (some? (name->part "file"))
        "binary file part is present")
    (is (some #(and (= (:name %) "timestamp_granularities[]")
                    (= (:content %) "segment"))
              parts))
    (.delete tmp)))

(deftest test-groq-attached
  (let [profile (provider/get-provider :groq)
        ctor (:profile/transcribe-transport-constructor profile)]
    (is (some? ctor))
    (let [t (ctor)
          tmp (java.io.File/createTempFile "speech" ".wav")
          _ (spit tmp "x")
          built (tt/build-transcribe-request t profile
                                             {:transcribe/file tmp
                                              :transcribe/model "whisper-large-v3"})]
      (is (.endsWith ^String (:url built) "/audio/transcriptions"))
      (is (.startsWith ^String (:url built) "https://api.groq.com/openai/v1"))
      (.delete tmp))))

(deftest test-parse-response-json
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        raw {:text "Hello there."}
        parsed (tt/parse-transcribe-response t profile raw)]
    (is (= "Hello there." (:transcription/text parsed)))
    (is (schema/validate-transcribe-response parsed))))

(deftest test-parse-response-verbose-json
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        raw {:text "Hello there."
             :language "english"
             :duration 1.42
             :segments [{:id 0 :start 0.0 :end 1.42 :text "Hello there."}]
             :words [{:word "Hello" :start 0.0 :end 0.5}
                     {:word "there" :start 0.6 :end 1.0}]}
        parsed (tt/parse-transcribe-response t profile raw)]
    (is (= "Hello there." (:transcription/text parsed)))
    (is (= "english" (:transcription/language parsed)))
    (is (= 1.42 (:transcription/duration-seconds parsed)))
    (is (= 1 (count (:transcription/segments parsed))))
    (is (= 2 (count (:transcription/words parsed))))
    (is (schema/validate-transcribe-response parsed))))

(deftest test-parse-response-plain-text
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        parsed (tt/parse-transcribe-response t profile "plain transcript")]
    (is (= "plain transcript" (:transcription/text parsed)))
    (is (schema/validate-transcribe-response parsed))))
