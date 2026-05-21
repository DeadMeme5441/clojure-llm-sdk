(ns llm.sdk.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.cache :as cache]
            [llm.sdk.provider :as provider]))

;; ---------------------------------------------------------------------------
;; Marker building
;; ---------------------------------------------------------------------------

(deftest test-marker-default-ttl
  (is (= {:type "ephemeral"} (cache/marker)))
  (is (= {:type "ephemeral"} (cache/marker "5m"))))

(deftest test-marker-1h-ttl
  (is (= {:type "ephemeral" :ttl "1h"} (cache/marker "1h"))))

;; ---------------------------------------------------------------------------
;; system_and_3 layout — native
;; ---------------------------------------------------------------------------

(deftest test-system-and-3-empty
  (is (= [] (cache/apply-system-and-3 [])))
  (is (= [] (cache/apply-system-and-3 [] {:breakpoints 4}))))

(deftest test-system-and-3-marks-system-and-tail-native
  (let [messages [{:role "system" :content "sys"}
                  {:role "user" :content "u1"}
                  {:role "assistant" :content "a1"}
                  {:role "user" :content "u2"}
                  {:role "assistant" :content "a2"}
                  {:role "user" :content "u3"}]
        marked (cache/apply-system-and-3 messages {:layout :native})]
    (testing "system message gets marker on inner content block"
      (let [sys-content (:content (first marked))]
        (is (vector? sys-content))
        (is (= {:type "ephemeral"}
               (get-in (last sys-content) [:cache_control])))))
    (testing "last 3 non-system messages get markers"
      ;; total 6 messages, system = 1 breakpoint, 3 more for tail.
      ;; That means messages at indices 3, 4, 5 are marked.
      (doseq [i [3 4 5]]
        (let [c (:content (nth marked i))]
          (is (= {:type "ephemeral"}
                 (get-in (last c) [:cache_control]))
              (str "expected marker on message " i)))))
    (testing "middle messages stay un-marked"
      (let [c (:content (nth marked 1))]
        (is (or (string? c)
                (not (some :cache_control c))))))))

(deftest test-system-and-3-envelope-layout
  (let [messages [{:role "system" :content "sys"}
                  {:role "user" :content "u1"}
                  {:role "assistant" :content "a1"}
                  {:role "user" :content "u2"}]
        marked (cache/apply-system-and-3 messages {:layout :envelope})]
    (testing "marker lives on outer dict, content untouched"
      (is (= {:type "ephemeral"} (:cache_control (first marked))))
      (is (= "sys" (:content (first marked)))))
    (testing "tail messages marked on envelope"
      (doseq [i [1 2 3]]
        (is (= {:type "ephemeral"} (:cache_control (nth marked i))))))))

(deftest test-system-and-3-no-system-message
  (let [messages [{:role "user" :content "u1"}
                  {:role "assistant" :content "a1"}
                  {:role "user" :content "u2"}]
        marked (cache/apply-system-and-3 messages {:layout :native :breakpoints 3})]
    (testing "all messages get marked when no system role + breakpoints = count"
      (doseq [m marked]
        (let [c (:content m)]
          (is (= {:type "ephemeral"}
                 (get-in (last c) [:cache_control]))))))))

(deftest test-system-and-3-respects-breakpoint-count
  (let [messages [{:role "system" :content "sys"}
                  {:role "user" :content "u1"}
                  {:role "user" :content "u2"}
                  {:role "user" :content "u3"}
                  {:role "user" :content "u4"}]
        ;; breakpoints=2 → system + only the very last message
        marked (cache/apply-system-and-3 messages {:layout :envelope :breakpoints 2})]
    (is (= {:type "ephemeral"} (:cache_control (first marked))))
    (is (= {:type "ephemeral"} (:cache_control (last marked))))
    (is (nil? (:cache_control (nth marked 1))))
    (is (nil? (:cache_control (nth marked 2))))
    (is (nil? (:cache_control (nth marked 3))))))

