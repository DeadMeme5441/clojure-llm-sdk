(ns llm.sdk.transport.speak
  "Sibling protocol to llm.sdk.transport/Transport, scoped to text-to-
   speech. Seventh modality alongside chat / embed / moderate / rerank
   / image / transcribe.

   TTS responses are raw audio bytes (mp3/wav/opus/aac/flac/pcm) rather
   than JSON, so the driver reads :body as a byte array, not parsed
   JSON. The transport provides the content-type → :audio/content-type
   mapping.")

(defprotocol SpeakTransport
  "Provider-specific TTS format conversion."
  (build-speak-request [this profile request]
    "Given a provider profile and canonical SpeakRequest, return the
     native HTTP request map. The response is always treated as a
     byte stream, so :Accept defaults to audio/* and the body is JSON.")
  (parse-speak-response [this profile resp]
    "Given the raw HTTP response ({:body bytes :status :headers}),
     return a canonical SpeakResponse with :audio/bytes,
     :audio/content-type, :audio/model, and optional :response/usage.")
  (parse-speak-error [this profile status body]
    "Given a non-2xx HTTP status and body bytes (or text), return a
     classified error."))
