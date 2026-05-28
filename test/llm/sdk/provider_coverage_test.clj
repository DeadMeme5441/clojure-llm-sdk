(ns llm.sdk.provider-coverage-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.models :as models]
            [llm.sdk.provider :as provider]
            [llm.sdk.provider-coverage :as coverage]
            [llm.sdk.providers.codex :as codex]
            [llm.sdk.providers.codex.responses :as codex-impl]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.embed :as embed-transport]
            [llm.sdk.transport.image :as image-transport]
            [llm.sdk.transport.moderate :as moderation-transport]
            [llm.sdk.transport.rerank :as rerank-transport]
            [llm.sdk.transport.speak :as speak-transport]
            [llm.sdk.transport.transcribe :as transcribe-transport]))

(def ^:private required-coverage-keys
  [:surfaces :context-cache :metrics :pricing :models :request-shape
   :response-shape :stream-shape :auth :errors :live-smoke])

(def ^:private surface->profile-key
  {:complete :profile/transport-constructor
   :streaming :profile/transport-constructor
   :embedding :profile/embed-transport-constructor
   :moderation :profile/moderation-transport-constructor
   :rerank :profile/rerank-transport-constructor
   :image-generation :profile/image-transport-constructor
   :transcription :profile/transcribe-transport-constructor
   :tts :profile/speak-transport-constructor})

(defn- present-set? [x]
  (and (set? x) (seq x)))

(defn- model-for [provider-id surface]
  (case surface
    :embedding (case provider-id
                 :cohere "embed-english-v3.0"
                 :ollama-native "nomic-embed-text"
                 "text-embedding-3-small")
    :rerank (case provider-id
              :cohere "rerank-english-v3.0"
              :voyage "rerank-2"
              :jina "jina-reranker-v2-base-multilingual"
              "rerank-test")
    :moderation "omni-moderation-latest"
    :image-generation (case provider-id
                        :vertex-imagen "imagen-3.0-generate-002"
                        :bedrock "amazon.titan-image-generator-v1"
                        "gpt-image-1")
    :transcription "whisper-1"
    :tts (case provider-id
           :elevenlabs "eleven_multilingual_v2"
           "tts-1")
    :complete (case provider-id
                :bedrock "claude-3-5-sonnet"
                :codex "gpt-5-codex"
                :codex-backend "gpt-5-codex"
                :gemini-native "gemini-2.5-flash"
                :vertex-gemini "gemini-2.5-flash"
                :ollama-native "llama3.2"
                "test-model")
    "test-model"))

(defn- chat-request [provider-id]
  {:request/model (model-for provider-id :complete)
   :request/messages [{:message/role :system :message/content "System"}
                      {:message/role :user :message/content "Hi"}]
   :request/provider-options
   (case provider-id
     :vertex-gemini {:vertex {:project "test-project"
                              :location "us-central1"
                              :access-token "stub-token"}}
     {})})

(defn- build-surface-request [provider-id surface]
  (let [profile (provider/get-provider provider-id)]
    (case surface
      :complete
      (transport/build-request ((:profile/transport-constructor profile))
                               profile
                               (chat-request provider-id))

      :streaming
      (transport/build-request ((:profile/transport-constructor profile))
                               profile
                               (assoc (chat-request provider-id)
                                      :request/stream? true))

      :embedding
      (embed-transport/build-embed-request
       ((:profile/embed-transport-constructor profile))
       profile
       {:embed/model (model-for provider-id :embedding)
        :embed/inputs ["alpha" "beta"]})

      :moderation
      (moderation-transport/build-moderation-request
       ((:profile/moderation-transport-constructor profile))
       profile
       {:moderation/model (model-for provider-id :moderation)
        :moderation/inputs ["safe text"]})

      :rerank
      (rerank-transport/build-rerank-request
       ((:profile/rerank-transport-constructor profile))
       profile
       {:rerank/model (model-for provider-id :rerank)
        :rerank/query "query"
        :rerank/documents ["doc one" "doc two"]})

      :image-generation
      (image-transport/build-image-request
       ((:profile/image-transport-constructor profile))
       profile
       (merge {:image/model (model-for provider-id :image-generation)
               :image/prompt "a small diagram"}
              (case provider-id
                :vertex-imagen
                {:image/provider-options {:vertex {:project "test-project"
                                                   :location "us-central1"
                                                   :access-token "stub-token"}}}
                {})))

      :transcription
      (transcribe-transport/build-transcribe-request
       ((:profile/transcribe-transport-constructor profile))
       profile
       {:transcribe/model (model-for provider-id :transcription)
        :transcribe/file (.getBytes "fake audio")
        :transcribe/filename "fake.wav"})

      :tts
      (speak-transport/build-speak-request
       ((:profile/speak-transport-constructor profile))
       profile
       (merge {:speak/model (model-for provider-id :tts)
               :speak/input "hello"}
              (case provider-id
                :elevenlabs {:speak/voice "voice-id"}
                {}))))))

