(ns llm.sdk.stream
  "Streaming event taxonomy and reducer.
   Stream events → final canonical response.
   Preserves event order in output parts.")

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

(defn usage-event [usage & {:keys [cost]}]
  (cond-> {:event/type :stream/usage
           :usage usage}
    cost (assoc :cost cost)))

(defn provider-state-event [provider data]
  {:event/type :stream/provider-state
   :provider-state/provider provider
   :provider-state/data data})

(defn citation-event
  "Emit one citation. Adapters that surface citations on a final SSE
   chunk (Perplexity is the first) return a vector ending in usage
   and end events; sdk/complete flattens multi-event return values
   from parse-stream-event."
  [url & {:keys [title snippet date last-updated source]}]
  (cond-> {:event/type :stream/citation
           :citation/url url}
    title (assoc :citation/title title)
    snippet (assoc :citation/snippet snippet)
    date (assoc :citation/date date)
    last-updated (assoc :citation/last-updated last-updated)
    source (assoc :citation/source source)))

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
  [parts tool-calls-indexed finish-reason usage cost provider-data])

(defn- empty-acc []
  (->Accumulator [] {} nil nil nil {}))

(defn- deep-merge
  [& maps]
  (letfn [(merge-entry [a b]
            (if (and (map? a) (map? b))
              (merge-with merge-entry a b)
              b))]
    (apply merge-with merge-entry maps)))

(defn- update-last-text [parts delta]
  (if (and (seq parts) (= (:part/type (peek parts)) :text))
    (conj (pop parts) (update (peek parts) :text str delta))
    (conj parts {:part/type :text :text delta})))

(defn- update-last-reasoning [parts delta encrypted]
  (if (and (seq parts) (= (:part/type (peek parts)) :reasoning))
    (conj (pop parts) (update (peek parts) :reasoning/text str delta))
    (conj parts {:part/type :reasoning :reasoning/text delta :reasoning/encrypted (boolean encrypted)})))

(defn- base-tool-call [index]
  {:tool-call/index index
   :tool-call/id (str "tool_call_" index)
   :tool-call/name ""
   :tool-call/arguments ""
   :tool-call/provider-data {:stream/index index}})

(defn- tool-call-part [tc]
  (cond-> {:part/type :tool-call
           :tool-call/id (or (:tool-call/id tc)
                             (str "tool_call_" (:tool-call/index tc)))
           :tool-call/name (or (:tool-call/name tc) "")
           :tool-call/arguments (or (:tool-call/arguments tc) "")}
    (:tool-call/provider-data tc)
    (assoc :tool-call/provider-data (:tool-call/provider-data tc))))

(defn- tool-call-part-index [part]
  (get-in part [:tool-call/provider-data :stream/index]))

(defn- update-tool-call-part [parts index tc]
  (let [parts (vec parts)
        part (tool-call-part tc)
        pos (first (keep-indexed
                    (fn [i p]
                      (when (and (= (:part/type p) :tool-call)
                                 (= index (tool-call-part-index p)))
                        i))
                    parts))]
    (if (some? pos)
      (assoc parts pos part)
      (conj parts part))))

(defn- revise-tool-call [acc index f]
  (let [acc' (update-in acc [:tool-calls-indexed index]
                        (fn [tc]
                          (f (or tc (base-tool-call index)))))
        tc (get-in acc' [:tool-calls-indexed index])]
    (update acc' :parts update-tool-call-part index tc)))

(defn reduce-event
  "Reduce a single stream event into an accumulator."
  [acc event]
  (case (:event/type event)
    :stream/content-delta
    (update acc :parts update-last-text (:event/delta event))

    :stream/reasoning-delta
    (update acc :parts update-last-reasoning (:event/delta event) (:event/encrypted event))

    :stream/tool-call-start
    (revise-tool-call acc (:tool-call/index event)
                      (fn [tc]
                        (assoc tc
                               :tool-call/id (or (:tool-call/id event)
                                                 (:tool-call/id tc))
                               :tool-call/name (or (:tool-call/name event)
                                                   (:tool-call/name tc))
                               :tool-call/arguments (or (:tool-call/arguments tc) ""))))

    :stream/tool-call-delta
    (revise-tool-call acc (:tool-call/index event)
                      (fn [tc]
                        (update tc :tool-call/arguments str
                                (or (:tool-call/arguments-delta event) ""))))

    :stream/tool-call-end
    acc ;; marker only

    :stream/usage
    (cond-> (assoc acc :usage (:usage event))
      (:cost event) (assoc :cost (:cost event)))

    :stream/provider-state
    (update-in acc [:provider-data (:provider-state/provider event)]
               #(deep-merge (or % {}) (:provider-state/data event)))

    :stream/citation
    (update acc :parts conj
            (cond-> {:part/type :citation
                     :citation/url (:citation/url event)}
              (:citation/title event) (assoc :citation/title (:citation/title event))
              (:citation/snippet event) (assoc :citation/snippet (:citation/snippet event))
              (:citation/date event) (assoc :citation/date (:citation/date event))
              (:citation/last-updated event) (assoc :citation/last-updated (:citation/last-updated event))
              (:citation/source event) (assoc :citation/source (:citation/source event))))

    :stream/end
    ;; A terminal event without a reason must not clobber an earlier
    ;; provider-reported finish reason.
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
                        (mapv (fn [[_ tc]] (tool-call-part tc))))
        finish-reason (if (and (seq tool-calls)
                               (contains? #{nil :unknown :stop}
                                          (:finish-reason acc)))
                        :tool-calls
                        (or (:finish-reason acc) :unknown))]
    (cond-> {:response/provider provider
             :response/model model
             :response/parts (:parts acc)
             :response/finish-reason finish-reason}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      (:usage acc) (assoc :response/usage (:usage acc))
      (:cost acc) (assoc :response/cost (:cost acc))
      (seq (:provider-data acc)) (assoc :response/provider-data (:provider-data acc)))))

(defn events->response
  "Convenience: reduce events and convert to response in one step."
  [events provider model]
  (acc->response (reduce-events events) provider model))
