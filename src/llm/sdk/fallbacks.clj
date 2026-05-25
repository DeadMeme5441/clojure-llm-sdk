(ns llm.sdk.fallbacks
  "Sequential fallback across (provider, model) pairs.

   Try each provider in order with the canonical request. Returns the
   first successful Response. If every attempt fails, throws ex-info
   carrying an :attempts vector of {:provider :model :error/...} maps
   in attempt order — callers can inspect to decide whether to
   re-raise, surface to UI, etc.

   What this is NOT:
  - Credential pools / multi-key load balancing
  - Cooldown caches / weighted shuffle
  - TPM/RPM enforcement / budget routing
  - Latency-aware routing / complexity routing

   All of those are explicitly out of scope for the SDK. They are
   credential-pool plumbing, the exact kind of work LiteLLM's router.py
   exists for and that we delegate back to the calling application.

   Reference: litellm-ref/router_utils/fallback_event_handlers.py:85
   run_async_fallback (shape only — do NOT port the pool plumbing)."
  (:require [llm.sdk.errors :as errors]))

(defn- classify-from-ex [e provider]
  (let [data (ex-data e)
        {:keys [error status body]} data]
    (or error
        (errors/classify-error e
                               :status status
                               :body body
                               :provider provider))))

(defn with-fallbacks
  "Try each [provider-id model-id] pair in sequence with the given
   canonical request. The request's :request/model is rewritten to the
   pair's model-id at each attempt.

   Options:
     :complete-fn  Custom completion fn (defaults to llm.sdk/complete,
                   resolved lazily). Tests can pass a stub.
     :on-attempt   Side-effect fn called with each attempt's failure
                   map {:provider :model :error/reason ...} — handy
                   for hooking up the application's logger without
                   forcing a dependency in.

   On all-fail, throws ex-info with:
     :attempts   vector of failure maps in order
     :providers  the original provider list"
  ([providers request]
   (with-fallbacks providers request {}))
  ([providers request {:keys [complete-fn on-attempt]}]
   (when (empty? providers)
     (throw (ex-info "with-fallbacks called with empty provider list"
                     {:request request})))
   (let [complete (or complete-fn
                      @(requiring-resolve 'llm.sdk/complete))
         attempts (volatile! [])]
     (loop [remaining providers]
       (if (empty? remaining)
         (throw (ex-info "All providers in with-fallbacks failed"
                         {:attempts @attempts
                          :providers (vec providers)}))
         (let [[provider-id model-id] (first remaining)
               attempt-req (assoc request :request/model model-id)
               result (try
                        {:ok (complete provider-id attempt-req)}
                        (catch clojure.lang.ExceptionInfo e
                          {:err e})
                        (catch Exception e
                          {:err e}))]
           (if-let [resp (:ok result)]
             resp
             (let [e (:err result)
                   classified (classify-from-ex e provider-id)
                   attempt (merge {:provider provider-id
                                   :model model-id}
                                  classified)]
               (vswap! attempts conj attempt)
               (when on-attempt (on-attempt attempt))
               (recur (rest remaining))))))))))
