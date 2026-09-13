(ns llm.sdk.structure-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.cache.markers :as cache-markers]
            [llm.sdk.cache.policy :as cache-policy]
            [llm.sdk.cache.request :as cache-request]))

(deftest cache-facades-preserve-current-cache-surface
  (is (= {:type "ephemeral" :ttl "5m"} (cache-markers/marker)))
  (is (= false (cache-request/cache-enabled? {})))
  (is (= :prompt-key
         (:strategy (cache-policy/decide-strategy
                     {:profile/id :openai
                      :profile/protocol-family :openai-chat
                      :profile/base-url "https://api.openai.com/v1"}
                     "gpt-4o"
                     {:strategy :auto})))))
