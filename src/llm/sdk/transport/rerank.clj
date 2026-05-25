(ns llm.sdk.transport.rerank
  "Sibling protocol to llm.sdk.transport/Transport, scoped to rerank
   endpoints. Rerank is a natural pair-step to embeddings —
   search apps need both, and the three providers we ship adapters
   for (Cohere, Voyage, Jina) all share a similar wire shape with
   minor field-naming differences.")

(defprotocol RerankTransport
  "Provider-specific rerank format conversion."
  (build-rerank-request [this profile request]
    "Given a profile and canonical RerankRequest, return the
     native HTTP request map (:method :url :headers :body).")
  (parse-rerank-response [this profile raw-body]
    "Given a profile and parsed JSON body, return a canonical
     RerankResponse.")
  (parse-rerank-error [this profile status body]
    "Given a non-2xx HTTP status and body, return a classified error map."))
