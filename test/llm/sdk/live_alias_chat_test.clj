(ns llm.sdk.live-alias-chat-test
  "Live chat smokes for OpenAI-compatible aliases.

   Each test issues one tiny request (≤8 max-tokens) against the
   cheapest available model on each provider, then asserts the canonical
   response carries the right provider keyword and at least one text
   part. The intent is to verify the alias profile wires up end-to-end,
   not to spot-check provider behaviour beyond that — adapter unit
   tests in openai_aliases_test cover the body/URL/auth shape.

   These tests are env-gated. Without the matching API key, each is a
   clean skip with no assertions.

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-alias-chat-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

(defn- text-part [resp]
  (->> (:response/parts resp)
       (filter #(= (:part/type %) :text))
       first))

(defn- smoke-chat
  "Send 'Reply with the single word: ok' and verify the response shape."
  ([provider-id model]
   (smoke-chat provider-id model 8))
  ([provider-id model max-tokens]
   (let [resp (sdk/complete
               provider-id
               {:request/model model
                :request/messages [{:message/role :user
                                    :message/content "Reply with the single word: ok"}]
                :request/max-tokens max-tokens})]
     (is (= provider-id (:response/provider resp))
         (str provider-id " response carries provider id"))
     (is (string? (:text (text-part resp)))
         (str provider-id " returned at least one text part"))
     (is (contains? #{:stop :length} (:response/finish-reason resp))
         (str provider-id " finished cleanly")))))

(deftest ^:live live-kimi-code-chat
  (when (has-creds? "KIMI_API_KEY")
    (testing "Kimi Code chat live"
      (smoke-chat :kimi-code "kimi-for-coding" 128))))

(deftest ^:live live-deepseek-chat
  (when (has-creds? "DEEPSEEK_API_KEY")
    (testing "DeepSeek OpenAI-compatible chat live"
      (smoke-chat :deepseek "deepseek-chat"))))

(deftest ^:live live-mistral-chat
  (when (has-creds? "MISTRAL_API_KEY")
    (testing "Mistral chat live"
      (smoke-chat :mistral "mistral-small-latest"))))

(deftest ^:live live-groq-chat
  (when (has-creds? "GROQ_API_KEY")
    (testing "Groq chat live"
      (smoke-chat :groq "llama-3.1-8b-instant"))))

(deftest ^:live live-cerebras-chat
  (when (has-creds? "CEREBRAS_API_KEY")
    (testing "Cerebras chat live"
      (smoke-chat :cerebras "llama3.1-8b"))))

(deftest ^:live live-together-chat
  (when (has-creds? "TOGETHER_API_KEY")
    (testing "Together chat live"
      ;; Free-tier Llama keeps the smoke at $0.
      (smoke-chat :together "meta-llama/Llama-3.3-70B-Instruct-Turbo-Free"))))

(deftest ^:live live-xai-chat
  (when (has-creds? "XAI_API_KEY")
    (testing "xAI chat live"
      (smoke-chat :xai "grok-3-mini-fast"))))

(deftest ^:live live-huggingface-chat
  (when (has-creds? "HF_TOKEN")
    (testing "HuggingFace router chat live"
      ;; The router accepts namespaced model ids; pick a small free
      ;; chat model so the smoke stays cheap.
      (smoke-chat :huggingface "meta-llama/Llama-3.1-8B-Instruct"))))
