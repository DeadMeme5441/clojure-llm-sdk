(ns llm.sdk.providers.perplexity-test
  "Coverage for the Perplexity Agent API request, typed response, and SSE
   contracts. Live coverage lives in llm.sdk.live-perplexity-test."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.perplexity :as ppx]
            [llm.sdk.schema :as schema]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

(defn- build [request]
  (with-redefs [provider/resolve-auth-token (constantly "stub-token")]
    (transport/build-request (ppx/make-transport)
                             (provider/get-provider :perplexity)
                             request)))

(defn- parse-response [raw]
  (transport/parse-response (ppx/make-transport)
                            (provider/get-provider :perplexity)
                            raw))

(defn- parse-event [data]
  (transport/parse-stream-event
   (ppx/make-transport)
   (provider/get-provider :perplexity)
   (str "data: " (json/generate-string data))))

(defn- event-seq [event]
  (cond
    (nil? event) []
    (sequential? event) event
    :else [event]))

(deftest perplexity-agent-profile
  (let [profile (provider/get-provider :perplexity)]
    (is (= :perplexity-agent (:profile/protocol-family profile)))
    (is (= "https://api.perplexity.ai" (:profile/base-url profile)))
    (is (= ["PERPLEXITY_API_KEY"] (:profile/env-var-names profile)))
    (is (= :bearer (:profile/auth-strategy profile)))
    (is (false? (:profile/supports-model-listing profile)))
    (is (fn? (:profile/transport-constructor profile)))
    (is (every? (:profile/capabilities profile)
                [:tools :web-search :reasoning :json-schema :multimodal]))
    (is (every? (:profile/supported-params profile)
                [:request/tools :request/reasoning :request/max-tokens
                 :request/response-format]))
    (is (not (contains? (:profile/supported-params profile)
                        :request/tool-choice)))
    (is (not (contains? (:profile/supported-params profile)
                        :request/stop)))))

(deftest builds-current-agent-request
  (let [built
        (build
         {:request/model "perplexity/sonar"
          :request/messages
          [{:message/role :system :message/content "Be concise."}
           {:message/role :user
            :message/content
            [{:part/type :text :text "Inspect this image."}
             {:part/type :image
              :image/url "https://example.com/image.png"}]}]
          :request/tools
          [{:type :function
            :function {:name "lookup"
                       :description "Look up a record"
                       :parameters {:type "object"
                                    :properties {:id {:type "string"}}}
                       :strict true}}]
          :request/reasoning {:enabled true :effort :high}
          :request/temperature 0.2
          :request/top-p 0.8
          :request/max-tokens 250
          :request/response-format
          {:type :json_schema
           :name "answer"
           :description "A grounded answer"
           :strict true
           :json-schema {:type "object"
                         :properties {:answer {:type "string"}}
                         :required ["answer"]}}
          :request/stream? true
          :request/provider-options
          {:perplexity
           {:max-steps 3
            :language-preference "en"
            :previous-response-id "resp_previous"
            :store false
            :web-search
            {:filters {:search-domain-filter ["clojure.org"]
                       :search-recency-filter :month
                       :search-after-date-filter "01/01/2026"}
             :search-context-size :high
             :max-results 8
             :max-tokens 3000
             :max-tokens-per-page 800
             :user-location {:city "Boston" :country "US"}}}}})
        body (:body built)]
    (is (= :post (:method built)))
    (is (= "https://api.perplexity.ai/v1/agent" (:url built)))
    (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
    (is (= "perplexity/sonar" (:model body)))
    (is (= [{:type "message" :role "system" :content "Be concise."}
            {:type "message" :role "user"
             :content [{:type "input_text" :text "Inspect this image."}
                       {:type "input_image"
                        :image_url "https://example.com/image.png"}]}]
           (:input body)))
    (is (= {:type "function"
            :name "lookup"
            :description "Look up a record"
            :parameters {:type "object"
                         :properties {:id {:type "string"}}}
            :strict true}
           (first (:tools body))))
    (is (= {:type "web_search"
            :filters {:search_domain_filter ["clojure.org"]
                      :search_recency_filter "month"
                      :search_after_date_filter "01/01/2026"}
            :search_context_size "high"
            :max_results 8
            :max_tokens 3000
            :max_tokens_per_page 800
            :user_location {:city "Boston" :country "US"}}
           (second (:tools body))))
    (is (= {:effort "high"} (:reasoning body)))
    (is (= 250 (:max_output_tokens body)))
    (is (= "json_schema" (get-in body [:response_format :type])))
    (is (= {:type "object"
            :properties {:answer {:type "string"}}
            :required ["answer"]}
           (get-in body [:response_format :json_schema :schema])))
    (is (= 3 (:max_steps body)))
    (is (= "en" (:language_preference body)))
    (is (= "resp_previous" (:previous_response_id body)))
    (is (false? (:store body)))
    (is (true? (:stream body)))))

(deftest web-search-is-grounded-by-default-and-explicitly-disableable
  (let [request {:request/model "perplexity/sonar"
                 :request/messages
                 [{:message/role :user :message/content "What changed?"}]}
        default-body (:body (build request))
        disabled-body (:body (build (assoc request :request/provider-options
                                           {:perplexity {:web-search false}})))]
    (is (= [{:type "web_search"}] (:tools default-body)))
    (is (not (contains? disabled-body :tools)))))

(deftest explicit-model-fallback-chain-takes-precedence
  (let [body (:body
              (build
               {:request/model "perplexity/sonar"
                :request/messages
                [{:message/role :user :message/content "Answer."}]
                :request/provider-options
                {:perplexity
                 {:models ["perplexity/sonar"
                           "anthropic/claude-sonnet-4-6"]}}}))]
    (is (nil? (:model body)))
    (is (= ["perplexity/sonar" "anthropic/claude-sonnet-4-6"]
           (:models body)))))

(deftest function-call-state-replays-as-agent-input
  (let [call {:part/type :tool-call
              :tool-call/id "call_weather"
              :tool-call/name "weather"
              :tool-call/arguments "{\"city\":\"Paris\"}"
              :tool-call/provider-data
              {:id "fc_123"
               :call_id "call_weather"
               :status "completed"
               :thought_signature "opaque-signature"}}
        body (:body
              (build
               {:request/model "perplexity/sonar"
                :request/messages
                [{:message/role :assistant
                  :message/content [{:part/type :reasoning
                                     :reasoning/text "Need weather."}
                                    call]
                  :message/tool-calls [call]}
                 {:message/role :tool
                  :message/tool-call-id "call_weather"
                  :message/name "weather"
                  :message/content "{\"temperature\":18}"}]
                :request/provider-options
                {:perplexity {:web-search false}}}))]
    (is (= [{:type "function_call"
             :call_id "call_weather"
             :name "weather"
             :arguments "{\"city\":\"Paris\"}"
             :thought_signature "opaque-signature"}
            {:type "function_call_output"
             :call_id "call_weather"
             :name "weather"
             :output "{\"temperature\":18}"}]
           (:input body)))))

(deftest removed-sonar-options-fail-with-migration-guidance
  (let [request {:request/model "perplexity/sonar"
                 :request/messages
                 [{:message/role :user :message/content "Answer."}]}]
    (doseq [options [{:extra_body {:return_images true}}
                     {:perplexity {:disable_search true}}
                     {:perplexity {:search-mode "academic"}}
                     {:perplexity {:return-related-questions true}}
                     {:perplexity {:return-images true}}]]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"(obsolete|removed)"
           (build (assoc request :request/provider-options options)))))))

