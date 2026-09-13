(ns llm.sdk.sse
  "Small SSE helpers shared by provider adapters.

   This namespace owns standards-compliant event framing and JSON data
   extraction. Provider-specific event semantics stay in each adapter."
  (:require [cheshire.core :as json]
            [clojure.string :as str]))

(defn- data-value
  [line]
  (when (str/starts-with? line "data:")
    (let [value (subs line 5)]
      ;; The SSE grammar removes at most one optional space after the colon.
      (if (str/starts-with? value " ")
        (subs value 1)
        value))))

(defn data-payload
  "Return the joined payload from an SSE event record, nil for a non-data
   record or the OpenAI-style [DONE] terminal sentinel. Both `data:value` and
   `data: value` are valid; repeated data fields are joined with a newline."
  [record]
  (let [values (keep data-value (str/split (or record "") #"\r\n|\r|\n" -1))]
    (when (seq values)
      (let [payload (str/join "\n" values)]
        (when-not (= payload "[DONE]")
          payload)))))

(defn parse-json-data
  "Parse a JSON SSE event record into a keywordized map. Returns nil for
   non-data records, [DONE], empty data, or malformed JSON."
  [record]
  (when-let [payload (data-payload record)]
    (when-not (str/blank? payload)
      (try
        (json/parse-string payload true)
        (catch Exception _ nil)))))

(defn- field-line?
  [line]
  (or (str/starts-with? line ":")
      (boolean (re-find #"^(?:data|event|id|retry):" line))))

(defn event-seq
  "Frame raw input lines into SSE event records. Non-SSE lines are emitted
   individually so transports such as Ollama NDJSON keep their line semantics."
  [lines]
  (letfn [(step [remaining record]
            (lazy-seq
             (if-let [remaining (seq remaining)]
               (let [line (first remaining)]
                 (cond
                   (empty? line)
                   (if (seq record)
                     (cons (str/join "\n" record)
                           (step (rest remaining) []))
                     (step (rest remaining) []))

                   (seq record)
                   (step (rest remaining) (conj record line))

                   (field-line? line)
                   (step (rest remaining) [line])

                   :else
                   (cons line (step (rest remaining) []))))
               (when (seq record)
                 (list (str/join "\n" record))))))]
    (step lines [])))

(defn event->seq
  "Normalize provider parse-stream-event output to a sequence."
  [event]
  (cond
    (nil? event) nil
    (sequential? event) event
    :else [event]))
