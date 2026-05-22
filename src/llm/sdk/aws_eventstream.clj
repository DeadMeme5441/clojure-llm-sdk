(ns llm.sdk.aws-eventstream
  "Decoder for the AWS vnd.amazon.eventstream binary frame format used by
   Bedrock /converse-stream and Kinesis. Spec:
     https://docs.aws.amazon.com/AmazonS3/latest/API/RESTSelectObjectAppendix.html

   Each frame:
     [prelude (12 bytes)] [headers] [payload] [message-crc (4 bytes)]
   Prelude:
     [total-length (4 BE)] [headers-length (4 BE)] [prelude-crc (4 BE)]
   Each header:
     [name-len (1)] [name] [type (1)] [value-len (2 BE if variable)] [value]

   We skip CRC validation (caller already trusts the AWS connection)."
  (:require [cheshire.core :as json])
  (:import [java.io InputStream DataInputStream EOFException]
           [java.nio.charset StandardCharsets]))

;; ---------------------------------------------------------------------------
;; Header value types (subset; Bedrock only emits strings)
;; ---------------------------------------------------------------------------

(def ^:private header-types
  {0 :bool-true
   1 :bool-false
   2 :byte
   3 :int16
   4 :int32
   5 :int64
   6 :bytes
   7 :string
   8 :timestamp
   9 :uuid})

(defn- read-fully ^bytes [^DataInputStream in n]
  (let [buf (byte-array n)]
    (.readFully in buf)
    buf))

(defn- read-header [^DataInputStream in]
  (let [name-len (.readUnsignedByte in)
        name (String. (read-fully in name-len) StandardCharsets/UTF_8)
        type-byte (.readUnsignedByte in)
        t (get header-types type-byte :unknown)
        value (case t
                :bool-true true
                :bool-false false
                :byte (.readByte in)
                :int16 (.readShort in)
                :int32 (.readInt in)
                :int64 (.readLong in)
                (:bytes :string)
                (let [len (.readUnsignedShort in)]
                  (let [bs (read-fully in len)]
                    (if (= t :string) (String. bs StandardCharsets/UTF_8) bs)))
                :timestamp (.readLong in)
                :uuid (read-fully in 16)
                (read-fully in 0))]
    [name value]))

(defn- read-headers [headers-bytes]
  (let [in (-> headers-bytes
               java.io.ByteArrayInputStream.
               DataInputStream.)
        out (transient {})]
    (try
      (loop []
        (when (pos? (.available in))
          (let [[k v] (read-header in)]
            (assoc! out k v)
            (recur))))
      (catch EOFException _ nil))
    (persistent! out)))

(defn read-frame
  "Read one frame from a DataInputStream. Returns
   {:headers {} :payload <bytes>} or nil at EOF."
  [^DataInputStream in]
  (try
    (let [total-len (.readInt in)
          headers-len (.readInt in)
          ;; skip prelude CRC
          _ (.readInt in)
          headers-bytes (read-fully in headers-len)
          payload-len (- total-len headers-len 16)
          payload (read-fully in payload-len)
          _ (.readInt in)]   ; skip message CRC
      {:headers (read-headers headers-bytes)
       :payload payload})
    (catch EOFException _ nil)))

(defn frame-seq
  "Lazy seq of decoded frames from an InputStream."
  [^InputStream is]
  (let [in (DataInputStream. is)]
    (letfn [(step []
              (lazy-seq
                (when-let [f (read-frame in)]
                  (cons f (step)))))]
      (step))))

(defn frame->json
  "Decode a frame's payload as JSON, returning a map with
   :event-type, :content-type, and :data (parsed JSON map)."
  [{:keys [headers payload]}]
  (let [event-type (get headers ":event-type")
        ct (get headers ":content-type")
        data (try (json/parse-string (String. ^bytes payload StandardCharsets/UTF_8) true)
                  (catch Exception _ nil))]
    {:event-type event-type
     :content-type ct
     :headers headers
     :data data}))