(deftest unsupported-agent-request-fields-fail-closed
  (let [request {:request/model "perplexity/sonar"
                 :request/messages
                 [{:message/role :user :message/content "Answer."}]}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"provider/model IDs"
         (build (assoc request :request/model "sonar"))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not accept tool_choice"
         (build (assoc request :request/tool-choice :required))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"does not accept stop sequences"
         (build (assoc request :request/stop ["END"]))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"must be :text or :json_schema"
         (build (assoc request :request/response-format
                       {:type :json_object}))))))

(deftest parses-typed-agent-response-with-citations-and-authoritative-cost
  (let [response (parse-response
                  (load-fixture "fixtures/perplexity_response.json"))
        citations (filterv #(= :citation (:part/type %))
                           (:response/parts response))]
    (is (= :perplexity (:response/provider response)))
    (is (= "perplexity/sonar" (:response/model response)))
    (is (= :stop (:response/finish-reason response)))
    (is (= "Clojure Programming Language Overview"
           (:citation/title (first citations))))
    (is (= "1" (:citation/source-id (first citations))))
    (is (= "2025-01-15" (:citation/date (first citations))))
    (is (= "2025-01-16" (:citation/last-updated (first citations))))
    (is (= 2 (count citations)))
    (is (= 9 (get-in response [:response/usage :usage/input-tokens])))
    (is (= 2 (get-in response [:response/usage
                               :usage/cached-input-tokens])))
    (is (= 1 (get-in response [:response/usage
                               :usage/cache-write-tokens])))
    (is (= 1 (get-in response [:response/usage :usage/search-queries])))
    (is (= 0.00503 (get-in response [:response/cost :cost/usd])))
    (is (false? (get-in response [:response/cost :cost/estimated?])))
    (is (= :perplexity-reported
           (get-in response [:response/cost :cost/pricing-source])))
    (is (= "completed"
           (get-in response [:response/provider-data :status])))
    (is (schema/validate-response response))))

