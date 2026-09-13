(ns llm.sdk.providers.fake.chat
  "Fake/test provider that returns deterministic responses.
   Conforms to the Transport protocol."
  (:require [llm.sdk.transport :as t]))

(defrecord FakeTransport [response-fn]
  t/Transport
  (build-request [_ profile request]
    {:method :post
     :url (str (:profile/base-url profile) "/fake/chat")
     :headers {}
     :body request})

  (parse-response [_ profile raw]
    (if response-fn
      (response-fn raw)
      {:response/provider (:profile/id profile)
       :response/model "fake-model"
       :response/parts [{:part/type :text :text "Hello from fake provider."}]
       :response/finish-reason :stop}))

  (parse-stream-event [_ _profile _line]
    nil)

  (parse-error [_ _profile _status _body]
    {:error/reason :unknown
     :error/retryable false})

  (normalize-usage [_ _profile _raw]
    {:usage/input-tokens 10
     :usage/output-tokens 5
     :usage/total-tokens 15
     :usage/request-count 1})

  (request-capabilities [_]
    #{:chat}))

(defn make-fake-transport
  "Create a fake transport. Optionally pass a response-fn that receives
   the raw response and returns a canonical Response map."
  [& {:keys [response-fn]}]
  (->FakeTransport response-fn))

