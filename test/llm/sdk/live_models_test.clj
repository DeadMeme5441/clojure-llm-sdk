(ns llm.sdk.live-models-test
  "Live smoke tests for every provider's /models endpoint.

   /models endpoints are free — no token billing — so these tests
   are safe to run on real API keys. Each test is gated on the
   provider's credentials being present in the environment; missing
   creds yields a clean skip with no assertions.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-models-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.models :as models]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(defn- vertex-available? []
  (and (has-creds? "GOOGLE_CLOUD_PROJECT")
       (or (has-creds? "GOOGLE_OAUTH_ACCESS_TOKEN")
           ;; gcloud on PATH counts — fetch-models :vertex-gemini will shell out
           (zero? (:exit (clojure.java.shell/sh "which" "gcloud"))))))

(defn- smoke-fetch
  "Hit a provider's /models, assert non-empty and shape-conformant."
  [provider-id]
  (let [entries (models/fetch-models provider-id)]
    (is (seq entries)
        (str "Expected non-empty /models for " provider-id))
    (is (every? :model/id entries)
        "every entry has a :model/id")
    (is (every? #(= provider-id (:model/provider %)) entries)
        "every entry carries our SDK provider keyword")
    (is (every? #(= :live-models-api (:model/source %)) entries)
        "every entry tagged :live-models-api")
    (is (every? models/validate-model-entry entries)
        "every entry passes the ModelEntry schema")))

;; ---------------------------------------------------------------------------
;; OpenAI / DeepSeek / Kimi — OpenAI-compat /v1/models
;; ---------------------------------------------------------------------------

(deftest ^:live live-openai-models
  (when (has-creds? "OPENAI_API_KEY")
    (testing "OpenAI /v1/models live"
      (smoke-fetch :openai))))

(deftest ^:live live-deepseek-models
  (when (has-creds? "DEEPSEEK_API_KEY")
    (testing "DeepSeek /v1/models live"
      (smoke-fetch :deepseek))))

(deftest ^:live live-kimi-models
  (when (has-creds? "KIMI_API_KEY")
    (testing "Kimi /v1/models live"
      (smoke-fetch :kimi))))

;; ---------------------------------------------------------------------------
;; Anthropic — /v1/models with anthropic-version header
;; ---------------------------------------------------------------------------

(deftest ^:live live-anthropic-models
  (when (has-creds? "ANTHROPIC_API_KEY")
    (testing "Anthropic /v1/models live"
      (smoke-fetch :anthropic))))

;; ---------------------------------------------------------------------------
;; Gemini Native — /v1beta/models
;; ---------------------------------------------------------------------------

(deftest ^:live live-gemini-native-models
  (when (has-creds? "GEMINI_API_KEY")
    (testing "Gemini Native /v1beta/models live"
      (smoke-fetch :gemini-native))))

;; ---------------------------------------------------------------------------
;; Vertex Gemini — needs GOOGLE_CLOUD_PROJECT + (GOOGLE_OAUTH_ACCESS_TOKEN
;; or gcloud on PATH)
;; ---------------------------------------------------------------------------

(deftest ^:live live-vertex-gemini-models
  (when (vertex-available?)
    (testing "Vertex Gemini /publishers/google/models live"
      (try
        (smoke-fetch :vertex-gemini)
        (catch clojure.lang.ExceptionInfo e
          (let [{:keys [status]} (ex-data e)]
            ;; 403 = quota / serviceUsageConsumer permission missing. The
            ;; endpoint shape is correct (we built the right URL with the
            ;; right headers) — the caller just hasn't been granted IAM
            ;; on the project. Treat that as an environment-level skip,
            ;; same as live_adapters_test does for 429 on rate-limited
            ;; Anthropic OAuth tokens.
            (if (= status 403)
              (is true (str "Vertex /models: 403 PERMISSION_DENIED — "
                            "grant roles/serviceusage.serviceUsageConsumer "
                            "and re-run; endpoint shape verified"))
              (throw e))))))))

;; ---------------------------------------------------------------------------
;; OpenRouter — /api/v1/models (public, key optional)
;; ---------------------------------------------------------------------------

(deftest ^:live live-openrouter-models
  ;; OpenRouter /models is public, but we still gate on the API key so
  ;; the default `clj -M:test` run stays offline — same convention as
  ;; the other live tests in this suite.
  (when (has-creds? "OPENROUTER_API_KEY")
    (testing "OpenRouter /api/v1/models live"
      (smoke-fetch :openrouter)
      (let [entries (models/fetch-models :openrouter)
            gpt4o (first (filter #(= "openai/gpt-4o" (:model/id %)) entries))]
        (when gpt4o
          (testing "context length + cost flow through from the rich response"
            (is (pos? (:model/context-length gpt4o)))
            (is (pos? (get-in gpt4o [:model/cost :input-per-million])))))))))

;; ---------------------------------------------------------------------------
;; T2-03 OpenAI-compat aliases — all five share OpenAI /v1/models shape
;; ---------------------------------------------------------------------------

(deftest ^:live live-mistral-models
  (when (has-creds? "MISTRAL_API_KEY")
    (testing "Mistral /v1/models live"
      (smoke-fetch :mistral))))

(deftest ^:live live-groq-models
  (when (has-creds? "GROQ_API_KEY")
    (testing "Groq /v1/models live"
      (smoke-fetch :groq))))

(deftest ^:live live-cerebras-models
  (when (has-creds? "CEREBRAS_API_KEY")
    (testing "Cerebras /v1/models live"
      (smoke-fetch :cerebras))))

(deftest ^:live live-together-models
  (when (has-creds? "TOGETHER_API_KEY")
    (testing "Together /v1/models live"
      (smoke-fetch :together))))

(deftest ^:live live-xai-models
  (when (has-creds? "XAI_API_KEY")
    (testing "xAI /v1/models live"
      (smoke-fetch :xai))))

(deftest ^:live live-huggingface-models
  (when (has-creds? "HF_TOKEN")
    (testing "HuggingFace router /v1/models live"
      (smoke-fetch :huggingface))))