(deftest parses-function-calls-and-preserves-replay-state
  (let [raw {:id "resp_tool"
             :object "response"
             :created_at 1789290000
             :status "completed"
             :model "anthropic/claude-sonnet-4-6"
             :output [{:type "reasoning"
                       :id "reasoning_1"
                       :status "completed"
                       :summary [{:type "summary_text"
                                  :text "Need current weather."}]}
                      {:type "function_call"
                       :id "fc_123"
                       :status "completed"
                       :name "weather"
                       :call_id "call_weather"
                       :arguments "{\"city\":\"Paris\"}"
                       :thought_signature "opaque-signature"}]
             :usage {:input_tokens 5 :output_tokens 3 :total_tokens 8}}
        response (parse-response raw)
        call (first (:response/tool-calls response))]
    (is (= :tool-calls (:response/finish-reason response)))
    (is (= "Need current weather."
           (:reasoning/text
            (first (filter #(= :reasoning (:part/type %))
                           (:response/parts response))))))
    (is (= "call_weather" (:tool-call/id call)))
    (is (= "fc_123" (get-in call [:tool-call/provider-data :id])))
    (is (= "opaque-signature"
           (get-in call [:tool-call/provider-data :thought_signature])))
    (is (= call
           (first (filter #(= :tool-call (:part/type %))
                          (:response/parts response)))))))

(deftest url-annotations-are-used-without-search-results
  (let [raw {:id "resp_annotation"
             :object "response"
             :created_at 1789290000
             :status "completed"
             :model "perplexity/sonar"
             :output
             [{:type "message" :id "msg_1" :status "completed"
               :role "assistant"
               :content
               [{:type "output_text" :text "Grounded answer.[1]"
                 :annotations
                 [{:type "url_citation"
                   :url "https://example.com/source"
                   :title "Source"
                   :start_index 16
                   :end_index 19}]}]}]}
        response (parse-response raw)
        citation (first (filter #(= :citation (:part/type %))
                                (:response/parts response)))]
    (is (= "https://example.com/source" (:citation/url citation)))
    (is (= [16 19] (:citation/text-range citation)))
    (is (= "url_citation"
           (get-in citation
                   [:citation/provider-data :perplexity/annotation :type])))
    (is (schema/validate-response response))))

(deftest incomplete-and-failed-lifecycle-states-are-not-success
  (let [base {:id "resp_lifecycle"
              :object "response"
              :created_at 1789290000
              :model "perplexity/sonar"
              :output [{:type "message" :id "msg_1" :status "incomplete"
                        :role "assistant"
                        :content [{:type "output_text" :text "Partial"}]}]}]
    (is (= :incomplete
           (:response/finish-reason
            (parse-response (assoc base :status "incomplete")))))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"quota exhausted"
         (parse-response (assoc base
                                :status "failed"
                                :error {:code "quota"
                                        :message "quota exhausted"}))))))

