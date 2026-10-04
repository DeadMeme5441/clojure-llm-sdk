(ns llm.sdk.decide
  "Synchronous typed decisions through the shared operation lifecycle."
  (:require [llm.sdk.operation :as operation]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.decide :as dt]))

(defn decide
  "Evaluate :decision/state against :decision/questions with :decision/model.
   Accepts :config for per-call credentials and HTTP settings."
  [provider-id request & {:keys [config]}]
  (let [parsed
        (operation/run
         {:provider-id provider-id
          :request request
          :config config
          :validate-request schema/validate-decision-request
          :explain-request schema/explain-decision-request
          :invalid-error-type :schema/invalid-decision-request
          :invalid-message "Invalid llm.sdk decision request"
          :constructor-key :profile/decision-transport-constructor
          :unsupported-message "Decisions not supported by provider"
          :build-request dt/build-decision-request
          :parse-response
          (fn [transport profile response]
            (dt/parse-decision-response transport profile (:body response)))
          :parse-error dt/parse-decision-error
          :transport-error-message "Provider decision transport error"
          :api-error-message "Provider decision API error"})
        questions (:decision/questions request)
        answers (:decision/answers parsed)]
    (when-not (and (= (count questions) (count answers))
                   (every? (fn [[id question]]
                             (= (:type question) (:type (get answers id))))
                           questions))
      (throw (ex-info "Provider returned mismatched decision answers"
                      {:error/type :provider/invalid-decision-response
                       :provider provider-id})))
    (if-let [cost (or (:response/cost parsed)
                     (pricing/canonical-cost provider-id (:decision/model parsed)
                                             (:response/usage parsed)))]
      (assoc parsed :response/cost cost)
      parsed)))
