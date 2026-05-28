(ns llm.sdk.providers.bedrock.rerank
  "Bedrock Agent Runtime /rerank adapter.

   Canonical SDK rerank requests follow Cohere-style inputs:
   {model, query, documents, top-n}. Bedrock expects an Agent Runtime
   request containing queries, sources, and a Bedrock reranking
   configuration. The request is signed by llm.sdk.rerank via SigV4."
  (:require [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.providers.bedrock.converse]
            [llm.sdk.transport.rerank :as rt]))

(defn- aws-region []
  (or (System/getenv "AWS_REGION")
      (System/getenv "AWS_DEFAULT_REGION")
      "us-east-1"))

(defn- bedrock-agent-runtime-base-url []
  (str "https://bedrock-agent-runtime." (aws-region) ".amazonaws.com"))

(defn- source-for [doc]
  {:type "INLINE"
   :inlineDocumentSource
   (if (map? doc)
     {:type "JSON"
      :jsonDocument doc}
     {:type "TEXT"
      :textDocument {:text (str doc)}})})

(defn build-rerank-request-bedrock
  [_profile request]
  (let [documents (:rerank/documents request)
        top-n (or (:rerank/top-n request) (count documents))
        body {:queries [{:type "TEXT"
                         :textQuery {:text (:rerank/query request)}}]
              :rerankingConfiguration
              {:type "BEDROCK_RERANKING_MODEL"
               :bedrockRerankingConfiguration
               {:modelConfiguration {:modelArn (:rerank/model request)}
                :numberOfResults top-n}}
              :sources (mapv source-for documents)}]
    {:method :post
     :url (str (bedrock-agent-runtime-base-url) "/rerank")
     :headers {"Content-Type" "application/json"}
     :llm.sdk.providers.bedrock/aws-service "bedrock"
     :llm.sdk.providers.bedrock/aws-region (aws-region)
     :body body}))

(defn- ->int [x]
  (cond
    (int? x) x
    (number? x) (int x)
    :else 0))

(defn- normalize-usage [raw]
  (let [usage (:usage raw)
        total (+ (->int (:inputTokens usage))
                 (->int (:outputTokens usage)))]
    (cond-> {:usage/request-count 1
             :usage/provider-raw usage}
      (contains? usage :inputTokens)
      (assoc :usage/input-tokens (->int (:inputTokens usage)))
      (contains? usage :outputTokens)
      (assoc :usage/output-tokens (->int (:outputTokens usage)))
      (pos? total)
      (assoc :usage/total-tokens total))))

(defn parse-rerank-response-bedrock
  [_profile raw]
  (let [results (->> (:results raw)
                     (mapv (fn [r]
                             {:rerank/index (:index r)
                              :rerank/score (double (or (:relevanceScore r) 0.0))})))]
    (cond-> {:rerank/provider :bedrock
             :rerank/model nil
             :rerank/results results
             :rerank/raw raw}
      (:id raw) (assoc :rerank/id (:id raw))
      (:usage raw) (assoc :response/usage (normalize-usage raw)))))

(defn parse-rerank-error-bedrock
  [_profile status body]
  (errors/classify-error (Exception. "Bedrock rerank API error")
                         :status status
                         :body body
                         :provider :bedrock))

(defrecord BedrockRerankTransport []
  rt/RerankTransport
  (build-rerank-request [_ profile request]
    (build-rerank-request-bedrock profile request))
  (parse-rerank-response [_ profile raw]
    (parse-rerank-response-bedrock profile raw))
  (parse-rerank-error [_ profile status body]
    (parse-rerank-error-bedrock profile status body)))

(defn make-transport [] (->BedrockRerankTransport))

(when-let [p (provider/get-provider :bedrock)]
  (provider/register-provider
   (-> p
       (assoc :profile/rerank-transport-constructor make-transport)
       (update :profile/capabilities (fnil conj #{}) :rerank))))
