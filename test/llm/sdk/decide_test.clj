(ns llm.sdk.decide-test
  (:require [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.schema :as schema]))

(def request
  {:decision/model "jev-latest"
   :decision/state {:ticket "The payment is failing."}
   :decision/questions {"ticket/urgent" {:type :noul
                                         :instructions "Is this urgent?"}}})

(defn- error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest invalid-questions-fail-before-network
  (with-redefs [http/request (fn [_] (throw (AssertionError. "Unexpected HTTP")))]
    (doseq [questions [{}
                       {:urgent {:type :noul :instructions "Urgent?"}}
                       {"urgent" {:type :noul :instructions "Urgent?"
                                  :criteria {"maybe" "Uncertain"}}}
                       {"team" {:type :choice :instructions "Team?" :criteria {}}}
                       {"team" {:type :choice :instructions "Team?"
                                :criteria (zipmap (map str (range 256)) (repeat nil))}}
                       {"severity" {:type :score :instructions "Severity?"
                                    :criteria ["Only one level"]}}
                       {"severity" {:type :score :instructions "Severity?"
                                    :criteria (vec (repeat 11 "Level"))}}]]
      (is (= :schema/invalid-decision-request
             (:error/type
              (error-data #(sdk/decide :typesafe
                                      (assoc request :decision/questions questions)))))))
    (is (= :schema/invalid-decision-request
           (:error/type
            (error-data #(sdk/decide :typesafe
                                    (assoc request :decision/state {:score ##NaN}))))))))

(deftest structured-questions-and-boundaries
  (is (schema/validate-decision-request
       (assoc request :decision/state ["A" {:nested [true nil 2.5]}]
              :decision/questions
              {"team" {:type :choice
                       :instructions {:question "Which team?" :context ["support"]}
                       :criteria (zipmap (map str (range 255)) (repeat nil))}
               "severity" {:type :score :instructions ["How severe?"]
                           :criteria (vec (repeat 10 {:description "Level"}))}
               "urgent" {:type :noul :instructions "Urgent?"
                         :criteria {"true" {:meaning "Immediate"}
                                    "false" ["Can wait"]}}}))))

(deftest incomplete-or-mismatched-answers-are-errors
  (doseq [answers [{:wrong {:type "noul" :noul 0.7}}
                   {:ticket/urgent {:type "choice" :choice "yes"
                                    :probabilities {:yes 1.0} :confidence 1.0}}
                   {:ticket/urgent {:type "noul" :noul 0.7}
                    :unexpected {:type "noul" :noul 0.2}}]]
    (with-redefs [http/request (constantly {:status 200
                                           :body {:model "jev-1.13.0"
                                                  :answers answers}})]
      (is (= :provider/invalid-decision-response
             (:error/type (error-data #(sdk/decide :typesafe request))))))))

(deftest unsupported-provider-rejects-decisions
  (with-redefs [http/request (fn [_] (throw (AssertionError. "Unexpected HTTP")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Decisions not supported"
                         (sdk/decide :openai request)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not support chat"
                         (sdk/complete :typesafe
                                       {:request/model "jev-latest"
                                        :request/messages [{:message/role :user
                                                            :message/content "Hello"}]})))))

(deftest provider-reported-cost-wins-and-unknown-is-not-zero
  (let [body {:model "jev-1.13.0"
              :answers {:ticket/urgent {:type "noul" :noul 0.9}}
              :usage {:input_tokens 100 :output_tokens 20 :cost 0.0000042}}]
    (with-redefs [http/request (constantly {:status 200 :body body})]
      (let [response (sdk/decide :openrouter request)]
        (is (= 0.0000042 (get-in response [:response/cost :cost/usd])))
        (is (false? (get-in response [:response/cost :cost/estimated?])))
        (is (= 120 (get-in response [:response/usage :usage/total-tokens])))))
    (with-redefs [http/request (constantly {:status 200 :body (dissoc body :usage)})]
      (let [response (sdk/decide :typesafe request)]
        (is (not (contains? response :response/usage)))
        (is (not (contains? response :response/cost)))
        (is (schema/validate-decision-response response))))))
