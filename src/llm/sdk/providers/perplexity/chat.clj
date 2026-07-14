(ns llm.sdk.providers.perplexity.chat
  "Perplexity Sonar transport.
   Builds the current /v1/sonar request shape and normalizes grounded
   citations, search results, usage/cost, and full or concise SSE streams."
  (:require [llm.sdk.sse :as sse]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai.chat :as openai]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

(defn- normalize-perplexity-usage [raw]
  (cond-> (usage/normalize-openai-usage raw)
    (some? (:reasoning_tokens raw))
    (assoc :usage/reasoning-tokens
           (usage/->int (:reasoning_tokens raw)))))

(defn- reported-cost [usage-raw]
  (let [cost (:cost usage-raw)]
    (when (number? (:total_cost cost))
      {:cost/usd (:total_cost cost)
       :cost/estimated? false
       :cost/pricing-source :perplexity-reported
       :cost/source-url
       "https://docs.perplexity.ai/api-reference/sonar-post"
       :cost/breakdown cost})))

;; ---------------------------------------------------------------------------
;; Citation extraction
;; ---------------------------------------------------------------------------

(defn- search-result->citation [r]
  (cond-> {:part/type :citation :citation/url (:url r)}
    (:title r) (assoc :citation/title (:title r))
    (:snippet r) (assoc :citation/snippet (:snippet r))))

(defn extract-citation-parts
  "Return a vector of CitationPart maps from a Perplexity raw response.
   Prefers :search_results (carries title + snippet); falls back to
   :citations (URL-only)."
  [raw]
  (cond
    (seq (:search_results raw))
    (mapv search-result->citation (:search_results raw))

    (seq (:citations raw))
    (mapv #(hash-map :part/type :citation :citation/url %)
          (filter string? (:citations raw)))

    :else
    []))

;; Request building
;; ---------------------------------------------------------------------------

(defn build-request-perplexity
  [profile request]
  (let [base (openai/build-request-openai profile request)
        base-body (:body base)
        provider-extra (get-in request
                               [:request/provider-options :extra_body])
        reasoning (:request/reasoning request)
        body (merge (dissoc base-body :extra_body)
                    (:extra_body base-body)
                    provider-extra)
        body (cond-> (dissoc body :reasoning)
               (and (:enabled reasoning) (:effort reasoning))
               (assoc :reasoning_effort (name (:effort reasoning)))
               (:request/stream? request)
               (assoc :stream true))]
    (assoc base
           :url (str (:profile/base-url profile) "/v1/sonar")
           :body body)))

;; ---------------------------------------------------------------------------
;; Response parsing — extend OpenAI parser with citations
;; ---------------------------------------------------------------------------

(defn parse-response-perplexity
  [profile raw]
  (let [base (openai/parse-response-openai profile raw)
        citation-parts (extract-citation-parts raw)
        parts (:response/parts base [])
        provider-data (merge (:response/provider-data base)
                             (select-keys raw [:images :related_questions]))
        usage-raw (:usage raw)
        actual-cost (reported-cost usage-raw)]
    (cond-> (assoc base :response/provider :perplexity)
      (seq citation-parts)
      (assoc :response/parts (into parts citation-parts))
      usage-raw
      (assoc :response/usage (normalize-perplexity-usage usage-raw))
      (seq provider-data)
      (assoc :response/provider-data provider-data)
      actual-cost
      (assoc :response/cost actual-cost))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- citation-events-from-data [data]
  (cond
    (seq (:search_results data))
    (mapv (fn [r]
            (stream/citation-event (:url r)
                                   :title (:title r)
                                   :snippet (:snippet r)))
          (:search_results data))

    (seq (:citations data))
    (mapv #(stream/citation-event %)
          (filter string? (:citations data)))

    :else
    nil))

(defn- reasoning-events-from-data [data]
  (->> (get-in data [:choices 0 :delta :reasoning_steps])
       (keep :thought)
       (filter string?)
       (mapv stream/reasoning-delta)))

(defn parse-stream-event-perplexity
  "Parse both full and concise Sonar SSE chunks.
   Full-mode metadata can repeat on content chunks, so citations and usage are
   emitted only when the choice finishes. Concise reasoning metadata is
   translated to canonical reasoning deltas and final search results are
   emitted from chat.completion.done."
  [_profile line]
  (when-let [data (parse-sse-line line)]
    (let [choice (first (:choices data))
          delta (:delta choice)
          finish (:finish_reason choice)
          object (:object data)
          final? (or (some? finish)
                     (= "chat.completion.done" object))
          content (:content delta)
          reasoning-events (when (= "chat.reasoning" object)
                             (reasoning-events-from-data data))
          citations (when final? (citation-events-from-data data))
          usage-raw (when final? (:usage data))
          events (cond-> []
                   (seq content)
                   (conj (stream/content-delta content))
                   (seq reasoning-events)
                   (into reasoning-events)
                   (seq citations)
                   (into citations)
                   usage-raw
                   (conj (stream/usage-event
                          (normalize-perplexity-usage usage-raw)
                          :cost (reported-cost usage-raw)))
                   final?
                   (conj (stream/end-event
                          :finish-reason
                          (case finish
                            "stop" :stop
                            nil :stop
                            "length" :length
                            :unknown))))]
      (case (count events)
        0 nil
        1 (first events)
        events))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-perplexity
  [_profile status body]
  (errors/classify-api-error :perplexity "Perplexity" status body))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord PerplexityTransport []
  t/Transport
  (build-request [_this profile request]
    (build-request-perplexity profile request))
  (parse-response [_this profile raw]
    (parse-response-perplexity profile raw))
  (parse-stream-event [_this profile line]
    (parse-stream-event-perplexity profile line))
  (parse-error [_this profile status body]
    (parse-error-perplexity profile status body))
  (normalize-usage [_this _profile raw]
    (normalize-perplexity-usage raw))
  (request-capabilities [_]
    #{:chat :streaming :json-schema :web-search :reasoning}))

(defn make-transport [] (->PerplexityTransport))

;; ---------------------------------------------------------------------------
;; Custom cost calculator
;;
;; Perplexity's Sonar models charge token-style + per-search-query (the
;; \"Sonar Pro\" tier is the canonical case). The default token math
;; covers input/output, then we add an extra per-search-query line item
;; for any pricing entry that carries :search-cost-per-call. The
;; default calculator's :pricing.clj implementation skips the addend, so
;; we plug in here.
;; ---------------------------------------------------------------------------

(defn perplexity-cost-calculator
  "Reads canonical token + search-query usage and produces a
   cost-result. Falls back to the default token cost when no
   :search-cost-per-call is set."
  [{:keys [_provider _model usage pricing]}]
  (let [base ((requiring-resolve 'llm.sdk.pricing/estimate-cost)
              usage pricing)
        search-queries (or (:usage/search-queries usage)
                           (get-in usage [:usage/provider-raw :num_search_queries])
                           0)
        per-call (some-> pricing :search-cost-per-call bigdec)
        addend (if (and per-call (pos? search-queries))
                 (.multiply (bigdec search-queries) per-call)
                 0M)
        search-only-cost? (and per-call
                               (pos? (.signum ^java.math.BigDecimal addend))
                               (nil? (:cost/amount-usd base)))
        amount (cond
                 (and (:cost/amount-usd base) (pos? (.signum ^java.math.BigDecimal addend)))
                 (.add ^java.math.BigDecimal (bigdec (:cost/amount-usd base)) addend)
                 (:cost/amount-usd base) (:cost/amount-usd base)
                 (pos? (.signum ^java.math.BigDecimal addend)) addend
                 :else nil)
        status (if (or (= :actual (:cost/status base)) search-only-cost?)
                 :actual
                 (:cost/status base))]
    (-> base
        (assoc :cost/amount-usd amount)
        (assoc :cost/status status)
        (update :cost/notes (fnil conj [])
                (str "Perplexity search queries: " search-queries
                     (when per-call
                       (str " @ $" per-call " each")))))))

;; Register
(when-let [p (provider/get-provider :perplexity)]
  (provider/register-provider
   (-> p
       (assoc :profile/transport-constructor make-transport
              :profile/cost-calculator perplexity-cost-calculator)
       (update :profile/capabilities (fnil conj #{}) :reasoning)
       (update :profile/supported-params (fnil conj #{}) :request/reasoning))))
