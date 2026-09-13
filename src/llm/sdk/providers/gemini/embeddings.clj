(ns llm.sdk.providers.gemini.embeddings
  "Gemini native batch embeddings over models.batchEmbedContents."
  (:require [clojure.string :as str]
            [llm.sdk.errors :as errors]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport.embed :as et]
            [llm.sdk.usage :as usage]))

(def ^:private supported-provider-options #{:task-type :title})

(defn- model-resource-name [model]
  (if (str/starts-with? (str/lower-case model) "models/")
    model
    (str "models/" model)))

(defn- task-type-value [task-type]
  (if (keyword? task-type)
    (-> task-type name str/upper-case (str/replace "-" "_"))
    task-type))

(defn- validate-options! [request]
  (let [encoding (:embed/encoding-format request)
        opts (:embed/provider-options request)
        unsupported (vec (remove supported-provider-options (keys opts)))]
    (when (and encoding (not= :float encoding))
      (throw (ex-info "Gemini native embeddings only support numeric float vectors"
                      {:provider :gemini-native
                       :error/type :request/unsupported-embedding-encoding
                       :encoding-format encoding})))
    (when (contains? request :embed/user)
      (throw (ex-info "Gemini native embeddings do not support :embed/user"
                      {:provider :gemini-native
                       :error/type :provider/unsupported-option
                       :option :embed/user})))
    (when (seq unsupported)
      (throw (ex-info "Unsupported Gemini native embedding provider options"
                      {:provider :gemini-native
                       :error/type :provider/unsupported-option
                       :options unsupported})))))

(defn build-embed-request-gemini
  [profile request]
  (validate-options! request)
  (let [model (model-resource-name (:embed/model request))
        opts (:embed/provider-options request)
        config (cond-> {}
                 (some? (:task-type opts))
                 (assoc :taskType (task-type-value (:task-type opts)))
                 (some? (:title opts))
                 (assoc :title (:title opts))
                 (some? (:embed/dimensions request))
                 (assoc :outputDimensionality (:embed/dimensions request)))
        embed-request (fn [input]
                        (cond-> {:model model
                                 :content {:parts [{:text input}]}}
                          (seq config) (assoc :embedContentConfig config)))]
    {:method :post
     :url (str (:profile/base-url profile) "/" model
               ":batchEmbedContents")
     :headers (merge (provider/default-headers
                      profile
                      (provider/resolve-auth-token profile))
                     {"Content-Type" "application/json"})
     :body {:requests (mapv embed-request (:embed/inputs request))}}))

(defn normalize-gemini-embedding-usage
  [raw]
  (when-let [input (usage/->int (:promptTokenCount raw))]
    {:usage/input-tokens input
     :usage/output-tokens 0
     :usage/total-tokens input
     :usage/request-count 1
     :usage/provider-raw raw}))

(defn- numeric-vector! [index embedding]
  (let [values (:values embedding)]
    (when-not (and (sequential? values) (every? number? values))
      (throw (ex-info "Gemini returned a non-numeric embedding vector"
                      {:provider :gemini-native
                       :error/type :response/invalid-embedding
                       :embedding/index index})))
    (vec values)))

(defn parse-embed-response-gemini
  [profile raw]
  (let [vectors (mapv numeric-vector! (range) (:embeddings raw))
        first-vector (first vectors)
        normalized-usage (normalize-gemini-embedding-usage
                          (:usageMetadata raw))]
    (cond-> {:embed/provider (or (:profile/id profile) :gemini-native)
             :embed/model nil
             :embed/vectors vectors
             :embed/raw raw}
      first-vector (assoc :embed/dimensions (count first-vector))
      normalized-usage (assoc :response/usage normalized-usage))))

(defn parse-embed-error-gemini
  [_profile status body]
  (errors/classify-api-error :gemini-native "Gemini" status body))

(defrecord GeminiNativeEmbedTransport []
  et/EmbedTransport
  (build-embed-request [_ profile request]
    (build-embed-request-gemini profile request))
  (parse-embed-response [_ profile raw]
    (parse-embed-response-gemini profile raw))
  (parse-embed-error [_ profile status body]
    (parse-embed-error-gemini profile status body))
  (normalize-embed-usage [_ _ raw]
    (normalize-gemini-embedding-usage raw)))

(defn make-transport []
  (->GeminiNativeEmbedTransport))

