(ns llm.sdk.websocket-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.providers.codex.responses :as codex]
            [llm.sdk.websocket :refer [response close-connections!]])
  (:import [java.io DataInputStream EOFException InputStream OutputStream]
           [java.net InetAddress ServerSocket Socket SocketException]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]))


(defn- daemon [f]
  (doto (Thread. ^Runnable f)
    (.setDaemon true)
    (.start)))

(defn- read-headers [^InputStream in]
  (let [text (StringBuilder.)]
    (loop []
      (let [b (.read in)]
        (when (= -1 b) (throw (EOFException.)))
        (.append text (char b))
        (when (> (.length text) 16384)
          (throw (ex-info "Oversized test handshake" {})))
        (if (str/ends-with? (str text) "\r\n\r\n")
          (let [[request-line & headers] (str/split (str text) #"\r\n")]
            {:request-line request-line
             :headers (into {} (map (fn [line]
                                      (let [[k v] (str/split line #":\s*" 2)]
                                        [(str/lower-case k) v]))
                                    headers))})
          (recur))))))

(defn- upgrade! [^Socket socket]
  (let [{:keys [headers] :as handshake} (read-headers (.getInputStream socket))
        digest (.digest (MessageDigest/getInstance "SHA-1")
                        (.getBytes (str (get headers "sec-websocket-key")
                                        "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                   StandardCharsets/US_ASCII))
        accept (.encodeToString (Base64/getEncoder) digest)
        out (.getOutputStream socket)]
    (.write out (.getBytes (str "HTTP/1.1 101 Switching Protocols\r\n"
                               "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                               "Sec-WebSocket-Accept: " accept "\r\n\r\n")
                          StandardCharsets/US_ASCII))
    (.flush out)
    handshake))

(defn- send-frame! [^Socket socket opcode final? ^bytes payload]
  (let [^OutputStream out (.getOutputStream socket)
        n (alength payload)]
    (locking out
      (.write out (int (bit-or opcode (if final? 128 0))))
      (if (< n 126)
        (.write out (int n))
        (do (.write out (int 126))
            (.write out (int (bit-and 255 (bit-shift-right n 8))))
            (.write out (int (bit-and 255 n)))))
      (.write out payload)
      (.flush out))))

(defn- send-json! [socket event]
  (send-frame! socket 1 true (.getBytes (json/generate-string event)
                                      StandardCharsets/UTF_8)))

(defn- read-frame [^Socket socket]
  (let [in (DataInputStream. (.getInputStream socket))
        first-byte (.readUnsignedByte in)
        second-byte (.readUnsignedByte in)
        masked? (pos? (bit-and second-byte 128))
        short-length (bit-and second-byte 127)
        length (case short-length
                 126 (.readUnsignedShort in)
                 127 (.readLong in)
                 short-length)]
    (when-not masked?
      (throw (ex-info "Client WebSocket frames must be masked" {})))
    (when-not (<= 0 length 1048576)
      (throw (ex-info "Invalid client test frame length" {:length length})))
    (let [mask (byte-array 4)
          data (byte-array length)]
      (.readFully in mask)
      (.readFully in data)
      (dotimes [i length]
        (aset-byte data i (unchecked-byte (bit-xor (aget data i)
                                                  (aget mask (mod i 4))))))
      {:opcode (bit-and first-byte 15)
       :final? (pos? (bit-and first-byte 128))
       :payload data})))

(defn- read-request [socket]
  (loop [parts []]
    (let [{:keys [opcode final? payload]} (read-frame socket)]
      (case opcode
        8 nil
        9 (do (send-frame! socket 10 true payload) (recur parts))
        10 (recur parts)
        (if final?
          (let [bytes (java.io.ByteArrayOutputStream.)]
            (doseq [^bytes part (conj parts payload)] (.write bytes part))
            (json/parse-string (.toString bytes "UTF-8") true))
          (recur (conj parts payload)))))))

(defn- start-server [handler]
  (let [server (ServerSocket. 0 50 (InetAddress/getByName "127.0.0.1"))
        sockets (atom [])
        workers (atom [])
        stopped? (atom false)
        errors (atom [])
        requests (atom [])
        accepted (atom 0)
        accept-thread
        (daemon
         (fn []
           (try
             (while (not @stopped?)
               (let [socket (.accept server)
                     connection (swap! accepted inc)]
                 (swap! sockets conj socket)
                 (swap! workers conj
                        (daemon
                         (fn []
                           (try
                             (with-open [socket socket]
                               (.setSoTimeout ^Socket socket 5000)
                               (let [handshake (upgrade! socket)]
                                 (loop []
                                   (when-let [body (read-request socket)]
                                     (let [request (assoc handshake :connection connection
                                                         :body body)]
                                       (swap! requests conj request)
                                       (when-not (= :close (handler socket request))
                                         (recur)))))))
                             (catch EOFException _)
                             (catch SocketException _)
                             (catch Throwable t
                               (when-not @stopped? (swap! errors conj t)))))))))
             (catch SocketException _)
             (catch Throwable t (swap! errors conj t)))))]
    {:url (str "http://127.0.0.1:" (.getLocalPort server) "/responses")
     :requests requests
     :accepted accepted
     :stop! (fn []
              (reset! stopped? true)
              (.close server)
              (doseq [^Socket socket @sockets] (.close socket))
              (.join accept-thread 1000)
              (doseq [^Thread worker @workers]
                (.interrupt worker)
                (.join worker 1000))
              (is (empty? @errors) (str "Loopback server errors: " (mapv str @errors))))}))

(defn- with-server [handler f]
  (close-connections!)
  (let [server (start-server handler)]
    (try (f server)
         (finally
           (close-connections!)
           ((:stop! server))))))

(defn- request [server & [model]]
  {:url (:url server)
   :headers {"authorization" "Bearer loopback-test-only"
             "chatgpt-account-id" "loopback-account"
             "session_id" "loopback-session"}
   :body {:model (or model "loopback-model")
          :input [{:role "user" :content "Hello"}]
          :stream true :background false}
   :timeout-ms 2000
   :connect-timeout-ms 2000})

(defn- completed [id]
  {:type "response.completed"
   :response {:id id :status "completed" :output []}})

(defn- await! [task]
  (let [result (deref task 4000 ::timeout)]
    (when (= ::timeout result)
      (when (future? task) (future-cancel task))
      (throw (ex-info "Loopback operation did not finish within four seconds" {})))
    result))

(defn- consume [req]
  (await! (future
            (let [{:keys [status body]} (response req)]
              (when-not (= 200 status)
                (throw (ex-info "Unexpected transport status" {:status status})))
              (with-open [body body] (slurp body))))))

(deftest upgrade-create-fragmentation-and-persistent-reuse
  (with-server
    (fn [socket {:keys [connection]}]
      (let [event (json/generate-string (assoc (completed (str connection)) :text "héllo"))
            bytes (.getBytes event StandardCharsets/UTF_8)
            ;; Split inside the UTF-8 encoding of é, not just between JSON tokens.
            split (inc (count (.getBytes (subs event 0 (.indexOf event "é"))
                                       StandardCharsets/UTF_8)))]
        (send-frame! socket 1 false (java.util.Arrays/copyOfRange bytes 0 split))
        (send-frame! socket 9 true (.getBytes "ping" StandardCharsets/UTF_8))
        (send-frame! socket 0 true (java.util.Arrays/copyOfRange bytes split (alength bytes)))))
    (fn [server]
      (let [first-result (consume (request server))
            second-result (consume (request server))
            [first-request second-request] @(:requests server)]
        (is (str/includes? first-result "héllo"))
        (is (str/includes? second-result "response.completed"))
        (is (str/starts-with? first-result "data: "))
        (is (= (:connection first-request) (:connection second-request))
            "Completion ends the InputStream while the socket stays reusable")
        (is (= "GET /responses HTTP/1.1" (:request-line first-request)))
        (is (= "Bearer loopback-test-only" (get-in first-request [:headers "authorization"])))
        (is (= "loopback-account" (get-in first-request [:headers "chatgpt-account-id"])))
        (is (= "loopback-session" (get-in first-request [:headers "session_id"])))
        (is (= {:type "response.create" :stream true :model "loopback-model"
                :input [{:role "user" :content "Hello"}]}
               (:body first-request)))))))

(deftest consumer-close-discards-unfinished-connection
  (let [received (promise)]
    (with-server
      (fn [socket {:keys [body]}]
        (if (= "abandon" (:model body))
          (do (send-json! socket {:type "response.output_text.delta" :delta "partial"})
              (deliver received true))
          (send-json! socket (completed "next"))))
      (fn [server]
        (let [{:keys [body]} (response (request server "abandon"))]
          (await! received)
          (.close ^InputStream body))
        (is (str/includes? (consume (request server "next")) "next"))
        (is (= 2 @(:accepted server)) "An unfinished response cannot be leased again")))))

(defn- transport-error-data [value]
  (when (instance? Throwable value)
    (some #(when (= :websocket-error (:type (ex-data %))) (ex-data %))
          (take-while some? (iterate ex-cause value)))))

(deftest failed-events-discard-connection
  (doseq [event [{:type "error" :error {:code "server_error" :message "loopback failure"}}
                 {:type "response.failed"
                  :response {:status "failed"
                             :error {:code "server_error" :message "loopback failure"}}}]]
    (testing (:type event)
      (with-server
        (fn [socket {:keys [body]}]
          (send-json! socket (if (= "fail" (:model body)) event (completed "recovered"))))
        (fn [server]
          (let [result (try (consume (request server "fail"))
                            (catch Exception e e))]
            (is (= :response (:phase (transport-error-data result))))
            (is (= event (:body (transport-error-data result)))))
          (is (str/includes? (consume (request server "next")) "recovered"))
          (is (= 2 @(:accepted server))))))))

(deftest premature-peer-close-is-not-success-or-replayed
  (with-server
    (fn [socket {:keys [body]}]
      (if (= "disconnect" (:model body))
        :close
        (send-json! socket (completed "recovered"))))
    (fn [server]
      (let [result (try (consume (request server "disconnect"))
                        (catch Exception e e))
            error (transport-error-data result)]
        (is (= :transport (:error/type error)))
        (is (true? (:request-sent? error)))
        (is (false? (:retryable? error))))
      (is (= ["disconnect"] (mapv #(get-in % [:body :model]) @(:requests server)))
          "Never replay a request after it was sent")
      (is (str/includes? (consume (request server "next")) "recovered"))
      (is (= 2 @(:accepted server))))))

(deftest response-timeout-discards-connection
  (let [received (promise)]
    (with-server
      (fn [socket {:keys [body]}]
        (if (= "stall" (:model body))
          (deliver received true)
          (send-json! socket (completed "recovered"))))
      (fn [server]
        (let [pending (future
                        (try
                          (let [{:keys [body]} (response (assoc (request server "stall")
                                                               :timeout-ms 500))]
                            (with-open [body body] (slurp body)))
                          (catch Exception e e)))]
          (await! received)
          (is (= :timeout (:phase (transport-error-data (await! pending))))))
        (is (str/includes? (consume (request server "next")) "recovered"))
        (is (= 2 @(:accepted server)))))))

(deftest active-response-does-not-block-an-independent-lease
  (let [received (promise)
        release (promise)]
    (with-server
      (fn [socket {:keys [body]}]
        (when (= "first" (:model body))
          (deliver received true)
          (await! release))
        (send-json! socket (completed (:model body))))
      (fn [server]
        (let [first-result (future (consume (request server "first")))]
          (try
            (await! received)
            (is (str/includes? (consume (request server "second")) "second"))
            (is (= 2 @(:accepted server)))
            (finally (deliver release true)))
          (is (str/includes? (await! first-result) "first")))))))

(deftest credentials-isolate-idle-connections-and-explicit-close-disposes-them
  (with-server
    (fn [socket _] (send-json! socket (completed "done")))
    (fn [server]
      (consume (request server))
      (consume (assoc-in (request server) [:headers "authorization"] "Bearer other-loopback-only"))
      (is (= 2 @(:accepted server)) "Different credentials cannot share a socket")
      (close-connections!)
      (consume (request server))
      (is (= 3 @(:accepted server)) "Explicit disposal forces a new handshake"))))

(defn- with-offline-sdk [f]
  (with-redefs [codex/codex-backend-auth-headers
                (fn [] {"Authorization" "Bearer loopback-test-only"})
                http/request
                (fn [_] (throw (ex-info "Unexpected HTTP request in WebSocket test" {})))]
    (f sdk/complete)))

(defn- sdk-request []
  {:request/model "gpt-5.5"
   :request/messages [{:message/role :user :message/content "Hello"}]})

(defn- sdk-config [server]
  {:base-url (str/replace (:url server) #"/responses$" "")
   :transport :websocket :timeout-ms 2000 :connect-timeout-ms 2000})

(defn- send-sdk-events! [socket]
  (doseq [event
          [{:type "response.created" :response {:id "sdk-response" :model "gpt-5.5"}}
           {:type "response.output_text.delta" :delta "Checking"}
           {:type "response.reasoning_summary_text.delta" :delta "Need a lookup"}
           {:type "response.output_item.added" :output_index 1
            :item {:type "function_call" :call_id "call_lookup" :name "lookup"}}
           {:type "response.function_call_arguments.delta" :output_index 1 :delta "{\"q\":"}
           {:type "response.function_call_arguments.delta" :output_index 1 :delta "\"hello\"}"}
           {:type "response.function_call_arguments.done" :output_index 1}
           {:type "response.completed"
            :response {:id "sdk-response" :model "gpt-5.5" :status "completed"
                       :usage {:input_tokens 23 :input_tokens_details {:cached_tokens 3}
                               :output_tokens 16 :output_tokens_details {:reasoning_tokens 9}
                               :total_tokens 39}}}]]
    (send-json! socket event)))

(deftest sdk-streaming-and-complete-preserve-tools-reasoning-and-usage
  (with-offline-sdk
    (fn [complete]
      (with-server
        (fn [socket _] (send-sdk-events! socket))
        (fn [server]
          (doseq [stream? [false true]]
            (let [events (atom [])
                  result (await! (future
                                   (complete :codex-backend (sdk-request)
                                             :config (sdk-config server)
                                             :stream? stream?
                                             :on-event #(swap! events conj %))))]
              (is (= :codex-backend (:response/provider result)))
              (is (= :tool-calls (:response/finish-reason result)))
              (is (= "Checking" (:text (first (filter #(= :text (:part/type %))
                                                       (:response/parts result))))))
              (is (= "Need a lookup"
                     (:reasoning/text (first (filter #(= :reasoning (:part/type %))
                                                    (:response/parts result))))))
              (is (= {:tool-call/id "call_lookup" :tool-call/name "lookup"
                      :tool-call/arguments "{\"q\":\"hello\"}"}
                     (select-keys (first (:response/tool-calls result))
                                  [:tool-call/id :tool-call/name :tool-call/arguments])))
              (is (= {:usage/input-tokens 20 :usage/cached-input-tokens 3
                      :usage/output-tokens 16 :usage/reasoning-tokens 9}
                     (select-keys (:response/usage result)
                                  [:usage/input-tokens :usage/cached-input-tokens
                                   :usage/output-tokens :usage/reasoning-tokens])))
              (when stream?
                (is (some #(= :stream/content-delta (:event/type %)) @events))
                (is (= :stream/end (:event/type (last @events)))))))
          (is (= 1 @(:accepted server))
              "Both SDK modes use and return the same persistent connection"))))))

(deftest sdk-incomplete-response-preserves-partial-output
  (with-offline-sdk
    (fn [complete]
      (with-server
        (fn [socket _]
          (send-json! socket {:type "response.output_text.delta" :delta "partial"})
          (send-json! socket {:type "response.incomplete"
                             :response {:status "incomplete"
                                        :incomplete_details {:reason "max_output_tokens"}
                                        :usage {:input_tokens 3 :output_tokens 1}}}))
        (fn [server]
          (let [result (complete :codex-backend (sdk-request) :config (sdk-config server))]
            (is (= :incomplete (:response/finish-reason result)))
            (is (= "partial" (:text (first (:response/parts result)))))
            (is (= 1 (get-in result [:response/usage :usage/output-tokens])))))))))

(deftest sdk-callback-failure-closes-unfinished-response
  (with-offline-sdk
    (fn [complete]
      (with-server
        (fn [socket {:keys [body]}]
          (if (= "abort" (get-in body [:input 0 :content 0 :text]))
            (send-json! socket {:type "response.output_text.delta" :delta "partial"})
            (send-sdk-events! socket)))
        (fn [server]
          (let [failure (ex-info "consumer callback stopped" {:callback-test true})
                result (await!
                        (future
                          (try
                            (complete :codex-backend
                                      (assoc (sdk-request) :request/messages
                                             [{:message/role :user :message/content "abort"}])
                                      :config (sdk-config server) :stream? true
                                      :on-event (fn [event]
                                                  (when (= :stream/content-delta (:event/type event))
                                                    (throw failure))))
                            (catch Exception e e))))]
            (is (identical? failure result)))
          (let [result (await! (future (complete :codex-backend (sdk-request)
                                                :config (sdk-config server))))]
            (is (= :tool-calls (:response/finish-reason result))))
          (is (= 2 @(:accepted server))
              "Callback failure closes the body rather than leaking the active lease"))))))

(def ^:private replay-output
  [{:type "reasoning" :id "rs_first" :status "completed"
    :summary [{:type "summary_text" :text "Check the record"}]
    :encrypted_content "opaque-reasoning"}
   {:type "message" :id "msg_first" :status "completed" :role "assistant"
    :phase "commentary"
    :content [{:type "output_text" :text "Looking it up"
               :annotations [] :logprobs []}]}
   {:type "function_call" :id "fc_first" :status "completed"
    :call_id "call_first" :name "lookup" :arguments "{\"q\":\"hello\"}"}])

(def ^:private replay-input
  [{:type "reasoning"
    :summary [{:type "summary_text" :text "Check the record"}]
    :encrypted_content "opaque-reasoning"}
   {:type "message" :status "completed" :role "assistant" :phase "commentary"
    :content [{:type "output_text" :text "Looking it up"}]}
   {:type "function_call" :call_id "call_first" :name "lookup"
    :arguments "{\"q\":\"hello\"}"}])

(def ^:private tool-result
  {:type "function_call_output" :call_id "call_first" :output "Found it"})

(defn- append-input [req items]
  (update-in req [:body :input] into items))

(deftest incremental-canonical-tool-replay-and-chained-full-history
  (doseq [output-source [:completed :item-done :item-done-empty-terminal]]
    (testing (name output-source)
      (let [turn (atom 0)]
        (with-server
          (fn [socket _]
            (let [n (swap! turn inc)
                  output (if (= n 1) replay-output [])]
              (if (or (= output-source :completed) (> n 1))
                (send-json! socket (assoc-in (completed (str "r" n)) [:response :output] output))
                (do
                  ;; Arrival order is not output order.
                  (doseq [index (reverse (range (count output)))]
                    (send-json! socket {:type "response.output_item.done"
                                       :output_index index :item (nth output index)}))
                  (send-json! socket
                              (cond-> (completed (str "r" n))
                                (= output-source :item-done)
                                (update :response dissoc :output)))))))
          (fn [server]
            (let [first-req (request server)
                  second-req (append-input first-req (conj replay-input tool-result))
                  user-next {:role "user" :content "Now summarize"}
                  third-req (append-input second-req [user-next])]
              (doseq [req [first-req second-req third-req]] (consume req))
              (let [[first-wire second-wire third-wire] (mapv :body @(:requests server))]
                (is (nil? (:previous_response_id first-wire)))
                (is (= "r1" (:previous_response_id second-wire)))
                (is (= [tool-result] (:input second-wire)))
                (is (= "r2" (:previous_response_id third-wire)))
                (is (= [user-next] (:input third-wire))
                    "Third turn compares against full history, not the second wire delta")
                (is (< (count (json/generate-string second-wire))
                       (count (json/generate-string (:body second-req))))))
              (is (= 1 @(:accepted server))))))))))

(deftest incremental-requires-exact-history-and-request-properties
  (doseq [[label change]
          [["edited user" #(assoc-in % [:body :input 0 :content] "Edited")]
           ["edited assistant" #(assoc-in % [:body :input 2 :content 0 :text] "Different")]
           ["message phase" #(assoc-in % [:body :input 2 :phase] "final_answer")]
           ["encrypted reasoning" #(assoc-in % [:body :input 1 :encrypted_content] "other")]
           ["reasoning summary" #(assoc-in % [:body :input 1 :summary 0 :text] "other")]
           ["tool arguments" #(assoc-in % [:body :input 3 :arguments] "{\"q\":\"other\"}")]
           ["tool call id" #(assoc-in % [:body :input 3 :call_id] "call_other")]
           ["model" #(assoc-in % [:body :model] "other-model")]
           ["tools" #(assoc-in % [:body :tools 0 :name] "other-tool")]
           ["instructions" #(assoc-in % [:body :instructions] "Different instructions")]
           ["sampling" #(assoc-in % [:body :temperature] 0.5)]
           ["not a strict extension" #(update-in % [:body :input] pop)]]]
    (testing label
      (with-server
        (fn [socket _]
          (send-json! socket (assoc-in (completed "baseline") [:response :output] replay-output)))
        (fn [server]
          (let [base (-> (request server)
                         (assoc-in [:body :instructions] "Use the tool")
                         (assoc-in [:body :tools] [{:type "function" :name "lookup"
                                                   :parameters {:type "object"}}]))
                next-req (change (append-input base (conj replay-input tool-result)))]
            (consume base)
            (consume next-req)
            (let [wire (:body (last @(:requests server)))]
              (is (nil? (:previous_response_id wire)))
              (is (= (:input (:body next-req)) (:input wire))))))))))

(deftest incremental-fails-closed-on-unrecognized-or-missing-output
  (doseq [terminal [(assoc-in (completed "baseline") [:response :output]
                              [(assoc (last replay-output) :unknown_metadata "unreplayable")])
                    (assoc-in (completed "baseline") [:response :output]
                              [{:type "future_output" :text "unknown"}])
                    (update (completed "baseline") :response dissoc :output)
                    (update (completed "baseline") :response dissoc :id)]]
    (with-server
      (fn [socket _] (send-json! socket terminal))
      (fn [server]
        (let [base (request server)
              next-req (append-input base [(last replay-input) tool-result])]
          (consume base)
          (consume next-req)
          (is (= (:input (:body next-req))
                 (get-in (last @(:requests server)) [:body :input])))
          (is (nil? (get-in (last @(:requests server)) [:body :previous_response_id]))))))))

(deftest incremental-disabled-or-invalidated-sends-full-history
  (doseq [mode [:disabled :reconnect :incomplete :credentials]]
    (testing (name mode)
      (let [turn (atom 0)]
        (with-server
          (fn [socket _]
            (send-json! socket
                        (if (and (= 1 (swap! turn inc)) (= mode :incomplete))
                          {:type "response.incomplete"
                           :response {:id "partial" :status "incomplete" :output []}}
                          (completed "baseline"))))
          (fn [server]
            (let [base (request server)
                  next-req (cond-> (append-input base [{:role "user" :content "Next"}])
                             (= mode :disabled) (assoc :incremental? false)
                             (= mode :credentials)
                             (assoc-in [:headers "authorization"] "Bearer isolated-loopback"))]
              (consume base)
              (when (= mode :reconnect) (close-connections!))
              (consume next-req)
              (let [wire (:body (last @(:requests server)))]
                (is (nil? (:previous_response_id wire)))
                (is (= (:input (:body next-req)) (:input wire)))))))))))

(deftest incremental-prefers-matching-idle-conversation
  (let [received (promise)
        release (promise)]
    (with-server
      (fn [socket {:keys [body connection]}]
        (when (and (= connection 1) (nil? (:previous_response_id body)))
          (deliver received true)
          (await! release))
        (send-json! socket (completed (str "conversation-" connection))))
      (fn [server]
        (let [a (request server)
              b (assoc-in a [:body :input 0 :content] "Conversation B")
              first-result (future (consume a))]
          (try
            (await! received)
            (consume b)
            (finally (deliver release true)))
          (await! first-result)
          ;; Request each conversation: arbitrary first-idle selection fails one.
          (consume (append-input b [{:role "user" :content "Continue B"}]))
          (consume (append-input a [{:role "user" :content "Continue A"}]))
          (let [[_ _ b-next a-next] @(:requests server)]
            (is (= 2 (:connection b-next)))
            (is (= "conversation-2" (get-in b-next [:body :previous_response_id])))
            (is (= 1 (:connection a-next)))
            (is (= "conversation-1" (get-in a-next [:body :previous_response_id]))))
          (is (= 2 @(:accepted server))))))))

(def ^:private missing-previous
  {:type "error" :error {:code "previous_response_not_found"
                         :message "The previous response is unavailable"}})

(deftest incremental-missing-previous-recovers-full-once-and-rebases
  (let [turn (atom 0)]
    (with-server
      (fn [socket _]
        (let [n (swap! turn inc)]
          (send-json! socket (if (= n 2) missing-previous (completed (str "r" n))))))
      (fn [server]
        (let [base (request server)
              next-req (append-input base [{:role "user" :content "Second"}])
              third-item {:role "user" :content "Third"}]
          (consume base)
          (is (str/includes? (consume next-req) "r3"))
          (consume (append-input next-req [third-item]))
          (let [[_ delta retry-wire third-wire] (mapv :body @(:requests server))]
            (is (= "r1" (:previous_response_id delta)))
            (is (= [{:role "user" :content "Second"}] (:input delta)))
            (is (nil? (:previous_response_id retry-wire)))
            (is (= (:input (:body next-req)) (:input retry-wire)))
            (is (= "r3" (:previous_response_id third-wire)))
            (is (= [third-item] (:input third-wire)))))))))

(deftest incremental-recovery-never-replays-unsafe-errors
  (doseq [mode [:repeated-missing :created :delta :generic :explicit]]
    (testing (name mode)
      (let [turn (atom 0)]
        (with-server
          (fn [socket _]
            (let [n (swap! turn inc)]
              (if (= n 1)
                (send-json! socket (completed "baseline"))
                (do
                  (case mode
                    :created (send-json! socket {:type "response.created"
                                                :response {:id "generation-started"}})
                    :delta (send-json! socket {:type "response.output_text.delta" :delta "Partial"})
                    nil)
                  (send-json! socket (if (= mode :generic)
                                       {:type "error" :error {:code "server_error" :message "Failed"}}
                                       missing-previous))))))
          (fn [server]
            (let [base (request server)
                  next-req (cond-> (append-input base [{:role "user" :content "Next"}])
                             (= mode :explicit) (assoc-in [:body :previous_response_id] "user-chosen"))]
              (consume base)
              (let [result (try (consume next-req) (catch Exception e e))]
                (is (= :response (:phase (transport-error-data result)))))
              (is (= (if (= mode :repeated-missing) 3 2) (count @(:requests server))))
              (when (= mode :explicit)
                (is (= "user-chosen"
                       (get-in (last @(:requests server)) [:body :previous_response_id])))))))))))

(deftest sdk-incremental-config-disables-wire-continuation
  (with-offline-sdk
    (fn [complete]
      (with-server
        (fn [socket _] (send-json! socket (completed "sdk-baseline")))
        (fn [server]
          (complete :codex-backend (sdk-request) :config (sdk-config server))
          (complete :codex-backend
                    (update (sdk-request) :request/messages conj
                            {:message/role :user :message/content "Continue"})
                    :config (assoc (sdk-config server) :incremental? false))
          (let [wire (:body (last @(:requests server)))]
            (is (nil? (:previous_response_id wire)))
            (is (= ["Hello" "Continue"]
                   (mapv #(get-in % [:content 0 :text]) (:input wire))))))))))
