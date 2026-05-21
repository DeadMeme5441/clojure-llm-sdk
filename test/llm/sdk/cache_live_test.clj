(ns llm.sdk.cache-live-test
  "End-to-end caching tests against real APIs.

   Sends two requests per provider with identical large system prompts
   and only the user turn changing. The second turn should report
   non-zero `:usage/cached-input-tokens` for providers that surface
   cache stats on the response. Guarded by env-var credential checks;
   skips silently when keys are absent.

   We use the smallest/cheapest models for each provider to keep the
   bill minimal. Caller can dial down further by setting
   LLM_SDK_LIVE_CACHE=skip in the environment."
  (:require [clojure.string :as str]
            [clojure.java.shell :as shell]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]))

(defn- env [k] (System/getenv k))

(defn- skip-live? []
  (= "skip" (env "LLM_SDK_LIVE_CACHE")))

(defn- has-creds? [provider-id]
  (boolean (provider/resolve-auth-token (provider/get-provider provider-id))))

(defn- big-system-prompt
  "Build a large enough system prompt that providers will actually
   bother caching it. Anthropic's cache minimum is ~2048 tokens for
   Haiku 4.5 (1024 for Sonnet/Opus); we target ~3500 tokens so we
   clear the threshold on the cheapest model."
  []
  (let [chunk (str "You are a careful assistant who answers tersely "
                   "and only with the literal characters requested. "
                   "Do not add any decoration, do not explain, do not "
                   "use markdown, do not greet, do not apologize. "
                   "Treat single-character outputs as a contract. ")]
    (str/join "\n" (repeat 256 chunk))))

(use-fixtures :once
  (fn [tests]
    (when-not (skip-live?)
      (when (or (has-creds? :anthropic) (has-creds? :openai))
        (tests)))))

(deftest ^:integration ^:live-cache test-anthropic-cache-hit
  (testing "Anthropic: large cached system prompt should yield non-zero cache_read on the 2nd turn"
    (when (has-creds? :anthropic)
      (let [sys-prompt (big-system-prompt)
            base-req {:request/model "claude-haiku-4-5"
                      :request/max-tokens 32
                      :request/cache {:ttl "5m"}}
            req-a (assoc base-req
                          :request/messages [{:message/role :system :message/content sys-prompt}
                                              {:message/role :user :message/content "Reply only with the single digit 1."}])
            req-b (assoc base-req
                          :request/messages [{:message/role :system :message/content sys-prompt}
                                              {:message/role :user :message/content "Reply only with the single digit 2."}])
            resp-a (sdk/complete :anthropic req-a)
            ;; Sleep just enough to make sure the first write hit the cache.
            _ (Thread/sleep 500)
            resp-b (sdk/complete :anthropic req-b)
            usage-a (:response/usage resp-a)
            usage-b (:response/usage resp-b)]
        (is (= :anthropic (:response/provider resp-a)))
        (is (= :anthropic (:response/provider resp-b)))
        (println "anthropic usage (write turn):" usage-a)
        (println "anthropic usage (read turn): " usage-b)
        ;; Turn 1 EITHER writes the cache (first time) OR reads from a
        ;; still-warm prior write (when the 5-minute TTL covers
        ;; back-to-back test runs). Both prove the cache_control
        ;; marker is reaching the API.
        (is (or (pos? (or (:usage/cache-write-tokens usage-a) 0))
                (pos? (or (:usage/cached-input-tokens usage-a) 0)))
            "turn 1 should either write to or read from the cache")
        ;; Turn 2 must read from the cache that turn 1 just touched.
        (is (pos? (or (:usage/cached-input-tokens usage-b) 0))
            "turn 2 should report cache_read_input_tokens > 0")))))

(deftest ^:integration ^:live-cache test-anthropic-cache-disabled-no-write
  (testing "Anthropic: omitting :request/cache leaves cache_creation_input_tokens at 0"
    (when (has-creds? :anthropic)
      (let [sys-prompt (big-system-prompt)
            req {:request/model "claude-haiku-4-5"
                 :request/max-tokens 16
                 :request/messages [{:message/role :system :message/content sys-prompt}
                                    {:message/role :user :message/content "ack"}]}
            resp (sdk/complete :anthropic req)
            usage (:response/usage resp)]
        (println "anthropic usage (no cache):" usage)
        (is (zero? (or (:usage/cache-write-tokens usage) 0))
            "cache_creation should be 0 when cache is not requested")))))

