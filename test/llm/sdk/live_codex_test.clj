(ns llm.sdk.live-codex-test
  "Opt-in ChatGPT OAuth coverage. Run with clojure -M:live-test -n llm.sdk.live-codex-test.
   CODEX_TEST_MODEL overrides the default gpt-5.6-luna. Never prints credentials."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.providers.codex.auth :as auth]
            [llm.sdk.stream :as stream]
            [llm.sdk.websocket :as websocket])
  (:import [java.awt.image BufferedImage]
           [java.io ByteArrayOutputStream]
           [java.util Base64]
           [java.util.concurrent Callable Executors TimeUnit]
           [javax.imageio ImageIO]))

(defn- model [] (or (System/getenv "CODEX_TEST_MODEL") "gpt-5.6-luna"))
(defn- config [transport]
  {:transport transport :timeout-ms 60000 :connect-timeout-ms 15000})
(defn- request [prompt]
  {:request/model (model)
   :request/reasoning {:enabled true :effort :low}
   :request/messages [{:message/role :user :message/content prompt}]})
(defn- text [response]
  (str/trim (apply str (keep :text (:response/parts response)))))
(defn- assistant [response]
  (cond-> {:message/role :assistant :message/content (:response/parts response)}
    (:response/provider-data response)
    (assoc :message/provider-data (:response/provider-data response))))
(defn- complete [transport req & options]
  (apply sdk/complete :codex-backend req :config (config transport) options))

(use-fixtures :each
  (fn [f]
    (when-not (auth/codex-backend-available?)
      (throw (ex-info "Live OAuth tests require Codex CLI ChatGPT credentials"
                      {:error/type :auth/missing-codex-backend-token})))
    (try (f) (finally (websocket/close-connections!)))))

