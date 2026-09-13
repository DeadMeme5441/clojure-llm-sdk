(ns llm.sdk.transport.embed
  "Sibling protocol to llm.sdk.transport/Transport, scoped to embedding
   endpoints. The first non-chat modality.

   We keep this protocol narrow on purpose. Embeddings don't stream,
   don't take tool calls, and don't carry reasoning — bolting them onto
   the chat Transport protocol would dilute both. New modalities
   (image, audio) get their own narrow protocols too."
  (:import [java.nio ByteBuffer ByteOrder]
           [java.util Base64]))

(defn decode-float32-base64
  "Decode a Base64-encoded little-endian IEEE 754 float32 vector.

   Throws ExceptionInfo when `encoded` is not valid Base64 or the decoded
   byte count is not a whole number of float32 values."
  [encoded]
  (when-not (string? encoded)
    (throw (ex-info "Invalid base64 embedding encoding"
                    {:error/type :embedding/invalid-base64
                     :encoded/type (type encoded)})))
  (let [bytes (try
                (.decode (Base64/getDecoder) ^String encoded)
                (catch IllegalArgumentException cause
                  (throw (ex-info "Invalid base64 embedding encoding"
                                  {:error/type :embedding/invalid-base64
                                   :encoded-length (count encoded)}
                                  cause))))]
    (when-not (zero? (mod (alength bytes) Float/BYTES))
      (throw (ex-info "Invalid base64 embedding byte length"
                      {:error/type :embedding/invalid-byte-length
                       :byte-length (alength bytes)})))
    (let [buffer (doto (ByteBuffer/wrap bytes)
                   (.order ByteOrder/LITTLE_ENDIAN))]
      (loop [values (transient [])]
        (if (.hasRemaining buffer)
          (recur (conj! values (.getFloat buffer)))
          (persistent! values))))))

(defprotocol EmbedTransport
  "Provider-specific embedding format conversion."
  (build-embed-request [this profile request]
    "Given a provider profile and canonical EmbedRequest, return the
     native HTTP request map (:method :url :headers :body).")
  (parse-embed-response [this profile raw-body]
    "Given a provider profile and the parsed JSON body of an embedding
     response, return a canonical EmbedResponse.")
  (parse-embed-error [this profile status body]
    "Given a non-2xx HTTP status and body, return a classified error
     map (same shape as the chat-side classifier).")
  (normalize-embed-usage [this profile raw-usage]
    "Given the embedding-response usage block, return the canonical
     Usage shape (no completion / reasoning tokens)."))
