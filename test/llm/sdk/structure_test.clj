(ns llm.sdk.structure-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.cache.markers :as cache-markers]
            [llm.sdk.cache.policy :as cache-policy]
            [llm.sdk.cache.request :as cache-request]
            [llm.sdk.provider.auth :as provider-auth]
            [llm.sdk.provider.registry :as provider-registry]
            [llm.sdk.providers.anthropic.chat :as anthropic-chat]
            [llm.sdk.providers.bedrock.converse :as bedrock-converse]
            [llm.sdk.providers.codex.responses :as codex-responses]
            [llm.sdk.providers.cohere.chat :as cohere-chat]
            [llm.sdk.providers.gemini.native :as gemini-native]
            [llm.sdk.providers.openai.chat :as openai-chat]))

(deftest provider-facades-delegate-to-existing-public-surface
  (is (fn? provider-registry/get-provider))
  (is (fn? provider-auth/default-headers))
  (is (some? (provider-registry/get-provider :openrouter))))

(deftest provider-family-namespaces-compile-and-expose-transports
  (doseq [make [openai-chat/make-transport
                anthropic-chat/make-transport
                gemini-native/make-transport
                cohere-chat/make-transport
                bedrock-converse/make-transport
                codex-responses/make-transport]]
    (is (fn? make))))

(deftest cache-facades-preserve-current-cache-surface
  (is (= {:type "ephemeral"} (cache-markers/marker)))
  (is (= false (cache-request/cache-enabled? {})))
  (is (= :prompt-key
         (:strategy (cache-policy/decide-strategy
                     {:profile/id :openai
                      :profile/protocol-family :openai-chat
                      :profile/base-url "https://api.openai.com/v1"}
                     "gpt-4o"
                     {:strategy :auto})))))
