(ns llm.sdk.providers.perplexity
  "Compatibility shim. Implementation lives in llm.sdk.providers.perplexity.chat."
  (:require [llm.sdk.providers.perplexity.chat :as impl]))

(def make-transport impl/make-transport)
(def extract-citation-parts impl/extract-citation-parts)
(def build-request-perplexity impl/build-request-perplexity)
(def parse-response-perplexity impl/parse-response-perplexity)
(def parse-stream-event-perplexity impl/parse-stream-event-perplexity)
(def parse-error-perplexity impl/parse-error-perplexity)
(def perplexity-cost-calculator impl/perplexity-cost-calculator)
