(ns llm.sdk.providers.decision-transport-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [llm.sdk.http :as http]
            [llm.sdk.providers.openrouter.decide :as openrouter]
            [llm.sdk.providers.typesafe.decide :as typesafe]
            [llm.sdk.transport.decide :as dt]))

(def typesafe-profile
  {:profile/id :typesafe
   :profile/base-url "https://api.typesafe.ai/v1"
   :profile/auth-strategy :bearer
   :profile/auth-token "synthetic-token"
   :profile/default-headers {"Content-Type" "application/json"}})

(def openrouter-profile
  (assoc typesafe-profile
         :profile/id :openrouter
         :profile/base-url "https://openrouter.ai/api/v1"))

(def request
  {:decision/model "jev-latest"
   :decision/state [{"ticket/id" "007"} {"message" "My payment failed."}]
   :decision/questions
   {"ticket/urgent" {:type :noul
                     :instructions {"question" "Is this urgent?"}
                     :criteria {"true" ["Time-sensitive" {"deadline" true}]
                                "false" "Routine"}}
    "ticket/team" {:type :choice
                   :instructions "Who owns this?"
                   :criteria {"billing/team" nil "technical/team" "Bugs"}}
    "007" {:type :score
           :instructions ["Rate urgency" {"context" "Payment failure"}]
           :criteria [{"severity/label" "Calm"}
                      ["Urgent" {"blocking" true}]]}}})

(defn- error-data [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest typed-decision-wire-body
  (doseq [[transport profile] [[(typesafe/make-transport) typesafe-profile]
                               [(openrouter/make-transport) openrouter-profile]]]
    (let [built (dt/build-decision-request transport profile request)
          wire (json/parse-string (json/generate-string (:body built)))]
      (is (= :post (:method built)))
      (is (= "Bearer synthetic-token" (get-in built [:headers "Authorization"])))
      (is (= (:decision/state request) (get wire "state")))
      (is (= "jev-latest" (get wire "model")))
      (is (= #{"ticket/urgent" "ticket/team" "007"}
             (set (keys (get wire "questions")))))
      (is (= "noul" (get-in wire ["questions" "ticket/urgent" "type"])))
      (is (= (get-in request [:decision/questions "ticket/urgent" :criteria])
             (get-in wire ["questions" "ticket/urgent" "criteria"])))
      (is (= {"billing/team" nil "technical/team" "Bugs"}
             (get-in wire ["questions" "ticket/team" "criteria"])))
      (is (= (get-in request [:decision/questions "007" :criteria])
             (get-in wire ["questions" "007" "criteria"]))))))

(deftest native-endpoint-selection-and-runtime-headers
  (is (= "https://api.typesafe.ai/v1/systemone"
         (:url (dt/build-decision-request
                (typesafe/make-transport) typesafe-profile request))))
  (let [transport (openrouter/make-transport)]
    (doseq [[base expected]
            [["https://openrouter.ai/api/v1" "https://openrouter.ai/api/alpha/decisions"]
             ["https://proxy.example/router/api/v1/"
              "https://proxy.example/router/api/alpha/decisions"]]]
      (is (= expected (:url (dt/build-decision-request
                            transport (assoc openrouter-profile :profile/base-url base)
                            request)))))
    (let [profile (assoc openrouter-profile
                         :profile/base-url "https://proxy.example/router/api/v1/"
                         :profile/default-headers
                         {"http-referer" "https://app.example"
                          "x-openrouter-title" "Caller app"
                          "X-Custom" "preserved"})
          built (dt/build-decision-request
                 transport profile
                 (assoc request :decision/provider-options {:api :systemone}))]
      (is (= "https://proxy.example/router/api/v1/systemone" (:url built)))
      (is (= "https://app.example" (get-in built [:headers "http-referer"])))
      (is (= "Caller app" (get-in built [:headers "x-openrouter-title"])))
      (is (not (contains? (:headers built) "HTTP-Referer")))
      (is (= "preserved" (get-in built [:headers "X-Custom"])))
      (is (= "Bearer synthetic-token" (get-in built [:headers "Authorization"])))
      (is (not (contains? (:body built) :api))))))

(deftest unsupported-api-is-rejected
  (doseq [[transport profile api]
          [[(typesafe/make-transport) typesafe-profile :decisions]
           [(typesafe/make-transport) typesafe-profile nil]
           [(openrouter/make-transport) openrouter-profile :chat]
           [(openrouter/make-transport) openrouter-profile "decisions"]]]
    (is (= :request/unsupported-decision-api
           (:error/type
            (error-data #(dt/build-decision-request
                          transport profile
                          (assoc request :decision/provider-options {:api api}))))))))