(deftest test-system-and-3-ttl-1h
  (let [messages [{:role "system" :content "sys"}
                  {:role "user" :content "u1"}]
        marked (cache/apply-system-and-3 messages {:ttl "1h" :layout :envelope})]
    (is (= {:type "ephemeral" :ttl "1h"} (:cache_control (first marked))))
    (is (= {:type "ephemeral" :ttl "1h"} (:cache_control (last marked))))))

;; ---------------------------------------------------------------------------
;; System / tools array caching
;; ---------------------------------------------------------------------------

(deftest test-apply-system-blocks-cache
  (let [blocks [{:type "text" :text "a"}
                {:type "text" :text "b"}]
        marked (cache/apply-system-blocks-cache blocks {})]
    (is (nil? (:cache_control (first marked))))
    (is (= {:type "ephemeral"} (:cache_control (last marked))))))

(deftest test-apply-tools-cache
  (let [tools [{:name "foo"} {:name "bar"}]
        marked (cache/apply-tools-cache tools {})]
    (is (nil? (:cache_control (first marked))))
    (is (= {:type "ephemeral"} (:cache_control (last marked))))))

;; ---------------------------------------------------------------------------
;; Layout policy
;; ---------------------------------------------------------------------------

(deftest test-decide-strategy-anthropic-native
  (let [profile (provider/get-provider :anthropic)
        d (cache/decide-strategy profile "claude-sonnet-4-6" {})]
    (is (= :system-and-3 (:strategy d)))
    (is (= :native (:layout d)))))

(deftest test-decide-strategy-openrouter-envelope
  (let [profile (provider/get-provider :openrouter)
        d-claude (cache/decide-strategy profile "anthropic/claude-sonnet-4-6" {})
        d-qwen (cache/decide-strategy profile "qwen/qwen3-coder" {})
        d-other (cache/decide-strategy profile "openai/gpt-4o" {})]
    (is (= :system-and-3 (:strategy d-claude)))
    (is (= :envelope (:layout d-claude)))
    (is (= :system-and-3 (:strategy d-qwen)))
    (is (= :envelope (:layout d-qwen)))
    ;; non-Claude, non-Qwen OpenRouter falls through to prompt-key
    (is (= :prompt-key (:strategy d-other)))))

(deftest test-decide-strategy-openai-prompt-key
  (let [profile (provider/get-provider :openai)
        d (cache/decide-strategy profile "gpt-4o" {})]
    (is (= :prompt-key (:strategy d)))))

(deftest test-decide-strategy-gemini-implicit-default
  (let [native-profile (provider/get-provider :gemini-native)
        vertex-profile (provider/get-provider :vertex-gemini)]
    (testing "no cached-content-id → :implicit (server-side, no opt-in)"
      (is (= :implicit (:strategy (cache/decide-strategy native-profile "gemini-2.5-pro" {}))))
      (is (= :implicit (:strategy (cache/decide-strategy vertex-profile "gemini-2.5-pro" {})))))
    (testing "explicit cached-content-id wins when supplied"
      (is (= :explicit (:strategy (cache/decide-strategy native-profile "gemini-2.5-pro"
                                                          {:cached-content-id "cachedContents/x"}))))
      (is (= :explicit (:strategy (cache/decide-strategy vertex-profile "gemini-2.5-pro"
                                                          {:cached-content-id "cachedContents/x"})))))))

(deftest test-decide-strategy-respects-caller-override
  (let [profile (provider/get-provider :anthropic)
        d-none (cache/decide-strategy profile "claude-sonnet-4-6" {:strategy :none})
        d-explicit (cache/decide-strategy profile "claude-sonnet-4-6" {:strategy :explicit})]
    (is (= :none (:strategy d-none)))
    (is (= :explicit (:strategy d-explicit)))))

;; ---------------------------------------------------------------------------
;; cache-enabled? semantics
;; ---------------------------------------------------------------------------

(deftest test-cache-enabled-defaults
  (is (false? (cache/cache-enabled? {})))
  (is (true? (cache/cache-enabled? {:request/cache {}})))
  (is (false? (cache/cache-enabled? {:request/cache {:enabled? false}})))
  (is (false? (cache/cache-enabled? {:request/cache {:strategy :none}})))
  (is (true? (cache/cache-enabled? {:request/cache {:ttl "1h"}}))))
