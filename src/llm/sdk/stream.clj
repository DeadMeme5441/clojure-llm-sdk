(ns llm.sdk.stream
  "Streaming event taxonomy and reducer.
   Stream events → final canonical response.
   Preserves event order in output parts."
  (:require [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Event constructors
;; ---------------------------------------------------------------------------

(defn start-event [& {:keys [request-id]}]
  {:event/type :stream/start
   :event/request-id request-id})

(defn content-delta [delta]
  {:event/type :stream/content-delta
   :event/delta delta})

(defn reasoning-delta
  [delta & {:keys [encrypted index signature]}]
  (cond-> {:event/type :stream/reasoning-delta
           :event/encrypted (boolean encrypted)}
    (some? delta) (assoc :event/delta delta)
    (some? index) (assoc :event/index index)
    (some? signature) (assoc :reasoning/signature signature)))

(defn tool-call-start
  [index id name & {:keys [provider-data]}]
  (cond-> {:event/type :stream/tool-call-start
           :tool-call/index index
           :tool-call/id id
           :tool-call/name name}
    (some? provider-data) (assoc :tool-call/provider-data provider-data)))

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
  "Emit one citation. A URL is optional when the provider supplies another
   stable source identifier or provider-native metadata."
  [url & {:keys [title snippet text-range source-id date last-updated source
                 provider-data]}]
  (cond-> {:event/type :stream/citation}
    (some? url) (assoc :citation/url url)
    title (assoc :citation/title title)
    snippet (assoc :citation/snippet snippet)
    text-range (assoc :citation/text-range text-range)
    source-id (assoc :citation/source-id source-id)
    date (assoc :citation/date date)
    last-updated (assoc :citation/last-updated last-updated)
    source (assoc :citation/source source)
    provider-data (assoc :citation/provider-data provider-data)))

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
  [parts tool-calls-indexed finish-reason usage cost provider-data errors])

(defn- empty-acc []
  (->Accumulator [] {} nil nil nil {} []))

(defn- deep-merge
  [& maps]
  (letfn [(merge-entry [a b]
            (if (and (map? a) (map? b))
              (merge-with merge-entry a b)
              b))]
    (apply merge-with merge-entry maps)))

(defn- derived-total-tokens
  [usage]
  (+ (or (:usage/input-tokens usage) 0)
     (or (:usage/cached-input-tokens usage) 0)
     (or (:usage/cache-write-tokens usage) 0)
     (or (:usage/output-tokens usage) 0)))

(defn- merge-usage
  [current update]
  (let [update (or update {})
        merged (deep-merge (or current {}) update)
        merged (assoc merged
                      :usage/input-tokens
                      (or (:usage/input-tokens merged) 0)
                      :usage/output-tokens
                      (or (:usage/output-tokens merged) 0))]
    (if (contains? update :usage/total-tokens)
      merged
      (assoc merged :usage/total-tokens (derived-total-tokens merged)))))

(defn- update-last-text [parts delta]
  (if (and (seq parts) (= (:part/type (peek parts)) :text))
    (conj (pop parts) (update (peek parts) :text str delta))
    (conj parts {:part/type :text :text delta})))

(def ^:private reasoning-index-key ::reasoning-index)

(defn- revise-reasoning-part
  [part delta encrypted signature index]
  (cond-> (or part
              (cond-> {:part/type :reasoning
                       :reasoning/text ""
                       :reasoning/encrypted (boolean encrypted)}
                (some? index) (assoc reasoning-index-key index)))
    (some? delta) (update :reasoning/text str delta)
    encrypted (assoc :reasoning/encrypted true)
    (some? signature) (assoc :reasoning/signature signature)))

(defn- update-reasoning
  [parts delta encrypted signature index]
  (let [parts (vec parts)
        pos (if (some? index)
              (first
               (keep-indexed
                (fn [i part]
                  (when (and (= :reasoning (:part/type part))
                             (= index (get part reasoning-index-key)))
                    i))
                parts))
              (when (and (seq parts)
                         (= :reasoning (:part/type (peek parts)))
                         (not (contains? (peek parts) reasoning-index-key)))
                (dec (count parts))))]
    (if (some? pos)
      (update parts pos revise-reasoning-part
              delta encrypted signature index)
      (conj parts
            (revise-reasoning-part nil delta encrypted signature index)))))

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
    (update acc :parts update-reasoning
            (:event/delta event)
            (:event/encrypted event)
            (:reasoning/signature event)
            (:event/index event))

    :stream/tool-call-start
    (revise-tool-call acc (:tool-call/index event)
                      (fn [tc]
                        (-> tc
                            (assoc :tool-call/id (or (:tool-call/id event)
                                                     (:tool-call/id tc))
                                   :tool-call/name (or (:tool-call/name event)
                                                      (:tool-call/name tc))
                                   :tool-call/arguments (or (:tool-call/arguments tc) ""))
                            (update :tool-call/provider-data
                                    #(assoc (deep-merge
                                             (or % {})
                                             (or (:tool-call/provider-data event) {}))
                                            :stream/index
                                            (:tool-call/index event))))))

    :stream/tool-call-delta
    (revise-tool-call acc (:tool-call/index event)
                      (fn [tc]
                        (update tc :tool-call/arguments str
                                (or (:tool-call/arguments-delta event) ""))))

    :stream/tool-call-end
    acc ;; marker only

    :stream/usage
    (cond-> (update acc :usage merge-usage (:usage event))
      (:cost event) (update :cost #(deep-merge (or % {}) (:cost event))))

    :stream/provider-state
    (update-in acc [:provider-data (:provider-state/provider event)]
               #(deep-merge (or % {}) (:provider-state/data event)))

    :stream/citation
    (update acc :parts conj
            (merge {:part/type :citation}
                   (select-keys
                    event
                    [:citation/url :citation/title :citation/snippet
                     :citation/text-range :citation/source-id :citation/date
                     :citation/last-updated :citation/source
                     :citation/provider-data])))

    :stream/error
    (update acc :errors conj (:error/error event))

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

(defn- response-map
  [acc provider model]
  (let [parts (mapv #(dissoc % reasoning-index-key) (:parts acc))
        tool-calls (->> (:tool-calls-indexed acc)
                        (sort-by key)
                        (mapv (fn [[_ tc]] (tool-call-part tc))))
        finish-reason (if (and (seq tool-calls)
                               (contains? #{nil :unknown :stop}
                                          (:finish-reason acc)))
                        :tool-calls
                        (or (:finish-reason acc) :unknown))]
    (cond-> {:response/provider provider
             :response/model model
             :response/parts parts
             :response/finish-reason finish-reason}
      (seq tool-calls) (assoc :response/tool-calls tool-calls)
      (:usage acc) (assoc :response/usage (:usage acc))
      (:cost acc) (assoc :response/cost (:cost acc))
      (seq (:provider-data acc)) (assoc :response/provider-data (:provider-data acc)))))

(defn- classify-stream-error
  [error provider]
  (if (and (map? error) (:error/reason error))
    error
    (let [message (if (map? error)
                    (or (:error/message error)
                        (:message error)
                        (str error))
                    (str error))
          classified (errors/classify-error
                      (if (instance? Throwable error)
                        error
                        (Exception. message))
                      :status (when (map? error)
                                (or (:status error) (:error/status error)))
                      :body (when (map? error) error)
                      :provider provider)]
      (if (map? error)
        (merge classified error)
        classified))))

(defn acc->response
  "Convert an accumulator to a canonical Response map. A provider error event
   makes accumulation fail while retaining both the classified error and the
   response accumulated before the failure in ex-data."
  [acc provider model]
  (let [response (response-map acc provider model)]
    (if-let [error (first (:errors acc))]
      (throw (ex-info "Provider stream error"
                      {:error (classify-stream-error error provider)
                       :stream/error error
                       :partial-response response
                       :provider provider}
                      (when (instance? Throwable error) error)))
      response)))

(defn events->response
  "Convenience: reduce events and convert to response in one step."
  [events provider model]
  (acc->response (reduce-events events) provider model))
