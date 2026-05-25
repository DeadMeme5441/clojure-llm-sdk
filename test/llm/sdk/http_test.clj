(ns llm.sdk.http-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk.http :as http])
  (:import [java.nio.charset StandardCharsets]))

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
