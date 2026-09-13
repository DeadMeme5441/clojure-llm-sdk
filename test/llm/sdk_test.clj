(ns llm.sdk-test
  "Tests for the public llm.sdk surface — specifically the
   registry-backed list-models / model-capabilities / model-info /
   refresh-models! API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.aws-eventstream :as aws-eventstream]
            [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.http :as http]
            [llm.sdk.models :as models]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.registry :as registry]
            [llm.sdk.sse :as sse])
  (:import [java.io ByteArrayInputStream]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "sdk-test" "")]
    (.delete d) (.mkdirs d) d))

(defn isolate [f]
  (let [d (temp-dir)]
    (binding [mdev/*cache-dir* (.getPath d)]
      (mdev/reset-cache!)
      (registry/clear-live!)
      (registry/clear-overrides!)
      (try (f)
           (finally
             (doseq [c (.listFiles d)] (.delete c))
             (.delete d))))))

(use-fixtures :each isolate)

(defn- offline [f]
  (with-redefs [http/request (fn [_] {:status 500 :body {:error "offline"}})]
    (f)))

(defn- tracking-sse-body [content closed?]
  (proxy [ByteArrayInputStream] [(.getBytes content "UTF-8")]
    (close []
      (reset! closed? true)
      (proxy-super close))))

(defn- run-codex-stream [content]
  (let [events (atom [])
        closed? (atom false)
        response
        (with-redefs [http/sse-response
                      (fn [_]
                        {:status 200
                         :headers {}
                         :body (tracking-sse-body content closed?)})]
          (sdk/complete
           :codex
           {:request/model "gpt-5.3-codex"
            :request/messages [{:message/role :user
                                :message/content "reply"}]}
           :stream? true
           :on-event #(swap! events conj %)
           :config {:api-key "test-key"}))]
    {:events @events :response response :closed? @closed?}))

(deftest complete-validates-canonical-request-before-network
  (let [ex (try
             (sdk/complete :openai {:request/model "gpt-4o-mini"})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :schema/invalid-request
           (get-in (ex-data ex) [:error/type])))))

(deftest complete-streaming-classifies-http-errors
  (with-redefs [http/sse-response
                (fn [_]
                  {:status 401
                   :headers {}
                   :body {:error {:message "Invalid API key"
                                  :type "invalid_request_error"}}})]
    (let [ex (try
               (sdk/complete :openai
                             {:request/model "gpt-4o-mini"
                              :request/messages [{:message/role :user
                                                  :message/content "hi"}]}
                             :stream? true)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= 401 (:status (ex-data ex))))
      (is (= :auth (get-in (ex-data ex) [:error :error/reason]))))))

(deftest complete-streaming-does-not-duplicate-provider-end
  (let [{:keys [events response closed?]}
        (run-codex-stream
         (str "data: {\"type\":\"response.output_text.delta\","
              "\"delta\":\"ok\"}\n\n"
              "data: {\"type\":\"response.completed\","
              "\"response\":{\"id\":\"resp_live\","
              "\"model\":\"gpt-5.3-codex\","
              "\"status\":\"completed\","
              "\"usage\":{\"input_tokens\":2,\"output_tokens\":1,"
              "\"total_tokens\":3}}}\n\n"))]
    (is closed?)
    (is (= 1 (count (filter #(= :stream/end (:event/type %)) events))))
    (is (= "ok" (get-in response [:response/parts 0 :text])))
    (is (= :stop (:response/finish-reason response)))))

(deftest complete-streaming-defers-terminal-until-usage-trailer
  (let [{:keys [events response]}
        (run-codex-stream
         (str "data: {\"type\":\"response.output_text.delta\","
              "\"delta\":\"ok\"}\n\n"
              "data: {\"type\":\"response.completed\","
              "\"response\":{\"id\":\"resp_live\","
              "\"model\":\"gpt-5.3-codex\","
              "\"status\":\"completed\"}}\n\n"
              "data: {\"type\":\"response.usage\","
              "\"usage\":{\"input_tokens\":25,\"output_tokens\":15,"
              "\"total_tokens\":40}}\n\n"))]
    (is (= [:stream/start :stream/content-delta :stream/usage :stream/end]
           (mapv :event/type events)))
    (is (= :stop (:event/finish-reason (last events))))
    (is (= {:usage/input-tokens 25
            :usage/output-tokens 15
            :usage/total-tokens 40}
           (select-keys (:response/usage response)
                        [:usage/input-tokens
                         :usage/output-tokens
                         :usage/total-tokens])))))

(deftest complete-streaming-appends-fallback-end
  (let [{:keys [events response]}
        (run-codex-stream
         "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n")]
    (is (= 1 (count (filter #(= :stream/end (:event/type %)) events))))
    (is (= :stream/end (:event/type (last events))))
    (is (= :incomplete (:event/finish-reason (last events))))
    (is (= :incomplete (:response/finish-reason response)))
    (is (= "ok" (get-in response [:response/parts 0 :text])))))

(deftest complete-streaming-exposes-error-events-to-lazy-consumers
  (let [closed? (atom false)]
    (with-redefs [http/sse-response
                  (fn [_]
                    {:status 200
                     :headers {}
                     :body (tracking-sse-body
                            (str "data: {\"type\":\"response.error\","
                                 "\"error\":{\"message\":\"provider failed\"}}"
                                 "\n\n")
                            closed?)})]
      (let [events
            (vec
             (sdk/complete
              :codex
              {:request/model "gpt-5.3-codex"
               :request/messages [{:message/role :user
                                   :message/content "reply"}]}
              :stream? true
              :config {:api-key "test-key"}))]
        (is (= [:stream/start :stream/error :stream/end]
               (mapv :event/type events)))
        (is (= "provider failed"
               (get-in events [1 :error/error :error/message])))
        (is (= :incomplete (:event/finish-reason (last events))))
        (is @closed?)))))

(deftest streaming-handle-supports-explicit-close-and-with-open
  (let [closed? (atom false)]
    (with-redefs [http/sse-response
                  (fn [_]
                    {:status 200
                     :headers {}
                     :body (tracking-sse-body
                            "data:{\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                            closed?)})]
      (with-open [events (sdk/complete
                          :codex
                          {:request/model "gpt-5.3-codex"
                           :request/messages [{:message/role :user
                                               :message/content "reply"}]}
                          :stream? true
                          :config {:api-key "test-key"})]
        (is (instance? java.io.Closeable events))
        (is (= :stream/start (:event/type (first events))))
        (is (false? @closed?))))
    (is @closed?)))

(deftest reducing-a-stream-prefix-closes-its-body
  (let [closed? (atom false)]
    (with-redefs [http/sse-response
                  (fn [_]
                    {:status 200
                     :headers {}
                     :body (tracking-sse-body
                            "data:{\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                            closed?)})]
      (let [events (sdk/complete
                    :codex
                    {:request/model "gpt-5.3-codex"
                     :request/messages [{:message/role :user
                                         :message/content "reply"}]}
                    :stream? true
                    :config {:api-key "test-key"})
            seen (reduce (fn [types event]
                           (let [types (conj types (:event/type event))]
                             (if (= :stream/content-delta (:event/type event))
                               (reduced types)
                               types)))
                         []
                         events)]
        (is (= [:stream/start :stream/content-delta] seen))
        (is @closed?)))))

(deftest callback-failure-closes-stream-body
  (let [closed? (atom false)
        failure (ex-info "callback failed" {})
        caught
        (with-redefs [http/sse-response
                      (fn [_]
                        {:status 200
                         :headers {}
                         :body (tracking-sse-body
                                "data:{\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n"
                                closed?)})]
          (try
            (sdk/complete
             :codex
             {:request/model "gpt-5.3-codex"
              :request/messages [{:message/role :user
                                  :message/content "reply"}]}
             :stream? true
             :on-event (fn [event]
                         (when (= :stream/content-delta (:event/type event))
                           (throw failure)))
             :config {:api-key "test-key"})
            nil
            (catch Exception e e)))]
    (is (identical? failure caught))
    (is @closed?)))

(deftest parser-failure-closes-stream-body
  (let [closed? (atom false)
        failure (ex-info "parser failed" {})
        caught
        (with-redefs [http/sse-response
                      (fn [_]
                        {:status 200
                         :headers {}
                         :body (tracking-sse-body
                                "data:{\"delta\":\"ok\"}\n\n"
                                closed?)})
                      sse/parse-json-data
                      (fn [& _] (throw failure))]
          (try
            (dorun
             (sdk/complete
              :codex
              {:request/model "gpt-5.3-codex"
               :request/messages [{:message/role :user
                                   :message/content "reply"}]}
              :stream? true
              :config {:api-key "test-key"}))
            nil
            (catch Exception e e)))]
    (is (identical? failure caught))
    (is @closed?)))

(deftest bedrock-stream-closes-and-emits-terminal-after-metadata
  (let [closed? (atom false)
        body (tracking-sse-body "" closed?)
        seen (atom [])]
    (with-redefs [aws-sigv4/maybe-sign (fn [_ req] req)
                  http/binary-stream-request
                  (fn [_] {:status 200 :headers {} :body body})
                  aws-eventstream/frame-seq
                  (fn [_]
                    [{:event-type "contentBlockDelta"
                      :data {:contentBlockIndex 0 :delta {:text "ok"}}}
                     {:event-type "messageStop"
                      :data {:stopReason "end_turn"}}
                     {:event-type "metadata"
                      :data {:usage {:inputTokens 2
                                     :outputTokens 1
                                     :totalTokens 3}}}])
                  aws-eventstream/frame->json identity]
      (let [response
            (sdk/complete
             :bedrock
             {:request/model "claude-sonnet-4-5"
              :request/messages [{:message/role :user
                                  :message/content "reply"}]}
             :stream? true
             :on-event #(swap! seen conj %))]
        (is (= [:stream/start :stream/content-delta :stream/usage :stream/end]
               (mapv :event/type @seen)))
        (is (= :stop (:response/finish-reason response)))
        (is (= 3 (get-in response [:response/usage :usage/total-tokens])))))
    (is @closed?)))

;; ---------------------------------------------------------------------------
;; list-models
;; ---------------------------------------------------------------------------

(deftest list-models-no-arg-returns-distinct-ids
  (offline
   (fn []
     (let [ms (sdk/list-models)]
       (is (> (count ms) 30))
       (is (= (count ms) (count (set ms))))
       (is (some #(= "gpt-4o" %) ms))
       (is (some #(re-find #"claude" %) ms))))))

(deftest list-models-with-provider-returns-entries
  (offline
   (fn []
     (let [openai-models (sdk/list-models :openai)
           anthropic-models (sdk/list-models :anthropic)]
       (is (every? #(= :openai (:model/provider %)) openai-models))
       (is (every? #(= :anthropic (:model/provider %)) anthropic-models))
       (is (some #(= "gpt-4o" (:model/id %)) openai-models))))))

;; ---------------------------------------------------------------------------
;; model-capabilities + model-context-length + model-info
;; ---------------------------------------------------------------------------

(deftest model-capabilities-includes-tools-for-gpt4o
  (offline
   (fn []
     (let [caps (sdk/model-capabilities "gpt-4o")]
       (is (set? caps))
       (is (contains? caps :tools))))))

(deftest model-capabilities-provider-aware-form
  (offline
   (fn []
     (let [caps (sdk/model-capabilities :openai "gpt-4o")]
       (is (contains? caps :tools))))))

(deftest model-context-length-returns-int
  (offline
   (fn []
     (is (pos? (sdk/model-context-length "gpt-4o")))
     (is (pos? (sdk/model-context-length :anthropic "claude-opus-4-7"))))))

(deftest model-info-includes-cost-source-context
  (offline
   (fn []
     (let [m (sdk/model-info :openai "gpt-4o")]
       (is (some? m))
       (is (pos? (:model/context-length m)))
       (is (pos? (get-in m [:model/cost :input-per-million])))
       (is (contains? #{:models-dev :litellm-snapshot :bundled-snapshot}
                      (:model/source m))
           "no live refresh, no override → an offline tier source")))))

;; ---------------------------------------------------------------------------
;; refresh-models! — live tier population
;; ---------------------------------------------------------------------------

(deftest refresh-models-single-provider
  (with-redefs [models/fetch-models
                (fn [pid]
                  [{:model/id "gpt-new-2099" :model/provider pid
                    :model/source :live-models-api}])]
    (let [result (sdk/refresh-models! :provider :openai)]
      (is (= {:count 1} (:openai result)))
      (let [m (sdk/model-info :openai "gpt-new-2099")]
        (is (some? m))
        (is (= :live-models-api (:model/source m)))))))

(deftest refresh-models-all-providers
  (with-redefs [models/fetch-models
                (fn [pid]
                  (if (#{:codex :bedrock :fake} pid)
                    (throw (ex-info "no /models" {:provider pid :error :unsupported}))
                    [{:model/id (str "stub-" (name pid))
                      :model/provider pid
                      :model/source :live-models-api}]))]
    (let [result (sdk/refresh-models!)
          successes (count (filter (fn [[_ v]] (:count v)) result))]
      (is (>= successes 5))
      (testing "live entries land in the registry"
        (is (some? (sdk/model-info :openai "stub-openai")))
        (is (some? (sdk/model-info :anthropic "stub-anthropic")))))))

;; ---------------------------------------------------------------------------
;; register-model-info — override roundtrip
;; ---------------------------------------------------------------------------

(deftest register-model-info-roundtrip
  (offline
   (fn []
     (sdk/register-model-info :custom-llm "magic-9000"
                              {:model/context-length 9000
                               :model/capabilities #{:chat :tools}
                               :model/cost {:input-per-million 0.5
                                            :output-per-million 2.0}})
     (let [m (sdk/model-info :custom-llm "magic-9000")]
       (is (= 9000 (:model/context-length m)))
       (is (contains? (:model/capabilities m) :tools)))
     (let [r (sdk/estimate-cost :custom-llm "magic-9000"
                                {:usage/input-tokens 2000
                                 :usage/output-tokens 1000})]
       (is (= :actual (:cost/status r)))))))

;; ---------------------------------------------------------------------------
;; estimate-cost — works across providers without manual registration
;; ---------------------------------------------------------------------------

(deftest estimate-cost-covers-every-snapshot-provider
  (offline
   (fn []
     (doseq [[pid mid] [[:openai "gpt-4o"]
                        [:anthropic "claude-opus-4-7"]
                        [:gemini-native "gemini-2.5-pro"]
                        [:deepseek "deepseek-chat"]]]
       (let [r (sdk/estimate-cost pid mid {:usage/input-tokens 1000
                                           :usage/output-tokens 500})]
         (is (= :actual (:cost/status r))
             (str "expected pricing for " pid "/" mid))
         (is (pos? (:cost/amount-usd r))))))))

(deftest stream-handles-consume-once-and-release-their-unread-tail
  (let [closed? (atom false)]
    (with-redefs [http/sse-response
                  (fn [_]
                    {:status 200
                     :body (tracking-sse-body
                            (str "data: {\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}\n\n"
                                 "data: [DONE]\n\n")
                            closed?)})]
      (with-open [events (sdk/complete
                          :openai
                          {:request/model "test-model"
                           :request/messages [{:message/role :user :message/content "hello"}]}
                          :stream? true)]
        (is (= :stream/start (:event/type (first events))))
        (let [remaining (into [] events)]
          (is (= [:stream/content-delta :stream/end] (mapv :event/type remaining)))
          (is (= "hello" (:event/delta (first remaining)))))
        (is @closed?)
        (is (nil? (seq events)))))))
