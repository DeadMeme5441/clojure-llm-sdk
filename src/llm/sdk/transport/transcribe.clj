(ns llm.sdk.transport.transcribe
  "Sibling protocol to llm.sdk.transport/Transport, scoped to audio
   transcription (speech-to-text). Sixth modality sibling alongside
   chat / embed / moderate / rerank / image.

   Transcription has a different wire shape from the rest: requests
   are multipart/form-data (binary audio + form fields), responses
   carry text + optional segments / words / language detection.")

(defprotocol TranscribeTransport
  "Provider-specific transcription format conversion."
  (build-transcribe-request [this profile request]
    "Given a provider profile and canonical TranscribeRequest, return
     the native HTTP request map. Multipart bodies live under
     :multipart as a vector of clj-http/hato multipart entries.")
  (parse-transcribe-response [this profile raw-body]
    "Given a provider profile and the parsed response (JSON or text),
     return a canonical TranscribeResponse.")
  (parse-transcribe-error [this profile status body]
    "Given a non-2xx HTTP status and body, return a classified error.")
  (normalize-transcribe-usage [this profile raw-usage]
    "Optional: normalize provider usage data (most STT providers
     don't return token counts, but some return duration_seconds)."))
