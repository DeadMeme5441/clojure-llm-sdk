(ns llm.sdk.pricing
  "Pricing lookup + cost estimation, layered on llm.sdk.registry.

   Data flow:
     (sdk/estimate-cost ...) ─► estimate-cost-for-model
                                       │
                                       ▼
                                 registry/lookup        ─► merged ModelEntry
                                  - override tier       (pricing from cost map)
                                  - live /models tier
                                  - models.dev tier (including bundled snapshot)

   The hardcoded pricing snapshot that previously lived here folded into
   the bundled models.dev snapshot at resources/models-dev-snapshot.json
   (every former entry verified present with current pricing). Callers
   who need to inject custom pricing should use
   llm.sdk.registry/register-entry!, which bypasses the public registries.

   The PricingEntry record shape is preserved for callers who already
   consume it — internally we convert ModelEntry's :model/cost map back
   to this shape at lookup time."
  (:require [clojure.string :as str]
            [llm.sdk.registry :as registry]
            [llm.sdk.provider :as provider]))

;; ---------------------------------------------------------------------------
;; Legacy entry shapes — preserved for callers that already depend on them
;; ---------------------------------------------------------------------------

(defn pricing-entry
  "Construct a pricing entry. Token costs are per-million-tokens in USD.
   Modality-specific token rates are kept distinct from text rates; callers
   must not substitute one for another.

   Per-unit knobs:
     :image-per-image              dollars per image returned
     :image-per-megapixel          dollars per megapixel produced
     :transcription-per-minute     dollars per minute of audio in
     :tts-per-million-chars        dollars per 1M output characters
     :search-per-call              dollars per search query (Perplexity)
     :rerank-per-search-unit       dollars per billed rerank search unit"
  [& {:keys [input output cache-read cache-write request-cost
             image-input image-output audio-input audio-output
             image-cache-read audio-cache-read
             image-per-image image-per-megapixel
             transcription-per-minute tts-per-million-chars
             search-per-call rerank-per-search-unit
             source source-url pricing-version]}]
  {:input-cost-per-million input
   :output-cost-per-million output
   :cache-read-cost-per-million cache-read
   :cache-write-cost-per-million cache-write
   :image-input-cost-per-million image-input
   :image-output-cost-per-million image-output
   :audio-input-cost-per-million audio-input
   :audio-output-cost-per-million audio-output
   :image-cache-read-cost-per-million image-cache-read
   :audio-cache-read-cost-per-million audio-cache-read
   :request-cost request-cost
   :image-cost-per-image image-per-image
   :image-cost-per-megapixel image-per-megapixel
   :transcription-cost-per-minute transcription-per-minute
   :tts-cost-per-million-chars tts-per-million-chars
   :search-cost-per-call search-per-call
   :rerank-cost-per-search-unit rerank-per-search-unit
   :source (or source :registry)
   :source-url source-url
   :pricing-version pricing-version})

(defn cost-result
  [amount status source label & {:keys [notes]}]
  {:cost/amount-usd amount
   :cost/status status
   :cost/source source
   :cost/label label
   :cost/notes (or notes [])})

;; ---------------------------------------------------------------------------
;; ModelEntry → pricing-entry adapter
;; ---------------------------------------------------------------------------

(defn- model-entry->pricing-entry
  "Convert the :model/cost map on a ModelEntry back into a legacy
   pricing-entry. Returns nil when the model entry has no cost data."
  [model-entry]
  (when-let [c (:model/cost model-entry)]
    (when (some some?
                [(:input-per-million c)
                 (:output-per-million c)
                 (:cache-read-per-million c)
                 (:cache-write-per-million c)
                 (:image-input-per-million c)
                 (:image-output-per-million c)
                 (:audio-input-per-million c)
                 (:audio-output-per-million c)
                 (:image-cache-read-per-million c)
                 (:audio-cache-read-per-million c)
                 (:image-per-image c)
                 (:image-per-megapixel c)
                 (:transcription-per-minute c)
                 (:tts-per-million-chars c)
                 (:search-per-call c)
                 (:rerank-per-search-unit c)
                 (:request-cost c)])
      (let [cost-source (:model/cost-source model-entry)]
        (pricing-entry
         :input (:input-per-million c)
         :output (:output-per-million c)
         :cache-read (:cache-read-per-million c)
         :cache-write (:cache-write-per-million c)
         :image-input (:image-input-per-million c)
         :image-output (:image-output-per-million c)
         :audio-input (:audio-input-per-million c)
         :audio-output (:audio-output-per-million c)
         :image-cache-read (:image-cache-read-per-million c)
         :audio-cache-read (:audio-cache-read-per-million c)
         :request-cost (:request-cost c)
         :image-per-image (:image-per-image c)
         :image-per-megapixel (:image-per-megapixel c)
         :transcription-per-minute (:transcription-per-minute c)
         :tts-per-million-chars (:tts-per-million-chars c)
         :search-per-call (:search-per-call c)
         :rerank-per-search-unit (:rerank-per-search-unit c)
         :source (or (:source cost-source)
                     (:model/source model-entry))
         :source-url (or (:source-url cost-source)
                         (:model/source-url model-entry))
         :pricing-version (:source-revision cost-source))))))

