(ns llm.sdk.codex-oauth-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.providers.codex.auth :as auth]
            [llm.sdk.websocket :as websocket])
  (:import [java.io ByteArrayInputStream]))

(def request
  {:request/model "gpt-5.6-luna"
   :request/messages [{:message/role :user :message/content "probe"}]})

(defn- body [suffix]
  (ByteArrayInputStream.
   (.getBytes (str "data: {\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}\n\n" suffix)
              "UTF-8")))

(defn- completed []
  (body "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_probe\",\"model\":\"gpt-5.6-luna\",\"status\":\"completed\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"))

(deftest authentication-rejection-recovers-once-before-generation
  (doseq [transport [:sse :websocket]]
    (let [requests (atom [])
          recoveries (atom 0)
          send (fn [req]
                 (swap! requests conj req)
                 (if (= 1 (count @requests))
                   (if (= :sse transport)
                     {:status 401 :body {:error {:message "expired"}}}
                     (throw (ex-info "Rejected upgrade"
                                     {:status 401 :phase :handshake :request-sent? false})))
                   {:status 200 :body (completed)}))]
      (with-redefs [auth/request-auth
                    (fn [_]
                      {:headers {"Authorization" "Bearer old"
                                 "ChatGPT-Account-ID" "old-account"
                                 "X-OpenAI-Fedramp" "true"
                                 "session-id" "conversation"}
                       :recover! (fn []
                                   (swap! recoveries inc)
                                   {"Authorization" "Bearer fresh"})})
                    http/sse-response send
                    websocket/response send]
        (let [response (sdk/complete :codex-backend request :config {:transport transport})
              retried (second @requests)]
          (is (= "ok" (get-in response [:response/parts 0 :text])))
          (is (= 1 @recoveries))
          (is (= 2 (count @requests)))
          (is (= "Bearer fresh" (get-in retried [:headers "Authorization"])))
          (is (nil? (get-in retried [:headers "ChatGPT-Account-ID"])))
          (is (nil? (get-in retried [:headers "X-OpenAI-Fedramp"])))
          (is (= "conversation" (get-in retried [:headers "session-id"])))
          (is (not (contains? retried :auth/recover!))))))))

(deftest repeated-authentication-rejection-is-not-an-unbounded-retry
  (let [requests (atom 0)
        recoveries (atom 0)]
    (with-redefs [auth/request-auth
                  (fn [_]
                    {:headers {"Authorization" "Bearer old"}
                     :recover! (fn [] (swap! recoveries inc) {"Authorization" "Bearer fresh"})})
                  http/sse-response
                  (fn [_] (swap! requests inc) {:status 401 :body {:error {:message "rejected"}}})]
      (let [failure (try (sdk/complete :codex-backend request :retry true)
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= 401 (:status failure)))
        (is (= 2 @requests))
        (is (= 1 @recoveries))))))

(deftest authentication-error-after-output-never-replays-a-generation
  (let [requests (atom 0)
        recoveries (atom 0)]
    (with-redefs [auth/request-auth
                  (fn [_]
                    {:headers {"Authorization" "Bearer old"}
                     :recover! (fn [] (swap! recoveries inc) {"Authorization" "Bearer fresh"})})
                  http/sse-response
                  (fn [_]
                    (swap! requests inc)
                    {:status 200
                     :body (body "data: {\"type\":\"error\",\"status\":401,\"error\":{\"message\":\"expired\"}}\n\n")})]
      (let [failure (try (sdk/complete :codex-backend request
                                       :stream? true :on-event (fn [_]))
                         nil
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= "ok" (get-in failure [:partial-response :response/parts 0 :text])))
        (is (= 1 @requests))
        (is (zero? @recoveries))))))

(deftest blocking-oauth-response-without-terminal-event-is-incomplete
  (with-redefs [auth/request-auth
                (constantly {:headers {"Authorization" "Bearer synthetic"}})
                http/sse-response (fn [_] {:status 200 :body (body "")})]
    (let [response (sdk/complete :codex-backend request)]
      (is (= :incomplete (:response/finish-reason response)))
      (is (= "ok" (get-in response [:response/parts 0 :text]))))))
