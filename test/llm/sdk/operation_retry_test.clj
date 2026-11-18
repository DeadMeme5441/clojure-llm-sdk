(ns llm.sdk.operation-retry-test
  (:require [clojure.test :refer [deftest is]]
            [hato.client :as hc]
            [llm.sdk :as sdk]
            [llm.sdk.errors :as errors]
            [llm.sdk.http :as http]
            [llm.sdk.retry :as retry])
  (:import [java.io ByteArrayInputStream]
           [java.time Instant]))


(defn classify [status body]
  (errors/classify-error nil :status status :body body :provider :openai))


(def policy
  {:retry/max-attempts  3
   :retry/base-delay-ms 0
   :retry/max-delay-ms  10000
   :retry/jitter-ratio  0
   :retry/budget-ms     5000})


(defn failure [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))


(deftest metadata-and-retry-budget
  (let [clock    (atom 0)
        sent     (atom [])
        sleeps   (atom [])
        response {:status  429
                  :headers {"Retry-After" "2" "X-Request-ID" "req-1"}
                  :body    {:error {:message "Rate limited"}}}
        send     (fn [req] (swap! sent conj req) response)]
    (binding [retry/*clock-ms* #(deref clock)
              retry/*sleep-fn* #(do (swap! sleeps conj %) (swap! clock + %))]
      (let [error (failure #(retry/request! send {:timeout-ms 9000} classify :openai policy))]
        (is (= 3 (:attempts error)))
        (is (= 429 (:status error)))
        (is (= "req-1" (:request-id error)))
        (is (= 2000 (:retry-after-ms error)))
        (is (= [5000 3000 1000] (mapv :timeout-ms @sent)))
        (is (= [2000 2000] @sleeps))))))


(deftest never-retry-before-retry-after
  (binding [retry/*sleep-fn* (fn [_] (throw (AssertionError. "must not sleep")))]
    (let [error (failure #(retry/request! (constantly {:status 429 :headers {"retry-after" "20"}})
                                          {}
                                          classify
                                          :openai
                                          policy))]
      (is (= :retry/deadline (:error/type error)))
      (is (= 1 (:attempts error)))
      (is (= 20000 (:retry-after-ms error)))))
  (is (= 2000
         (retry/parse-retry-after "Sun, 06 Nov 1994 08:49:32 GMT"
                                  (Instant/parse "1994-11-06T08:49:30Z")))))


(deftest nontransient-errors-and-response-cleanup
  (doseq [[status body] [[401 {}] [402 {}] [400 {}] [413 {}] [422 {}]
                        [429 {:error {:message "insufficient_quota"}}]]]
    (let [calls (atom 0)]
      (failure #(retry/request! (fn [_] (swap! calls inc) {:status status :body body})
                                {}
                                classify
                                :openai
                                policy))
      (is (= 1 @calls) (str status))))
  (let [closed (atom false)
        body   (proxy [ByteArrayInputStream] [(byte-array 0)]
                 (close [] (reset! closed true)))]
    (failure #(retry/request! (constantly {:status 401 :body body}) {} classify :openai nil))
    (is @closed)))


(deftest cancellation-is-never-retried
  (let [calls (atom 0)]
    (try
      (let [interrupted? (try
                           (retry/request! (fn [_] (swap! calls inc) (throw (InterruptedException.)))
                                           {} classify :openai policy)
                           :not-thrown
                           (catch InterruptedException _ (Thread/interrupted)))]
        (is (true? interrupted?))
        (is (= 1 @calls)))
      (finally (Thread/interrupted)))))


(deftest one-shot-default-and-malformed-success
  (let [calls (atom 0)]
    (with-redefs [http/request (fn [_] (swap! calls inc) {:status 200 :body {:data []}})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/embed :openai {:embed/model "test" :embed/inputs ["a"]}
                              :config {:api-key "test"}
                              :retry policy)))
      (is (= 1 @calls))))
  (let [calls (atom 0)]
    (with-redefs [http/request (fn [_] (swap! calls inc) {:status 503 :body {}})]
      (is (thrown? clojure.lang.ExceptionInfo
                   (sdk/embed :openai {:embed/model "test" :embed/inputs ["a"]}
                              :config {:api-key "test"})))
      (is (= 1 @calls)))))

(deftest wrapped-interruption-is-never-replayed
  (let [calls (atom 0)
        interrupted? (try
                       (retry/request! (fn [_]
                                         (swap! calls inc)
                                         (throw (java.io.IOException. "wrapped" (InterruptedException.))))
                                       {} classify :openai policy)
                       false
                       (catch InterruptedException _ (Thread/interrupted)))]
    (is interrupted?)
    (is (= 1 @calls))))


(deftest nonchat-json-multipart-and-binary-retries
  (let [calls (atom 0)]
    (binding [retry/*sleep-fn* (fn [_])]
      (with-redefs [http/request (fn [_]
                                   (if (= 1 (swap! calls inc))
                                     {:status 503 :body {}}
                                     {:status 200 :body {:model "test" :data [{:index 0 :embedding [0.1 0.2]}]}}))]
        (is (= [[0.1 0.2]]
               (:embed/vectors
                (sdk/embed :openai {:embed/model "test" :embed/inputs ["a"]}
                           :config {:api-key "test"}
                           :retry policy)))))
      (is (= 2 @calls))))
  (doseq [operation [:transcribe :speak]]
    (let [calls (atom [])]
      (binding [retry/*sleep-fn* (fn [_])]
        (with-redefs [hc/request (fn [request]
                                   (swap! calls conj request)
                                   (if (= 1 (count @calls))
                                     {:status 503 :body "{}"}
                                     {:status  200
                                      :headers {"content-type" "audio/mpeg"}
                                      :body    (if (= :transcribe operation) "{\"text\":\"hello\"}" (byte-array [1 2]))}))]
          (if (= :transcribe operation)
            (is (= "hello"
                   (:transcription/text
                    (sdk/transcribe :openai {:transcribe/model "test" :transcribe/file (byte-array [1 2])}
                                    :config {:api-key "test"}
                                    :retry policy))))
            (is (= [1 2]
                   (vec (:audio/bytes
                         (sdk/speak :openai {:speak/model "test" :speak/input "hi" :speak/voice "alloy"}
                                    :config {:api-key "test"}
                                    :retry policy)))))))
        (is (= 2 (count @calls))))))
  (is (= :request/non-replayable-body
         (:error/type (failure #(retry/request! (fn [_] (throw (AssertionError.)))
                                                {:multipart [{:content (ByteArrayInputStream. (byte-array [1]))}]}
                                                classify
                                                :openai
                                                policy))))))
