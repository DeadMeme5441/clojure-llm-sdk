(ns llm.sdk.models-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [malli.core :as m]
            [llm.sdk.models :as models]
            [llm.sdk.http :as http]))

;; ---------------------------------------------------------------------------
;; Fixture loading
;; ---------------------------------------------------------------------------

(defn- load-fixture [name]
  (-> (io/resource (str "llm/sdk/fixtures/models/" name ".json"))
      slurp
      (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; Parser tests
;; ---------------------------------------------------------------------------

(deftest parse-openai-style-extracts-ids
  (let [body (load-fixture "openai")
        entries (models/parse-openai-style body :openai "https://api.openai.com/v1/models")]
    (is (= 7 (count entries)))
    (is (every? string? (map :model/id entries)))
    (is (every? #(= :openai (:model/provider %)) entries))
    (is (every? #(= :live-models-api (:model/source %)) entries))
    (is (every? :model/fetched-at entries))
    (is (some #(= "gpt-4o" (:model/id %)) entries))
    (is (some #(= "o3" (:model/id %)) entries))))

(deftest parse-openai-style-honours-provider-keyword
  (let [body (load-fixture "openai")
        for-deepseek (models/parse-openai-style body :deepseek "https://api.deepseek.com/v1/models")
        for-kimi (models/parse-openai-style body :kimi "https://api.moonshot.cn/v1/models")]
    (is (every? #(= :deepseek (:model/provider %)) for-deepseek))
    (is (every? #(= :kimi (:model/provider %)) for-kimi))))

(deftest parse-anthropic-models-maps-display-name
  (let [body (load-fixture "anthropic")
        entries (models/parse-anthropic-models body :anthropic "https://api.anthropic.com/v1/models")]
    (is (= 4 (count entries)))
    (let [opus (first (filter #(= "claude-opus-4-7-20250507" (:model/id %)) entries))]
      (is (= "Claude Opus 4.7" (:model/display-name opus)))
      (is (= :anthropic (:model/provider opus))))))

(deftest parse-gemini-models-strips-prefix-extracts-limits-and-caps
  (let [body (load-fixture "gemini-native")
        entries (models/parse-gemini-models body :gemini-native "https://generativelanguage.googleapis.com/v1beta/models")
        pro (first (filter #(= "gemini-2.5-pro" (:model/id %)) entries))
        embed (first (filter #(= "text-embedding-004" (:model/id %)) entries))]
    (is (= 4 (count entries)))
    (testing "models/ prefix stripped"
      (is (every? #(not (clojure.string/starts-with? (:model/id %) "models/"))
                  entries)))
    (testing "context + max-output extracted"
      (is (= 1048576 (:model/context-length pro)))
      (is (= 65536 (:model/max-output-tokens pro))))
    (testing "supportedGenerationMethods → capabilities"
      (is (= #{:chat :streaming :count-tokens} (:model/capabilities pro)))
      (is (= #{:embedding} (:model/capabilities embed))))
    (testing "displayName preserved"
      (is (= "Gemini 2.5 Pro" (:model/display-name pro))))))

(deftest parse-openrouter-models-converts-per-token-pricing
  (let [body (load-fixture "openrouter")
        entries (models/parse-openrouter-models body :openrouter "https://openrouter.ai/api/v1/models")
        gpt4o (first (filter #(= "openai/gpt-4o" (:model/id %)) entries))
        sonnet (first (filter #(= "anthropic/claude-sonnet-4" (:model/id %)) entries))]
    (is (= 3 (count entries)))
    (testing "context_length lifted"
      (is (= 128000 (:model/context-length gpt4o)))
      (is (= 200000 (:model/context-length sonnet))))
    (testing "top_provider.max_completion_tokens lifted"
      (is (= 16384 (:model/max-output-tokens gpt4o)))
      (is (= 8192 (:model/max-output-tokens sonnet))))
    (testing "per-token strings → per-million USD floats"
      ;; 0.0000025 per token * 1_000_000 = 2.5 per million
      (is (= 2.5 (get-in gpt4o [:model/cost :input-per-million])))
      (is (= 10.0 (get-in gpt4o [:model/cost :output-per-million])))
      ;; Sonnet 4 cache fields
      (is (= 3.0 (get-in sonnet [:model/cost :input-per-million])))
      (is (= 15.0 (get-in sonnet [:model/cost :output-per-million])))
      (is (= 0.3 (get-in sonnet [:model/cost :cache-read-per-million])))
      (is (= 3.75 (get-in sonnet [:model/cost :cache-write-per-million]))))
    (testing "no cache pricing on gpt-4o"
      (is (nil? (get-in gpt4o [:model/cost :cache-read-per-million])))
      (is (nil? (get-in gpt4o [:model/cost :cache-write-per-million]))))))

;; ---------------------------------------------------------------------------
;; Schema conformance
;; ---------------------------------------------------------------------------

(deftest parsed-entries-conform-to-malli-schema
  (let [all-entries
        (concat
         (models/parse-openai-style (load-fixture "openai") :openai "u")
         (models/parse-anthropic-models (load-fixture "anthropic") :anthropic "u")
         (models/parse-gemini-models (load-fixture "gemini-native") :gemini-native "u")
         (models/parse-openrouter-models (load-fixture "openrouter") :openrouter "u"))]
    (doseq [e all-entries]
      (is (models/validate-model-entry e)
          (str "Entry failed schema: " (pr-str e) "\n"
               "Explain: " (pr-str (models/explain-model-entry e)))))))

;; ---------------------------------------------------------------------------
;; Dispatcher: fetch-models
;; ---------------------------------------------------------------------------

(deftest fetch-models-rejects-unsupported-providers
  (testing "Codex / Codex-backend / Bedrock / fake have no /models"
    (doseq [pid [:codex :codex-backend :bedrock :fake]]
      (is (thrown? clojure.lang.ExceptionInfo (models/fetch-models pid))))))

(deftest supports-models-listing-reports-expected-set
  (is (true? (models/supports-models-listing? :openai)))
  (is (true? (models/supports-models-listing? :anthropic)))
  (is (true? (models/supports-models-listing? :gemini-native)))
  (is (true? (models/supports-models-listing? :vertex-gemini)))
  (is (true? (models/supports-models-listing? :openrouter)))
  (is (true? (models/supports-models-listing? :deepseek)))
  (is (true? (models/supports-models-listing? :kimi)))
  (is (false? (models/supports-models-listing? :codex)))
  (is (false? (models/supports-models-listing? :bedrock)))
  (is (false? (models/supports-models-listing? :fake))))

;; ---------------------------------------------------------------------------
;; Fetch-models end-to-end with mocked HTTP
;; ---------------------------------------------------------------------------

(defn- mock-http
  "Build a stub for llm.sdk.http/request that captures the URL/headers it
   was called with and returns a canned response."
  [captured response]
  (fn [req]
    (reset! captured req)
    response))

(deftest fetch-models-openai-end-to-end
  (let [captured (atom nil)
        body (load-fixture "openai")]
    (with-redefs [http/request (mock-http captured {:status 200 :body body})]
      (let [entries (models/fetch-models :openai)]
        (testing "URL built from profile base-url + /models"
          (is (= "https://api.openai.com/v1/models" (:url @captured)))
          (is (= :get (:method @captured))))
        (testing "parsed entries match fixture"
          (is (= 7 (count entries)))
          (is (some #(= "gpt-4o" (:model/id %)) entries))
          (is (every? #(= :openai (:model/provider %)) entries)))
        (testing "source-url tag captures the endpoint we hit"
          (is (every? #(= "https://api.openai.com/v1/models"
                          (:model/source-url %))
                      entries)))))))

(deftest fetch-models-anthropic-includes-version-header
  (let [captured (atom nil)
        body (load-fixture "anthropic")]
    (with-redefs [http/request (mock-http captured {:status 200 :body body})]
      (models/fetch-models :anthropic)
      (let [headers (:headers @captured)]
        (testing "anthropic-version header carried from default-headers"
          (is (= "2023-06-01" (get headers "anthropic-version"))))))))

(deftest fetch-models-openrouter-tolerates-missing-token
  (let [captured (atom nil)
        body (load-fixture "openrouter")]
    (with-redefs [http/request (mock-http captured {:status 200 :body body})]
      ;; Clear the env var via dynamic binding wouldn't work for System/getenv,
      ;; but provider/resolve-auth-token returns nil when env is absent at the
      ;; JVM level — when present, it'll just attach the bearer. Either way the
      ;; request should succeed.
      (let [entries (models/fetch-models :openrouter)]
        (is (= 3 (count entries)))
        (is (every? #(= :openrouter (:model/provider %)) entries))))))

(deftest fetch-models-throws-on-non-2xx
  (with-redefs [http/request (constantly {:status 401 :body {:error "unauthorized"}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Provider /models fetch failed"
                          (models/fetch-models :openai)))))

;; ---------------------------------------------------------------------------
;; OpenAI-shape aliases (DeepSeek, Kimi) — use their own fixtures
;; ---------------------------------------------------------------------------

(deftest deepseek-fixture-parses
  (let [body (load-fixture "deepseek")
        entries (models/parse-openai-style body :deepseek
                                           "https://api.deepseek.com/v1/models")]
    (is (= 2 (count entries)))
    (is (some #(= "deepseek-chat" (:model/id %)) entries))
    (is (some #(= "deepseek-reasoner" (:model/id %)) entries))
    (is (every? #(= :deepseek (:model/provider %)) entries))))

(deftest kimi-fixture-parses
  (let [body (load-fixture "kimi")
        entries (models/parse-openai-style body :kimi
                                           "https://api.moonshot.cn/v1/models")]
    (is (= 4 (count entries)))
    (is (some #(= "moonshot-v1-128k" (:model/id %)) entries))
    (is (some #(= "kimi-k2-0905-preview" (:model/id %)) entries))
    (is (every? #(= :kimi (:model/provider %)) entries))))

;; ---------------------------------------------------------------------------
;; Vertex /models — different envelope key + name prefix from Gemini Native
;; ---------------------------------------------------------------------------

(deftest vertex-fixture-parses
  (let [body (load-fixture "vertex")
        entries (models/parse-gemini-models
                 body :vertex-gemini
                 "https://us-central1-aiplatform.googleapis.com/v1/projects/x/locations/us-central1/publishers/google/models")]
    (is (= 2 (count entries)))
    (testing "publishers/google/models/ prefix stripped"
      (is (some #(= "gemini-2.5-pro" (:model/id %)) entries))
      (is (some #(= "gemini-2.5-flash" (:model/id %)) entries)))
    (is (every? #(= :vertex-gemini (:model/provider %)) entries))
    (is (every? #(= 1048576 (:model/context-length %)) entries))))
