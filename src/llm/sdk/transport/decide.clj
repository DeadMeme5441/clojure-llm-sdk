(ns llm.sdk.transport.decide
  "Provider-native typed decision request and response conversion.")

(defprotocol DecisionTransport
  "Typed decisions use native endpoints, not chat completions."
  (build-decision-request [this profile request]
    "Return a native HTTP request map (:method :url :headers :body).")
  (parse-decision-response [this profile raw-body]
    "Return a canonical DecisionResponse from a decoded JSON body.")
  (parse-decision-error [this profile status body]
    "Return a classified error map for a non-2xx response."))
