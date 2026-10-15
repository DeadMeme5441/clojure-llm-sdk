(ns llm.sdk.anthropic-oauth-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.provider.auth :as auth]
            [llm.sdk.providers.anthropic.chat :as anthropic]
            [llm.sdk.stream :as stream])
  (:import [java.io ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

(def request
  {:request/model "claude-sonnet-4-6"
   :request/messages [{:message/role :system :message/content "Hermes Agent / Nous Research remain unchanged"}
                      {:message/role :user :message/content "Use the REPL"}]
   :request/tools [{:type :function :function {:name "repl" :description "Evaluate"
                                              :parameters {:type "object" :properties {}}}}]
   :request/tool-choice {:type :function :function {:name "repl"}}
   :request/cache {:enabled? true :strategy :system-and-3 :tools-cache? true}})

(defn- oauth-profile []
  (auth/apply-runtime-config (sdk/provider-profile :anthropic)
                             {:auth-mode :oauth :auth-token "opaque-access-token"}))

(defn- fixture-stream [finish-reason stop?]
  (apply str
         (map #(str "data: " (json/generate-string %) "\n\n")
              (cond-> [{:type "message_start" :message {:id "fixture" :model "claude-sonnet-4-6"
                                                       :usage {:input_tokens 10}}}
                       {:type "content_block_start" :index 0
                        :content_block {:type "tool_use" :id "call" :name "_repl" :input {}}}
                       {:type "content_block_delta" :index 0
                        :delta {:type "input_json_delta" :partial_json "{\"source\":\"(+ 1 2)\"}"}}
                       {:type "content_block_stop" :index 0}
                       {:type "message_delta" :delta {:stop_reason finish-reason}
                        :usage {:output_tokens 5}}]
                stop? (conj {:type "message_stop"})))))

(deftest explicit-oauth-is-bearer-only-through-public-complete
  (let [original (sdk/provider-profile :anthropic)
        captured (atom nil)
        response (with-redefs [http/sse-response
                               (fn [req]
                                 (reset! captured req)
                                 {:status 200 :body (ByteArrayInputStream.
                                                    (.getBytes (fixture-stream "tool_use" true)
                                                               StandardCharsets/UTF_8))})]
                   (sdk/complete :anthropic request :stream? true :on-event (fn [_])
                                 :config {:auth-mode :oauth :auth-token "opaque-access-token"
                                          :headers {"X-API-Key" "must-not-send"
                                                    "Authorization" "must-not-send"
                                                    "Anthropic-Beta" "fixture-beta"}
                                          :connect-timeout-ms 1234 :timeout-ms 5678}))
        built @captured
        headers (:headers built)]
    (is (= "https://api.anthropic.com/v1/messages?beta=true" (:url built)))
    (is (= "Bearer opaque-access-token" (get headers "authorization")))
    (is (not-any? #(= "x-api-key" (str/lower-case (name %))) (keys headers)))
    (is (not (str/includes? (pr-str built) "must-not-send")))
    (is (str/includes? (get headers "anthropic-beta") "oauth-2025-04-20"))
    (is (str/includes? (get headers "anthropic-beta") "fixture-beta"))
    (is (= 1234 (:connect-timeout-ms built)))
    (is (= 5678 (:timeout-ms built)))
    (is (= "_repl" (get-in built [:body :tools 0 :name])))
    (is (= "_repl" (get-in built [:body :tool_choice :name])))
    (is (= "Hermes Agent / Nous Research remain unchanged" (get-in built [:body :system 1 :text])))
    (is (some :cache_control (get-in built [:body :system])))
    (is (some :cache_control (get-in built [:body :tools])))
    (is (= "repl" (get-in response [:response/tool-calls 0 :tool-call/name])))
    (is (= :tool-calls (:response/finish-reason response)))
    (is (= 0.000105M (get-in response [:response/cost :cost/usd])))
    (is (= original (sdk/provider-profile :anthropic)))))

(deftest oauth-tool-names-roundtrip-through-streaming-unary-and-native-replay
  (doseq [name ["repl" "_private" "mcp_tool"]]
    (let [wire-name (str "_" name)
          block {:type "tool_use" :id "call" :name wire-name :input {:untouched wire-name}}
          profile (oauth-profile)
          event (anthropic/parse-stream-event-anthropic
                 profile (str "data: " (json/generate-string
                                        {:type "content_block_start" :index 0 :content_block block})))
          unary (anthropic/parse-response-anthropic
                 profile {:content [block] :stop_reason "tool_use"})
          call (assoc (select-keys event [:tool-call/name :tool-call/id :tool-call/provider-data])
                      :part/type :tool-call :tool-call/arguments "{}")
          replay (update request :request/messages conj
                         {:message/role :assistant :message/content [] :message/tool-calls [call]}
                         {:message/role :tool :message/tool-call-id "call" :message/content "done"})
          built (anthropic/build-request-anthropic profile replay)]
      (is (= name (:tool-call/name event)))
      (is (= name (get-in event [:tool-call/provider-data :anthropic/content-block :name])))
      (is (= wire-name (get-in event [:tool-call/provider-data :anthropic/input :untouched])))
      (is (= name (get-in unary [:response/tool-calls 0 :tool-call/name])))
      (is (= wire-name (get-in unary [:response/raw :content 0 :name])))
      (is (= wire-name (get-in built [:body :messages 1 :content 0 :name])))
      (is (= "call" (get-in built [:body :messages 2 :content 0 :tool_use_id]))))))

(deftest oauth-preserves-thinking-images-and-explicit-cache-off
  (let [input (-> request
                  (assoc :request/reasoning {:enabled true :effort :high}
                         :request/cache {:enabled? false})
                  (update :request/messages conj
                          {:message/role :user
                           :message/content [{:part/type :image :image/data "AAAA"
                                              :image/mime-type "image/png"}]}))
        built (anthropic/build-request-anthropic (oauth-profile) input)]
    (is (= "adaptive" (get-in built [:body :thinking :type])))
    (is (= "high" (get-in built [:body :output_config :effort])))
    (is (= "AAAA" (get-in built [:body :messages 1 :content 0 :source :data])))
    (is (not-any? :cache_control (get-in built [:body :system])))
    (is (not-any? :cache_control (get-in built [:body :tools])))))

(deftest oauth-rejects-custom-endpoints-and-missing-tokens
  (doseq [url ["http://api.anthropic.com/v1" "https://proxy.invalid/v1"
               "https://api.anthropic.com.evil/v1" "https://api.anthropic.com/v1?redirect=evil"
               "https://api.anthropic.com/v1/" nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"official Anthropic endpoint"
                         (anthropic/build-request-anthropic
                          (assoc (oauth-profile) :profile/base-url url) request))))
  (doseq [token [nil "" " " 123]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (anthropic/build-request-anthropic
                  (assoc (oauth-profile) :profile/auth-token token :profile/env-var-names []) request)))))

(deftest token-shape-does-not-select-oauth
  (let [profile (assoc (sdk/provider-profile :anthropic) :profile/auth-token "sk-ant-oat01-fixture")
        built (anthropic/build-request-anthropic profile request)]
    (is (= "sk-ant-oat01-fixture" (get-in built [:headers "x-api-key"])))
    (is (= "repl" (get-in built [:body :tools 0 :name])))
    (is (= "Hermes Agent / Nous Research remain unchanged" (get-in built [:body :system 0 :text])))))

(deftest anthropic-stream-completion-requires-message-stop-and-known-finish
  (doseq [consumer [:callback :pull]
          [reason stop? expected] [["tool_use" true :tool-calls]
                                   ["tool_use" false :incomplete]
                                   [nil true :incomplete]
                                   ["unrecognized_reason" true :incomplete]]]
    (let [closed? (atom false)
          response (with-redefs [http/sse-response
                                 (fn [_]
                                   {:status 200
                                    :body (proxy [ByteArrayInputStream]
                                                 [(.getBytes (fixture-stream reason stop?) StandardCharsets/UTF_8)]
                                            (close [] (reset! closed? true) (proxy-super close)))})]
                     (case consumer
                       :callback (sdk/complete :anthropic request :stream? true :on-event (fn [_])
                                               :config {:auth-mode :oauth :auth-token "opaque"})
                       :pull (with-open [events (sdk/complete :anthropic request :stream? true
                                                              :config {:auth-mode :oauth :auth-token "opaque"})]
                               (stream/events->response events :anthropic (:request/model request)))))]
      (is (= expected (:response/finish-reason response)) (str consumer " " reason " stop=" stop?))
      (is @closed?))))

(deftest malformed-stream-data-fails-before-a-later-valid-terminator
  (with-redefs [http/sse-response
                (fn [_] {:status 200
                         :body (ByteArrayInputStream.
                                (.getBytes (str "data: {broken\n\n" (fixture-stream "tool_use" true))
                                           StandardCharsets/UTF_8))})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Malformed Anthropic stream"
                         (sdk/complete :anthropic request :stream? true :on-event (fn [_])
                                       :config {:auth-mode :oauth :auth-token "opaque"})))))
