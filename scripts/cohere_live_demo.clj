(ns cohere-live-demo
  "Exercise every Cohere modality the SDK supports — embed, rerank,
   chat (non-streaming + streaming + RAG + tool calls) — and print
   real responses so you can see what the trial key gets back.

   Run:
     source .env && clojure -M -m cohere-live-demo"
  (:require [llm.sdk :as sdk]))

(defn- banner [s]
  (println)
  (println (str "==== " s " ====")))

(defn- die [msg]
  (println "FAIL:" msg)
  (System/exit 1))

(defn- check [pred msg]
  (if pred
    (println " ✓" msg)
    (die msg)))

(defn -main [& _]
  (when-not (System/getenv "COHERE_API_KEY")
    (die "COHERE_API_KEY not set"))

  ;; ----------------------------------------------------------------
  ;; 1. Embed — exercise input_type variants + dimensions
  ;; ----------------------------------------------------------------
  (banner "embed-english-v3.0 (search_document)")
  (let [resp (sdk/embed :cohere
                        {:embed/model "embed-english-v3.0"
                         :embed/inputs
                         ["Clojure is a Lisp dialect for the JVM."
                          "Python is a general-purpose language."
                          "Rust is a memory-safe systems language."]
                         :embed/provider-options {:input-type "search_document"}})]
    (println "provider:" (:embed/provider resp))
    (println "model:" (:embed/model resp))
    (println "dims:" (:embed/dimensions resp))
    (println "n vectors:" (count (:embed/vectors resp)))
    (println "first vec[0..4]:" (vec (take 5 (first (:embed/vectors resp)))))
    (println "usage:" (:response/usage resp))
    (check (= :cohere (:embed/provider resp)) "embed provider :cohere")
    (check (= 1024 (:embed/dimensions resp)) "v3 dims = 1024")
    (check (= 3 (count (:embed/vectors resp))) "3 vectors returned")
    (check (every? number? (first (:embed/vectors resp))) "vector is numeric"))

  (banner "embed-english-v3.0 (search_query)")
  (let [resp (sdk/embed :cohere
                        {:embed/model "embed-english-v3.0"
                         :embed/inputs ["lisp on the JVM"]
                         :embed/provider-options {:input-type "search_query"}})]
    (println "first vec[0..4]:" (vec (take 5 (first (:embed/vectors resp)))))
    (check (= 1 (count (:embed/vectors resp))) "query embed returned 1 vector"))

  (banner "embed-english-light-v3.0 (light model — 384 dims)")
  (let [resp (sdk/embed :cohere
                        {:embed/model "embed-english-light-v3.0"
                         :embed/inputs ["smoke test"]
                         :embed/provider-options {:input-type "search_document"}})]
    (println "dims:" (:embed/dimensions resp))
    (check (= 384 (:embed/dimensions resp)) "light v3 dims = 384"))

  ;; ----------------------------------------------------------------
  ;; 2. Rerank — score 5 candidates, ask for top 3
  ;; ----------------------------------------------------------------
  (banner "rerank-english-v3.0")
  (let [resp (sdk/rerank :cohere
                         {:rerank/model "rerank-english-v3.0"
                          :rerank/query "Lisp dialect that runs on the JVM"
                          :rerank/documents
                          ["Python is a general-purpose language."
                           "JavaScript runs in browsers."
                           "Clojure is a Lisp dialect for the JVM."
                           "Rust is a memory-safe systems language."
                           "Common Lisp is a multi-paradigm Lisp dialect."]
                          :rerank/top-n 3
                          :rerank/return-documents true})]
    (println "provider:" (:rerank/provider resp))
    (println "model:" (:rerank/model resp))
    (println "results:")
    (doseq [r (:rerank/results resp)]
      (println (format "  idx=%d  score=%.4f  doc=%s"
                       (:rerank/index r)
                       (double (:rerank/score r))
                       (pr-str (:rerank/document r)))))
    (check (= 3 (count (:rerank/results resp))) "top-n = 3 honored")
    (check (= 2 (:rerank/index (first (:rerank/results resp))))
           "Clojure doc (idx 2) ranks #1 for 'Lisp on JVM' query"))

  (banner "rerank-multilingual-v3.0")
  (let [resp (sdk/rerank :cohere
                         {:rerank/model "rerank-multilingual-v3.0"
                          :rerank/query "Quel est le langage Lisp pour la JVM?"
                          :rerank/documents
                          ["Python est un langage généraliste."
                           "Clojure est un dialecte Lisp pour la JVM."
                           "Rust est un langage système."]
                          :rerank/top-n 2
                          :rerank/return-documents true})]
    (doseq [r (:rerank/results resp)]
      (println (format "  idx=%d  score=%.4f  doc=%s"
                       (:rerank/index r)
                       (double (:rerank/score r))
                       (pr-str (:rerank/document r)))))
    (check (= 1 (:rerank/index (first (:rerank/results resp))))
           "Clojure (idx 1) ranks #1 on French query"))

  ;; ----------------------------------------------------------------
  ;; 3. Chat — basic
  ;; ----------------------------------------------------------------
  (banner "chat command-r-08-2024 (basic)")
  (let [resp (sdk/complete :cohere
                           {:request/model "command-r-08-2024"
                            :request/messages
                            [{:message/role :system :message/content "You are concise."}
                             {:message/role :user :message/content "Name a Lisp dialect that runs on the JVM in one word."}]
                            :request/max-tokens 30})]
    (println "finish:" (:response/finish-reason resp))
    (println "text:" (some :text (:response/parts resp)))
    (println "usage:" (:response/usage resp))
    (check (= :cohere (:response/provider resp)) "chat provider :cohere")
    (check (= :stop (:response/finish-reason resp)) "finish-reason :stop")
    (check (pos? (get-in resp [:response/usage :usage/output-tokens])) "output tokens >0"))

  ;; ----------------------------------------------------------------
  ;; 4. Chat — streaming
  ;; ----------------------------------------------------------------
  (banner "chat streaming")
  (let [events (doall
                (sdk/complete :cohere
                              {:request/model "command-r-08-2024"
                               :request/messages
                               [{:message/role :user
                                 :message/content "Count from 1 to 5, comma-separated."}]
                               :request/max-tokens 30}
                              :stream? true))
        text (apply str
                    (keep (fn [e]
                            (when (= :stream/content-delta (:event/type e))
                              (:event/delta e)))
                          events))
        kinds (frequencies (map :event/type events))]
    (println "event-type counts:" kinds)
    (println "assembled text:" (pr-str text))
    (check (contains? kinds :stream/start) "got start event")
    (check (contains? kinds :stream/content-delta) "got content-delta events")
    (check (contains? kinds :stream/end) "got end event"))

  ;; ----------------------------------------------------------------
  ;; 5. Chat — RAG with documents + citation_options
  ;; ----------------------------------------------------------------
  (banner "chat with documents + citations")
  (let [resp (sdk/complete :cohere
                           {:request/model "command-r-08-2024"
                            :request/messages
                            [{:message/role :user
                              :message/content "Using the documents, when was Clojure released?"}]
                            :request/max-tokens 80
                            :request/provider-options
                            {:cohere
                             {:documents [{:id "doc1"
                                           :data {:title "Clojure"
                                                  :snippet "Clojure was first released by Rich Hickey in 2007."}}
                                          {:id "doc2"
                                           :data {:title "Lisp"
                                                  :snippet "Lisp was invented by John McCarthy in 1958."}}]
                              :citation_options {:mode "ACCURATE"}}}})
        citations (filter #(= :citation (:part/type %)) (:response/parts resp))]
    (println "text:" (some :text (:response/parts resp)))
    (println "citations:")
    (doseq [c citations]
      (println (format "  url=%s  title=%s  range=[%s..%s]  text=%s"
                       (:citation/url c)
                       (:citation/title c)
                       (:citation/start c)
                       (:citation/end c)
                       (pr-str (:citation/snippet c)))))
    (check (= :cohere (:response/provider resp)) "RAG: provider :cohere")
    (check (pos? (count citations)) "at least one citation surfaced"))

  ;; ----------------------------------------------------------------
  ;; 6. Chat — tool call (forced)
  ;; ----------------------------------------------------------------
  (banner "chat with forced tool call")
  (let [resp (sdk/complete :cohere
                           {:request/model "command-r-08-2024"
                            :request/messages
                            [{:message/role :user
                              :message/content "What's the weather in Tokyo?"}]
                            :request/tools
                            [{:type "function"
                              :function {:name "get_weather"
                                         :description "Get the current weather for a city"
                                         :parameters {:type "object"
                                                      :properties {:city {:type "string"}}
                                                      :required ["city"]}}}]
                            :request/tool-choice :required
                            :request/max-tokens 80})]
    (println "finish:" (:response/finish-reason resp))
    (doseq [tc (:response/tool-calls resp)]
      (println (format "  tool-call id=%s name=%s args=%s"
                       (:tool-call/id tc)
                       (:tool-call/name tc)
                       (:tool-call/arguments tc))))
    (check (= :tool-calls (:response/finish-reason resp)) "finish-reason :tool-calls")
    (check (seq (:response/tool-calls resp)) "tool_calls present")
    (check (= "get_weather" (:tool-call/name (first (:response/tool-calls resp))))
           "called get_weather"))

  (println)
  (println "ALL COHERE LIVE CHECKS PASSED ✓"))
