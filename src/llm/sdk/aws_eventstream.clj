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

(def ^:private max-frame-length (* 24 1024 1024))
(def ^:private max-headers-length (* 128 1024))

(defn- read-fully ^bytes [^DataInputStream in n]
  (let [buf (byte-array n)]
    (.readFully in buf)
    buf))

(defn- read-header [^DataInputStream in]
  (let [name-len (.readUnsignedByte in)
        name (String. (read-fully in name-len) StandardCharsets/UTF_8)
        type-byte (.readUnsignedByte in)
        t (get header-types type-byte)
        value (case t
                :bool-true true
                :bool-false false
                :byte (.readByte in)
                :int16 (.readShort in)
                :int32 (.readInt in)
                :int64 (.readLong in)
                (:bytes :string)
                (let [len (.readUnsignedShort in)
                      bs (read-fully in len)]
                  (if (= t :string) (String. bs StandardCharsets/UTF_8) bs))
                :timestamp (.readLong in)
                :uuid (read-fully in 16)
                (throw (ex-info "Unknown AWS event-stream header type"
                                {:header name :type-byte type-byte})))]
    [name value]))

(defn- read-headers [headers-bytes]
  (let [in (-> headers-bytes
               java.io.ByteArrayInputStream.
               DataInputStream.)]
    (try
      (loop [out (transient {})]
        (if (pos? (.available in))
          (let [[k v] (read-header in)]
            (recur (assoc! out k v)))
          (persistent! out)))
      (catch EOFException e
        (throw (ex-info "Truncated AWS event-stream headers"
                        {:headers-length (alength ^bytes headers-bytes)}
                        e))))))

(defn read-frame
  "Read one frame from a DataInputStream. Returns
   {:headers {} :payload <bytes>} or nil only when EOF precedes a new frame.
   Truncated and invalid frames throw ExceptionInfo."
  [^DataInputStream in]
  (let [first-byte (.read in)]
    (when-not (= -1 first-byte)
      (try
        (let [total-len (bit-or (bit-shift-left first-byte 24)
                                (bit-shift-left (.readUnsignedByte in) 16)
                                (bit-shift-left (.readUnsignedByte in) 8)
                                (.readUnsignedByte in))
              headers-len (.readInt in)
              _ (.readInt in)
              payload-len (- total-len headers-len 16)]
          (when (or (< total-len 16) (> total-len max-frame-length))
            (throw (ex-info "Invalid AWS event-stream frame length"
                            {:total-length total-len
                             :maximum max-frame-length})))
          (when (or (neg? headers-len)
                    (> headers-len max-headers-length)
                    (> headers-len (- total-len 16)))
            (throw (ex-info "Invalid AWS event-stream headers length"
                            {:total-length total-len
                             :headers-length headers-len
                             :maximum max-headers-length})))
          (let [headers-bytes (read-fully in headers-len)
                payload (read-fully in payload-len)
                _ (.readInt in)]
            {:headers (read-headers headers-bytes)
             :payload payload}))
        (catch EOFException e
          (throw (ex-info "Truncated AWS event-stream frame" {} e)))))))

(defn frame-seq
  "Lazy seq of decoded frames from an InputStream. Full consumption and decoder
   failures close the stream; callers abandoning a prefix must still close its
   owning SDK stream handle."
  [^InputStream is]
  (let [in (DataInputStream. is)]
    (letfn [(step []
              (lazy-seq
               (try
                 (if-let [frame (read-frame in)]
                   (cons frame (step))
                   (do (.close in) nil))
                 (catch Throwable t
                   (try (.close in) (catch Throwable _))
                   (throw t)))))]
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
