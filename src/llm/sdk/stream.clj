(ns llm.sdk.stream
  "Streaming event taxonomy and reducer.
   Stream events → final canonical response.
   Preserves event order in output parts."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Event constructors
;; ---------------------------------------------------------------------------

(defn start-event [& {:keys [request-id]}]
  {:event/type :stream/start
   :event/request-id request-id})

(defn content-delta [delta]
  {:event/type :stream/content-delta
   :event/delta delta})

(defn reasoning-delta [delta & {:keys [encrypted]}]
  {:event/type :stream/reasoning-delta
   :event/delta delta
   :event/encrypted (boolean encrypted)})

(defn tool-call-start [index id name]
  {:event/type :stream/tool-call-start
   :tool-call/index index
   :tool-call/id id
   :tool-call/name name})

(defn tool-call-delta [index arguments-delta]
  {:event/type :stream/tool-call-delta
   :tool-call/index index
   :tool-call/arguments-delta arguments-delta})

(defn tool-call-end [index]
  {:event/type :stream/tool-call-end
   :tool-call/index index})

(defn usage-event [usage]
  {:event/type :stream/usage
   :usage usage})

(defn provider-state-event [provider data]
  {:event/type :stream/provider-state
   :provider-state/provider provider
   :provider-state/data data})

(defn citation-event
  "Emit one citation. Adapters that surface citations on a final SSE
   chunk (Perplexity is the first) return a vector ending in usage
   and end events; sdk/complete flattens multi-event return values
   from parse-stream-event."
  [url & {:keys [title snippet]}]
  (cond-> {:event/type :stream/citation
           :citation/url url}
    title (assoc :citation/title title)
    snippet (assoc :citation/snippet snippet)))

(defn error-event [error]
  {:event/type :stream/error
   :error/error error})

(defn end-event [& {:keys [finish-reason]}]
  {:event/type :stream/end
   :event/finish-reason finish-reason})

;; ---------------------------------------------------------------------------
;; Reducer
;; ---------------------------------------------------------------------------

(defrecord Accumulator
  [parts tool-calls-indexed finish-reason usage provider-data])

(defn- empty-acc []
  (->Accumulator [] {} nil nil {}))

(defn- update-last-text [parts delta]
  (if (and (seq parts) (= (:part/type (peek parts)) :text))
    (conj (pop parts) (update (peek parts) :text str delta))
    (conj parts {:part/type :text :text delta})))

(defn- update-last-reasoning [parts delta encrypted]
  (if (and (seq parts) (= (:part/type (peek parts)) :reasoning))
    (conj (pop parts) (update (peek parts) :reasoning/text str delta))
    (conj parts {:part/type :reasoning :reasoning/text delta :reasoning/encrypted (boolean encrypted)})))

(defn- update-tool-call [acc index f & args]
  (update-in acc [:tool-calls-indexed index]
             (fn [tc]
               (let [base (or tc {:tool-call/index index})]
                 (apply f base args)))))

(defn reduce-event
  "Reduce a single stream event into an accumulator."
  [acc event]
  (case (:event/type event)
    :stream/content-delta
    (update acc :parts update-last-text (:event/delta event))

    :stream/reasoning-delta
    (update acc :parts update-last-reasoning (:event/delta event) (:event/encrypted event))

    :stream/tool-call-start
    (update-tool-call acc (:tool-call/index event)
                      assoc
                      :tool-call/id (:tool-call/id event)
                      :tool-call/name (:tool-call/name event)
                      :tool-call/arguments "")

    :stream/tool-call-delta
    (update-tool-call acc (:tool-call/index event)
                      update :tool-call/arguments str (:tool-call/arguments-delta event))

    :stream/tool-call-end
    acc ;; marker only

    :stream/usage
    (assoc acc :usage (:usage event))

    :stream/provider-state
    (assoc-in acc [:provider-data
                   (:provider-state/provider event)]
              (:provider-state/data event))

    :stream/citation
    (update acc :parts conj
            (cond-> {:part/type :citation
                     :citation/url (:citation/url event)}
              (:citation/title event) (assoc :citation/title (:citation/title event))
              (:citation/snippet event) (assoc :citation/snippet (:citation/snippet event))))

    :stream/end
    ;; sdk/complete appends a synthetic terminal :stream/end with no
    ;; finish-reason after the provider's own stream is consumed —
    ;; only update when the event actually carries one so we don't
    ;; clobber the real reason the provider already reported.
    (if-let [fr (:event/finish-reason event)]
      (assoc acc :finish-reason fr)
      acc)

    ;; default
    acc))

(defn reduce-events
  "Reduce a sequence of stream events to an accumulator."
  [events]
  (reduce reduce-event (empty-acc) events))

(defn acc->response
  "Convert an accumulator to a canonical Response map."
  [acc provider model]
  (let [tool-calls (->> (:tool-calls-indexed acc)
                        (sort-by key)
                        (mapv (fn [[_ tc]]
                                {:part/type :tool-call
                                 :tool-call/id (:tool-call/id tc)
                                 :tool-call/name (:tool-call/name tc)
                                 :tool-call/arguments (:tool-call/arguments tc)})))
        parts (into (:parts acc) tool-calls)]
    {:response/provider provider
     :response/model model
     :response/parts parts
     :response/tool-calls (not-empty tool-calls)
     :response/finish-reason (or (:finish-reason acc) :unknown)
     :response/usage (:usage acc)
     :response/provider-data (:provider-data acc)}))

(defn events->response
  "Convenience: reduce events and convert to response in one step."
  [events provider model]
  (acc->response (reduce-events events) provider model))