(def ^:private buildable-surfaces
  #{:complete :streaming :embedding :moderation :rerank
    :image-generation :transcription :tts})

(deftest coverage-exists-for-every-registered-provider
  (is (= (set (sdk/list-providers))
         (set (keys coverage/provider-coverage)))))

(deftest coverage-rows-have-required-contract-surfaces
  (doseq [[provider-id row] coverage/provider-coverage]
    (testing (name provider-id)
      (doseq [k required-coverage-keys]
        (is (contains? row k) (str "missing " k)))
      (is (present-set? (:surfaces row)))
      (is (present-set? (:context-cache row)))
      (is (present-set? (:metrics row)))
      (is (present-set? (:pricing row)))
      (is (present-set? (:models row)))
      (is (present-set? (:request-shape row)))
      (is (present-set? (:response-shape row)))
      (is (present-set? (:stream-shape row)))
      (is (present-set? (:auth row)))
      (is (present-set? (:errors row)))
      (is (some? (:live-smoke row))))))

(deftest declared-surfaces-match-registered-profile-constructors
  (doseq [[provider-id row] coverage/provider-coverage
          :let [profile (provider/get-provider provider-id)]]
    (testing (name provider-id)
      (doseq [[surface profile-key] surface->profile-key
              :when (contains? (:surfaces row) surface)]
        (is (fn? (get profile profile-key))
            (str surface " declared without " profile-key)))
      (when (contains? (:surfaces row) :streaming)
        (is (contains? (:profile/capabilities profile) :streaming)))
      (when (contains? (:surfaces row) :tools)
        (is (contains? (:profile/capabilities profile) :tools)))
      (when (contains? (:surfaces row) :json-schema)
        (is (contains? (:profile/capabilities profile) :json-schema)))
      (when (contains? (:surfaces row) :reasoning)
        (is (contains? (:profile/capabilities profile) :reasoning)))
      (when (contains? (:surfaces row) :file-attachments)
        (is (contains? (:profile/capabilities profile) :file-attachments))))))

(deftest model-listing-coverage-matches-models-dispatch
  (doseq [[provider-id row] coverage/provider-coverage
          :let [live? (models/supports-models-listing? provider-id)
                models (:models row)]]
    (testing (name provider-id)
      (if live?
        (is (contains? models :live-models-api))
        (is (not (contains? models :live-models-api)))))))

(deftest all-chat-providers-have-cache-cost-and-metrics-coverage
  (doseq [[provider-id row] coverage/provider-coverage
          :when (contains? (:surfaces row) :complete)]
    (testing (name provider-id)
      (is (seq (:context-cache row)))
      (is (seq (:pricing row)))
      (is (seq (:metrics row)))
      (is (seq (:request-shape row)))
      (is (seq (:response-shape row)))
      (is (seq (:stream-shape row))))))

(deftest declared-provider-surfaces-build-offline-requests
  (with-redefs [provider/resolve-auth-token (constantly "stub-token")
                gcp-auth/resolve-access-token (constantly "stub-token")
                gcp-auth/resolve-project (constantly "test-project")
                codex/codex-backend-auth-headers
                (constantly {"Authorization" "Bearer stub-token"})
                codex-impl/codex-backend-auth-headers
                (constantly {"Authorization" "Bearer stub-token"})]
    (doseq [[provider-id row] coverage/provider-coverage
            surface (sort (set/intersection buildable-surfaces
                                            (:surfaces row)))]
      (testing (str (name provider-id) " " (name surface))
        (let [built (build-surface-request provider-id surface)]
          (is (map? built))
          (is (#{:post :get} (:method built)))
          (is (string? (:url built))))))))
