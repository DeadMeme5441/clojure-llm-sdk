(ns llm.sdk.live-decide-test
  "Cheap, opt-in Jev smokes for TypeSafe and both OpenRouter endpoints."
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
            [llm.sdk :as sdk]
            [llm.sdk.schema :as schema]))

(defn- configured? [env-name]
  (not (str/blank? (System/getenv env-name))))

(defn smoke [provider-id model api]
  (let [response
        (sdk/decide
         provider-id
         {:decision/model model
          :decision/state "Customer: All credit card payments fail. We are losing sales. Please fix this now."
          :decision/questions
          {"urgent" {:type :noul :instructions "Is immediate action requested?"}
           "team" {:type :choice :instructions "Which team should handle this?"
                   :criteria {"payments" "Payment failures"
                              "accounts" "Login and account access"}}
           "severity" {:type :score :instructions "How severe is the problem?"
                       :criteria ["Cosmetic issue" "Function impaired" "Revenue blocked"]}}
          :decision/provider-options {:api api}}
         :config {:timeout-ms 60000})]
    (is (schema/validate-decision-response response))
    (is (= {"urgent" :noul "team" :choice "severity" :score}
           (update-vals (:decision/answers response) :type)))
    (is (= #{"payments" "accounts"}
           (set (keys (get-in response [:decision/answers "team" :probabilities])))))
    (is (= #{"0" "1" "2"}
           (set (keys (get-in response [:decision/answers "severity" :legend])))))
    (is (pos? (get-in response [:response/usage :usage/input-tokens])))
    (when (= :openrouter provider-id)
      (is (number? (get-in response [:response/cost :cost/usd])))
      (is (false? (get-in response [:response/cost :cost/estimated?]))))
    (prn {:provider provider-id :api api
          :model (:decision/model response)
          :answers (:decision/answers response)
          :usage (dissoc (:response/usage response) :usage/provider-raw)
          :cost (:response/cost response)})
    response))

(deftest ^:live typesafe-systemone
  (when (or (configured? "TYPESAFE_AI_API_KEY") (configured? "TYPESAFE_API_KEY"))
    (smoke :typesafe "jev-latest" :systemone)))

(deftest ^:live openrouter-decisions
  (when (configured? "OPENROUTER_API_KEY")
    (smoke :openrouter "typesafe/jev-1.13" :decisions)))

(deftest ^:live openrouter-systemone
  (when (configured? "OPENROUTER_API_KEY")
    (smoke :openrouter "jev-latest" :systemone)))
