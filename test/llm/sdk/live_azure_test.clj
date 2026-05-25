(ns llm.sdk.live-azure-test
  "Live smoke for Azure OpenAI deployment routing.

   Needs three env vars: AZURE_OPENAI_API_KEY, AZURE_OPENAI_ENDPOINT,
   and AZURE_OPENAI_DEPLOYMENT. Optional: AZURE_OPENAI_API_VERSION
   (defaults to a recent preview).

   To run only this suite:
     source .env && clj -M:live-test -n llm.sdk.live-azure-test"
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.providers.openai-chat :as openai]))

(defn- has-creds? []
  (and (System/getenv "AZURE_OPENAI_API_KEY")
       (System/getenv "AZURE_OPENAI_ENDPOINT")
       (System/getenv "AZURE_OPENAI_DEPLOYMENT")))

(deftest ^:live live-azure-chat
  (when (has-creds?)
    (testing "Azure OpenAI deployment-routed chat completion"
      (openai/register-azure-deployment!
       {:id :azure-live-smoke
        :endpoint (System/getenv "AZURE_OPENAI_ENDPOINT")
        :deployment (System/getenv "AZURE_OPENAI_DEPLOYMENT")
        :api-version (or (System/getenv "AZURE_OPENAI_API_VERSION")
                         "2024-08-01-preview")
        :env-var-names ["AZURE_OPENAI_API_KEY"]})
      (let [resp (sdk/complete
                  :azure-live-smoke
                  {:request/model "ignored-by-azure"
                   :request/messages [{:message/role :user
                                       :message/content
                                       "Reply with the single word: ok"}]
                   :request/max-tokens 8})
            text-part (some #(when (= :text (:part/type %)) %)
                            (:response/parts resp))]
        (is (= :azure-live-smoke (:response/provider resp)))
        (is (string? (:text text-part)))
        (is (contains? #{:stop :length} (:response/finish-reason resp)))))))
