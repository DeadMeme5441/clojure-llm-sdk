(ns llm.sdk.cache.policy
  "Provider/model cache strategy policy."
  (:require [clojure.string :as str]))

(defn- base-url-host [base-url]
  (try
    (when (seq base-url)
      (-> base-url java.net.URI. .getHost (or "") str/lower-case))
    (catch Exception _ "")))

(defn- claude-model? [model]
  (and (string? model)
       (str/includes? (str/lower-case model) "claude")))

(defn- qwen-model? [model]
  (and (string? model)
       (str/includes? (str/lower-case model) "qwen")))

(defn- anthropic-host? [base-url]
  (let [h (base-url-host base-url)]
    (or (= h "api.anthropic.com")
        (str/ends-with? h ".anthropic.com"))))

(defn- openrouter-host? [base-url]
  (let [h (base-url-host base-url)]
    (or (= h "openrouter.ai")
        (str/ends-with? h ".openrouter.ai"))))

(defn- minimax-host? [base-url]
  (let [h (base-url-host base-url)]
    (#{"api.minimax.io" "api.minimaxi.com"} h)))

(defn- xai-host? [base-url]
  (let [h (base-url-host base-url)]
    (or (= h "api.x.ai")
        (str/ends-with? h ".x.ai"))))

(defn decide-strategy
  "Decide the caching strategy and layout for a provider/model pair."
  [profile model request-cache]
  (let [provider-id (:profile/id profile)
        protocol (:profile/protocol-family profile)
        base-url (:profile/base-url profile)
        intent (get request-cache :strategy :auto)]
    (cond
      (= intent :none)
      {:strategy :none :layout nil :reason "caller disabled"}

      (= intent :explicit)
      {:strategy :explicit :layout nil :reason "caller explicit"}

      (or (= provider-id :anthropic)
          (and (= protocol :anthropic-messages) (anthropic-host? base-url)))
      {:strategy :system-and-3 :layout :native :reason "anthropic native"}

      (and (= protocol :anthropic-messages) (minimax-host? base-url))
      {:strategy :system-and-3 :layout :native :reason "minimax anthropic"}

      (and (= protocol :anthropic-messages) (claude-model? model))
      {:strategy :system-and-3 :layout :native :reason "anthropic-compat gateway"}

      (and (or (= provider-id :openrouter) (openrouter-host? base-url))
           (or (claude-model? model) (qwen-model? model)))
      {:strategy :system-and-3 :layout :envelope :reason "openrouter envelope"}

      (or (= provider-id :xai) (xai-host? base-url))
      {:strategy :prompt-key :layout nil :reason "xai responses"}

      (= protocol :bedrock)
      {:strategy :cache-point :layout nil :reason "bedrock converse"}

      (#{:gemini-native :vertex-gemini} provider-id)
      (cond
        (:cached-content-id request-cache)
        {:strategy :explicit :layout nil :reason "gemini cached content reference"}

        (= provider-id :vertex-gemini)
        {:strategy :implicit :layout nil :reason "vertex gemini implicit only"}

        :else
        {:strategy :implicit :layout nil :reason "gemini native implicit only"})

      (#{:openai-chat :codex :openrouter} protocol)
      {:strategy :prompt-key :layout nil :reason "openai cache routing"}

      :else
      {:strategy :none :layout nil :reason "no-op default"})))
