(ns vertex-stream-smoke
  "Tiny live smoke: ask Vertex Gemini to stream 'pong' and print each
   delta as it arrives, then the aggregate response with cost/cache."
  (:require [llm.sdk :as sdk]))

(defn -main [& _]
  (let [project (or (System/getenv "GOOGLE_CLOUD_PROJECT")
                    (throw (Exception. "set GOOGLE_CLOUD_PROJECT")))
        request {:request/model "gemini-2.5-flash"
                 :request/messages
                 [{:message/role :user
                   :message/content "Reply with exactly the word 'pong'."}]
                 :request/max-tokens 64
                 :request/provider-options
                 {:vertex {:project project :location "us-central1"}}}
        deltas (atom [])
        events (atom [])
        on-event (fn [ev]
                   (swap! events conj (:event/type ev))
                   (when (= :stream/content-delta (:event/type ev))
                     (swap! deltas conj (:event/delta ev))
                     (print (:event/delta ev)) (flush)))
        resp (sdk/complete :vertex-gemini request
                           :stream? true :on-event on-event)]
    (println)
    (println "events seen:" (frequencies @events))
    (println "aggregated text:" (apply str @deltas))
    (println "response/provider:" (:response/provider resp))
    (println "response/usage:" (:response/usage resp))
    (println "response/cost:" (:response/cost resp))
    (println "response/cache:" (:response/cache resp))
    (println "finish:" (:response/finish-reason resp))))