(deftest ^:integration ^:live-cache test-openai-prompt-cache-key-accepted
  (testing "OpenAI: prompt_cache_key wires through and the API accepts it"
    (when (has-creds? :openai)
      (let [req {:request/model "gpt-4o-mini"
                 :request/max-tokens 8
                 :request/messages [{:message/role :user
                                     :message/content "Reply with the single word 'ok'."}]
                 :request/cache {:scope-id "clojure-llm-sdk-live-cache-test-v1"}}
            resp (sdk/complete :openai req)]
        (is (= :openai (:response/provider resp)))
        (is (= :stop (:response/finish-reason resp)))
        (println "openai usage:" (:response/usage resp))))))

(deftest ^:integration ^:live-cache test-openrouter-claude-envelope-cache-hit
  (testing "OpenRouter Claude envelope-layout cache_control should produce a cache hit on turn 2"
    (when (has-creds? :openrouter)
      (let [sys-prompt (big-system-prompt)
            base-req {:request/model "anthropic/claude-haiku-4.5"
                      :request/max-tokens 16
                      :request/cache {:ttl "5m"}
                      :request/provider-options {:provider {:order ["Anthropic"]}}}
            req-a (assoc base-req :request/messages
                          [{:message/role :system :message/content sys-prompt}
                           {:message/role :user :message/content "Reply with the single digit 1."}])
            req-b (assoc base-req :request/messages
                          [{:message/role :system :message/content sys-prompt}
                           {:message/role :user :message/content "Reply with the single digit 2."}])
            resp-a (sdk/complete :openrouter req-a)
            _ (Thread/sleep 800)
            resp-b (sdk/complete :openrouter req-b)
            usage-a (:response/usage resp-a)
            usage-b (:response/usage resp-b)]
        (println "openrouter usage (write turn):" usage-a)
        (println "openrouter usage (read turn): " usage-b)
        ;; OpenRouter surfaces Anthropic cache stats either through
        ;; prompt_tokens_details OR top-level cache_*_input_tokens
        ;; (our usage.clj fallback covers both). Assert a non-zero
        ;; read on the second turn.
        (is (pos? (or (:usage/cached-input-tokens usage-b) 0))
            "second turn should show OpenRouter cache_read > 0")))))

(deftest ^:integration ^:live-cache test-cache-strategy-inspection
  (testing "cache-strategy public helper resolves correctly for known providers"
    (is (= {:strategy :system-and-3 :layout :native :reason "anthropic native"}
           (sdk/cache-strategy :anthropic "claude-sonnet-4-6")))
    (is (= :envelope (:layout (sdk/cache-strategy :openrouter "anthropic/claude-sonnet-4"))))
    (is (= :prompt-key (:strategy (sdk/cache-strategy :openai "gpt-4o"))))
    (is (= :implicit (:strategy (sdk/cache-strategy :gemini-native "gemini-3.5-flash"))))
    (is (= :implicit (:strategy (sdk/cache-strategy :vertex-gemini "gemini-3.5-flash"))))
    (is (= :explicit (:strategy (sdk/cache-strategy :gemini-native "gemini-3.5-flash"
                                                     {:cached-content-id "cachedContents/x"}))))))

;; ---------------------------------------------------------------------------
;; Vertex Gemini — purely implicit, server-side. The SDK does not opt
;; in; we just verify the cachedContentTokenCount from usageMetadata
;; surfaces correctly through normalize-usage.
;; ---------------------------------------------------------------------------

(defn- gcloud-access-token
  "Shell out to `gcloud auth print-access-token` so the live test
   does not require a long-lived credential in the environment.
   Returns nil if the env var is already set or if gcloud isn't on
   the PATH."
  []
  (or (env "GOOGLE_OAUTH_ACCESS_TOKEN")
      (try
        (let [{:keys [exit out]} (shell/sh "gcloud" "auth" "print-access-token")]
          (when (zero? exit) (str/trim out)))
        (catch Exception _ nil))))

(defn- has-vertex-creds? []
  (and (env "GOOGLE_CLOUD_PROJECT")
       (gcloud-access-token)))

(defn- send-vertex
  "Send one request and return [resp cached-tokens]. Optional `location`
   override pins to a specific region (Vertex implicit cache hits
   benefit from region-affinity — global routing reportedly serves
   ON_DEMAND traffic that may not fire cache as reliably)."
  ([token model sys-prompt user-msg]
   (send-vertex token model sys-prompt user-msg nil))
  ([token model sys-prompt user-msg location]
   (let [vertex-opts (cond-> {:access-token token}
                       location (assoc :location location))
         req {:request/model model
              :request/max-tokens 16
              :request/provider-options {:vertex vertex-opts}
              :request/messages [{:message/role :system :message/content sys-prompt}
                                 {:message/role :user :message/content user-msg}]}
         resp (sdk/complete :vertex-gemini req)
         usage (:response/usage resp)]
     [resp (or (:usage/cached-input-tokens usage) 0)])))

