(ns llm.sdk.sse
  "Small SSE helpers shared by provider adapters.

   This namespace intentionally handles only the common line envelope:
   `data: ...`, `[DONE]`, and JSON parsing. Provider-specific event
   semantics stay in each adapter."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn data-payload
  "Return the payload string from an SSE data line, nil for non-data
   lines or the OpenAI-style [DONE] terminal sentinel."
  [line]
  (when (str/starts-with? line "data: ")
    (let [payload (subs line 6)]
      (when-not (= payload "[DONE]")
        payload))))

(defn parse-json-data
  "Parse a JSON SSE data line into a keywordized map. Returns nil for
   non-data lines, [DONE], or malformed JSON."
  [line]
  (when-let [payload (data-payload line)]
    (try
      (json/parse-string payload true)
      (catch Exception _ nil))))

(defn event->seq
  "Normalize provider parse-stream-event output to a sequence."
  [event]
  (cond
    (nil? event) nil
    (sequential? event) event
    :else [event]))
