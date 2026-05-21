(ns llm.sdk.transport.embed
  "Sibling protocol to llm.sdk.transport/Transport, scoped to embedding
   endpoints. The first non-chat modality.

   We keep this protocol narrow on purpose. Embeddings don't stream,
   don't take tool calls, and don't carry reasoning — bolting them onto
   the chat Transport protocol would dilute both. New modalities
   (image, audio) get their own narrow protocols too.")

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
