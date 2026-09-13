(ns llm.sdk.moderate
  "Driver for moderation requests — the moderate counterpart to
   llm.sdk/complete and llm.sdk/embed.

   Resolves the provider profile, picks up its
   :profile/moderation-transport-constructor, builds the request, and
   returns a canonical ModerationResponse.

   Providers without a moderation transport throw ex-info rather
   than NullPointer."
  (:require [llm.sdk.operation :as operation]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.moderate :as mt]))

(defn moderate
  "Send a canonical ModerationRequest and return a ModerationResponse.

   :moderation/inputs is a vector of either plain strings (for text-
   only moderation) or {:type :text :text \"...\"} / {:type :image_url
   :image_url \"https://...\"} maps. The OpenAI omni-moderation models
   accept the multi-modal shape."
  [provider-id request & {:keys [config]}]
  (let [parsed
        (operation/run
         {:provider-id provider-id
          :request request
          :config config
          :validate-request schema/validate-moderation-request
          :explain-request schema/explain-moderation-request
          :invalid-error-type :schema/invalid-moderation-request
          :invalid-message "Invalid llm.sdk moderation request"
          :constructor-key :profile/moderation-transport-constructor
          :unsupported-message "Moderation not supported by provider"
          :build-request mt/build-moderation-request
          :parse-response
          (fn [transport profile response]
            (let [parsed (mt/parse-moderation-response
                          transport profile (:body response))]
              (when-not (seq (:moderation/results parsed))
                (throw
                 (ex-info "Provider returned an empty moderation response"
                          {:provider provider-id
                           :error/type :provider/invalid-moderation-response
                           :response parsed})))
              parsed))
          :parse-error mt/parse-moderation-error
          :transport-error-message "Provider moderation transport error"
          :api-error-message "Provider moderation API error"})]
    (update parsed :moderation/model
            #(or % (:moderation/model request)))))
