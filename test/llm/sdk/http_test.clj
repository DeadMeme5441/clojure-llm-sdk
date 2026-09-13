(ns llm.sdk.http-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.http :as http])
  (:import [java.nio.charset StandardCharsets]
           [java.net InetSocketAddress]
           [java.net.http HttpTimeoutException]
           [java.util.concurrent Callable CountDownLatch ExecutionException Executors TimeUnit]
           [com.sun.net.httpserver HttpServer HttpHandler]))

(deftest line-seq-closeable-closes-stream-at-eof
  (let [closed? (atom false)
        input (proxy [java.io.ByteArrayInputStream]
                     [(.getBytes "data: one\n\ndata: two\n" StandardCharsets/UTF_8)]
                (close []
                  (reset! closed? true)
                  (proxy-super close)))]
    (is (= ["data: one" "" "data: two"]
           (doall (http/line-seq-closeable input))))
    (is @closed?)))

(deftest request-deadline-is-enforced-by-the-http-client
  (let [release (CountDownLatch. 1)
        executor (Executors/newSingleThreadExecutor)
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setExecutor server executor)
    (.createContext server "/slow"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (try (.await release)
                             (finally (.close exchange))))))
    (.start server)
    (try
      (is (thrown? HttpTimeoutException
                   (http/request {:method :get
                                  :url (str "http://127.0.0.1:"
                                            (.getPort (.getAddress server)) "/slow")
                                  :timeout-ms 200})))
      (finally
        (.countDown release)
        (.stop server 0)
        (.shutdownNow executor)))))

(deftest streaming-query-parameters-reach-the-server
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/events"
                    (reify HttpHandler
                      (handle [_ exchange]
                        (let [body (.getBytes (.getRawQuery (.getRequestURI exchange))
                                              StandardCharsets/UTF_8)]
                          (.sendResponseHeaders exchange 200 (alength body))
                          (with-open [out (.getResponseBody exchange)]
                            (.write out body))))))
    (.start server)
    (try
      (doseq [request [http/sse-response http/binary-stream-request]]
        (let [response (request {:method :get
                                 :url (str "http://127.0.0.1:"
                                           (.getPort (.getAddress server)) "/events")
                                 :query-params {"key" "synthetic-token"}})]
          (with-open [body (:body response)]
            (is (= "key=synthetic-token" (slurp body))))))
      (finally (.stop server 0)))))

(deftest streaming-inactivity-is-bounded-after-successful-headers
  (doseq [request [http/sse-response http/binary-stream-request]]
    (let [release (CountDownLatch. 1)
          executor (Executors/newSingleThreadExecutor)
          server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
      (.createContext server "/stalled"
                      (reify HttpHandler
                        (handle [_ exchange]
                          (.sendResponseHeaders exchange 200 0)
                          (with-open [out (.getResponseBody exchange)]
                            (.write out (int 65))
                            (.flush out)
                            (.await release)))))
      (.start server)
      (try
        (with-open [body (:body (request
                                 {:method :get
                                  :url (str "http://127.0.0.1:"
                                            (.getPort (.getAddress server)) "/stalled")
                                  :timeout-ms 500}))]
          (is (= 65 (.read body)))
          (let [read-task (.submit executor ^Callable (fn [] (.read body)))
                failure (try (.get read-task 3 TimeUnit/SECONDS)
                             nil
                             (catch ExecutionException e (.getCause e)))]
            (is (instance? HttpTimeoutException failure))))
        (finally
          (.countDown release)
          (.stop server 0)
          (.shutdownNow executor))))))