(defn- official-openai-pricing
  "Small current-pricing fallback for OpenAI models whose bundled
   models.dev entries exist but omit specialized cost data."
  [provider model]
  (when (= provider :openai)
    (case model
      ;; OpenAI pricing page, image generation models, standard tier. These
      ;; endpoints report text input, image input, and image output separately.
      "gpt-image-1-mini"
      (pricing-entry :input 2.0 :image-input 2.5 :image-output 8.0
                     :cache-read 0.20
                     :source :openai-pricing-page
                     :source-url "https://developers.openai.com/api/docs/pricing")
      "gpt-image-1.5"
      (pricing-entry :input 5.0 :image-input 8.0 :image-output 32.0
                     :cache-read 1.25
                     :source :openai-pricing-page
                     :source-url "https://developers.openai.com/api/docs/pricing")
      "gpt-image-2"
      (pricing-entry :input 5.0 :image-input 8.0 :image-output 30.0
                     :cache-read 1.25
                     :source :openai-pricing-page
                     :source-url "https://developers.openai.com/api/docs/pricing")
      nil)))

;; ---------------------------------------------------------------------------
;; Registry-backed lookup
;; ---------------------------------------------------------------------------

(defn get-pricing
  "Get the pricing-entry for (provider, model), or nil when no tier of
   the registry has cost data for the pair. The small official fallback
   supplements, but never hides, non-nil catalog rates."
  [provider model]
  (let [fallback (official-openai-pricing provider model)
        catalog (some-> (registry/lookup provider model)
                        model-entry->pricing-entry)]
    (cond
      (and fallback catalog)
      (merge fallback
             (dissoc (into {} (filter (comp some? val)) catalog)
                     :source :source-url))

      catalog catalog
      :else fallback)))

(defn register-pricing
  "Register a caller-provided pricing entry as a registry override.
   Internally converts to ModelEntry shape and pushes into the override
   tier. The entry argument can be a legacy pricing-entry map, OR a map
   with :input/:output/:cache-read/:cache-write/:request-cost shorthand."
  [provider model entry]
  (let [runtime-shape?
        (some #(contains? entry %)
              [:input-cost-per-million
               :output-cost-per-million
               :cache-read-cost-per-million
               :cache-write-cost-per-million
               :image-input-cost-per-million
               :image-output-cost-per-million
               :audio-input-cost-per-million
               :audio-output-cost-per-million
               :image-cache-read-cost-per-million
               :audio-cache-read-cost-per-million
               :rerank-cost-per-search-unit])
        norm (if runtime-shape?
               entry
               (pricing-entry
                :input (:input entry)
                :output (:output entry)
                :cache-read (:cache-read entry)
                :cache-write (:cache-write entry)
                :image-input (:image-input entry)
                :image-output (:image-output entry)
                :audio-input (:audio-input entry)
                :audio-output (:audio-output entry)
                :image-cache-read (:image-cache-read entry)
                :audio-cache-read (:audio-cache-read entry)
                :request-cost (:request-cost entry)
                :image-per-image (:image-per-image entry)
                :image-per-megapixel (:image-per-megapixel entry)
                :transcription-per-minute (:transcription-per-minute entry)
                :tts-per-million-chars (:tts-per-million-chars entry)
                :search-per-call (:search-per-call entry)
                :rerank-per-search-unit (:rerank-per-search-unit entry)
                :source (:source entry :user-override)
                :source-url (:source-url entry)))
        cost (cond-> {}
               (:input-cost-per-million norm)
               (assoc :input-per-million (:input-cost-per-million norm))
               (:output-cost-per-million norm)
               (assoc :output-per-million (:output-cost-per-million norm))
               (:cache-read-cost-per-million norm)
               (assoc :cache-read-per-million (:cache-read-cost-per-million norm))
               (:cache-write-cost-per-million norm)
               (assoc :cache-write-per-million (:cache-write-cost-per-million norm))
               (:image-input-cost-per-million norm)
               (assoc :image-input-per-million (:image-input-cost-per-million norm))
               (:image-output-cost-per-million norm)
               (assoc :image-output-per-million (:image-output-cost-per-million norm))
               (:audio-input-cost-per-million norm)
               (assoc :audio-input-per-million (:audio-input-cost-per-million norm))
               (:audio-output-cost-per-million norm)
               (assoc :audio-output-per-million (:audio-output-cost-per-million norm))
               (:image-cache-read-cost-per-million norm)
               (assoc :image-cache-read-per-million (:image-cache-read-cost-per-million norm))
               (:audio-cache-read-cost-per-million norm)
               (assoc :audio-cache-read-per-million (:audio-cache-read-cost-per-million norm))
               (:request-cost norm) (assoc :request-cost (:request-cost norm))
               (:image-cost-per-image norm) (assoc :image-per-image (:image-cost-per-image norm))
               (:image-cost-per-megapixel norm) (assoc :image-per-megapixel (:image-cost-per-megapixel norm))
               (:transcription-cost-per-minute norm) (assoc :transcription-per-minute (:transcription-cost-per-minute norm))
               (:tts-cost-per-million-chars norm) (assoc :tts-per-million-chars (:tts-cost-per-million-chars norm))
               (:search-cost-per-call norm) (assoc :search-per-call (:search-cost-per-call norm))
               (:rerank-cost-per-search-unit norm)
               (assoc :rerank-per-search-unit
                      (:rerank-cost-per-search-unit norm)))
        cost-source
        (cond-> {:source (or (:source norm) :user-override)}
          (:source-url norm) (assoc :source-url (:source-url norm))
          (:pricing-version norm)
          (assoc :source-revision (:pricing-version norm)))]
    (registry/register-entry!
     provider model
     (cond-> {}
       (seq cost) (assoc :model/cost cost
                         :model/cost-source cost-source)))))

