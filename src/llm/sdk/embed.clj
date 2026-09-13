(ns llm.sdk.embed
  "Driver for embedding requests — the embed counterpart to
   llm.sdk/complete. Resolves the provider profile, picks up its
   :profile/embed-transport-constructor, builds the request, sends it,
   and returns a canonical EmbedResponse.

   Providers without an embed transport throw ex-info on call rather
   than returning nil — surfacing missing capability at the call site
   is friendlier than letting a downstream NullPointer explode."
  (:require [llm.sdk.operation :as operation]
            [llm.sdk.pricing :as pricing]
            [llm.sdk.schema :as schema]
            [llm.sdk.transport.embed :as et]))

(defn- dense-vector? [value]
  (and (sequential? value)
       (seq value)
       (every? #(and (number? %)
                     (Double/isFinite (double %)))
               value)))

(defn- opaque-jina-response? [provider-id parsed]
  (and (= :jina provider-id)
       (seq (get-in parsed [:embed/provider-data :raw]))))

(defn- validate-provider-response! [provider-id request parsed]
  (let [vectors (:embed/vectors parsed)
        opaque (get-in parsed [:embed/provider-data :raw])
        expected (count (:embed/inputs request))
        expected-dimensions (:embed/dimensions request)
        dense? (and (vector? vectors)
                    (every? dense-vector? vectors)
                    (or (empty? vectors)
                        (apply = (map count vectors)))
                    (or (nil? expected-dimensions)
                        (every? #(= expected-dimensions (count %)) vectors)))
        opaque-jina? (opaque-jina-response? provider-id parsed)
        cardinality (if opaque-jina?
                      (+ (count vectors) (count opaque))
                      (count vectors))]
    (when-not (and (map? parsed)
                   dense?
                   (= expected cardinality)
                   (or opaque-jina? (empty? opaque)))
      (throw
       (ex-info "Provider returned invalid or incomplete embeddings"
                {:provider provider-id
                 :error/type :provider/invalid-embedding-response
                 :expected-count expected
                 :actual-count cardinality
                 :response parsed})))))

(defn embed
  "Send a canonical EmbedRequest and return a canonical EmbedResponse.

   The request map must contain :embed/model and :embed/inputs (a
   vector of strings). Optional fields: :embed/dimensions,
   :embed/encoding-format (:float or :base64), :embed/user,
   :embed/provider-options."
  [provider-id request & {:keys [config]}]
  (let [parsed
        (operation/run
         {:provider-id provider-id
          :request request
          :config config
          :validate-request schema/validate-embed-request
          :explain-request schema/explain-embed-request
          :invalid-error-type :schema/invalid-embed-request
          :invalid-message "Invalid llm.sdk embed request"
          :constructor-key :profile/embed-transport-constructor
          :unsupported-message "Embedding not supported by provider"
          :build-request et/build-embed-request
          :parse-response
          (fn [transport profile response]
            (et/parse-embed-response transport profile (:body response)))
          :parse-error et/parse-embed-error
          :transport-error-message "Provider embed transport error"
          :api-error-message "Provider embed API error"})
        parsed (update parsed :embed/model
                       #(or % (:embed/model request)))
        _ (validate-provider-response! provider-id request parsed)
        usage (:response/usage parsed)
        cost (or (:response/cost parsed)
                 (pricing/canonical-cost provider-id
                                         (:embed/model parsed)
                                         usage))]
    (cond-> parsed
      cost (assoc :response/cost cost))))
