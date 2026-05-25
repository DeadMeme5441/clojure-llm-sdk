(ns llm.sdk.aws-eventstream-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [llm.sdk.aws-eventstream :as es])
  (:import [java.io ByteArrayOutputStream DataOutputStream
            ByteArrayInputStream]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Encode a frame so we can round-trip through the decoder. We don't
;; compute valid CRCs — the decoder skips CRC validation per its docstring.
;; ---------------------------------------------------------------------------

(defn- encode-string-header [^DataOutputStream out name value]
  (let [name-bytes (.getBytes ^String name StandardCharsets/UTF_8)
        val-bytes (.getBytes ^String value StandardCharsets/UTF_8)]
    (.writeByte out (count name-bytes))
    (.write out name-bytes)
    (.writeByte out 7)                 ; type 7 = string
    (.writeShort out (count val-bytes))
    (.write out val-bytes)))

(defn- encode-headers ^bytes [headers]
  (let [bos (ByteArrayOutputStream.)
        out (DataOutputStream. bos)]
    (doseq [[k v] headers]
      (encode-string-header out k v))
    (.toByteArray bos)))

(defn- encode-frame
  "Encode a frame given a headers map and a payload byte array."
  ^bytes [headers ^bytes payload]
  (let [headers-bytes (encode-headers headers)
        headers-len (count headers-bytes)
        payload-len (count payload)
        total-len (+ headers-len payload-len 16)
        bos (ByteArrayOutputStream.)
        out (DataOutputStream. bos)]
    (.writeInt out total-len)
    (.writeInt out headers-len)
    (.writeInt out 0)                  ; prelude CRC (not validated)
    (.write out headers-bytes)
    (.write out payload)
    (.writeInt out 0)                  ; message CRC (not validated)
    (.toByteArray bos)))

(defn- json-bytes ^bytes [m]
  (.getBytes ^String (json/generate-string m) StandardCharsets/UTF_8))

(defn- stream-of [& frames]
  (let [bos (ByteArrayOutputStream.)]
    (doseq [f frames] (.write bos ^bytes f))
    (ByteArrayInputStream. (.toByteArray bos))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest test-decode-single-frame
  (let [frame-bytes (encode-frame {":event-type" "contentBlockDelta"
                                   ":content-type" "application/json"}
                                  (json-bytes {:delta {:text "hello"}
                                               :contentBlockIndex 0}))
        [frame] (es/frame-seq (ByteArrayInputStream. frame-bytes))]
    (is (some? frame))
    (is (= "contentBlockDelta" (get-in frame [:headers ":event-type"])))
    (let [decoded (es/frame->json frame)]
      (is (= "contentBlockDelta" (:event-type decoded)))
      (is (= "hello" (get-in decoded [:data :delta :text]))))))

(deftest test-decode-multiple-frames
  (let [f1 (encode-frame {":event-type" "messageStart"} (json-bytes {:role "assistant"}))
        f2 (encode-frame {":event-type" "contentBlockDelta"}
                         (json-bytes {:delta {:text "Hi"}}))
        f3 (encode-frame {":event-type" "contentBlockDelta"}
                         (json-bytes {:delta {:text " there"}}))
        f4 (encode-frame {":event-type" "messageStop"} (json-bytes {:stopReason "end_turn"}))
        f5 (encode-frame {":event-type" "metadata"}
                         (json-bytes {:usage {:inputTokens 12 :outputTokens 3 :totalTokens 15}}))
        frames (es/frame-seq (stream-of f1 f2 f3 f4 f5))
        events (mapv es/frame->json frames)]
    (is (= 5 (count events)))
    (is (= ["messageStart" "contentBlockDelta" "contentBlockDelta"
            "messageStop" "metadata"]
           (mapv :event-type events)))
    (is (= "Hi" (get-in (second events) [:data :delta :text])))
    (is (= 12 (get-in (last events) [:data :usage :inputTokens])))))

(deftest test-empty-stream
  (is (empty? (es/frame-seq (ByteArrayInputStream. (byte-array 0))))))