;; ---------------------------------------------------------------------------
;; Billing route — informational only
;; ---------------------------------------------------------------------------

(defn resolve-billing-route
  "Determine billing route metadata from model name, provider, and an
   optional base-url. Pure data — not a pricing lookup."
  [model & {:keys [provider base-url]}]
  (let [p (or provider "unknown")
        url (or base-url "")]
    {:billing-route/provider p
     :billing-route/model model
     :billing-route/base-url url
     :billing-route/mode (cond
                           (str/includes? url "openrouter") :openrouter
                           (str/includes? url "localhost") :local
                           :else :direct)}))

;; ---------------------------------------------------------------------------
;; Live refresh — delegate to registry
;; ---------------------------------------------------------------------------

(declare estimate-cost)

(def openrouter-provider-prefixes
  "Best-effort mapping from SDK provider ids to OpenRouter model id prefixes.

   This is intentionally conservative. OpenRouter pricing is billing-route
   pricing for OpenRouter, not proof of the direct provider's current direct
   API price. Callers can always pass the full OpenRouter model id
   (for example, \"openai/gpt-4o\") to avoid inference."
  {:openai "openai"
   :anthropic "anthropic"
   :gemini-native "google"
   :vertex-gemini "google"
   :mistral "mistralai"
   :xai "x-ai"
   :deepseek "deepseek"
   :cohere "cohere"
   :perplexity "perplexity"})

(defn openrouter-model-id
  "Return the OpenRouter model id for a provider/model pair.
   If model already contains a slash, it is treated as an OpenRouter id."
  [provider model]
  (cond
    (nil? model) nil
    (str/includes? model "/") model
    (= provider :openrouter) model
    :else (when-let [prefix (openrouter-provider-prefixes provider)]
            (str prefix "/" model))))

(defn fetch-openrouter-pricing!
  "Refresh OpenRouter's live /models catalog. This populates registry live
   entries under provider :openrouter, including pricing from
   https://openrouter.ai/api/v1/models. Returns the number of entries fetched,
   or 0 on failure."
  []
  (try
    (count (registry/refresh! :openrouter))
    (catch Exception _ 0)))

(defn get-openrouter-pricing
  "Get OpenRouter billing-route pricing for provider/model.

   Unlike get-pricing, this deliberately looks under provider :openrouter.
   Use it when the actual call is routed through OpenRouter or when comparing
   OpenRouter pricing against direct-provider pricing. Call
   fetch-openrouter-pricing! first when fresh live data is required."
  [provider model]
  (when-let [or-model (openrouter-model-id provider model)]
    (some-> (registry/lookup :openrouter or-model)
            model-entry->pricing-entry)))

(defn fetch-pricing!
  "Refresh pricing for a billing-route by hitting the provider's live
   /models endpoint via registry/refresh!. Returns the number of
   entries fetched, or 0 when the provider lacks /models support."
  [billing-route & {:keys [_api-key]}]
  ;; api-key is forwarded only via the provider profile's env var path —
  ;; the SDK doesn't accept per-call key injection for /models fetches.
  ;; Reserved for future use.
  (let [pid (when-let [p (:billing-route/provider billing-route)]
              (cond
                (keyword? p) p
                (string? p) (keyword p)))]
    (if pid
      (try
        (count (registry/refresh! pid))
        (catch Exception _ 0))
      0)))

(defn estimate-openrouter-cost
  "Estimate cost using OpenRouter billing-route pricing. Does not refresh live
   data by itself; call fetch-openrouter-pricing! when freshness matters."
  [provider model usage]
  (estimate-cost usage (get-openrouter-pricing provider model)))

;; ---------------------------------------------------------------------------
;; Cost estimation
;; ---------------------------------------------------------------------------

(defn- finite-nonnegative-number?
  [x]
  (and (number? x)
       (try
         (not (neg? (bigdec x)))
         (catch Exception _ false))))

(defn- valid-count? [x]
  (finite-nonnegative-number? x))

(defn- valid-rate? [x]
  (finite-nonnegative-number? x))

(defn- per-million [n cost-per-m]
  (when (and (valid-count? n) (valid-rate? cost-per-m))
    (-> (bigdec n)
        (.multiply (bigdec cost-per-m))
        (.movePointLeft 6))))

(defn- positive-count? [x]
  (and (valid-count? x) (pos? x)))

(def ^:private token-rate-keys
  [:input-cost-per-million
   :output-cost-per-million
   :cache-read-cost-per-million
   :cache-write-cost-per-million
   :image-input-cost-per-million
   :image-output-cost-per-million
   :audio-input-cost-per-million
   :audio-output-cost-per-million
   :image-cache-read-cost-per-million
   :audio-cache-read-cost-per-million])

(defn- token-pricing-present? [pricing]
  (some #(some? (get pricing %)) token-rate-keys))

(defn- first-detail-map [raw keys]
  (some (fn [k]
          (let [v (get raw k)]
            (when (map? v) v)))
        keys))

(defn- detail-count [details k]
  (let [v (get details k)]
    (when (valid-count? v) v)))

(defn- modality-partition? [details]
  (and (map? details)
       (some #(contains? details %)
             [:text_tokens :image_tokens :audio_tokens])))

(defn- raw-token-details [usage]
  (let [raw (:usage/provider-raw usage)
        input (first-detail-map
               raw
               [:input_tokens_details :prompt_tokens_details
                :input_token_details :prompt_token_details])
        output (first-detail-map
                raw
                [:output_tokens_details :completion_tokens_details
                 :output_token_details :completion_token_details])
        cached (or (first-detail-map
                    input
                    [:cached_tokens_details :cache_read_tokens_details
                     :cached_input_tokens_details])
                   (first-detail-map
                    raw
                    [:cached_tokens_details :cache_read_tokens_details
                     :cached_input_tokens_details]))]
    {:input input :output output :cached cached}))

(defn- aggregate-modality-count
  [usage modality direction]
  (let [aggregate (get usage (keyword "usage" (str (name modality) "-tokens")))
        other-count (get usage (if (= direction :input)
                                 :usage/output-tokens
                                 :usage/input-tokens))]
    (when (and (number? aggregate)
               (number? other-count)
               (zero? other-count))
      aggregate)))

(defn- direction-modalities
  [usage direction total details default-modality]
  (let [partition? (modality-partition? details)
        partition-count
        (fn [modality]
          (let [detail-key (keyword (str (name modality) "_tokens"))
                aggregate (get usage
                               (keyword "usage"
                                        (str (name modality) "-tokens")))]
            (cond
              (contains? details detail-key)
              (detail-count details detail-key)

              (positive-count? aggregate)
              nil

              :else 0)))
        modality-count
        (fn [modality]
          (cond
            partition? (partition-count modality)
            (= default-modality modality) total
            default-modality 0
            (positive-count?
             (get usage
                  (keyword "usage" (str (name modality) "-tokens"))))
            (aggregate-modality-count usage modality direction)
            :else 0))
        unresolved-image (modality-count :image)
        unresolved-audio (modality-count :audio)
        unresolved (cond-> []
                     (nil? unresolved-image)
                     (conj (str "missing " (name direction)
                                " image-token detail"))
                     (nil? unresolved-audio)
                     (conj (str "missing " (name direction)
                                " audio-token detail")))
        image (or unresolved-image 0)
        audio (or unresolved-audio 0)
        invalid? (and (= direction :output)
                      (valid-count? total)
                      (> (+ image audio) total))]
    {:image image
     :audio audio
     :problems (cond-> unresolved
                 invalid?
                 (conj (str (name direction)
                            " modality tokens exceed total tokens")))}))

(defn- token-cost-analysis
  [usage pricing {:keys [input-modality output-modality]}]
  (let [input-present? (contains? usage :usage/input-tokens)
        output-present? (contains? usage :usage/output-tokens)
        cache-read-present? (contains? usage :usage/cached-input-tokens)
        cache-write-present? (contains? usage :usage/cache-write-tokens)
        input (:usage/input-tokens usage)
        output (:usage/output-tokens usage)
        cache-read (:usage/cached-input-tokens usage)
        cache-write (:usage/cache-write-tokens usage)
        token-route? (or input-present? output-present?
                         cache-read-present? cache-write-present?
                         (contains? usage :usage/image-tokens)
                         (contains? usage :usage/audio-tokens))
        details (raw-token-details usage)
        input-split (direction-modalities
                     usage :input input (:input details) input-modality)
        output-split (direction-modalities
                      usage :output output (:output details) output-modality)
        cached-details (:cached details)
        cached-partition? (modality-partition? cached-details)
        cached-image (cond
                       (not (positive-count? cache-read)) 0
                       cached-partition?
                       (if (contains? cached-details :image_tokens)
                         (detail-count cached-details :image_tokens)
                         (when-not (positive-count? (:image input-split)) 0))
                       (positive-count? (:image input-split)) nil
                       :else 0)
        cached-audio (cond
                       (not (positive-count? cache-read)) 0
                       cached-partition?
                       (if (contains? cached-details :audio_tokens)
                         (detail-count cached-details :audio_tokens)
                         (when-not (positive-count? (:audio input-split)) 0))
                       (positive-count? (:audio input-split)) nil
                       :else 0)
        cache-problems
        (cond-> []
          (and (positive-count? cache-read)
               (or (nil? cached-image) (nil? cached-audio)))
          (conj "missing cached-token modality detail")
          (and (positive-count? cache-write)
               (or (positive-count? (:image input-split))
                   (positive-count? (:audio input-split))))
          (conj "missing cache-write modality detail")
          (and (number? cache-read)
               (> (+ (or cached-image 0) (or cached-audio 0)) cache-read))
          (conj "cached modality tokens exceed cached input tokens")
          (and (number? cached-image)
               (> cached-image (:image input-split)))
          (conj "cached image tokens exceed image input tokens")
          (and (number? cached-audio)
               (> cached-audio (:audio input-split)))
          (conj "cached audio tokens exceed audio input tokens")
          (and (number? input)
               (number? cached-image)
               (number? cached-audio)
               (> (+ (- (:image input-split) cached-image)
                     (- (:audio input-split) cached-audio))
                  input))
          (conj "uncached modality tokens exceed input tokens"))
        uncached-image (when (number? cached-image)
                         (- (:image input-split) cached-image))
        uncached-audio (when (number? cached-audio)
                         (- (:audio input-split) cached-audio))
        text-input (when (and (number? input)
                              (number? uncached-image)
                              (number? uncached-audio))
                     (- input uncached-image uncached-audio))
        text-output (when (number? output)
                      (- output
                         (:image output-split)
                         (:audio output-split)))
        cached-text (when (and (number? cache-read)
                               (number? cached-image)
                               (number? cached-audio))
                      (- cache-read cached-image cached-audio))
        counts {:input-tokens text-input
                :image-input-tokens uncached-image
                :audio-input-tokens uncached-audio
                :output-tokens text-output
                :image-output-tokens (:image output-split)
                :audio-output-tokens (:audio output-split)
                :cached-input-tokens cached-text
                :image-cached-input-tokens cached-image
                :audio-cached-input-tokens cached-audio
                :cache-write-tokens cache-write}
        rate-keys {:input-tokens :input-cost-per-million
                   :image-input-tokens :image-input-cost-per-million
                   :audio-input-tokens :audio-input-cost-per-million
                   :output-tokens :output-cost-per-million
                   :image-output-tokens :image-output-cost-per-million
                   :audio-output-tokens :audio-output-cost-per-million
                   :cached-input-tokens :cache-read-cost-per-million
                   :image-cached-input-tokens
                   :image-cache-read-cost-per-million
                   :audio-cached-input-tokens
                   :audio-cache-read-cost-per-million
                   :cache-write-tokens :cache-write-cost-per-million}
        usage-count-keys
        [:usage/input-tokens :usage/output-tokens
         :usage/cached-input-tokens :usage/cache-write-tokens
         :usage/image-tokens :usage/audio-tokens]
        invalid-counts
        (keep (fn [k]
                (when (and (contains? usage k)
                           (not (valid-count? (get usage k))))
                  k))
              usage-count-keys)
        missing-counts
        (cond-> []
          (and token-route?
               (some #(some? (get pricing %))
                     [:input-cost-per-million
                      :image-input-cost-per-million
                      :audio-input-cost-per-million])
               (not (valid-count? input)))
          (conj :usage/input-tokens)
          (and token-route?
               (some #(some? (get pricing %))
                     [:output-cost-per-million
                      :image-output-cost-per-million
                      :audio-output-cost-per-million])
               (not (valid-count? output)))
          (conj :usage/output-tokens))
        missing-rates
        (reduce-kv
         (fn [acc count-key count]
           (let [rate-key (get rate-keys count-key)]
             (if (and (token-pricing-present? pricing)
                      (positive-count? count)
                      (not (valid-rate? (get pricing rate-key))))
               (conj acc rate-key)
               acc)))
         []
         counts)
        pieces
        (reduce-kv
         (fn [acc count-key count]
           (let [rate (get pricing (get rate-keys count-key))]
             (if-let [piece (per-million count rate)]
               (conj acc piece)
               acc)))
         []
         counts)]
    {:token-route? token-route?
     :counts counts
     :pieces pieces
     :problems (into (vec (distinct (concat (:problems input-split)
                                            (:problems output-split)
                                            cache-problems
                                            invalid-counts)))
                     (concat missing-counts missing-rates))}))

(defn- request-cost-analysis [usage request-cost]
  (when (some? request-cost)
    (let [request-count (if (contains? usage :usage/request-count)
                          (:usage/request-count usage)
                          1)]
      (if (and (valid-count? request-count)
               (valid-rate? request-cost))
        {:piece (.multiply (bigdec request-cost)
                           (bigdec request-count))}
        {:problem (if (valid-rate? request-cost)
                    :usage/request-count
                    :request-cost)}))))

(defn estimate-cost
  "Compute cost from canonical Usage and a pricing-entry. Returns a
   cost-result map. Pure — no registry lookup. The optional context identifies
   endpoint-guaranteed input/output modalities when raw usage omits a split."
  ([usage pricing]
   (estimate-cost usage pricing {}))
  ([usage pricing context]
   (if-not pricing
     (cost-result nil :unknown :none "No pricing data")
     (let [{:keys [token-route? pieces problems]}
           (token-cost-analysis usage pricing context)
           request-analysis
           (request-cost-analysis usage (:request-cost pricing))
           request-piece (:piece request-analysis)
           pieces (cond-> pieces request-piece (conj request-piece))
           missing-usage? (and (token-pricing-present? pricing)
                               (not token-route?))
           amount (when (seq pieces) (reduce + 0M pieces))
           problems (cond-> problems
                      (:problem request-analysis)
                      (conj (:problem request-analysis))
                      missing-usage?
                      (conj "missing token usage"))]
       (cond
         (seq problems)
         (cost-result nil :estimated (:source pricing)
                      "Incomplete token pricing data"
                      :notes [(str "Missing or ambiguous fields: "
                                   (str/join ", " (map name problems)))])
         (some? amount)
         (cost-result amount :actual (:source pricing)
                      "Usage-based pricing")
         :else
         (cost-result nil :estimated (:source pricing)
                      "Incomplete pricing data"))))))

(defn estimate-cost-for-model
  "Look up pricing through the registry and estimate cost for the given
   usage. Returns a cost-result. Works for any provider+model the
   merged registry knows — no manual registration required.

   When the provider profile carries a :profile/cost-calculator fn, it
   is called with {:provider :model :usage :pricing} and must return a
   cost-result map. This is the escape hatch for providers whose pricing
   doesn't fit the default per-million-token formula (Perplexity's
   citation-token surcharge is the canonical example)."
  [provider model usage]
  (let [pricing (get-pricing provider model)
        custom-fn (some-> (provider/get-provider provider)
                          :profile/cost-calculator)]
    (if custom-fn
      (custom-fn {:provider provider :model model
                  :usage usage :pricing pricing})
      (estimate-cost usage pricing))))

;; ---------------------------------------------------------------------------
;; Canonical :response/cost stamping
;;
;; Honesty rule: never substitute $0 for unknown. When pricing or usage
;; is missing, :cost/usd is the keyword :unknown.
;; ---------------------------------------------------------------------------

(defn- canonical-token-breakdown [usage pricing context]
  (let [{:keys [counts]} (token-cost-analysis usage pricing context)
        count-entry
        (fn [breakdown count-key output-key _rate-key]
          (let [count (get counts count-key)]
            (if (positive-count? count)
              (assoc breakdown output-key count)
              breakdown)))]
    (-> (cond-> {}
          (contains? usage :usage/input-tokens)
          (assoc :input-tokens (:usage/input-tokens usage))
          (contains? usage :usage/output-tokens)
          (assoc :output-tokens (:usage/output-tokens usage))
          (contains? usage :usage/request-count)
          (assoc :request-count (:usage/request-count usage))
          (contains? usage :usage/cached-input-tokens)
          (assoc :cached-input-tokens (:usage/cached-input-tokens usage))
          (contains? usage :usage/cache-write-tokens)
          (assoc :cache-write-tokens (:usage/cache-write-tokens usage)))
        (count-entry :input-tokens :text-input-tokens
                     :input-cost-per-million)
        (count-entry :image-input-tokens :image-input-tokens
                     :image-input-cost-per-million)
        (count-entry :audio-input-tokens :audio-input-tokens
                     :audio-input-cost-per-million)
        (count-entry :output-tokens :text-output-tokens
                     :output-cost-per-million)
        (count-entry :image-output-tokens :image-output-tokens
                     :image-output-cost-per-million)
        (count-entry :audio-output-tokens :audio-output-tokens
                     :audio-output-cost-per-million)
        (count-entry :cached-input-tokens :text-cached-input-tokens
                     :cache-read-cost-per-million)
        (count-entry :image-cached-input-tokens :image-cached-input-tokens
                     :image-cache-read-cost-per-million)
        (count-entry :audio-cached-input-tokens :audio-cached-input-tokens
                     :audio-cache-read-cost-per-million)
        (cond->
         (:input-cost-per-million pricing)
          (assoc :input-cost-per-million
                 (:input-cost-per-million pricing))
          (:output-cost-per-million pricing)
          (assoc :output-cost-per-million
                 (:output-cost-per-million pricing))
          (:cache-read-cost-per-million pricing)
          (assoc :cache-read-cost-per-million
                 (:cache-read-cost-per-million pricing))
          (:cache-write-cost-per-million pricing)
          (assoc :cache-write-cost-per-million
                 (:cache-write-cost-per-million pricing))
          (:image-input-cost-per-million pricing)
          (assoc :image-input-cost-per-million
                 (:image-input-cost-per-million pricing))
          (:image-output-cost-per-million pricing)
          (assoc :image-output-cost-per-million
                 (:image-output-cost-per-million pricing))
          (:audio-input-cost-per-million pricing)
          (assoc :audio-input-cost-per-million
                 (:audio-input-cost-per-million pricing))
          (:audio-output-cost-per-million pricing)
          (assoc :audio-output-cost-per-million
                 (:audio-output-cost-per-million pricing))
          (:image-cache-read-cost-per-million pricing)
          (assoc :image-cache-read-cost-per-million
                 (:image-cache-read-cost-per-million pricing))
          (:audio-cache-read-cost-per-million pricing)
          (assoc :audio-cache-read-cost-per-million
                 (:audio-cache-read-cost-per-million pricing))
          (:request-cost pricing)
          (assoc :request-cost (:request-cost pricing))))))

(defn canonical-cost
  "Build a canonical :response/cost map for (provider, model, usage).
   Returns nil when usage is nil. Optional context identifies an endpoint's
   guaranteed input/output modalities when provider detail is absent."
  ([provider model usage]
   (canonical-cost provider model usage {}))
  ([provider model usage context]
   (when usage
     (let [pricing (get-pricing provider model)
           custom-fn (some-> (provider/get-provider provider)
                             :profile/cost-calculator)
           result (if custom-fn
                    (custom-fn {:provider provider :model model
                                :usage usage :pricing pricing})
                    (estimate-cost usage pricing context))
           amount (:cost/amount-usd result)
           status (:cost/status result)
           src (:source pricing)
           src-url (:source-url pricing)]
       (if (and (= status :actual) (some? amount))
         {:cost/usd amount
          :cost/estimated? true
          :cost/pricing-source (when src (name src))
          :cost/source-url src-url
          :cost/breakdown (canonical-token-breakdown usage pricing context)}
         {:cost/usd :unknown
          :cost/estimated? true
          :cost/pricing-source (when src (name src))
          :cost/source-url src-url
          :cost/reason (cond
                         (nil? pricing) "no pricing data for model"
                         :else (or (:cost/label result)
                                   "incomplete pricing data"))})))))

(defn cost-result->canonical
  "Convert a modality-specific cost-result into the public
   :response/cost shape. Used by non-chat drivers whose cost is based
   on image count, audio duration, or synthesized characters instead of
   chat token usage."
  [result pricing breakdown]
  (let [amount (:cost/amount-usd result)
        status (:cost/status result)
        src (:source pricing)
        src-url (:source-url pricing)]
    (if (and (= status :actual) (some? amount))
      {:cost/usd amount
       :cost/estimated? true
       :cost/pricing-source (when src (name src))
       :cost/source-url src-url
       :cost/breakdown breakdown}
      {:cost/usd :unknown
       :cost/estimated? true
       :cost/pricing-source (when src (name src))
       :cost/source-url src-url
       :cost/reason (cond
                      (nil? pricing) "no pricing data for model"
                      :else (or (:cost/label result) "incomplete pricing data"))})))

(defn canonical-cache
  "Build a canonical :response/cache map from a usage map.

   Status is :hit when the provider reported a positive cached-input-tokens
   count, :miss when it explicitly reported 0, and :unknown when the
   provider did not report cache stats at all.

   The usage normalizers omit :usage/cached-input-tokens when the raw
   provider payload had no cache field — that absence is how :unknown
   propagates here."
  [usage]
  (let [cached (:usage/cached-input-tokens usage)
        write (:usage/cache-write-tokens usage)]
    (cond-> {:cache/status (cond
                             (and (number? cached) (pos? cached)) :hit
                             (and (number? cached) (zero? cached)) :miss
                             :else :unknown)
             :cache/cached-tokens (if (number? cached) cached :unknown)}
      (or (number? write) (some? cached))
      (assoc :cache/cache-write-tokens (if (number? write) write :unknown)))))

(defn stamp-response-cost-and-cache
  "Preserve an existing response cost, estimating from usage only when absent.
   Cache information is derived from usage. Pure transform — does not touch
   the wire. Missing usage leaves an absent cost absent and stamps unknown
   cache information."
  [response provider-id model]
  (let [usage (:response/usage response)
        cost (or (:response/cost response)
                 (canonical-cost provider-id model usage))
        cache (canonical-cache (or usage {}))]
    (cond-> response
      cost (assoc :response/cost cost)
      true (assoc :response/cache cache))))

;; ---------------------------------------------------------------------------
;; Per-modality cost helpers
;; ---------------------------------------------------------------------------

(defn embedding-cost
  "Cost for a text embedding call. Embeddings only meter input tokens."
  [usage pricing]
  (let [tokens (:usage/input-tokens usage)
        rate (:input-cost-per-million pricing)]
    (if-let [amount (per-million tokens rate)]
      (cost-result amount :actual (:source pricing)
                   (str "Embedding tokens: " tokens))
      (cost-result nil :estimated (:source pricing)
                   "Missing embedding token usage or pricing"))))

(defn image-cost
  "Cost for an image-generation call. Token-metered endpoints use their known
   text-input/image-output context, while flat-rate endpoints may bill per
   image or per megapixel."
  [{:keys [usage n-images width height]} pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (if (and usage
             (some #(contains? usage %)
                   [:usage/input-tokens :usage/output-tokens
                    :usage/image-tokens :usage/audio-tokens]))
      (estimate-cost usage pricing
                     {:input-modality :text
                      :output-modality :image})
      (let [n n-images
            per-image (:image-cost-per-image pricing)
            per-mp (:image-cost-per-megapixel pricing)
            megapixels (when (and (valid-count? width)
                                  (valid-count? height))
                         (/ (* width height) 1000000.0))]
        (cond
          (some? per-image)
          (if (and (valid-count? n) (valid-rate? per-image))
            (cost-result (-> (bigdec n) (.multiply (bigdec per-image)))
                         :actual (:source pricing)
                         (str "Images: " n))
            (cost-result nil :estimated (:source pricing)
                         "Missing image count or per-image pricing"))

          (some? per-mp)
          (if (and (valid-count? n)
                   (some? megapixels)
                   (valid-rate? per-mp))
            (cost-result (-> (bigdec n)
                             (.multiply (bigdec megapixels))
                             (.multiply (bigdec per-mp)))
                         :actual (:source pricing)
                         (str "Images: " n " @ "
                              (format "%.2f" megapixels) " MP each"))
            (cost-result nil :estimated (:source pricing)
                         "Missing image count, dimensions, or per-megapixel pricing"))

          :else
          (cost-result nil :estimated (:source pricing)
                       "No image pricing for model"))))))

(defn transcription-cost
  "Cost for speech-to-text. Token-billed responses use audio-input/text-output
   rates. Duration-billed responses use reported seconds and a per-minute rate."
  [{:keys [usage duration-seconds]} pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (if (and usage
             (some #(contains? usage %)
                   [:usage/input-tokens :usage/output-tokens
                    :usage/audio-tokens]))
      (estimate-cost usage pricing
                     {:input-modality :audio
                      :output-modality :text})
      (let [seconds (or (:usage/duration-seconds usage)
                        duration-seconds)
            per-min (:transcription-cost-per-minute pricing)]
        (if (and (valid-count? seconds) (valid-rate? per-min))
          (let [minutes (/ seconds 60.0)
                amount (-> (bigdec minutes) (.multiply (bigdec per-min)))]
            (cost-result amount :actual (:source pricing)
                         (str "Duration: " (format "%.2f" minutes) " min")))
          (cost-result nil :estimated (:source pricing)
                       "Missing duration or per-minute pricing"))))))

(defn rerank-cost
  "Cost for reranking. Provider-reported search units are an explicit billing
   dimension and are never substituted with request or query counts."
  [usage pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (if (contains? usage :usage/search-units)
      (let [units (:usage/search-units usage)
            rate (:rerank-cost-per-search-unit pricing)]
        (if (and (valid-count? units) (valid-rate? rate))
          (cost-result (-> (bigdec units) (.multiply (bigdec rate)))
                       :actual (:source pricing)
                       (str "Rerank search units: " units))
          (cost-result nil :estimated (:source pricing)
                       "Missing rerank search-unit usage or pricing")))
      (estimate-cost usage pricing))))

(defn tts-cost
  "Cost for a text-to-speech call. Most TTS providers (OpenAI, ElevenLabs)
   price per million output characters."
  [{:keys [characters]} pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [per-m (:tts-cost-per-million-chars pricing)]
      (if-let [amount (per-million characters per-m)]
        (cost-result amount :actual (:source pricing)
                     (str "Characters: " characters))
        (cost-result nil :estimated (:source pricing)
                     "Missing TTS character usage or pricing")))))
