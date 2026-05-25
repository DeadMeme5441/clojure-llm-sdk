(ns llm.sdk.transport.image
  "Sibling protocol to llm.sdk.transport/Transport, scoped to image
   generation endpoints.

   Image generation is per-request: no streaming, no tools, no
   reasoning. The protocol is narrow on purpose, matching the
   embed/moderate/rerank pattern.")

(defprotocol ImageTransport
  "Provider-specific image generation format conversion."
  (build-image-request [this profile request]
    "Given a profile and canonical ImageGenRequest, return the
     native HTTP request map (:method :url :headers :body).")
  (parse-image-response [this profile raw-body]
    "Given a profile and parsed JSON body, return a canonical
     ImageGenResponse.")
  (parse-image-error [this profile status body]
    "Given a non-2xx HTTP status and body, return a classified
     error map."))
