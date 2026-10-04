(ns llm.sdk.providers.openrouter.decide
  "Native OpenRouter Decisions and opt-in TypeSafe-compatible System One."
  (:require [clojure.string :as str]
            [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.provider.auth :as auth]
            [llm.sdk.providers.typesafe.decide :as typesafe]
            [llm.sdk.transport.decide :as dt]))

(defn- openrouter-headers []
  {"HTTP-Referer" (or (System/getenv "OPENROUTER_HTTP_REFERER")
                      "https://github.com/DeadMeme5441/clojure-llm-sdk")
   "X-OpenRouter-Title" (or (System/getenv "OPENROUTER_APP_NAME")
                            "clojure-llm-sdk")})

(defn build-decision-request-openrouter [profile request]
  (let [opts (:decision/provider-options request)
        api (if (contains? opts :api) (:api opts) :decisions)
        base (str/replace (:profile/base-url profile) #"/+$" "")
        url (case api
              :decisions (str (str/replace base #"/v1$" "") "/alpha/decisions")
              :systemone (str base "/systemone")
              (throw (ex-info "Unsupported OpenRouter decision API"
                              {:error/type :request/unsupported-decision-api
                               :provider (:profile/id profile)
                               :api api})))
        routing (if (contains? opts :provider) {:provider (:provider opts)} {})]
    {:method :post
     :url url
     :headers (auth/merge-headers
               (openrouter-headers)
               (provider/default-headers profile
                                         (provider/resolve-auth-token profile)))
     :body (typesafe/build-decision-body profile request routing)}))

(defn parse-decision-response-openrouter [profile raw]
  (let [response (typesafe/parse-systemone-response profile raw)
        usage-raw (get-in response [:response/usage :usage/provider-raw])
        cost (:cost usage-raw)]
    (cond-> response
      (and (number? cost) (Double/isFinite (double cost)) (not (neg? cost)))
      (assoc :response/cost
             {:cost/usd cost
              :cost/estimated? false
              :cost/pricing-source :openrouter-reported
              :cost/source-url
              "https://openrouter.ai/docs/api/api-reference/alphadecisions/submit-a-decisions-request"
              :cost/breakdown
              (select-keys usage-raw [:cost :cost_details :is_byok])}))))

(defn parse-decision-error-openrouter [profile status body]
  (errors/classify-error (Exception. "OpenRouter decision API error")
                         :status status :body body :provider (:profile/id profile)))

(defrecord OpenRouterDecisionTransport []
  dt/DecisionTransport
  (build-decision-request [_ profile request]
    (build-decision-request-openrouter profile request))
  (parse-decision-response [_ profile raw]
    (parse-decision-response-openrouter profile raw))
  (parse-decision-error [_ profile status body]
    (parse-decision-error-openrouter profile status body)))

(defn make-transport [] (->OpenRouterDecisionTransport))
