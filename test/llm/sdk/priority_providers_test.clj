(ns llm.sdk.priority-providers-test
  "Verifies that the canonical :response/cost and :response/cache shape
   lands correctly on every priority provider listed in the project
   vision doc: OpenAI, Anthropic, Gemini native, Vertex Gemini,
   OpenRouter, Ollama, and the fake provider.

   These tests exercise the full sdk/complete code path (no live
   network) by stubbing llm.sdk.http/request with canned raw payloads
   shaped like the real provider responses."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.http :as http]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.registry :as registry]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "priority-test" "")]
    (.delete d) (.mkdirs d) d))

(defn isolate [f]
  (let [d (temp-dir)]
    (binding [mdev/*cache-dir* (.getPath d)]
      (mdev/reset-cache!)
      (registry/clear-live!)
      (registry/clear-overrides!)
      (try (f)
           (finally
             (doseq [c (.listFiles d)] (.delete c))
             (.delete d))))))

(use-fixtures :each isolate)

(defn- offline [f]
  (with-redefs [http/request (fn [_] {:status 500 :body {:error "offline"}})]
    (f)))

(defn- canonical-shape-ok? [resp expected-provider]
  (and (= expected-provider (:response/provider resp))
       (contains? resp :response/cache)
       (or (nil? (:response/usage resp))
           (contains? resp :response/cost))
       (#{:hit :miss :unknown}
        (get-in resp [:response/cache :cache/status]))))

;; ---------------------------------------------------------------------------
;; OpenAI (and OpenAI-wire — OpenRouter)
;; ---------------------------------------------------------------------------

(defn- openai-style-body [model usage]
  {:id "chatcmpl-stub"
   :model model
   :choices [{:index 0
              :message {:role "assistant" :content "ok"}
              :finish_reason "stop"}]
   :usage usage})

(deftest openai-stamps-honest-cache-hit
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body (openai-style-body
                                          "gpt-4o"
                                          {:prompt_tokens 1000
                                           :completion_tokens 500
                                           :total_tokens 1500
                                           :prompt_tokens_details {:cached_tokens 250}})})]
       (let [resp (sdk/complete :openai
                                {:request/model "gpt-4o"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :openai))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (number? (get-in resp [:response/cost :cost/usd]))))))))

(deftest openai-stamps-honest-cache-unknown
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body (openai-style-body
                                          "gpt-4o"
                                          {:prompt_tokens 1000
                                           :completion_tokens 500
                                           :total_tokens 1500})})]
       (let [resp (sdk/complete :openai
                                {:request/model "gpt-4o"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :unknown (get-in resp [:response/cache :cache/status])))
         (is (= :unknown (get-in resp [:response/cache :cache/cached-tokens]))))))))

(deftest openrouter-stamps-cache-from-top-level-claude-fields
  ;; OpenRouter Claude proxies surface Anthropic-style cache_*input_tokens
  ;; at the top level; the OpenAI normalizer's fallback covers them.
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body (openai-style-body
                                          "anthropic/claude-sonnet-4"
                                          {:prompt_tokens 2500
                                           :completion_tokens 800
                                           :total_tokens 3300
                                           :cache_read_input_tokens 1500
                                           :cache_creation_input_tokens 200})})]
       (let [resp (sdk/complete :openrouter
                                {:request/model "anthropic/claude-sonnet-4"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :openrouter))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 1500 (get-in resp [:response/cache :cache/cached-tokens]))))))))

;; ---------------------------------------------------------------------------
;; Anthropic
;; ---------------------------------------------------------------------------

(deftest anthropic-stamps-honest-cache-hit
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body {:id "msg_1"
                                          :model "claude-sonnet-4-6"
                                          :content [{:type "text" :text "ok"}]
                                          :stop_reason "end_turn"
                                          :usage {:input_tokens 1000
                                                  :output_tokens 100
                                                  :cache_read_input_tokens 300
                                                  :cache_creation_input_tokens 50}}})]
       (let [resp (sdk/complete :anthropic
                                {:request/model "claude-sonnet-4-6"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :anthropic))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 300 (get-in resp [:response/cache :cache/cached-tokens])))
         (is (= 50 (get-in resp [:response/cache :cache/cache-write-tokens]))))))))

(deftest anthropic-stamps-honest-cache-unknown
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body {:id "msg_2"
                                          :model "claude-sonnet-4-6"
                                          :content [{:type "text" :text "ok"}]
                                          :stop_reason "end_turn"
                                          :usage {:input_tokens 100
                                                  :output_tokens 20}}})]
       (let [resp (sdk/complete :anthropic
                                {:request/model "claude-sonnet-4-6"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :unknown (get-in resp [:response/cache :cache/status])))
         (is (= :unknown (get-in resp [:response/cache :cache/cached-tokens]))))))))

;; ---------------------------------------------------------------------------
;; Gemini native
;; ---------------------------------------------------------------------------

(deftest gemini-native-stamps-cache-when-provider-reports-cached
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body {:candidates
                                          [{:content {:parts [{:text "ok"}]}
                                            :finishReason "STOP"}]
                                          :usageMetadata {:promptTokenCount 1000
                                                          :candidatesTokenCount 100
                                                          :totalTokenCount 1100
                                                          :cachedContentTokenCount 400}}})]
       (let [resp (sdk/complete :gemini-native
                                {:request/model "gemini-2.5-pro"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :gemini-native))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 400 (get-in resp [:response/cache :cache/cached-tokens]))))))))

(deftest gemini-native-cache-unknown-when-silent
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body {:candidates
                                          [{:content {:parts [{:text "ok"}]}
                                            :finishReason "STOP"}]
                                          :usageMetadata {:promptTokenCount 100
                                                          :candidatesTokenCount 20}}})]
       (let [resp (sdk/complete :gemini-native
                                {:request/model "gemini-2.5-pro"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :unknown (get-in resp [:response/cache :cache/status])))
         (is (= :unknown (get-in resp [:response/cache :cache/cached-tokens]))))))))

;; ---------------------------------------------------------------------------
;; Vertex Gemini — same payload shape as Gemini native but routes via Vertex
;; ---------------------------------------------------------------------------

(deftest vertex-gemini-stamps-cache-with-stubbed-auth
  (offline
   (fn []
     (with-redefs [gcp-auth/resolve-access-token (fn [_ _] "stub-bearer")
                   gcp-auth/resolve-project (fn [_ _] "stub-project")
                   http/request (fn [_]
                                  {:status 200
                                   :body {:candidates
                                          [{:content {:parts [{:text "ok"}]}
                                            :finishReason "STOP"}]
                                          :usageMetadata {:promptTokenCount 200
                                                          :candidatesTokenCount 50
                                                          :cachedContentTokenCount 80}}})]
       (let [resp (sdk/complete :vertex-gemini
                                {:request/model "gemini-2.5-pro"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]
                                 :request/provider-options
                                 {:vertex {:project "p" :location "us-central1"}}})]
         (is (canonical-shape-ok? resp :vertex-gemini))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 80 (get-in resp [:response/cache :cache/cached-tokens]))))))))

;; ---------------------------------------------------------------------------
;; Ollama native — usage carries only prompt_eval_count + eval_count;
;; cache is always :unknown for this provider since it doesn't report any.
;; ---------------------------------------------------------------------------

(deftest ollama-stamps-honest-unknown-cache
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body {:model "llama3.2"
                                          :message {:role "assistant" :content "ok"}
                                          :done true
                                          :done_reason "stop"
                                          :prompt_eval_count 100
                                          :eval_count 25}})]
       (let [resp (sdk/complete :ollama-native
                                {:request/model "llama3.2"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :ollama-native))
         (is (= :unknown (get-in resp [:response/cache :cache/status])))
         (is (= :unknown (get-in resp [:response/cache :cache/cached-tokens]))))))))

;; ---------------------------------------------------------------------------
;; Cost honesty across providers — when pricing is missing, :unknown
;; ---------------------------------------------------------------------------

(deftest cost-unknown-stamped-when-pricing-unknown
  (offline
   (fn []
     (with-redefs [http/request (fn [_]
                                  {:status 200
                                   :body (openai-style-body
                                          "some-mystery-model"
                                          {:prompt_tokens 10
                                           :completion_tokens 5
                                           :total_tokens 15})})]
       (let [resp (sdk/complete :openai
                                {:request/model "some-mystery-model"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (= :unknown (get-in resp [:response/cost :cost/usd])))
         (is (true? (get-in resp [:response/cost :cost/estimated?])))
         (is (string? (get-in resp [:response/cost :cost/reason]))))))))
