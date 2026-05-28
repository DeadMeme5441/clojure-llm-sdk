(ns llm.sdk.priority-providers-test
  "Verifies that the canonical :response/cost and :response/cache shape
   lands correctly on every priority provider listed in the project
   vision doc: OpenAI, Codex, Codex backend, Anthropic, Gemini native,
   Vertex Gemini, OpenRouter, Perplexity, Cohere, Bedrock, Ollama, and
   the fake provider.

   These tests exercise the full sdk/complete code path (no live
   network) by stubbing llm.sdk.http/request with canned raw payloads
   shaped like the real provider responses."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.gcp-auth :as gcp-auth]
            [llm.sdk.http :as http]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.providers.codex :as codex]
            [llm.sdk.providers.codex.responses :as codex-impl]
            [llm.sdk.registry :as registry]
            [llm.sdk.schema :as schema])
  (:import [java.io ByteArrayInputStream]))

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
       (schema/validate-response resp)
       (contains? resp :response/cache)
       (or (nil? (:response/usage resp))
           (contains? resp :response/cost))
       (#{:hit :miss :unknown}
        (get-in resp [:response/cache :cache/status]))))

(defn- sse-stream [s]
  (ByteArrayInputStream. (.getBytes ^String s "UTF-8")))

;; ---------------------------------------------------------------------------
;; OpenAI, Codex backend, and OpenAI-wire OpenRouter
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

(deftest codex-standard-stamps-responses-json-usage-cost-and-cache
  (offline
   (fn []
     (registry/register-entry!
      :codex "o3"
      {:model/cost {:input-per-million 1.0
                    :output-per-million 2.0
                    :cache-read-per-million 0.2}})
     (with-redefs [http/request
                   (fn [_]
                     {:status 200
                      :body {:id "resp_json"
                             :model "o3"
                             :status "completed"
                             :output [{:type "message"
                                       :role "assistant"
                                       :status "completed"
                                       :content [{:type "output_text"
                                                  :text "ok"}]}]
                             :usage {:input_tokens 12
                                     :input_tokens_details {:cached_tokens 0}
                                     :output_tokens 4
                                     :total_tokens 16}}})]
       (let [resp (sdk/complete :codex
                                {:request/model "o3"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :codex))
         (is (= "resp_json" (:response/id resp)))
         (is (= "o3" (:response/model resp)))
         (is (= :miss (get-in resp [:response/cache :cache/status])))
         (is (= 12 (get-in resp [:response/usage :usage/input-tokens])))
         (is (number? (get-in resp [:response/cost :cost/usd]))))))))

(deftest codex-backend-stamps-completed-sse-usage-cost-and-cache
  (offline
   (fn []
     (registry/register-entry!
      :codex-backend "gpt-5.5"
      {:model/cost {:input-per-million 1.0
                    :output-per-million 2.0
                    :cache-read-per-million 0.2}})
     (with-redefs [codex/codex-backend-auth-headers
                   (fn [] {"Authorization" "Bearer test-token"})
                   codex-impl/codex-backend-auth-headers
                   (fn [] {"Authorization" "Bearer test-token"})
                   http/sse-response
                   (fn [_]
                     {:status 200
                      :body (sse-stream
                             (str "data: {\"type\":\"response.created\","
                                  "\"response\":{\"id\":\"resp_sse\","
                                  "\"model\":\"gpt-5.5\"}}\n\n"
                                  "data: {\"type\":\"response.output_text.delta\","
                                  "\"delta\":\"pong\"}\n\n"
                                  "data: {\"type\":\"response.completed\","
                                  "\"response\":{\"id\":\"resp_sse\","
                                  "\"model\":\"gpt-5.5\","
                                  "\"usage\":{\"input_tokens\":23,"
                                  "\"input_tokens_details\":{\"cached_tokens\":3},"
                                  "\"output_tokens\":16,"
                                  "\"output_tokens_details\":{\"reasoning_tokens\":9},"
                                  "\"total_tokens\":39}}}\n\n"))})]
       (let [resp (sdk/complete :codex-backend
                                {:request/model "gpt-5.5"
                                 :request/messages [{:message/role :user
                                                     :message/content "ping"}]})]
         (is (canonical-shape-ok? resp :codex-backend))
         (is (= "resp_sse" (:response/id resp)))
         (is (= "gpt-5.5" (:response/model resp)))
         (is (= [{:part/type :text :text "pong"}] (:response/parts resp)))
         (is (= :stop (:response/finish-reason resp)))
         (is (= 20 (get-in resp [:response/usage :usage/input-tokens])))
         (is (= 3 (get-in resp [:response/usage :usage/cached-input-tokens])))
         (is (= 16 (get-in resp [:response/usage :usage/output-tokens])))
         (is (= 9 (get-in resp [:response/usage :usage/reasoning-tokens])))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 3 (get-in resp [:response/cache :cache/cached-tokens])))
         (is (number? (get-in resp [:response/cost :cost/usd]))))))))