(deftest typed-sse-normalizes-text-search-tools-usage-and-cost
  (let [raw-response
        {:id "resp_stream"
         :object "response"
         :created_at 1789290000
         :status "completed"
         :model "perplexity/sonar"
         :output
         [{:type "search_results"
           :queries ["Clojure creator"]
           :results [{:id 1
                      :url "https://example.com/clojure"
                      :title "Clojure"
                      :snippet "Created by Rich Hickey"
                      :source "web"}]}
          {:type "function_call"
           :id "fc_1"
           :status "completed"
           :name "save"
           :call_id "call_save"
           :arguments "{\"answer\":\"Rich Hickey\"}"
           :thought_signature "sig"}]
         :usage
         {:input_tokens 10
          :output_tokens 4
          :total_tokens 14
          :tool_calls_details {:web_search {:invocation 1}}
          :cost {:currency "USD"
                 :input_cost 0.00001
                 :output_cost 0.000004
                 :tool_calls_cost 0.005
                 :total_cost 0.005014}}}
        wire-events
        [{:type "response.created" :sequence_number 0
          :response {:id "resp_stream" :object "response"
                     :created_at 1789290000 :status "in_progress"
                     :model "perplexity/sonar" :output []}}
         {:type "response.reasoning.started" :sequence_number 1
          :thought "Searching primary sources"}
         {:type "response.reasoning.search_results" :sequence_number 2
          :thought "Found the primary source"
          :results (get-in raw-response [:output 0 :results])}
         {:type "response.output_text.delta" :sequence_number 3
          :item_id "msg_1" :output_index 1 :content_index 0
          :delta "Rich Hickey created Clojure."}
         {:type "response.output_item.done" :sequence_number 4
          :output_index 2 :item (get-in raw-response [:output 1])}
         {:type "response.completed" :sequence_number 5
          :response raw-response}]
        events (into [] (mapcat #(event-seq (parse-event %))) wire-events)
        created-event (first events)
        response (stream/events->response events :perplexity "perplexity/sonar")
        citation (first (filter #(= :citation (:part/type %))
                                (:response/parts response)))
        call (first (:response/tool-calls response))]
    (is (= :stream/provider-state (:event/type created-event)))
    (is (= :perplexity (:provider-state/provider created-event)))
    (is (= (first wire-events)
           (get-in created-event [:provider-state/data :agent/events 0])))
    (is (empty? (filter #(= :stream/start (:event/type %)) events)))
    (is (= (first wire-events)
           (get-in response
                   [:response/provider-data :perplexity :agent/events 0])))
    (is (= "Searching primary sources"
           (:event/delta (first (filter #(= :stream/reasoning-delta
                                             (:event/type %))
                                        events)))))
    (is (= "https://example.com/clojure" (:citation/url citation)))
    (is (= "call_save" (:tool-call/id call)))
    (is (= "sig" (get-in call [:tool-call/provider-data
                                :thought_signature])))
    (is (= 1 (get-in response [:response/usage :usage/search-queries])))
    (is (= 0.005014 (get-in response [:response/cost :cost/usd])))
    (is (= :tool-calls (:response/finish-reason response)))
    (is (schema/validate-response response))
    (is (every? schema/validate-stream-event events))))

(deftest failed-stream-emits-an-error-not-an-end
  (let [event (parse-event
               {:type "response.failed"
                :sequence_number 2
                :error {:code "internal_error" :message "agent failed"}})]
    (is (= :stream/error (:event/type event)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (stream/events->response [event]
                                          :perplexity
                                          "perplexity/sonar")))))

(deftest parse-error-classifies-authentication-failure
  (let [error (transport/parse-error
               (ppx/make-transport)
               (provider/get-provider :perplexity)
               401
               {:error {:message "Bad key"}})]
    (is (= :auth (:error/reason error)))))
