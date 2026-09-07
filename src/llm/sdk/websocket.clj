(ns llm.sdk.websocket
  "Exclusive Responses WebSocket leases, exposed as SSE InputStreams."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [llm.sdk.http :as http]
            [llm.sdk.errors :as errors])
  (:import [java.io InputStream]
           [java.net URI]
           [java.net.http HttpClient WebSocket WebSocket$Listener WebSocketHandshakeException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent CompletableFuture ExecutionException ScheduledThreadPoolExecutor
            ThreadFactory TimeUnit TimeoutException]))

(def ^:private max-idle 8)
(def ^:private idle-ms 60000)
(def ^:private max-buffer-bytes (* 16 1024 1024))
(def ^:private max-message-chars (* 8 1024 1024))
(defonce ^:private connections (atom #{}))
(defonce ^:private idle (atom []))
(defonce ^:private pool-lock (Object.))
(defonce ^:private default-client (delay (.build (HttpClient/newBuilder))))

(defn- now [] (System/nanoTime))
(defn- elapsed-ms [since] (/ (- (now) since) 1000000))

(defn- failure [phase sent? message cause data]
  (ex-info message (merge {:type :websocket-error :error/type :transport
                          :provider :codex-backend :phase phase
                          :request-sent? sent? :retryable? false
                          :error (cond-> (assoc (errors/classify-error
                                                (or cause (Exception. message))
                                                :status (:status data)
                                                :body (or (get-in data [:body :response])
                                                          (:body data)))
                                               :error/retryable false)
                                   (= phase :timeout) (assoc :error/reason :timeout))}
                         data)
           cause))

(defn- known-keys? [item allowed]
  (and (map? item) (every? allowed (keys item))))

(defn- canonical-output [item]
  ;; Only metadata discarded by the SDK may disappear during replay. Unknown
  ;; output shapes cannot establish a continuation baseline.
  (when (map? item)
    (case (:type item)
      "message"
      (when (and (known-keys? item #{:type :id :status :role :content :phase})
                 (= "assistant" (:role item))
                 (vector? (:content item))
                 (every? #(and (known-keys? % #{:type :text :annotations :logprobs})
                               (= "output_text" (:type %)) (string? (:text %)))
                         (:content item)))
        (-> item
            (dissoc :id :status)
            (update :content #(mapv (fn [part] (dissoc part :annotations :logprobs)) %))))
      "function_call"
      (when (and (known-keys? item #{:type :id :status :call_id :name :arguments})
                 (every? string? ((juxt :call_id :name :arguments) item)))
        (dissoc item :id :status))
      "reasoning"
      (when (and (known-keys? item #{:type :id :status :summary :content :encrypted_content})
                 (vector? (:summary item))
                 (every? #(and (known-keys? % #{:type :text})
                               (= "summary_text" (:type %)) (string? (:text %)))
                         (:summary item)))
        (dissoc item :id :status))
      nil)))

(defn- request-properties [body]
  (dissoc body :input :type :stream :background :previous_response_id :client_metadata))

(defn- incremental-body [conn lease]
  (let [{:keys [full-body incremental?]} @lease
        {:keys [request output response-id]} (:baseline @conn)
        input (:input full-body)
        prior (:input request)
        prefix-size (+ (count prior) (count output))]
    (when (and incremental? request response-id
               (not (contains? full-body :previous_response_id))
               (vector? input) (vector? prior)
               (= (request-properties request) (request-properties full-body))
               (> (count input) prefix-size)
               (every? #(= (nth prior %) (nth input %)) (range (count prior)))
               (every? #(= (nth output %) (canonical-output (nth input (+ (count prior) %))))
                       (range (count output))))
      (assoc full-body :previous_response_id response-id :input (subvec input prefix-size)))))

(defn- completed-baseline [lease event]
  (let [response (:response event)
        items (:output-items @lease)
        ;; Codex streams the authoritative output in output_item.done and may
        ;; intentionally send response.completed.output=[]. Never treat that
        ;; empty terminal array as evidence that the server generated no items.
        output (if (seq items)
                 (when (= (set (keys items)) (set (range (count items))))
                   (mapv items (range (count items))))
                 (:output response))
        canonical (when (vector? output) (mapv canonical-output output))
        id (:id response)]
    (when (and (:incremental? @lease)
               (not (contains? (:full-body @lease) :previous_response_id))
               (string? id) (not (str/blank? id))
               canonical (every? some? canonical)
               (not (:invalid-output? @lease)))
      {:request (:full-body @lease) :output canonical :response-id id})))

(defn- discard! [conn error]
  (let [old (first (swap-vals! conn assoc :dead? true))]
    (when-not (:dead? old)
      (locking pool-lock
        (swap! connections disj conn)
        (swap! idle #(into [] (remove (fn [c] (identical? c conn))) %)))
      (when-let [lease (:lease old)]
        (locking lease
          (when-not (:released? @lease)
            ;; A server may close immediately after a valid terminal event.
            (when-not (and (:done? @lease) (= :close (:phase (ex-data error))))
              (swap! lease assoc :error error))
            (.notifyAll ^Object lease))))
      (when-let [^WebSocket socket (:socket old)]
        (.abort socket)))))

(defn- release! [conn lease]
  ;; Called under the lease monitor, once the final bytes have been consumed.
  (when-not (:released? @lease)
    (swap! lease assoc :released? true)
    (locking pool-lock
      (if (and (not (:dead? @conn)) (< (count @idle) max-idle))
        (do (swap! conn assoc :lease nil :idle-since (now) :baseline (:baseline @lease))
            (swap! idle conj conn))
        (discard! conn (failure :close true "WebSocket idle pool is full" nil {}))))))

(defn- sweep! []
  (doseq [conn @connections]
    (if-let [lease (:lease @conn)]
      (locking lease
        (when (and (identical? lease (:lease @conn))
                   (:sent? @lease)
                   (or (> (elapsed-ms (:activity @lease)) (:timeout-ms @lease))
                       (> (elapsed-ms (:consumer-activity @lease)) (:timeout-ms @lease))))
          (discard! conn (failure :timeout true "WebSocket response timed out" nil {}))))
      (locking pool-lock
        (let [{:keys [lease idle-since]} @conn]
          (when (and (nil? lease) idle-since (> (elapsed-ms idle-since) idle-ms))
            (discard! conn (failure :close false "Idle WebSocket expired" nil {}))))))))

(defonce ^:private reaper
  (let [executor (ScheduledThreadPoolExecutor.
                  1 (reify ThreadFactory
                      (newThread [_ runnable]
                        (doto (Thread. runnable "llm-websocket-reaper")
                          (.setDaemon true)))))]
    (.setRemoveOnCancelPolicy executor true)
    (.scheduleWithFixedDelay executor
                             ^Runnable (fn [] (try (sweep!) (catch Exception _)))
                             100 100 TimeUnit/MILLISECONDS)
    executor))

(defn close-connections!
  "Abort all active and idle WebSockets. Future requests can open new connections."
  []
  (doseq [conn @connections]
    (discard! conn (failure :close true "WebSocket connections closed" nil {})))
  nil)

(defn- accept-message! [conn text]
  (if-let [lease (:lease @conn)]
    (try
      (let [event (json/parse-string text true)
            event-type (:type event)
            terminal? (contains? #{"response.completed" "response.done" "response.incomplete"} event-type)
            incomplete? (or (= "response.incomplete" event-type)
                            (= "incomplete" (get-in event [:response :status])))
            failed? (or (contains? #{"error" "response.error" "response.failed"} event-type)
                        (and terminal? (contains? #{"failed" "cancelled"}
                                                  (get-in event [:response :status]))))]
        (cond
          (not (string? event-type))
          (discard! conn (failure :protocol true "WebSocket event has no type" nil {}))

          failed?
          (locking lease
            (if (and (:automatic? @lease) (not (:recovered? @lease))
                     (not (:observed? @lease)) (not (:recover? @lease))
                     (= "previous_response_not_found"
                        (or (get-in event [:error :code])
                            (get-in event [:response :error :code])))
                     (contains? #{"error" "response.error" "response.failed"} event-type))
              (do (swap! conn dissoc :baseline)
                  (swap! lease assoc :recover? true :activity (now))
                  (.notifyAll ^Object lease))
              (discard! conn (failure :response true "WebSocket response failed" nil
                                      {:status (or (:status event) (:status_code event))
                                       :body event}))))

          :else
          (let [wire-text (if (or (str/includes? text "\n") (str/includes? text "\r"))
                            (json/generate-string event)
                            text)
                bytes (.getBytes (str "data: " wire-text "\n\n") StandardCharsets/UTF_8)]
            (locking lease
              (cond
                (:done? @lease)
                (discard! conn (failure :protocol true "Event after terminal WebSocket response" nil {}))

                (:recover? @lease)
                (discard! conn (failure :protocol true "Event after rejected WebSocket continuation" nil {}))

                (> (+ (:bytes @lease) (alength bytes)) max-buffer-bytes)
                (discard! conn (failure :buffer true "WebSocket response buffer exceeded" nil {}))

                (not (:error @lease))
                ;; Every non-error event closes the safe recovery window,
                ;; including lifecycle events before the first output delta.
                (do
                  (when (= "response.output_item.done" event-type)
                    (let [index (:output_index event)]
                      (if (and (integer? index) (<= 0 index)
                               (not (contains? (:output-items @lease) index)))
                        (swap! lease assoc-in [:output-items index] (:item event))
                        (swap! lease assoc :invalid-output? true))))
                  (swap! lease #(-> %
                                    (update :queue conj bytes)
                                    (update :bytes + (alength bytes))
                                    (assoc :done? terminal? :activity (now) :observed? true)))
                  (when terminal?
                    (swap! lease assoc :baseline
                           (when-not incomplete? (completed-baseline lease event))))
                  (when (and terminal? incomplete?)
                    (discard! conn (failure :close true "Incomplete WebSocket response" nil {})))
                  (.notifyAll ^Object lease)))))))
      (catch Exception e
        (discard! conn (failure :protocol true "Invalid WebSocket response event" e {}))))
    (discard! conn (failure :protocol false "Unsolicited WebSocket response" nil {}))))

(defn- listener [conn]
  (let [text (StringBuilder.)]
    (reify WebSocket$Listener
      (onOpen [_ socket]
        (swap! conn assoc :socket socket)
        (if (:dead? @conn) (.abort ^WebSocket socket) (.request ^WebSocket socket 1)))
      (onText [_ socket data last?]
        (if (> (+ (.length text) (.length ^CharSequence data)) max-message-chars)
          (discard! conn (failure :buffer true "WebSocket message exceeds size limit" nil {}))
          (do
            (.append text ^CharSequence data)
            (when-let [lease (:lease @conn)] (swap! lease assoc :activity (now)))
            (when last?
              (let [message (.toString text)]
                (.setLength text 0)
                (accept-message! conn message)))))
        (.request ^WebSocket socket 1)
        nil)
      (onBinary [_ _ _ _]
        (discard! conn (failure :protocol true "Unexpected binary WebSocket message" nil {}))
        nil)
      (onPing [_ socket data]
        (.request ^WebSocket socket 1)
        (.sendPong ^WebSocket socket data))
      (onPong [_ socket _]
        (.request ^WebSocket socket 1)
        nil)
      (onClose [_ _ status _]
        (discard! conn (failure :close true "WebSocket closed before stream release" nil
                                {:close-status status}))
        nil)
      (onError [_ _ error]
        (discard! conn (failure :receive true "WebSocket transport failed" error {}))))))

(defn- await! [^CompletableFuture future timeout-ms]
  (try
    (.get future (long timeout-ms) TimeUnit/MILLISECONDS)
    (catch ExecutionException e (throw (.getCause e)))
    (catch TimeoutException e
      (.cancel future true)
      (throw e))))

(defn- websocket-url [url]
  (let [uri (URI/create url)]
    (case (.getScheme uri)
      "https" (str "wss" (subs url 5))
      "http" (str "ws" (subs url 4))
      (throw (failure :configuration false "WebSocket endpoint must use HTTP or HTTPS" nil {})))))

(defn- acquire! [key client url headers connect-ms lease]
  (let [cached (locking pool-lock
                 (when-let [conn (let [eligible (filter #(and (= key (:key @%))
                                                             (not (:dead? @%))) @idle)]
                                   (or (some #(when (incremental-body % lease) %) eligible)
                                       (first eligible)))]
                   (swap! idle #(into [] (remove (fn [c] (identical? c conn))) %))
                   (swap! conn assoc :lease lease :idle-since nil)
                   conn))]
    (or cached
        (let [conn (atom {:key key :lease lease :dead? false})]
          (swap! connections conj conn)
          (try
            (let [builder (.newWebSocketBuilder ^HttpClient client)]
              (.connectTimeout builder (Duration/ofMillis connect-ms))
              (doseq [[header value] headers]
                (.header builder header (str value)))
              (await! (.buildAsync builder (URI/create url) (listener conn)) connect-ms)
              conn)
            (catch Exception e
              (let [error (failure :handshake false "WebSocket handshake failed" e
                                   (if (instance? WebSocketHandshakeException e)
                                     {:status (.statusCode (.getResponse ^WebSocketHandshakeException e))}
                                     {}))]
                (discard! conn error)
                (throw error))))))))

(defn- send-body! [conn body timeout-ms]
  (await! (.sendText ^WebSocket (:socket @conn) (json/generate-string body) true)
          timeout-ms))

(defn- recover! [conn lease]
  ;; Run on the consumer, never block the WebSocket callback on a send future.
  (swap! lease assoc :recover? false :recovered? true :automatic? false)
  (try
    (send-body! conn (:full-body @lease) (:timeout-ms @lease))
    (catch Exception e
      (let [error (failure :send true "WebSocket full-request recovery failed" e {})]
        (discard! conn error)
        (throw error)))))

(defn- response-stream [conn lease]
  (let [current (atom nil)
        offset (atom 0)
        read-bytes (fn [^bytes target off len]
                     (locking lease
                       (when (:closed? @lease)
                         (throw (failure :close true "WebSocket response stream is closed" nil {})))
                       (if (zero? len)
                         0
                         (loop []
                           (when-let [error (:error @lease)] (throw error))
                           (when (:recover? @lease) (recover! conn lease))
                           (if-let [^bytes chunk @current]
                             (let [n (int (min len (- (alength chunk) @offset)))]
                               (System/arraycopy chunk @offset target off n)
                               (swap! offset + n)
                               (swap! lease #(-> % (update :bytes - n)
                                                 (assoc :consumer-activity (now))))
                               (when (= @offset (alength chunk))
                                 (reset! current nil)
                                 (when (and (:done? @lease) (empty? (:queue @lease)))
                                   (release! conn lease)))
                               n)
                             (cond
                               (seq (:queue @lease))
                               (do (reset! current (peek (:queue @lease)))
                                   (reset! offset 0)
                                   (swap! lease update :queue pop)
                                   (recur))
                               (:done? @lease) (do (release! conn lease) -1)
                               :else (do (.wait ^Object lease (long (:timeout-ms @lease)))
                                         (when (:closed? @lease)
                                           (throw (failure :close true "WebSocket response stream is closed" nil {})))
                                         (recur))))))))]
    (proxy [InputStream] []
      (read
        ([] (let [b (byte-array 1)
                  n (read-bytes b 0 1)]
              (if (= -1 n) -1 (bit-and 255 (aget b 0)))))
        ([b] (.read ^InputStream this b 0 (alength ^bytes b)))
        ([b off len]
         (java.util.Objects/checkFromIndexSize (int off) (int len) (alength ^bytes b))
         (try
           (read-bytes b off len)
           (catch InterruptedException e
             (.interrupt (Thread/currentThread))
             (let [error (failure :read true "WebSocket response read interrupted" e {})]
               (discard! conn error)
               (throw error))))))
      (close []
        (locking lease
          (when-not (:closed? @lease)
            (swap! lease assoc :closed? true :queue clojure.lang.PersistentQueue/EMPTY)
            (reset! current nil)
            (when-not (:released? @lease)
              (discard! conn (failure :close true "WebSocket response consumer closed" nil {})))
            (.notifyAll ^Object lease)))))))

(defn response
  "Send one response.create, returning {:status 200 :body InputStream}.
  The body adapts JSON events to SSE; errors throw rather than become EOF.
  Connections are reused only after successful terminal bytes are consumed.
  :timeout-ms bounds response inactivity (including abandoned consumers);
  :connect-timeout-ms bounds the upgrade. Incremental continuation is automatic
  unless :incremental? false; only a pre-generation previous_response_not_found
  for an automatic continuation can resend the original full request once."
  [{:keys [url headers body timeout-ms connect-timeout-ms http-client incremental?]}]
  (let [timeout-ms (long (or timeout-ms 120000))
        connect-ms (long (or connect-timeout-ms 30000))
        _ (when (or (not (pos? timeout-ms)) (not (pos? connect-ms)))
            (throw (failure :configuration false "WebSocket timeouts must be positive" nil {})))
        client (or http-client http/*http-client* @default-client)
        url (websocket-url url)
        headers (into {} (map (fn [[k v]] [(str/lower-case (name k)) v])) headers)
        headers (update headers "openai-beta"
                        (fn [value]
                          (let [flags (remove #(str/starts-with? % "responses_websockets=")
                                              (map str/trim (str/split (or value "") #",")))]
                            (str/join "," (conj (vec (remove str/blank? flags))
                                                "responses_websockets=2026-02-06")))))
        ;; Parse serialized bodies once. Map bodies keep their persistent input
        ;; vectors; matching is conservative when callers use noncanonical keys.
        full-body (assoc (dissoc (if (string? body)
                                  (json/parse-string body true)
                                  (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) body))
                                 :stream :background :type)
                         :type "response.create" :stream true)
        lease (atom {:queue clojure.lang.PersistentQueue/EMPTY :bytes 0
                     :full-body full-body :incremental? (not (false? incremental?))
                     :output-items {}
                     :activity (now) :timeout-ms timeout-ms})
        conn (acquire! [url headers client connect-ms timeout-ms]
                       client url headers connect-ms lease)]
    (try
      (when-let [error (:error @lease)] (throw error))
      (let [incremental (incremental-body conn lease)]
        (swap! conn dissoc :baseline)
        (swap! lease assoc :activity (now) :consumer-activity (now)
               :sent? true :automatic? (boolean incremental))
        (send-body! conn (or incremental full-body) timeout-ms))
      {:status 200 :body (response-stream conn lease)}
      (catch Exception e
        (let [error (failure :send true "WebSocket request send failed; request will not be replayed" e {})]
          (discard! conn error)
          (throw error))))))
