(ns llm.sdk.providers.typesafe.decide
  "TypeSafe System One wire format, also used by OpenRouter Decisions."
  (:require [clojure.string :as str]
            [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport :as transport]
            [llm.sdk.transport.decide :as dt]
            [llm.sdk.usage :as usage]))

(defn build-decision-body
  "Encode typed questions without changing user-defined IDs or JSON content.
   Additional canonical fields allow OpenRouter's provider routing preferences."
  ([profile request] (build-decision-body profile request {}))
  ([profile request additional-fields]
   (transport/merge-extra-body
    (:profile/id profile)
    (merge additional-fields
           {:model (:decision/model request)
            :state (:decision/state request)
            :questions (into {}
                             (map (fn [[id question]]
                                    [id (update question :type name)]))
                             (:decision/questions request))})
    (get-in request [:decision/provider-options :extra_body])
    #{:api})))

(defn build-decision-request-typesafe [profile request]
  (let [opts (:decision/provider-options request)
        api (if (contains? opts :api) (:api opts) :systemone)]
    (when-not (= :systemone api)
      (throw (ex-info "TypeSafe decisions support only :api :systemone"
                      {:error/type :request/unsupported-decision-api
                       :provider (:profile/id profile)
                       :api api})))
    {:method :post
     :url (str (str/replace (:profile/base-url profile) #"/+$" "")
               "/systemone")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body (build-decision-body profile request)}))

(defn- json-key [key]
  ;; `name` drops namespaces in keywordized IDs such as :ticket/urgency.
  (if (keyword? key) (subs (str key) 1) key))

(defn- json-content [value]
  (cond
    (map? value) (into {} (map (fn [[k v]] [(json-key k) (json-content v)])) value)
    (vector? value) (mapv json-content value)
    :else value))

(defn- keyword-fields [value]
  (if (map? value)
    (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) v])) value)
    value))

(defn- canonical-answer [answer]
  (if (map? answer)
    (let [answer (keyword-fields answer)]
      (cond-> answer
        (string? (:type answer)) (update :type keyword)
        (contains? answer :probabilities) (update :probabilities json-content)
        (contains? answer :legend) (update :legend json-content)))
    answer))

(defn- consistent-answer? [answer]
  (case (:type answer)
    :choice (contains? (:probabilities answer) (:choice answer))
    :score (= (set (keys (:legend answer)))
              (set (keys (:probabilities answer))))
    true))

(defn parse-systemone-response
  "Normalize the shared TypeSafe wire response while retaining native metadata.
   Missing or malformed typed answers are errors, never empty success results."
  [profile raw]
  (let [body (keyword-fields raw)
        answers (:answers body)
        usage-raw (keyword-fields (:usage body))
        metadata (when (map? body) (dissoc body :id :model :answers :usage))
        response
        (cond-> {:decision/provider (:profile/id profile)
                 :decision/model (:model body)
                 :decision/answers
                 (if (map? answers)
                   (into {} (map (fn [[id answer]]
                                   [(json-key id) (canonical-answer answer)]))
                         answers)
                   answers)
                 :decision/raw raw}
          (some? (:id body)) (assoc :decision/id (:id body))
          (seq metadata) (assoc :decision/provider-data metadata)
          (map? usage-raw) (assoc :response/usage
                                  (usage/normalize-openai-usage usage-raw)))]
    (when-not (and (schema/validate-decision-response response)
                   (every? consistent-answer? (vals (:decision/answers response))))
      (throw (ex-info "Provider returned a malformed typed decision response"
                      {:error/type :response/invalid-decision
                       :provider (:profile/id profile)
                       :explanation (schema/explain-decision-response response)})))
    response))

(defn parse-decision-error-typesafe [profile status body]
  (errors/classify-error (Exception. "TypeSafe decision API error")
                         :status status :body body :provider (:profile/id profile)))

(defrecord TypeSafeDecisionTransport []
  dt/DecisionTransport
  (build-decision-request [_ profile request]
    (build-decision-request-typesafe profile request))
  (parse-decision-response [_ profile raw]
    (parse-systemone-response profile raw))
  (parse-decision-error [_ profile status body]
    (parse-decision-error-typesafe profile status body)))

(defn make-transport [] (->TypeSafeDecisionTransport))
