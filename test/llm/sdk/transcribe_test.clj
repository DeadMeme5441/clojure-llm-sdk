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
    (is (= (.getName tmp) (:file-name (name->part "file"))))
    (is (some #(and (= (:name %) "timestamp_granularities[]")
                    (= (:content %) "segment"))
              parts))
    (.delete tmp)))

(deftest test-openai-build-request-byte-array-file-part
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        built (tt/build-transcribe-request
               t profile
               {:transcribe/file (.getBytes "fake audio")
                :transcribe/filename "fake.wav"
                :transcribe/model "whisper-1"})
        file-part (first (filter #(= "file" (:name %)) (:multipart built)))]
    (is (bytes? (:content file-part)))
    (is (= "fake.wav" (:file-name file-part)))))

(deftest test-openai-build-request-current-diarization-fields
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        built (tt/build-transcribe-request
               t profile
               {:transcribe/file (.getBytes "fake audio")
                :transcribe/filename "meeting.wav"
                :transcribe/model "gpt-4o-transcribe-diarize"
                :transcribe/response-format :diarized_json
                :transcribe/chunking-strategy :auto
                :transcribe/include [:logprobs]
                :transcribe/known-speaker-names ["agent"]
                :transcribe/known-speaker-references
                ["data:audio/wav;base64,AAAA"]})
        parts (:multipart built)
        values (fn [field]
                 (mapv :content (filter #(= field (:name %)) parts)))]
    (is (= ["diarized_json"] (values "response_format")))
    (is (= ["auto"] (values "chunking_strategy")))
    (is (= ["logprobs"] (values "include[]")))
    (is (= ["agent"] (values "known_speaker_names[]")))
    (is (= ["data:audio/wav;base64,AAAA"]
           (values "known_speaker_references[]")))))

(deftest test-openai-rejects-streaming-on-synchronous-transport
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Streaming transcription is not supported"
         (tt/build-transcribe-request
          t profile
          {:transcribe/file (.getBytes "fake audio")
           :transcribe/filename "meeting.wav"
           :transcribe/model "gpt-4o-transcribe-diarize"
           :transcribe/stream true})))))

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

(deftest test-parse-response-current-languages-and-logprobs
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        languages [{:code "en"}]
        logprobs [{:token "foo"
                   :bytes [102 111 111]
                   :logprob -0.2}]
        raw {:text "foo"
             :languages languages
             :logprobs logprobs}
        parsed (tt/parse-transcribe-response t profile raw)]
    (is (= languages (:transcription/languages parsed)))
    (is (= logprobs (:transcription/logprobs parsed)))
    (is (schema/validate-transcribe-response parsed))))

(deftest test-canonical-usage-and-rerank-embedding-schema-fields
  (is (schema/validate-usage
       {:usage/input-tokens 3
        :usage/output-tokens 1
        :usage/video-tokens 2}))
  (is (schema/validate-rerank-response
       {:rerank/provider :jina
        :rerank/results [{:rerank/index 0
                          :rerank/score 0.9
                          :rerank/embedding [0.1 -0.2 0.3]}]})))

(deftest test-parse-response-current-diarized-json-and-token-usage
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        raw {:task "transcribe"
             :duration 2.5
             :text "Hello."
             :segments [{:type "transcript.text.segment"
                         :id "seg_1"
                         :speaker "agent"
                         :start 0.0
                         :end 2.5
                         :text "Hello."}]
             :usage {:type "tokens"
                     :input_tokens 7
                     :output_tokens 2
                     :total_tokens 9
                     :input_token_details {:audio_tokens 7}}}
        parsed (tt/parse-transcribe-response t profile raw)]
    (is (= "agent" (get-in parsed [:transcription/segments 0 :speaker])))
    (is (= 7 (get-in parsed [:response/usage :usage/input-tokens])))
    (is (= 2 (get-in parsed [:response/usage :usage/output-tokens])))
    (is (schema/validate-transcribe-response parsed))))

(deftest test-parse-current-transcription-stream-events
  (let [profile (provider/get-provider :openai)
        delta (openai-tx/parse-stream-event
               profile
               "data: {\"type\":\"transcript.text.delta\",\"delta\":\"Hi\"}")
        segment (openai-tx/parse-stream-event
                 profile
                 "data: {\"type\":\"transcript.text.segment\",\"id\":\"seg_1\",\"speaker\":\"A\",\"text\":\"Hi\",\"start\":0,\"end\":1}")
        done (openai-tx/parse-stream-event
              profile
              "data: {\"type\":\"transcript.text.done\",\"text\":\"Hi\",\"logprobs\":[],\"usage\":{\"type\":\"tokens\",\"input_tokens\":3,\"output_tokens\":1,\"total_tokens\":4}}")]
    (is (= :stream/content-delta (:event/type delta)))
    (is (= :stream/provider-state (:event/type segment)))
    (is (= [:stream/provider-state :stream/usage :stream/end]
           (mapv :event/type done)))
    (is (= 4 (get-in (second done) [:usage :usage/total-tokens])))))

(deftest test-parse-response-plain-text
  (let [t (openai-tx/make-transport)
        profile (provider/get-provider :openai)
        parsed (tt/parse-transcribe-response t profile "plain transcript")]
    (is (= "plain transcript" (:transcription/text parsed)))
    (is (schema/validate-transcribe-response parsed))))