(deftest routing-and-extra-body-protect-canonical-fields
  (let [transport (openrouter/make-transport)
        preferences {:only ["typesafe"] :allow_fallbacks false}
        built (dt/build-decision-request
               transport openrouter-profile
               (assoc request :decision/provider-options
                      {:provider preferences
                       :extra_body {"session_id" "session-example" :user "demo"}}))]
    (is (= preferences (get-in built [:body :provider])))
    (is (= "session-example" (get-in built [:body :session_id])))
    (is (= "demo" (get-in built [:body :user])))
    (doseq [[t profile] [[transport openrouter-profile]
                         [(typesafe/make-transport) typesafe-profile]]
            key [:model "model" :state "state" :questions "questions" :api "api"]]
      (let [error (error-data
                   #(dt/build-decision-request
                     t profile
                     (assoc request :decision/provider-options
                            {:extra_body {key "collision"}})))]
        (is (= :request/protected-extra-body-override (:error/type error)))
        (is (= (keyword key) (:field error)))))
    (is (= :request/protected-extra-body-override
           (:error/type
            (error-data
             #(dt/build-decision-request
               transport openrouter-profile
               (assoc request :decision/provider-options
                      {:provider preferences
                       :extra_body {"provider" {:only ["other"]}}}))))))))

(def wire-response
  {"id" "decision-synthetic"
   "model" "typesafe/jev-1.13"
   "provider" "TypeSafe"
   "answers"
   {"ticket/urgent" {"type" "noul" "noul" 0.95}
    "ticket/team" {"type" "choice" "choice" "billing/team"
                   "probabilities" {"billing/team" 0.88 "technical/team" 0.12}
                   "confidence" 0.81}
    "007" {"type" "score" "score" 0.8
            "legend" {"0" {"severity/label" "Calm" "enabled" false "optional" nil}
                       "1" ["Urgent" {"blocking" true}]}
            "probabilities" {"0" 0.2 "1" 0.8}
            "confidence" 0.72}}
   "usage" {"input_tokens" 318 "output_tokens" 34 "cost" 0.000019992}})

(defn- decoded-response [wire]
  (http/decode-body (json/generate-string wire)))

