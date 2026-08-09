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

(defn- bedrock-options [request]
  (get-in request [:rerank/provider-options :bedrock] {}))

(defn build-rerank-request-bedrock
  [_profile request]
  (let [documents (:rerank/documents request)
        top-n (or (:rerank/top-n request) (count documents))
        options (bedrock-options request)
        model-configuration
        (cond-> {:modelArn (:rerank/model request)}
          (seq (:additional-model-request-fields options))
          (assoc :additionalModelRequestFields
                 (:additional-model-request-fields options)))
        body (cond-> {:queries [{:type "TEXT"
                                 :textQuery {:text (:rerank/query request)}}]
                      :rerankingConfiguration
                      {:type "BEDROCK_RERANKING_MODEL"
                       :bedrockRerankingConfiguration
                       {:modelConfiguration model-configuration
                        :numberOfResults top-n}}
                      :sources (mapv source-for documents)}
               (:next-token options)
               (assoc :nextToken (:next-token options)))]
    {:method :post
     :url (str (bedrock-agent-runtime-base-url) "/rerank")
     :headers {"Content-Type" "application/json"}
     :llm.sdk.providers.bedrock/aws-service "bedrock"
     :llm.sdk.providers.bedrock/aws-region (aws-region)
     :body body}))


(defn- document->canonical [document]
  (case (:type document)
    "TEXT" (get-in document [:textDocument :text])
    "JSON" (:jsonDocument document)
    nil))

(defn parse-rerank-response-bedrock
  [_profile raw]
  (let [results (->> (:results raw)
                     (mapv (fn [r]
                             (let [document (document->canonical (:document r))]
                               (cond-> {:rerank/index (:index r)
                                        :rerank/score
                                        (double (or (:relevanceScore r) 0.0))}
                                 (some? document)
                                 (assoc :rerank/document document))))))]
    (cond-> {:rerank/provider :bedrock
             :rerank/model nil
             :rerank/results results
             :rerank/raw raw}
      (:nextToken raw) (assoc :rerank/next-token (:nextToken raw)))))

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
