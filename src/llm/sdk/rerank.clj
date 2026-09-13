(ns llm.sdk.rerank
  "Driver for rerank requests — the rerank counterpart to sdk/complete,
   sdk/embed, and sdk/moderate.

   Resolves the profile, picks up its
   :profile/rerank-transport-constructor, builds and sends the request,
   returns a canonical RerankResponse. Providers without rerank
   support throw a clear ex-info."
  (:require [llm.sdk.aws-sigv4 :as aws-sigv4]
            [llm.sdk.operation :as operation]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.rerank :as rt]))

(defn- attach-request-documents [request parsed]
  (case (:rerank/return-documents request)
    true
    (update parsed :rerank/results
            (fn [results]
              (mapv (fn [result]
                      (if (contains? result :rerank/document)
                        result
                        (if-some [document
                                  (get (:rerank/documents request)
                                       (:rerank/index result))]
                          (assoc result :rerank/document document)
                          result)))
                    results)))

    false
    (update parsed :rerank/results
            (fn [results]
              (mapv #(dissoc % :rerank/document) results)))

    parsed))

(defn- stamp-rerank-cost [provider-id parsed]
  (if (contains? parsed :response/cost)
    parsed
    (let [usage (:response/usage parsed)
          pricing (pricing/get-pricing provider-id (:rerank/model parsed))
          result (when usage (pricing/rerank-cost usage pricing))
          cost
          (when result
            (pricing/cost-result->canonical
             result
             pricing
             (cond-> {}
               (contains? usage :usage/search-units)
               (assoc :search-units (:usage/search-units usage))
               (:rerank-cost-per-search-unit pricing)
               (assoc :rerank-cost-per-search-unit
                      (:rerank-cost-per-search-unit pricing))
               (contains? usage :usage/input-tokens)
               (assoc :input-tokens (:usage/input-tokens usage))
               (contains? usage :usage/output-tokens)
               (assoc :output-tokens (:usage/output-tokens usage)))))]
      (cond-> parsed
        cost (assoc :response/cost cost)))))

(defn rerank
  "Send a canonical RerankRequest, return a RerankResponse.

   Required keys: :rerank/model, :rerank/query, :rerank/documents.
   Documents are strings or maps; individual providers may require strings.
   Optional: :rerank/top-n, :rerank/return-documents,
   :rerank/next-token, :rerank/provider-options."
  [provider-id request & {:keys [config]}]
  (let [parsed
        (operation/run
         {:provider-id provider-id
          :request request
          :config config
          :validate-request schema/validate-rerank-request
          :explain-request schema/explain-rerank-request
          :invalid-error-type :schema/invalid-rerank-request
          :invalid-message "Invalid llm.sdk rerank request"
          :constructor-key :profile/rerank-transport-constructor
          :unsupported-message "Rerank not supported by provider"
          :build-request rt/build-rerank-request
          :sign-request aws-sigv4/maybe-sign
          :parse-response
          (fn [transport profile response]
            (rt/parse-rerank-response transport profile (:body response)))
          :parse-error rt/parse-rerank-error
          :transport-error-message "Provider rerank transport error"
          :api-error-message "Provider rerank API error"})
        parsed (update parsed :rerank/model
                       #(or % (:rerank/model request)))]
    (->> parsed
         (attach-request-documents request)
         (stamp-rerank-cost provider-id))))
