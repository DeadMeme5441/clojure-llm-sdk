(ns llm.sdk.providers.perplexity.chat
  "Perplexity Agent API transport.
   Builds /v1/agent requests and normalizes typed output and SSE events while
   retaining the provider's response, tool-call, search, lifecycle, and billing
   state."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.sse :as sse]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as t]
            [llm.sdk.usage :as usage]))

(defn- wire-name [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    (nil? x) nil
    :else (str x)))

(defn- option-key [x]
  (some-> (wire-name x) (str/replace "_" "-") keyword))

(defn- integer-value [x]
  (when (number? x) (int x)))

(defn- web-search-invocations [raw]
  (let [details (:tool_calls_details raw)
        entry (or (get details :web_search)
                  (get details "web_search"))]
    (integer-value (:invocation entry))))

(defn- normalize-perplexity-usage [raw]
  (let [base (usage/normalize-openai-usage raw)
        details (:input_tokens_details raw)
        cache-read (integer-value (:cache_read_input_tokens details))
        cache-write (integer-value (:cache_creation_input_tokens details))
        total-input (usage/->int (:input_tokens raw))
        search-queries (web-search-invocations raw)]
    (cond-> (assoc base :usage/input-tokens
                   (max 0 (- total-input (or cache-read 0) (or cache-write 0))))
      (some? cache-read) (assoc :usage/cached-input-tokens cache-read)
      (some? cache-write) (assoc :usage/cache-write-tokens cache-write)
      (some? search-queries) (assoc :usage/search-queries search-queries)
      (some? (:reasoning_tokens raw))
      (assoc :usage/reasoning-tokens (usage/->int (:reasoning_tokens raw))))))

(defn- reported-cost [usage-raw]
  (let [cost (:cost usage-raw)]
    (when (number? (:total_cost cost))
      {:cost/usd (:total_cost cost)
       :cost/estimated? false
       :cost/pricing-source :perplexity-reported
       :cost/source-url "https://docs.perplexity.ai/api-reference/agent-post"
       :cost/breakdown cost})))

;; ---------------------------------------------------------------------------
;; Citations
;; ---------------------------------------------------------------------------

(defn- search-result->citation [result]
  (cond-> {:part/type :citation
           :citation/url (:url result)
           :citation/provider-data {:perplexity/search-result result}}
    (:title result) (assoc :citation/title (:title result))
    (:snippet result) (assoc :citation/snippet (:snippet result))
    (some? (:id result)) (assoc :citation/source-id (str (:id result)))
    (:date result) (assoc :citation/date (:date result))
    (:last_updated result) (assoc :citation/last-updated (:last_updated result))
    (:source result) (assoc :citation/source (:source result))))

(defn- annotation->citation [annotation]
  (cond-> {:part/type :citation
           :citation/url (:url annotation)
           :citation/provider-data {:perplexity/annotation annotation}}
    (:title annotation) (assoc :citation/title (:title annotation))
    (and (int? (:start_index annotation)) (int? (:end_index annotation)))
    (assoc :citation/text-range [(:start_index annotation)
                                 (:end_index annotation)])))

(defn- output-items [raw]
  (if (sequential? (:output raw)) (:output raw) []))

