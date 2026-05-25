(ns llm.sdk.live-test
  "Live integration tests gated by environment variables.
   NEVER run these without explicit consent and valid credentials.
   These tests make real API calls and cost money."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]))

(defn- has-creds? [provider-id]
  (boolean (provider/resolve-auth-token (provider/get-provider provider-id))))

(use-fixtures :once
  (fn [tests]
    (when (or (has-creds? :openai) (has-creds? :anthropic))
      (tests))))

(deftest ^:integration test-openai-complete-smoke
  (when (has-creds? :openai)
    (testing "OpenAI Chat Completions smoke test"
      (let [req {:request/model "gpt-4o-mini"
                 :request/messages [{:message/role :user
                                     :message/content "Say exactly 'pong' and nothing else."}]
                 :request/max-tokens 10}
            resp (sdk/complete :openai req)]
        (is (= :openai (:response/provider resp)))
        (is (= :stop (:response/finish-reason resp)))
        (is (some #(= "pong" (str/lower-case (:text %)))
                  (:response/parts resp))
            (str "Expected 'pong' in parts: " (:response/parts resp)))))))

(deftest ^:integration test-anthropic-complete-smoke
  (when (has-creds? :anthropic)
    (testing "Anthropic Messages smoke test"
      (let [req {:request/model "claude-sonnet-4-20250514"
                 :request/messages [{:message/role :user
                                     :message/content "Say exactly 'pong' and nothing else."}]
                 :request/max-tokens 1024}
            resp (sdk/complete :anthropic req)]
        (is (= :anthropic (:response/provider resp)))
        (is (= :stop (:response/finish-reason resp)))
        (is (some #(= "pong" (str/lower-case (:text %)))
                  (:response/parts resp))
            (str "Expected 'pong' in parts: " (:response/parts resp)))))))

;; NOTE: To run live tests:
;;   export OPENAI_API_KEY=...
;;   export ANTHROPIC_API_KEY=...
;;   clj -M:test -i integration
;; Or:
;;   clj -M:live-test
