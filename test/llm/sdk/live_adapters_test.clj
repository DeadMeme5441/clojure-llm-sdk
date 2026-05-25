(ns llm.sdk.live-adapters-test
  "Live smoke tests for Codex, Anthropic OAuth, and OpenRouter.
   Gated by presence of respective API keys/tokens in environment."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.codex :as codex]))

(defn- has-creds? [env-var]
  (boolean (System/getenv env-var)))

;; ---------------------------------------------------------------------------
;; Codex / OpenAI Responses API (standard api.openai.com endpoint)
;; Uses OPENAI_API_KEY.
;; ---------------------------------------------------------------------------

(deftest ^:live test-codex-smoke
  (when (has-creds? "OPENAI_API_KEY")
    (testing "Codex Responses API (standard) smoke test"
      (let [req {:request/model "gpt-4o-mini"
                 :request/messages [{:message/role :user
                                     :message/content "Say exactly 'pong' and nothing else."}]
                 :request/max-tokens 16}
            resp (sdk/complete :codex req)]
        (is (= :codex (:response/provider resp)))
        (is (= :stop (:response/finish-reason resp)))
        (is (some #(= "pong" (str/lower-case (:text %)))
                  (:response/parts resp))
            (str "Expected 'pong' in parts: " (:response/parts resp)))))))

;; ---------------------------------------------------------------------------
;; Codex Backend (chatgpt.com/backend-api/codex)
;; Uses OAuth tokens from ~/.codex/auth.json
;; ---------------------------------------------------------------------------

(deftest ^:live test-codex-backend-smoke
  (when (codex/codex-backend-available?)
    (testing "Codex backend (chatgpt.com) smoke test"
      (let [req {:request/model "gpt-5.5"
                 :request/messages [{:message/role :system
                                     :message/content "You are a helpful assistant."}
                                    {:message/role :user
                                     :message/content "Say exactly 'pong' and nothing else."}]}
            resp (sdk/complete :codex-backend req)]
        (is (= :codex-backend (:response/provider resp)))
        (is (= :stop (:response/finish-reason resp)))
        (is (pos? (get-in resp [:response/usage :usage/input-tokens] 0)))
        (is (some #(= "pong" (str/lower-case (:text %)))
                  (:response/parts resp))
            (str "Expected 'pong' in parts: " (:response/parts resp)))))))

;; ---------------------------------------------------------------------------
;; Anthropic OAuth (Claude OAT token)
;; Uses CLAUDE_OAT_TOKEN with Bearer auth + Claude Code identity.
;; NOTE: OAuth/setup tokens (sk-ant-oat-*) have strict rate limits.
;;       A 429 means auth succeeded but the token is throttled.
;; ---------------------------------------------------------------------------

(deftest ^:live test-anthropic-oauth-smoke
  (when (has-creds? "CLAUDE_OAT_TOKEN")
    (testing "Anthropic OAuth smoke test"
      (let [oauth-profile {:profile/id :anthropic-oauth-temp
                           :profile/protocol-family :anthropic-messages
                           :profile/base-url "https://api.anthropic.com/v1"
                           :profile/auth-strategy :bearer
                           :profile/env-var-names ["CLAUDE_OAT_TOKEN"]
                           :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning :cache :thinking-blocks}
                           :profile/default-headers {"anthropic-version" "2023-06-01"}
                           :profile/transport-constructor
                           (fn [] ((requiring-resolve 'llm.sdk.providers.anthropic/make-transport)))}
            _ (provider/register-provider oauth-profile)
            req {:request/model "claude-sonnet-4-20250514"
                 :request/messages [{:message/role :user
                                     :message/content "Say exactly 'pong' and nothing else."}]
                 :request/max-tokens 1024}
            ;; Try the request; if we get a 429, the auth format is correct
            ;; but the token is rate-limited — that's still a success for the adapter.
            result (try
                     {:ok true :resp (sdk/complete :anthropic-oauth-temp req)}
                     (catch clojure.lang.ExceptionInfo e
                       (let [data (ex-data e)
                             status (:status data)
                             body (:body data)
                             ;; Body may be a string if JSON parse failed
                             body-map (if (string? body)
                                        (try (json/parse-string body true)
                                             (catch Exception _ {}))
                                        body)]
                         {:ok false :status status :body body-map :error e})))]
        (if (:ok result)
          (let [resp (:resp result)]
            (is (= :anthropic (:response/provider resp)))
            (is (= :stop (:response/finish-reason resp)))
            (is (some #(= "pong" (str/lower-case (:text %)))
                      (:response/parts resp))
                (str "Expected 'pong' in parts: " (:response/parts resp))))
          ;; If we got a 429, verify it's a rate limit (not an auth/format error)
          (do
            (is (= 429 (:status result))
                (str "Unexpected error status: " (:status result) 
                     " body: " (:body result)))
            (is (= "rate_limit_error" (get-in result [:body :error :type]))
                "Expected rate_limit_error when token is throttled")))))))

;; ---------------------------------------------------------------------------
;; OpenRouter
;; ---------------------------------------------------------------------------

(deftest ^:live test-openrouter-smoke
  (when (has-creds? "OPENROUTER_API_KEY")
    (testing "OpenRouter smoke test"
      (let [req {:request/model "openai/gpt-4o-mini"
                 :request/messages [{:message/role :user
                                     :message/content "Say exactly 'pong' and nothing else."}]
                 :request/max-tokens 10}
            resp (sdk/complete :openrouter req)]
        (is (= :stop (:response/finish-reason resp)))
        (is (some #(= "pong" (str/lower-case (:text %)))
                  (:response/parts resp))
            (str "Expected 'pong' in parts: " (:response/parts resp)))))))