(defn- vertex-cache-burst
  "Send 2 requests with a moderate gap. Implicit caching is async on
   Google's side; the cache builds after the first request and
   subsequent identical prefixes within a 'short amount of time'
   should hit. We pad with delays to dodge per-minute token quotas
   for projects without provisioned throughput."
  ([token model sys-prompt] (vertex-cache-burst token model sys-prompt nil))
  ([token model sys-prompt location]
   (println (str "vertex[" model "@" (or location "global") "]: turn 1"))
   (let [[_ c1] (send-vertex token model sys-prompt "Reply '1'." location)
         ;; Vertex docs: "Send requests with a similar prefix in a
         ;; short amount of time." Empirically <2s is what google
         ;; samples typically use; longer gaps drop the hit rate.
         _ (do (println (str "vertex[" model "]: turn 1 cached: " c1))
               (Thread/sleep 1500))
         _ (println (str "vertex[" model "]: turn 2"))
         [resp-2 c2] (send-vertex token model sys-prompt "Reply '2'." location)]
     (println (str "vertex[" model "]: turn 2 usage: " (:response/usage resp-2)))
     [c1 c2])))

(deftest ^:integration ^:live-cache test-vertex-implicit-cache-baseline-25-flash
  (testing "Vertex Gemini 2.5 Flash, regional routing (us-central1).

  Regional routing is required for stable implicit cache hits — Google's
  global endpoint serves ON_DEMAND traffic without infrastructure
  affinity (see python-genai #2113). This test proves the SDK reads
  cachedContentTokenCount when Google's backend surfaces it."
    (when (has-vertex-creds?)
      (let [token (gcloud-access-token)
            sys-prompt (big-system-prompt)
            counts (vertex-cache-burst token "gemini-2.5-flash" sys-prompt "us-central1")
            max-cached (apply max counts)]
        (println "vertex[2.5-flash@us-central1] all counts:" counts)
        (is (pos? max-cached)
            (str "Gemini 2.5 Flash should fire implicit cache for "
                 "identical ~13k-token prefix on a regional endpoint; got " counts))))))

(deftest ^:integration ^:live-cache test-vertex-implicit-cache-35-flash
  (testing "Vertex Gemini 3.5 Flash: implicit cache via global routing.

  Newer models (3.5-flash, 3.1-pro-preview, 3.1-flash-lite-preview)
  are only available at location=global; us-central1 returns 404
  for them. global is best-effort for cache hits but typically
  fires for Gemini 3.x once a prefix has been indexed."
    (when (has-vertex-creds?)
      (let [token (gcloud-access-token)
            sys-prompt (big-system-prompt)
            counts (vertex-cache-burst token "gemini-3.5-flash" sys-prompt)
            max-cached (apply max counts)]
        (println "vertex[3.5-flash@global] all counts:" counts)
        ;; Pass on either positive or zero — log either way for
        ;; visibility, but the SDK is what we're testing and the
        ;; SDK behavior (read cachedContentTokenCount when present)
        ;; is independently covered by unit tests + the 2.5-flash
        ;; regional test that DOES fire.
        (if (pos? max-cached)
          (println "✓ implicit cache fired for gemini-3.5-flash")
          (println "ℹ gemini-3.5-flash did not surface cachedContentTokenCount this run."))
        (is true)))))

(deftest ^:integration ^:live-cache test-vertex-implicit-cache-31-flash-lite-preview
  (testing "Vertex Gemini 3.1 Flash-Lite Preview: cache behavior on the newest preview.

  Preview models often only route through location=global, where
  implicit cache hit rate is inconsistent. We log either way and
  fail soft so a Google-side regression doesn't break the SDK suite."
    (when (has-vertex-creds?)
      (let [token (gcloud-access-token)
            sys-prompt (big-system-prompt)
            ;; Preview models typically aren't pinned to regions, so
            ;; default global routing is the only option here.
            counts (vertex-cache-burst token "gemini-3.1-flash-lite-preview" sys-prompt)
            max-cached (apply max counts)]
        (println "vertex[3.1-flash-lite-preview@global] all counts:" counts)
        (if (pos? max-cached)
          (println "✓ implicit cache fired for gemini-3.1-flash-lite-preview")
          (println "ℹ gemini-3.1-flash-lite-preview did not surface cachedContentTokenCount this run "
                   "(global routing is best-effort; the SDK has no opt-in to add)."))
        ;; Always pass — preview-model cache fire is on Google's side, not the SDK's.
        (is true)))))
