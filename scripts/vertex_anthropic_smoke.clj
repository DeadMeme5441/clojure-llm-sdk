(ns vertex-anthropic-smoke
  "Live smoke for Claude-on-Vertex (:vertex-anthropic). Sends a tiny unary
   prompt to each requested model, then a streaming prompt, printing
   provider/model/usage. Auth + project come from the standard GCP chain
   (gcloud ADC / GOOGLE_* env). Location defaults to GOOGLE_CLOUD_LOCATION
   or 'global'.

   Run:
     GOOGLE_CLOUD_PROJECT=your-gcp-project GOOGLE_CLOUD_LOCATION=global \\
       clojure -M -m vertex-anthropic-smoke claude-opus-4-6 claude-sonnet-4-6 claude-haiku-4-5"
  (:require [llm.sdk :as sdk]))

(defn- text [resp] (apply str (keep :text (:response/parts resp))))

(defn -main [& models]
  (let [project (or (System/getenv "GOOGLE_CLOUD_PROJECT")
                    (throw (Exception. "set GOOGLE_CLOUD_PROJECT")))
        location (or (System/getenv "GOOGLE_CLOUD_LOCATION") "global")
        models (or (seq models) ["claude-opus-4-6" "claude-sonnet-4-6" "claude-haiku-4-5"])
        opts {:vertex {:project project :location location}}]
    (doseq [m models]
      (println "\n========" m "(" location ") ========")
      (try
        (let [resp (sdk/complete :vertex-anthropic
                     {:request/model m
                      :request/messages [{:message/role :user
                                          :message/content "Reply with exactly the word: pong"}]
                      :request/max-tokens 32
                      :request/provider-options opts})]
          (println "  provider:" (:response/provider resp) " model:" (:response/model resp))
          (println "  text    :" (pr-str (text resp)))
          (println "  finish  :" (:response/finish-reason resp))
          (println "  usage   :" (:response/usage resp)))
        (catch Exception e
          (println "  FAIL:" (.getMessage e))
          (when-let [d (ex-data e)] (println "  status:" (:status d))))))
    (println "\n======== streaming:" (first models) "========")
    (let [deltas (atom [])
          resp (sdk/complete :vertex-anthropic
                 {:request/model (first models)
                  :request/messages [{:message/role :user :message/content "Count to three."}]
                  :request/max-tokens 32
                  :request/provider-options opts}
                 :stream? true
                 :on-event (fn [ev]
                             (when (= :stream/content-delta (:event/type ev))
                               (swap! deltas conj (:event/delta ev))
                               (print (:event/delta ev)) (flush))))]
      (println)
      (println "  aggregated:" (pr-str (apply str @deltas)))
      (println "  usage     :" (:response/usage resp)))))