(defn- search-results [raw]
  (into []
        (comp (filter #(= "search_results" (wire-name (:type %))))
              (mapcat #(or (:results %) [])))
        (output-items raw)))

(defn- message-annotations [raw]
  (into []
        (comp (filter #(= "message" (wire-name (:type %))))
              (mapcat #(or (:content %) []))
              (mapcat #(or (:annotations %) []))
              (filter #(and (= "url_citation" (wire-name (:type %)))
                            (string? (:url %)))))
        (output-items raw)))

(defn extract-citation-parts
  "Return Agent API citations. Search-result output is authoritative and
   carries stable numeric IDs; URL annotations are used when no search-result
   output is present."
  [raw]
  (let [results (search-results raw)]
    (if (seq results)
      (mapv search-result->citation results)
      (mapv annotation->citation (message-annotations raw)))))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(def ^:private accepted-native-options
  #{:web-search :max-steps :language-preference :models
    :previous-response-id :store})

(def ^:private removed-sonar-options
  #{:disable-search :enable-search-classifier :search-mode :search-type
    :return-related-questions :search-language-filter :stream-mode
    :return-images :num-images :image-domain-filter :image-format-filter
    :image-results-enhanced-relevance
    :return-videos :num-videos :media-response :web-search-options
    :search-domain-filter :search-recency-filter :search-after-date-filter
    :search-before-date-filter :last-updated-after-filter
    :last-updated-before-filter :num-search-results :reasoning-effort})

(def ^:private search-option-keys
  #{:filters :search-context-size :max-results :max-tokens
    :max-tokens-per-page :user-location})

(def ^:private search-filter-keys
  #{:search-domain-filter :search-recency-filter :search-after-date-filter
    :search-before-date-filter :last-updated-after-filter
    :last-updated-before-filter})

(def ^:private location-keys
  #{:city :country :region :latitude :longitude})

(defn- unsupported-option! [option message]
  (throw (ex-info message
                  {:provider :perplexity
                   :option option
                   :accepted-options accepted-native-options
                   :error/type :provider/unsupported-option})))

(defn- removed-option-message [raw-key option]
  (let [prefix (str "Perplexity Sonar option " (pr-str raw-key)
                    " was removed by the Agent API; ")]
    (str
     prefix
     (cond
       (= :disable-search option)
       "use :request/provider-options {:perplexity {:web-search false}}"

       (contains? search-filter-keys option)
       "move it under :perplexity :web-search :filters"

       (= :num-search-results option)
       "use :perplexity :web-search :max-results"

       (= :reasoning-effort option)
       "use canonical :request/reasoning {:effort ...}"

       (contains? #{:return-images :num-images :image-domain-filter
                    :image-format-filter :image-results-enhanced-relevance
                    :return-videos :num-videos :media-response
                    :search-language-filter :stream-mode}
                  option)
       "there is no Agent API equivalent, so remove it"

       (= :return-related-questions option)
       "request follow-up questions in the prompt or a JSON schema"

       :else
       "use the explicit :perplexity :web-search and :max-steps options"))))

(defn- reject-option-keys! [options accepted context]
  (doseq [raw-key (keys options)
          :let [k (option-key raw-key)]
          :when (not (contains? accepted k))]
    (unsupported-option!
     raw-key
     (if (contains? removed-sonar-options k)
       (removed-option-message raw-key k)
       (str "Unsupported Perplexity Agent API " context " option "
            (pr-str raw-key))))))

(defn- normalized-map [m]
  (into {} (map (fn [[k v]] [(option-key k) v])) m))

(defn- wire-enum [x]
  (if (keyword? x) (name x) x))

(defn- bounded-integer! [option value minimum maximum]
  (when-not (and (int? value) (<= minimum value maximum))
    (unsupported-option!
     option
     (str "Perplexity " option " must be an integer from "
          minimum " through " maximum))))

(defn- positive-integer! [option value]
  (when-not (and (int? value) (pos? value))
    (unsupported-option!
     option
     (str "Perplexity " option " must be a positive integer"))))

(defn- search-filters->wire [filters]
  (when-not (map? filters)
    (unsupported-option! :filters "Perplexity web-search :filters must be a map"))
  (reject-option-keys! filters search-filter-keys "web-search filter")
  (let [filters (normalized-map filters)
        recency (:search-recency-filter filters)]
    (when (and recency
               (not (contains? #{:hour :day :week :month :year}
                               (option-key recency))))
      (unsupported-option!
       :search-recency-filter
       "Perplexity search recency must be hour, day, week, month, or year"))
    (reduce-kv
     (fn [result k value]
       (assoc result
              (keyword (str/replace (name k) "-" "_"))
              (wire-enum value)))
     {}
     filters)))

(defn- location->wire [location]
  (when-not (map? location)
    (unsupported-option! :user-location
                         "Perplexity web-search :user-location must be a map"))
  (reject-option-keys! location location-keys "user-location")
  (normalized-map location))

(defn- web-search->wire [config]
  (cond
    (or (nil? config) (true? config)) {:type "web_search"}
    (false? config) nil
    (map? config)
    (let [config (normalized-map config)
          context-size (some-> (:search-context-size config) option-key)]
      (reject-option-keys! config search-option-keys "web-search")
      (when (and context-size
                 (not (contains? #{:low :medium :high} context-size)))
        (unsupported-option!
         :search-context-size
         "Perplexity search context size must be low, medium, or high"))
      (when (some? (:max-results config))
        (bounded-integer! :max-results (:max-results config) 1 50))
      (when (some? (:max-tokens config))
        (positive-integer! :max-tokens (:max-tokens config)))
      (when (some? (:max-tokens-per-page config))
        (positive-integer! :max-tokens-per-page
                           (:max-tokens-per-page config)))
      (cond-> {:type "web_search"}
        (:filters config) (assoc :filters
                                 (search-filters->wire (:filters config)))
        context-size
        (assoc :search_context_size (name context-size))
        (some? (:max-results config))
        (assoc :max_results (:max-results config))
        (some? (:max-tokens config))
        (assoc :max_tokens (:max-tokens config))
        (some? (:max-tokens-per-page config))
        (assoc :max_tokens_per_page (:max-tokens-per-page config))
        (:user-location config)
        (assoc :user_location (location->wire (:user-location config)))))
    :else
    (unsupported-option!
     :web-search
     "Perplexity :web-search must be false, true, or a configuration map")))

(defn- provider-options [request]
  (let [all (or (:request/provider-options request) {})]
    (when-not (map? all)
      (unsupported-option! :request/provider-options
                           "Perplexity provider options must be a map"))
    (when (or (contains? all :extra_body) (contains? all "extra_body"))
      (unsupported-option!
       :extra_body
       "Perplexity Sonar extra_body is obsolete; use explicit :perplexity Agent API options"))
    (let [nested (or (:perplexity all) (get all "perplexity") {})
          unrelated (apply dissoc all [:perplexity "perplexity"])]
      (when (seq unrelated)
        (reject-option-keys! unrelated #{} "provider"))
      (when-not (map? nested)
        (unsupported-option! :perplexity
                             "Perplexity provider options must be a map"))
      (let [options (normalized-map nested)]
        (reject-option-keys! options accepted-native-options "request")
        options))))

(defn- unsupported-content-part! [part]
  (throw (ex-info "Content part is not supported by the Perplexity Agent API"
                  {:provider :perplexity
                   :part/type (:part/type part)
                   :error/type :provider/unsupported-content-part})))

(defn- content-part->input [part]
  (case (:part/type part)
    :text {:type "input_text" :text (:text part)}
    :image
    (let [url (or (:image/url part)
                  (when-let [data (:image/data part)]
                    (str "data:" (or (:image/mime-type part) "image/png")
                         ";base64," data)))]
      (when-not (string? url)
        (throw (ex-info "Perplexity input images require :image/url or :image/data"
                        {:provider :perplexity
                         :error/type :request/missing-image-source})))
      {:type "input_image" :image_url url})
    (:reasoning :tool-call) nil
    (unsupported-content-part! part)))

(defn- message-content->input [content]
  (cond
    (string? content) content
    (nil? content) ""
    (sequential? content) (into [] (keep content-part->input) content)
    :else (throw (ex-info "Perplexity message content must be text or typed parts"
                          {:provider :perplexity
                           :error/type :request/invalid-message-content}))))

(defn- message-tool-calls [message]
  (->> (concat (:message/tool-calls message)
               (t/extract-tool-calls-from-parts (:message/content message)))
       distinct
       vec))

(defn- arguments-string [arguments]
  (cond
    (string? arguments) arguments
    (nil? arguments) ""
    :else (json/generate-string arguments)))

(defn- tool-call->input [call]
  (let [native (:tool-call/provider-data call)
        call-id (or (:call_id native) (:tool-call/id call))]
    (cond-> {:type "function_call"
             :call_id call-id
             :name (:tool-call/name call)
             :arguments (arguments-string (:tool-call/arguments call))}
      (:thought_signature native)
      (assoc :thought_signature (:thought_signature native)))))

(defn- tool-result-part->input [part]
  {:type "function_call_output"
   :call_id (:tool-result/id part)
   :name (:tool-result/name part)
   :output (:tool-result/content part)})

(defn- tool-message->input [message]
  (let [results (when (sequential? (:message/content message))
                  (filter #(= :tool-result (:part/type %))
                          (:message/content message)))]
    (if (seq results)
      (mapv tool-result-part->input results)
      (let [call-id (:message/tool-call-id message)]
        (when-not (and (string? call-id) (seq (str/trim call-id)))
          (throw (ex-info "Perplexity tool results require :message/tool-call-id"
                          {:provider :perplexity
                           :error/type :request/missing-tool-call-id})))
        [(cond-> {:type "function_call_output"
                  :call_id call-id
                  :output (or (t/content->string (:message/content message)) "")}
           (:message/name message) (assoc :name (:message/name message)))]))))

(defn- message->input-items [message]
  (case (:message/role message)
    :tool (tool-message->input message)
    :assistant
    (let [content (message-content->input (:message/content message))
          calls (message-tool-calls message)
          message-item (when (or (string? content) (seq content))
                         {:type "message" :role "assistant" :content content})]
      (cond-> [] message-item (conj message-item) (seq calls) (into (map tool-call->input calls))))
    (:system :developer :user)
    [{:type "message"
      :role (name (:message/role message))
      :content (message-content->input (:message/content message))}]
    (throw (ex-info "Unsupported Perplexity message role"
                    {:provider :perplexity
                     :message/role (:message/role message)
                     :error/type :request/unsupported-role}))))

(defn- messages->input [messages]
  (into [] (mapcat message->input-items) messages))

(defn- function-tool->wire [tool]
  (case (:type tool)
    :function
    (let [function (:function tool)]
      (cond-> {:type "function" :name (:name function)}
        (:description function) (assoc :description (:description function))
        (:parameters function) (assoc :parameters (:parameters function))
        (contains? function :strict) (assoc :strict (:strict function))))
    (throw (ex-info "Perplexity Agent API supports canonical function tools only"
                    {:provider :perplexity
                     :tool/type (:type tool)
                     :error/type :provider/unsupported-tool-type}))))

(defn- response-format->wire [format]
  (case (:type format)
    :text nil
    :json_schema
    (do
      (when-not (map? (:json-schema format))
        (unsupported-option!
         :request/response-format
         "Perplexity JSON schema responses require :json-schema"))
      {:type "json_schema"
       :json_schema
       (cond-> {:name (or (:name format) "response")
                :schema (:json-schema format)}
         (:description format) (assoc :description (:description format))
         (contains? format :strict) (assoc :strict (:strict format)))})
    (throw (ex-info "Perplexity Agent API response format must be :text or :json_schema"
                    {:provider :perplexity
                     :response-format/type (:type format)
                     :error/type :provider/unsupported-response-format}))))

(defn- reasoning->wire [reasoning]
  (when reasoning
    (when (some #(contains? reasoning %) [:budget :exclude :summary])
      (unsupported-option!
       :request/reasoning
       "Perplexity Agent reasoning supports :enabled and :effort only"))
    (let [enabled? (not= false (:enabled reasoning))
          effort (:effort reasoning)]
      (when (and enabled? (= :none effort))
        (unsupported-option!
         :request/reasoning
         "Perplexity Agent reasoning effort :none is unsupported; use :enabled false"))
      (when (and enabled? effort) {:effort (wire-enum effort)}))))

(defn- validate-model! [model]
  (when-not (and (string? model)
                 (re-matches #"[^/]+/.+" model))
    (throw (ex-info
            "Perplexity Agent API models use provider/model IDs (for example perplexity/sonar)"
            {:provider :perplexity
             :model model
             :error/type :request/invalid-agent-model}))))

(defn- validate-models! [models]
  (when-not (and (vector? models) (<= 1 (count models) 5))
    (unsupported-option! :models
                         "Perplexity :models must contain one to five model IDs"))
  (doseq [model models] (validate-model! model)))

(defn- validate-generation-values! [request]
  (when (some? (:request/max-tokens request))
    (positive-integer! :request/max-tokens (:request/max-tokens request)))
  (when-let [temperature (:request/temperature request)]
    (when-not (and (number? temperature) (<= 0 temperature 2))
      (unsupported-option!
       :request/temperature
       "Perplexity temperature must be between 0 and 2")))
  (when-let [top-p (:request/top-p request)]
    (when-not (and (number? top-p) (<= 0 top-p 1))
      (unsupported-option!
       :request/top-p
       "Perplexity top-p must be between 0 and 1"))))

(defn- validate-native-values! [options]
  (when (some? (:max-steps options))
    (bounded-integer! :max-steps (:max-steps options) 1 100))
  (when (and (contains? options :store)
             (not (boolean? (:store options))))
    (unsupported-option! :store "Perplexity :store must be boolean"))
  (when (and (:language-preference options)
             (not (and (string? (:language-preference options))
                       (re-matches #"[A-Za-z]{2}"
                                   (:language-preference options)))))
    (unsupported-option!
     :language-preference
     "Perplexity :language-preference must be an ISO 639-1 code"))
  (when (and (:previous-response-id options)
             (not (and (string? (:previous-response-id options))
                       (seq (str/trim (:previous-response-id options))))))
    (unsupported-option!
     :previous-response-id
     "Perplexity :previous-response-id must be a non-empty string")))

(defn build-request-perplexity [profile request]
  (let [model (:request/model request)
        _ (validate-model! model)
        _ (when (:request/tool-choice request)
            (unsupported-option!
             :request/tool-choice
             "Perplexity Agent API does not accept tool_choice"))
        _ (when (:request/stop request)
            (unsupported-option!
             :request/stop
             "Perplexity Agent API does not accept stop sequences"))
        options (provider-options request)
        models (:models options)
        _ (when models (validate-models! models))
        _ (validate-native-values! options)
        _ (validate-generation-values! request)
        web-search (web-search->wire
                    (if (contains? options :web-search)
                      (:web-search options)
                      true))
        function-tools (mapv function-tool->wire (:request/tools request))
        tools (cond-> function-tools web-search (conj web-search))
        reasoning (reasoning->wire (:request/reasoning request))
        response-format (when-let [format (:request/response-format request)]
                          (response-format->wire format))
        body (cond-> {:model model
                      :input (messages->input (:request/messages request))}
               models (dissoc :model)
               models (assoc :models models)
               (seq tools) (assoc :tools tools)
               (some? (:request/temperature request))
               (assoc :temperature (:request/temperature request))
               (some? (:request/top-p request))
               (assoc :top_p (:request/top-p request))
               (:request/max-tokens request)
               (assoc :max_output_tokens (:request/max-tokens request))
               reasoning (assoc :reasoning reasoning)
               response-format (assoc :response_format response-format)
               (:request/stream? request) (assoc :stream true)
               (some? (:max-steps options)) (assoc :max_steps (:max-steps options))
               (:language-preference options)
               (assoc :language_preference (:language-preference options))
               (:previous-response-id options)
               (assoc :previous_response_id (:previous-response-id options))
               (contains? options :store) (assoc :store (:store options)))]
    {:method :post
     :url (str (:profile/base-url profile) "/v1/agent")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body body}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- output-text-parts [item]
  (into []
        (comp (filter #(= "output_text" (wire-name (:type %))))
              (keep #(when (string? (:text %))
                       {:part/type :text :text (:text %)})))
        (:content item)))

(defn- reasoning-text [item]
  (let [join-text (fn [entries]
                    (when (sequential? entries)
                      (not-empty (apply str (keep :text entries)))))]
    (or (join-text (:summary item))
        (join-text (:content item))
        (when (string? (:thought item)) (not-empty (:thought item)))
        (when (string? (:text item)) (not-empty (:text item))))))

(defn- completed-item? [item]
  (not (contains? #{"failed" "incomplete" "in_progress" "queued" "cancelled"}
                  (wire-name (:status item)))))

(defn- function-call->part [item output-index]
  (when (completed-item? item)
    (let [call-id (or (:call_id item) (:id item) (str "tool_call_" output-index))]
      {:part/type :tool-call
       :tool-call/id call-id
       :tool-call/name (or (:name item) "")
       :tool-call/arguments (arguments-string (:arguments item))
       :tool-call/provider-data
       (assoc (select-keys item [:id :status :call_id :thought_signature :type])
              :output_index output-index)})))

(defn- output-item->parts [output-index item]
  (case (wire-name (:type item))
    "message" (output-text-parts item)
    "function_call" (if-let [call (function-call->part item output-index)] [call] [])
    "reasoning" (if-let [text (reasoning-text item)]
                  [(cond-> {:part/type :reasoning :reasoning/text text}
                     (:thought_signature item)
                     (assoc :reasoning/signature (:thought_signature item)))]
                  [])
    "search_results" (mapv search-result->citation (:results item))
    []))

(defn- response-tool-calls [parts]
  (into [] (filter #(= :tool-call (:part/type %))) parts))

(defn- response-status [raw]
  (wire-name (:status raw)))

(defn- item-incomplete? [item]
  (contains? #{"failed" "incomplete" "in_progress" "queued" "cancelled"}
             (wire-name (:status item))))

(defn- finish-reason [raw tool-calls]
  (let [status (response-status raw)]
    (cond
      (or (= "incomplete" status)
          (some item-incomplete? (output-items raw))) :incomplete
      (and (= "completed" status) (seq tool-calls)) :tool-calls
      (= "completed" status) :stop
      :else :unknown)))

(defn- lifecycle-error! [raw partial]
  (let [status (response-status raw)
        error (:error raw)]
    (throw (ex-info (or (:message error)
                        (str "Perplexity Agent response " status))
                    {:provider :perplexity
                     :response/status status
                     :error/type :provider/response-failed
                     :error error
                     :partial-response partial
                     :response/raw raw}))))

(defn parse-response-perplexity [_profile raw]
  (let [typed-parts (into []
                          (mapcat (fn [[index item]]
                                    (output-item->parts index item)))
                          (map-indexed vector (output-items raw)))
        annotation-parts (when-not (seq (search-results raw))
                           (extract-citation-parts raw))
        parts (into typed-parts annotation-parts)
        tool-calls (response-tool-calls parts)
        usage-raw (:usage raw)
        cost (reported-cost usage-raw)
        provider-data (cond-> {:status (:status raw)
                               :object (:object raw)
                               :created_at (:created_at raw)
                               :output_items (output-items raw)}
                        (:error raw) (assoc :error (:error raw)))
        response (cond-> {:response/id (:id raw)
                          :response/provider :perplexity
                          :response/model (:model raw)
                          :response/parts parts
                          :response/finish-reason (finish-reason raw tool-calls)
                          :response/provider-data provider-data
                          :response/raw raw}
                   (seq tool-calls) (assoc :response/tool-calls tool-calls)
                   usage-raw (assoc :response/usage
                                    (normalize-perplexity-usage usage-raw))
                   cost (assoc :response/cost cost))
        status (response-status raw)]
    (if (contains? #{"failed" "cancelled" "queued" "in_progress"} status)
      (lifecycle-error! raw response)
      response)))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(defn- parse-sse-line [line]
  (sse/parse-json-data line))

(defn- maybe-many [events]
  (let [events (vec (remove nil? events))]
    (case (count events)
      0 nil
      1 (first events)
      events)))

(defn- provider-state [data]
  (stream/provider-state-event
   :perplexity
   {:agent/events {(or (:sequence_number data) (:type data)) data}}))

(defn- citation-events [results]
  (mapv (fn [result]
          (stream/citation-event
           (:url result)
           :title (:title result)
           :snippet (:snippet result)
           :source-id (some-> (:id result) str)
           :date (:date result)
           :last-updated (:last_updated result)
           :source (:source result)
           :provider-data {:perplexity/search-result result}))
        results))

(defn- function-call-events [data]
  (let [item (:item data)
        index (int (or (:output_index data) 0))]
    (when (completed-item? item)
      [(stream/tool-call-start
        index
        (or (:call_id item) (:id item) (str "tool_call_" index))
        (or (:name item) "")
        :provider-data
        (assoc (select-keys item [:id :status :call_id :thought_signature :type])
               :output_index index))
       (stream/tool-call-delta index (arguments-string (:arguments item)))
       (stream/tool-call-end index)])))

(defn- completed-events [data]
  (let [response (:response data)
        status (response-status response)
        tool-calls (keep-indexed
                    (fn [index item]
                      (when (= "function_call" (wire-name (:type item)))
                        (function-call->part item index)))
                    (output-items response))
        finish (finish-reason response tool-calls)
        usage-raw (:usage response)]
    (if (contains? #{"failed" "cancelled"} status)
      [(stream/error-event
        {:error/type :provider
         :error/message (or (get-in response [:error :message])
                            (str "Perplexity Agent response " status))
         :error/raw response})]
      (cond-> [(provider-state data)]
        usage-raw
        (conj (stream/usage-event
               (normalize-perplexity-usage usage-raw)
               :cost (reported-cost usage-raw)))
        true (conj (stream/end-event :finish-reason finish))))))

(defn parse-stream-event-perplexity [_profile line]
  (when-let [data (parse-sse-line line)]
    (let [event-type (wire-name (:type data))
          thought (:thought data)]
      (case event-type
        "response.created" (provider-state data)

        "response.in_progress" (provider-state data)
        "response.output_text.delta" (stream/content-delta (:delta data))
        "response.output_text.done" (provider-state data)

        "response.output_item.added" (provider-state data)

        "response.output_item.done"
        (let [item (:item data)]
          (case (wire-name (:type item))
            "function_call"
            (maybe-many (concat (function-call-events data)
                                [(provider-state data)]))
            "search_results" (provider-state data)
            (provider-state data)))

        "response.reasoning.started"
        (maybe-many [(when (string? thought) (stream/reasoning-delta thought))
                     (provider-state data)])
        "response.reasoning.search_queries"
        (maybe-many [(when (string? thought) (stream/reasoning-delta thought))
                     (provider-state data)])
        "response.reasoning.search_results"
        (maybe-many (concat [(when (string? thought)
                               (stream/reasoning-delta thought))]
                            (citation-events (:results data))
                            [(provider-state data)]))
        "response.reasoning.fetch_url_queries"
        (maybe-many [(when (string? thought) (stream/reasoning-delta thought))
                     (provider-state data)])
        "response.reasoning.fetch_url_results"
        (maybe-many [(when (string? thought) (stream/reasoning-delta thought))
                     (provider-state data)])
        "response.reasoning.stopped"
        (maybe-many [(when (string? thought) (stream/reasoning-delta thought))
                     (provider-state data)])

        "response.completed" (maybe-many (completed-events data))
        "response.failed"
        (stream/error-event
         {:error/type :provider
          :error/message (or (get-in data [:error :message])
                             "Perplexity Agent response failed")
          :error/raw data})
        nil))))

;; ---------------------------------------------------------------------------
;; Transport and errors
;; ---------------------------------------------------------------------------

(defn parse-error-perplexity [_profile status body]
  (errors/classify-api-error :perplexity "Perplexity" status body))

(defrecord PerplexityTransport []
  t/Transport
  (build-request [_this profile request]
    (build-request-perplexity profile request))
  (parse-response [_this profile raw]
    (parse-response-perplexity profile raw))
  (parse-stream-event [_this profile line]
    (parse-stream-event-perplexity profile line))
  (parse-error [_this profile status body]
    (parse-error-perplexity profile status body))
  (normalize-usage [_this _profile raw]
    (normalize-perplexity-usage raw))
  (request-capabilities [_]
    #{:chat :streaming :tools :json-schema :web-search :reasoning :multimodal}))

(defn make-transport [] (->PerplexityTransport))

(defn perplexity-cost-calculator
  "Estimate from catalog pricing when an Agent response did not report its
   authoritative usage.cost. Search-query pricing is added when present."
  [{:keys [_provider _model usage pricing]}]
  (let [base ((requiring-resolve 'llm.sdk.pricing/estimate-cost) usage pricing)
        search-queries (or (:usage/search-queries usage)
                           (get-in usage [:usage/provider-raw
                                          :tool_calls_details
                                          :web_search
                                          :invocation])
                           0)
        per-call (some-> pricing :search-cost-per-call bigdec)
        addend (if (and per-call (pos? search-queries))
                 (.multiply (bigdec search-queries) per-call)
                 0M)
        search-only-cost? (and per-call
                               (pos? (.signum ^java.math.BigDecimal addend))
                               (nil? (:cost/amount-usd base)))
        amount (cond
                 (and (:cost/amount-usd base)
                      (pos? (.signum ^java.math.BigDecimal addend)))
                 (.add ^java.math.BigDecimal
                       (bigdec (:cost/amount-usd base)) addend)
                 (:cost/amount-usd base) (:cost/amount-usd base)
                 (pos? (.signum ^java.math.BigDecimal addend)) addend
                 :else nil)
        status (if (or (= :actual (:cost/status base)) search-only-cost?)
                 :actual
                 (:cost/status base))]
    (-> base
        (assoc :cost/amount-usd amount :cost/status status)
        (update :cost/notes (fnil conj [])
                (str "Perplexity search queries: " search-queries
                     (when per-call (str " @ $" per-call " each")))))))

(when-let [p (provider/get-provider :perplexity)]
  (provider/register-provider
   (assoc p
          :profile/transport-constructor make-transport
          :profile/cost-calculator perplexity-cost-calculator)))