(deftest decoded-answers-preserve-identifiers-and-structured-values
  (doseq [[transport profile] [[(typesafe/make-transport) typesafe-profile]
                               [(openrouter/make-transport) openrouter-profile]]]
    (let [raw (decoded-response wire-response)
          parsed (dt/parse-decision-response transport profile raw)
          answers (:decision/answers parsed)]
      (is (= #{"ticket/urgent" "ticket/team" "007"} (set (keys answers))))
      (is (= {:type :noul :noul 0.95} (get answers "ticket/urgent")))
      (is (= {:type :choice :choice "billing/team"
              :probabilities {"billing/team" 0.88 "technical/team" 0.12}
              :confidence 0.81}
             (get answers "ticket/team")))
      (is (= {:type :score :score 0.8
              :legend {"0" {"severity/label" "Calm" "enabled" false "optional" nil}
                       "1" ["Urgent" {"blocking" true}]}
              :probabilities {"0" 0.2 "1" 0.8} :confidence 0.72}
             (get answers "007")))
      (is (= (:profile/id profile) (:decision/provider parsed)))
      (is (= "decision-synthetic" (:decision/id parsed)))
      (is (= "TypeSafe" (get-in parsed [:decision/provider-data :provider])))
      (is (= raw (:decision/raw parsed)))
      (is (= 318 (get-in parsed [:response/usage :usage/input-tokens])))
      (is (= 34 (get-in parsed [:response/usage :usage/output-tokens])))
      (is (= 352 (get-in parsed [:response/usage :usage/total-tokens]))))))

(deftest reported-cost-is-authoritative-and-absence-is-not-zero
  (let [transport (openrouter/make-transport)
        parse #(dt/parse-decision-response transport openrouter-profile
                                           (decoded-response %))
        reported (:response/cost (parse wire-response))]
    (is (= 0.000019992 (:cost/usd reported)))
    (is (false? (:cost/estimated? reported)))
    (is (= :openrouter-reported (:cost/pricing-source reported)))
    (is (= 0.0 (get-in (parse (assoc-in wire-response ["usage" "cost"] 0.0))
                       [:response/cost :cost/usd])))
    (doseq [wire [(update wire-response "usage" dissoc "cost")
                 (dissoc wire-response "usage")]]
      (is (not (contains? (parse wire) :response/cost))))
    (is (not (contains? (parse (dissoc wire-response "usage")) :response/usage)))
    (let [partial (parse (assoc wire-response "usage" {"input_tokens" 9}))]
      (is (= 9 (get-in partial [:response/usage :usage/input-tokens])))
      (is (not (contains? (:response/usage partial) :usage/output-tokens)))
      (is (not (contains? (:response/usage partial) :usage/total-tokens))))))

(deftest malformed-typed-responses-are-rejected
  (let [raw (decoded-response wire-response)]
    (doseq [[transport profile] [[(typesafe/make-transport) typesafe-profile]
                                 [(openrouter/make-transport) openrouter-profile]]
            malformed
            [nil "not-json" (dissoc raw :model) (dissoc raw :answers)
             (assoc raw :answers {}) (assoc raw :answers [])
             (assoc-in raw [:answers :ticket/urgent] nil)
             (assoc-in raw [:answers :ticket/urgent :type] "boolean")
             (assoc-in raw [:answers :ticket/urgent :noul] 1.2)
             (update-in raw [:answers :ticket/team] dissoc :probabilities)
             (assoc-in raw [:answers :ticket/team :choice] "not-an-option")
             (assoc-in raw [:answers :ticket/team :confidence] Double/POSITIVE_INFINITY)
             (assoc-in raw [:answers :007 :score] Double/NaN)
             (update-in raw [:answers :007] dissoc :legend)
             (assoc-in raw [:answers :007 :legend] {"0" "Calm" "2" "Urgent"})]]
      (is (= :response/invalid-decision
             (:error/type
              (error-data #(dt/parse-decision-response transport profile malformed))))))))

(deftest errors-retain-provider-messages-and-status-classification
  (doseq [[transport profile] [[(typesafe/make-transport) typesafe-profile]
                               [(openrouter/make-transport) openrouter-profile]]
          [status message reason retryable?]
          [[400 "Invalid request parameters" :invalid-request false]
           [401 "Missing Authentication header" :auth false]
           [402 "Insufficient credits" :quota false]
           [403 "Forbidden" :auth false]
           [422 "Invalid question criteria" :invalid-request false]
           [429 "Rate limit exceeded" :rate-limit true]
           [500 "Upstream failure" :server true]
           [529 "Temporarily overloaded" :overloaded true]]]
    (let [parsed (dt/parse-decision-error transport profile status
                                          {:error {:message message}})]
      (is (= reason (:error/reason parsed)))
      (is (= retryable? (:error/retryable parsed)))
      (is (= message (:error/message parsed))))))
