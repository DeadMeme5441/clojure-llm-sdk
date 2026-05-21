(ns llm.sdk.transport.moderate
  "Sibling protocol to llm.sdk.transport/Transport, scoped to
   moderation endpoints (T2-13).

   Moderation doesn't stream, doesn't take tools, and returns boolean
   flags + per-category scores. We keep the protocol narrow on
   purpose, matching the embed-transport pattern.")

(defprotocol ModerationTransport
  "Provider-specific moderation format conversion."
  (build-moderation-request [this profile request]
    "Given a profile and canonical ModerationRequest, return the
     native HTTP request map (:method :url :headers :body).")
  (parse-moderation-response [this profile raw-body]
    "Given a profile and parsed JSON body, return a canonical
     ModerationResponse.")
  (parse-moderation-error [this profile status body]
    "Given a non-2xx HTTP status and body, return a classified
     error map."))