(deftest ^:live completion-surfaces-over-every-oauth-transport
  (doseq [transport [:sse :websocket]]
    (testing (name transport)
      (let [response (complete transport (request "Reply with exactly OAUTH_OK."))]
        (is (= "OAUTH_OK" (text response)))
        (is (= :stop (:response/finish-reason response)))
        (is (pos? (get-in response [:response/usage :usage/input-tokens] 0))))
      (let [events (atom [])
            response (complete transport (request "Reply with exactly STREAM_OK.")
                               :stream? true :on-event #(swap! events conj %))]
        (is (= "STREAM_OK" (text response)))
        (is (= :stream/start (:event/type (first @events))))
        (is (= :stream/end (:event/type (last @events))))
        (is (= 1 (count (filter #(= :stream/end (:event/type %)) @events))))
        (is (= :stop (:response/finish-reason response))))
      (with-open [events (complete transport (request "Reply with exactly PULL_OK.") :stream? true)]
        (let [response (stream/events->response events :codex-backend (model))]
          (is (= "PULL_OK" (text response)))
          (is (= :stop (:response/finish-reason response))))))))

(deftest ^:live structured-output-over-every-oauth-transport
  (doseq [transport [:sse :websocket]]
    (let [req (assoc (request "Return an object with answer equal to 42.")
                     :request/response-format
                     {:type :json_schema :name "probe" :strict true
                      :json-schema {:type "object"
                                    :properties {:answer {:type "integer"}}
                                    :required ["answer"] :additionalProperties false}})]
      (is (= {:answer 42} (json/parse-string (text (complete transport req)) true))))))

(deftest ^:live typed-function-results-and-reasoning-replay
  (doseq [transport [:sse :websocket]]
    (let [req (assoc (request "Use add to add 19 and 23. After its result, reply exactly RESULT=42.")
                     :request/tools
                     [{:type :function
                       :function {:name "add" :description "Add two integers" :strict true
                                  :parameters {:type "object"
                                               :properties {:a {:type "integer"} :b {:type "integer"}}
                                               :required ["a" "b"] :additionalProperties false}}}]
                     :request/tool-choice {:type :function :function {:name "add"}})
          first-response (complete transport req :stream? true :on-event (fn [_]))
          call (first (:response/tool-calls first-response))
          arguments (json/parse-string (:tool-call/arguments call) true)
          result (+ (:a arguments) (:b arguments))
          follow-up (-> req
                        (assoc :request/tool-choice :auto)
                        (update :request/messages into
                                [(assistant first-response)
                                 {:message/role :tool
                                  :message/content [{:part/type :tool-result
                                                     :tool-result/id (:tool-call/id call)
                                                     :tool-result/name (:tool-call/name call)
                                                     :tool-result/content (str result)}]}]))]
      (is (= :tool-calls (:response/finish-reason first-response)))
      (is (= 42 result))
      (is (= "RESULT=42" (text (complete transport follow-up :stream? true :on-event (fn [_]))))))))

(deftest ^:live default-reasoning-replays-without-server-storage
  (doseq [transport [:sse :websocket]]
    (let [req (dissoc (request "Remember the marker cobalt-47. Reply exactly ACK.") :request/reasoning)
          first-response (complete transport req)
          follow-up (update req :request/messages into
                            [(assistant first-response)
                             {:message/role :user
                              :message/content "Reply only with the marker I asked you to remember."}])]
      (is (= "cobalt-47" (text (complete transport follow-up)))))))

(deftest ^:live websocket-chained-continuation-and-reconnect
  (let [sends (atom [])
        send-body @#'websocket/send-body!
        req (request "Remember the marker cobalt-47. Reply exactly ACK.")]
    (with-redefs-fn
      {#'websocket/send-body!
       (fn [connection body timeout]
         (swap! sends conj {:incremental? (contains? body :previous_response_id)
                            :input-count (count (:input body))})
         (send-body connection body timeout))}
      (fn []
        (let [first-response (complete :websocket req)
              req2 (update req :request/messages into
                           [(assistant first-response)
                            {:message/role :user :message/content "Return only the remembered marker."}])
              second-response (complete :websocket req2)
              req3 (update req2 :request/messages into
                           [(assistant second-response)
                            {:message/role :user :message/content "Repeat the marker only."}])
              third-response (complete :websocket req3)]
          (is (= "cobalt-47" (text second-response) (text third-response)))
          (is (= [false true true] (mapv :incremental? @sends)))
          (is (= [1 1] (mapv :input-count (rest @sends))))
          (websocket/close-connections!)
          (is (= "cobalt-47" (text (complete :websocket req3))))
          (is (false? (:incremental? (last @sends)))))))))

(deftest ^:live cancellation-does-not-poison-the-next-request
  (doseq [transport [:sse :websocket]]
    (with-open [events (complete transport
                                 (request "List integers 1 through 200, one per line, with no explanation.")
                                 :stream? true)]
      (is (= :stream/content-delta
             (reduce (fn [_ event]
                       (when (= :stream/content-delta (:event/type event))
                         (reduced (:event/type event))))
                     nil events)))
      (is (nil? (seq events))))
    (is (= "AFTER_CANCEL_OK"
           (text (complete transport (request "Reply with exactly AFTER_CANCEL_OK.")))))))

(defn- red-image []
  (let [image (BufferedImage. 32 32 BufferedImage/TYPE_INT_RGB)
        out (ByteArrayOutputStream.)]
    (dotimes [x 32] (dotimes [y 32] (.setRGB image x y 0xFF0000)))
    (ImageIO/write image "png" out)
    (.encodeToString (Base64/getEncoder) (.toByteArray out))))

(deftest ^:live inline-images-and-files-over-every-oauth-transport
  (doseq [transport [:sse :websocket]]
    (let [image-request (assoc (request "unused") :request/messages
                               [{:message/role :user
                                 :message/content [{:part/type :text :text "Name the dominant color of this square using one uppercase word."}
                                                   {:part/type :image :image/data (red-image) :image/mime-type "image/png"}]}])
          file-request (assoc (request "unused") :request/messages
                              [{:message/role :user
                                :message/content [{:part/type :text :text "Read the attached note and return only its reference code."}
                                                  {:part/type :file :file/name "note.txt" :file/mime-type "text/plain"
                                                   :file/content "The reference code is QUARTZ_52."}]}])]
      (is (= "RED" (text (complete transport image-request))))
      (is (= "QUARTZ_52" (text (complete transport file-request)))))))

(deftest ^:live concurrent-websocket-conversations-stay-isolated
  (let [executor (Executors/newFixedThreadPool 2)]
    (try
      (let [jobs (mapv (fn [marker]
                         (.submit executor
                                  ^Callable (fn []
                                              (text (complete :websocket
                                                              (request (str "Reply with exactly " marker ".")))))))
                       ["LEFT_OK" "RIGHT_OK"])]
        (is (= ["LEFT_OK" "RIGHT_OK"]
               (mapv #(.get % 60 TimeUnit/SECONDS) jobs))))
      (finally (.shutdownNow executor)))))

(deftest ^:live custom-tool-roundtrip-over-every-oauth-transport
  (doseq [transport [:sse :websocket]]
    (let [req (assoc (request "Call echo with the text PING. After its result, reply exactly CUSTOM_OK.")
                     :request/tools [{:type :custom
                                      :custom {:name "echo" :description "Echo text"
                                               :format {:type :text}}}]
                     :request/tool-choice {:type :custom :custom {:name "echo"}})
          first-response (complete transport req :stream? true :on-event (fn [_]))
          call (first (:response/tool-calls first-response))
          follow-up (-> req
                        (assoc :request/tool-choice :auto)
                        (update :request/messages into
                                [(assistant first-response)
                                 {:message/role :tool
                                  :message/content [{:part/type :tool-result
                                                     :tool-result/id (:tool-call/id call)
                                                     :tool-result/name (:tool-call/name call)
                                                     :tool-result/content "CUSTOM_OK"}]}]))]
      (is (= "echo" (:tool-call/name call)))
      (is (= "CUSTOM_OK" (text (complete transport follow-up)))))))

(deftest ^:live explicit-credentials-bypass-managed-storage
  ;; Resolve a usable managed bearer once, then exercise host-managed mode.
  (auth/codex-backend-auth-headers)
  (let [credentials (auth/read-codex-auth)]
    (with-redefs-fn
      {#'auth/codex-auth-file-path
       (fn [] (throw (ex-info "External credentials must not read managed storage" {})))}
      (fn []
        (doseq [transport [:sse :websocket]]
          (is (= "EXTERNAL_OK"
                 (text (sdk/complete
                        :codex-backend (request "Reply with exactly EXTERNAL_OK.")
                        :config (assoc (config transport)
                                       :auth-token (:access-token credentials)
                                       :account-id (:account-id credentials)))))))))))

(deftest ^:live managed-refresh-recovers-real-http-unauthorized
  ;; This deliberately rejected request exercises a real OAuth refresh and
  ;; atomically updates the shared Codex auth.json. It never prints tokens.
  (let [request-auth auth/request-auth
        recoveries (atom 0)]
    (with-redefs [auth/request-auth
                  (fn [profile]
                    (let [{:keys [recover!] :as resolved} (request-auth profile)]
                      (-> resolved
                          (assoc-in [:headers "Authorization"]
                                    "Bearer deliberately-invalid-live-probe")
                          (assoc :recover! (fn []
                                             (swap! recoveries inc)
                                             (recover!))))))]
      (is (= "REFRESH_OK"
             (text (complete :sse (request "Reply with exactly REFRESH_OK.")))))
      (is (= 1 @recoveries)))))
