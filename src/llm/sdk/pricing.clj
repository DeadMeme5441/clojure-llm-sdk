(ns llm.sdk.pricing
  "Pricing registry and cost estimation.
   Hardcoded snapshot for stability; live fetch for OpenRouter/custom endpoints."
  (:require [clojure.string :as str]
            [llm.sdk.http :as http]))

;; ---------------------------------------------------------------------------
;; Data shapes
;; ---------------------------------------------------------------------------

(defn pricing-entry
  "Create a pricing entry. Costs are per-million-tokens in USD."
  [& {:keys [input output cache-read cache-write request-cost
             source source-url pricing-version]}]
  {:input-cost-per-million input
   :output-cost-per-million output
   :cache-read-cost-per-million cache-read
   :cache-write-cost-per-million cache-write
   :request-cost request-cost
   :source (or source :hardcoded)
   :source-url source-url
   :pricing-version pricing-version})

(defn cost-result
  [amount status source label & {:keys [notes]}]
  {:cost/amount-usd amount
   :cost/status status
   :cost/source source
   :cost/label label
   :cost/notes (or notes [])})

;; ---------------------------------------------------------------------------
;; Hardcoded snapshot
;; ---------------------------------------------------------------------------

(def ^:private snapshot
  (atom
   {;; OpenAI
    ["openai" "gpt-4o"]
    (pricing-entry :input 2.50 :output 10.00 :cache-read 1.25
                   :source :official-docs-snapshot)
    ["openai" "gpt-4o-mini"]
    (pricing-entry :input 0.15 :output 0.60 :cache-read 0.075
                   :source :official-docs-snapshot)
    ["openai" "gpt-4.1"]
    (pricing-entry :input 2.00 :output 8.00 :cache-read 0.50
                   :source :official-docs-snapshot)
    ["openai" "gpt-4.1-mini"]
    (pricing-entry :input 0.40 :output 1.60 :cache-read 0.10
                   :source :official-docs-snapshot)
    ["openai" "o3"]
    (pricing-entry :input 10.00 :output 40.00 :cache-read 2.50
                   :source :official-docs-snapshot)
    ["openai" "o3-mini"]
    (pricing-entry :input 1.10 :output 4.40 :cache-read 0.55
                   :source :official-docs-snapshot)

    ;; Anthropic
    ["anthropic" "claude-opus-4-7"]
    (pricing-entry :input 5.00 :output 25.00 :cache-read 0.50 :cache-write 6.25
                   :source :official-docs-snapshot)
    ["anthropic" "claude-sonnet-4-6"]
    (pricing-entry :input 3.00 :output 15.00 :cache-read 0.30 :cache-write 3.75
                   :source :official-docs-snapshot)
    ["anthropic" "claude-haiku-4-5"]
    (pricing-entry :input 1.00 :output 5.00 :cache-read 0.10 :cache-write 1.25
                   :source :official-docs-snapshot)

    ;; DeepSeek
    ["deepseek" "deepseek-chat"]
    (pricing-entry :input 0.27 :output 1.10
                   :source :official-docs-snapshot)
    ["deepseek" "deepseek-reasoner"]
    (pricing-entry :input 0.55 :output 2.19
                   :source :official-docs-snapshot)

    ;; Gemini
    ["google" "gemini-2.5-pro"]
    (pricing-entry :input 1.25 :output 10.00
                   :source :official-docs-snapshot)
    ["google" "gemini-2.5-flash"]
    (pricing-entry :input 0.15 :output 0.60
                   :source :official-docs-snapshot)
    ["google" "gemini-2.0-flash"]
    (pricing-entry :input 0.10 :output 0.40
                   :source :official-docs-snapshot)}))

;; ---------------------------------------------------------------------------
;; Registry API
;; ---------------------------------------------------------------------------

(defn register-pricing
  "Register a pricing entry for a provider+model key."
  [provider model entry]
  (swap! snapshot assoc [provider model] entry))

(defn get-pricing
  "Get pricing entry for provider+model. Returns nil if unknown."
  [provider model]
  (get @snapshot [provider model]))

(defn resolve-billing-route
  "Determine billing route from model name, provider, and optional base-url."
  [model & {:keys [provider base-url]}]
  (let [p (or provider "unknown")
        url (or base-url "")]
    {:billing-route/provider p
     :billing-route/model model
     :billing-route/base-url url
     :billing-route/mode (cond
                           (str/includes? url "openrouter") :openrouter
                           (str/includes? url "localhost") :local
                           :else :direct)}))

;; ---------------------------------------------------------------------------
;; Live fetch (OpenRouter)
;; ---------------------------------------------------------------------------

(defn- fetch-openrouter-pricing
  "Fetch live pricing from OpenRouter /api/v1/models. Returns a map of
   model-id → pricing-entry."
  [api-key]
  (try
    (let [resp (http/request
                {:method :get
                 :url "https://openrouter.ai/api/v1/models"
                 :headers (when api-key
                            {"Authorization" (str "Bearer " api-key)})})
          data (:body resp)]
      (into {}
            (for [m (:data data)
                  :let [id (:id m)
                        pricing (:pricing m)]
                  :when (and id pricing)]
              [["openrouter" id]
               (pricing-entry
                :input (:prompt pricing)
                :output (:completion pricing)
                :source :openrouter-api
                :source-url (str "https://openrouter.ai/api/v1/models/" id))])))
    (catch Exception e
      {})))

(defn fetch-pricing!
  "Fetch live pricing for a billing route. Mutates the snapshot atom.
   Currently supports OpenRouter. Returns number of entries fetched."
  [billing-route & {:keys [api-key]}]
  (let [entries (case (:billing-route/mode billing-route)
                  :openrouter (fetch-openrouter-pricing api-key)
                  {})]
    (swap! snapshot merge entries)
    (count entries)))

;; ---------------------------------------------------------------------------
;; Cost estimation
;; ---------------------------------------------------------------------------

(defn- safe-bigdec [x]
  (when x
    (try (bigdec x) (catch Exception _ nil))))

(defn- per-million [n cost-per-m]
  (when (and n cost-per-m)
    (* (bigdec n) cost-per-m 0.000001M)))

(defn estimate-cost
  "Estimate cost from usage and pricing entry. Returns a cost-result."
  [usage pricing-entry]
  (if-not pricing-entry
    (cost-result nil :unknown :none "No pricing data")
    (let [{:keys [input-cost-per-million
                  output-cost-per-million
                  cache-read-cost-per-million
                  cache-write-cost-per-million
                  request-cost]} pricing-entry
          input-tokens (:usage/input-tokens usage 0)
          output-tokens (:usage/output-tokens usage 0)
          cache-read (:usage/cached-input-tokens usage 0)
          cache-write (:usage/cache-write-tokens usage 0)]
      (if (and input-cost-per-million output-cost-per-million)
        (let [amount (reduce + 0M
                             [(per-million input-tokens input-cost-per-million)
                              (per-million output-tokens output-cost-per-million)
                              (per-million cache-read cache-read-cost-per-million)
                              (per-million cache-write cache-write-cost-per-million)
                              (safe-bigdec request-cost)])]
          (cost-result amount :actual (:source pricing-entry)
                       (str "Tokens: " input-tokens " in / " output-tokens " out")))
        (cost-result nil :estimated (:source pricing-entry)
                     "Incomplete pricing data")))))

(defn estimate-cost-for-model
  "Convenience: look up pricing and estimate cost in one call."
  [provider model usage]
  (let [route (resolve-billing-route model :provider provider)
        entry (get-pricing provider model)]
    (estimate-cost usage entry)))
