(ns llm.sdk.providers.perplexity
  "Perplexity transport — OpenAI-shape body + citation/search-results
   surfacing.

   Request building is identical to openai-chat. Response parsing
   extends the OpenAI parser with two extractions:

  - :search_results [{:url :title :snippet}, ...] → richer
       CitationPart per result
  - :citations [\"url\", ...]                       → URL-only
       CitationPart when search_results isn't present

   Usage normalization delegates to normalize-openai-usage, which
   already picks up Perplexity's :citation_tokens and
   :num_search_queries when present.

   Streaming: the final SSE chunk on /chat/completions carries
   :citations alongside :usage and :finish_reason. parse-stream-event
   returns a vector of events in that case — sdk/complete flattens
   multi-event return values."
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [llm.sdk.transport :as t]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.openai-chat :as openai]
            [llm.sdk.stream :as stream]
            [llm.sdk.usage :as usage]
            [llm.sdk.errors :as errors]))

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

;; ---------------------------------------------------------------------------
;; Request building — pure OpenAI delegation
;; ---------------------------------------------------------------------------

(defn build-request-perplexity
  [profile request]
  (openai/build-request-openai profile request))

;; ---------------------------------------------------------------------------
;; Response parsing — extend OpenAI parser with citations
;; ---------------------------------------------------------------------------

(defn parse-response-perplexity
  [profile raw]
  (let [base (openai/parse-response-openai profile raw)
        citation-parts (extract-citation-parts raw)
        parts (:response/parts base [])
        parts' (if (seq citation-parts)
                 (into parts citation-parts)
                 parts)]
    (cond-> base
      (seq citation-parts) (assoc :response/parts parts')
      true (assoc :response/provider :perplexity))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= payload "[DONE]")
        (try (json/parse-string payload true)
             (catch Exception _ nil))))))

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

(defn parse-stream-event-perplexity
  "Parse a Perplexity SSE line into one or more StreamEvents.

   Perplexity streams the same shape as OpenAI for content deltas.
   The final chunk packs citations, usage, and finish_reason together,
   so this returns a vector of events for that line (and a single
   event for the rest)."
  [_profile line]
  (when-let [data (parse-sse-line line)]
    (let [choice (first (:choices data))
          delta (:delta choice)
          finish (:finish_reason choice)
          usage-raw (:usage data)
          citations (citation-events-from-data data)]
      (cond
        ;; Final chunk with citations/usage/finish — emit all three.
        (or (seq citations) usage-raw finish)
        (cond-> []
          (seq citations)
          (into citations)
          usage-raw
          (conj (stream/usage-event
                 (usage/normalize-usage :perplexity usage-raw)))
          finish
          (conj (stream/end-event
                 :finish-reason
                 (case finish
                   ("stop" nil) :stop
                   "length" :length
                   "tool_calls" :tool-calls
                   "content_filter" :content-filter
                   :unknown))))

        ;; Content delta — single event, same as OpenAI.
        (seq (:content delta))
        (stream/content-delta (:content delta))

        :else nil))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-error-perplexity
  [_profile status body]
  (errors/classify-error (Exception. "Perplexity API error")
                         :status status
                         :body body
                         :provider :perplexity))

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
    (usage/normalize-usage :perplexity raw))
  (request-capabilities [_]
    #{:chat :streaming :json-schema :web-search}))

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
        amount (cond
                 (and (:cost/amount-usd base) (pos? (.signum ^java.math.BigDecimal addend)))
                 (.add ^java.math.BigDecimal (bigdec (:cost/amount-usd base)) addend)
                 (:cost/amount-usd base) (:cost/amount-usd base)
                 (pos? (.signum ^java.math.BigDecimal addend)) addend
                 :else nil)]
    (-> base
        (assoc :cost/amount-usd amount)
        (update :cost/notes (fnil conj [])
                (str "Perplexity search queries: " search-queries
                     (when per-call
                       (str " @ $" per-call " each")))))))

;; Register
(when-let [p (provider/get-provider :perplexity)]
  (provider/register-provider
   (assoc p
          :profile/transport-constructor make-transport
          :profile/cost-calculator perplexity-cost-calculator)))