(deftest perplexity-stamps-citations-search-usage-and-cost
  (offline
   (fn []
     (registry/register-entry!
      :perplexity "sonar-pro"
      {:model/cost {:input-per-million 1.0
                    :output-per-million 2.0
                    :search-per-call 0.005}})
     (with-redefs [http/request
                   (fn [_]
                     {:status 200
                      :body (assoc
                             (openai-style-body
                              "sonar-pro"
                              {:prompt_tokens 100
                               :completion_tokens 20
                               :total_tokens 120
                               :citation_tokens 7
                               :num_search_queries 1})
                             :search_results [{:url "https://example.com/a"
                                               :title "A"
                                               :snippet "snippet"}])})]
       (let [resp (sdk/complete :perplexity
                                {:request/model "sonar-pro"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :perplexity))
         (is (= 1 (count (filter #(= :citation (:part/type %))
                                 (:response/parts resp)))))
         (is (= 7 (get-in resp [:response/usage :usage/citation-tokens])))
         (is (= 1 (get-in resp [:response/usage :usage/search-queries])))
         (is (number? (get-in resp [:response/cost :cost/usd]))))))))

(deftest perplexity-stream-flattens-final-citation-usage-end
  (offline
   (fn []
     (registry/register-entry!
      :perplexity "sonar-pro"
      {:model/cost {:input-per-million 1.0
                    :output-per-million 2.0
                    :search-per-call 0.005}})
     (with-redefs [http/sse-response
                   (fn [_]
                     {:status 200
                      :body (sse-stream
                             (str "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n"
                                  "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                                  "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,"
                                  "\"total_tokens\":12,\"prompt_tokens_details\":{\"cached_tokens\":0},"
                                  "\"num_search_queries\":1},"
                                  "\"citations\":[\"https://example.com/source\"]}\n\n"
                                  "data: [DONE]\n\n"))})]
       (let [events-seen (atom [])
             resp (sdk/complete :perplexity
                                {:request/model "sonar-pro"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]}
                                :stream? true
                                :on-event #(swap! events-seen conj %))]
         (is (canonical-shape-ok? resp :perplexity))
         (is (= [:stream/start :stream/content-delta :stream/citation
                 :stream/usage :stream/end :stream/end]
                (mapv :event/type @events-seen)))
         (is (= :stop (:response/finish-reason resp)))
         (is (= :miss (get-in resp [:response/cache :cache/status])))
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
         (is (canonical-shape-ok? resp :openai))
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
         (is (canonical-shape-ok? resp :anthropic))
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
         (is (canonical-shape-ok? resp :gemini-native))
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
;; Cohere native chat
;; ---------------------------------------------------------------------------

(deftest cohere-stamps-native-chat-usage-and-schema-clean-citations
  (offline
   (fn []
     (registry/register-entry!
      :cohere "command-r-plus"
      {:model/cost {:input-per-million 1.0
                    :output-per-million 2.0}})
     (with-redefs [http/request
                   (fn [_]
                     {:status 200
                      :body {:id "cohere_resp"
                             :model "command-r-plus"
                             :finish_reason "COMPLETE"
                             :message {:role "assistant"
                                       :content [{:type "text" :text "ok"}]
                                       :citations [{:start 0
                                                    :end 2
                                                    :text "ok"
                                                    :sources [{:url "https://example.com/c"
                                                               :title "C"}]}]}
                             :usage {:billed_units {:input_tokens 10
                                                    :output_tokens 7}}}})]
       (let [resp (sdk/complete :cohere
                                {:request/model "command-r-plus"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :cohere))
         (is (= [0 2] (->> (:response/parts resp)
                           (filter #(= :citation (:part/type %)))
                           first
                           :citation/text-range)))
         (is (= 10 (get-in resp [:response/usage :usage/input-tokens])))
         (is (number? (get-in resp [:response/cost :cost/usd]))))))))

;; ---------------------------------------------------------------------------
;; Bedrock
;; ---------------------------------------------------------------------------

(deftest bedrock-stamps-converse-usage-cost-cache-and-schema
  (offline
   (fn []
     (registry/register-entry!
      :bedrock "claude-sonnet-4-5"
      {:model/cost {:input-per-million 3.0
                    :output-per-million 15.0
                    :cache-read-per-million 0.3
                    :cache-write-per-million 3.75}})
     (with-redefs [aws-sigv4/maybe-sign (fn [_ req] req)
                   http/request
                   (fn [_]
                     {:status 200
                      :body {:modelId "anthropic.claude-sonnet-4-5-20250101-v1:0"
                             :stopReason "end_turn"
                             :output {:message {:content [{:text "ok"}]}}
                             :usage {:inputTokens 20
                                     :outputTokens 5
                                     :totalTokens 25
                                     :cacheReadInputTokens 8
                                     :cacheWriteInputTokens 2}}})]
       (let [resp (sdk/complete :bedrock
                                {:request/model "claude-sonnet-4-5"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :bedrock))
         (is (= :hit (get-in resp [:response/cache :cache/status])))
         (is (= 8 (get-in resp [:response/cache :cache/cached-tokens])))
         (is (= 2 (get-in resp [:response/cache :cache/cache-write-tokens])))
         (is (number? (get-in resp [:response/cost :cost/usd]))))))))

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
;; Fake provider
;; ---------------------------------------------------------------------------

(deftest fake-provider-stamps-cache-unknown-without-usage
  (offline
   (fn []
     (with-redefs [http/request (fn [_] {:status 200 :body {}})]
       (let [resp (sdk/complete :fake
                                {:request/model "fake-model"
                                 :request/messages [{:message/role :user
                                                     :message/content "hi"}]})]
         (is (canonical-shape-ok? resp :fake))
         (is (nil? (:response/usage resp)))
         (is (nil? (:response/cost resp)))
         (is (= :unknown (get-in resp [:response/cache :cache/status]))))))))

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
