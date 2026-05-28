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
   Per-modality knobs:
     :image-cost-per-image           dollars per image returned
     :image-cost-per-megapixel       dollars per megapixel produced
     :transcription-cost-per-minute  dollars per minute of audio in
     :tts-cost-per-million-chars     dollars per 1M output characters
     :search-cost-per-call           dollars per search query (Perplexity)"
  [& {:keys [input output cache-read cache-write request-cost
             image-per-image image-per-megapixel
             transcription-per-minute tts-per-million-chars
             search-per-call
             source source-url pricing-version]}]
  {:input-cost-per-million input
   :output-cost-per-million output
   :cache-read-cost-per-million cache-read
   :cache-write-cost-per-million cache-write
   :request-cost request-cost
   :image-cost-per-image image-per-image
   :image-cost-per-megapixel image-per-megapixel
   :transcription-cost-per-minute transcription-per-minute
   :tts-cost-per-million-chars tts-per-million-chars
   :search-cost-per-call search-per-call
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
    (when (or (:input-per-million c)
              (:output-per-million c)
              (:image-per-image c)
              (:image-per-megapixel c)
              (:transcription-per-minute c)
              (:tts-per-million-chars c)
              (:search-per-call c)
              (:request-cost c))
      (pricing-entry
       :input (:input-per-million c)
       :output (:output-per-million c)
       :cache-read (:cache-read-per-million c)
       :cache-write (:cache-write-per-million c)
       :request-cost (:request-cost c)
       :image-per-image (:image-per-image c)
       :image-per-megapixel (:image-per-megapixel c)
       :transcription-per-minute (:transcription-per-minute c)
       :tts-per-million-chars (:tts-per-million-chars c)
       :search-per-call (:search-per-call c)
       :source (:model/source model-entry)
       :source-url (:model/source-url model-entry)))))

(defn- official-openai-pricing
  "Small current-pricing fallback for OpenAI models whose bundled
   models.dev entries exist but omit specialized cost data."
  [provider model]
  (when (= provider :openai)
    (case model
      ;; OpenAI pricing page, image generation models, standard tier.
      ;; gpt-image-* pricing is token-based. We map text input to
      ;; :input-cost-per-million and image output tokens to
      ;; :output-cost-per-million because OpenAI returns them in the
      ;; ordinary input/output token counters on /images/generations.
      "gpt-image-1-mini"
      (pricing-entry :input 2.0 :output 8.0
                     :cache-read 0.20
                     :source :openai-pricing-page
                     :source-url "https://developers.openai.com/api/docs/pricing")
      "gpt-image-1.5"
      (pricing-entry :input 5.0 :output 32.0
                     :cache-read 1.25
                     :source :openai-pricing-page
                     :source-url "https://developers.openai.com/api/docs/pricing")
      "gpt-image-2"
      (pricing-entry :input 5.0 :output 30.0
                     :cache-read 1.25
                     :source :openai-pricing-page
                     :source-url "https://developers.openai.com/api/docs/pricing")
      nil)))

;; ---------------------------------------------------------------------------
;; Registry-backed lookup
;; ---------------------------------------------------------------------------

(defn get-pricing
  "Get the pricing-entry for (provider, model), or nil when no tier of
   the registry has cost data for the pair."
  [provider model]
  (or (official-openai-pricing provider model)
      (some-> (registry/lookup provider model)
              model-entry->pricing-entry)))

(defn register-pricing
  "Register a caller-provided pricing entry as a registry override.
   Internally converts to ModelEntry shape and pushes into the override
   tier. The entry argument can be a legacy pricing-entry map, OR a map
   with :input/:output/:cache-read/:cache-write/:request-cost shorthand."
  [provider model entry]
  (let [norm (cond
               (:input-cost-per-million entry) entry
               :else
               (pricing-entry :input (:input entry)
                              :output (:output entry)
                              :cache-read (:cache-read entry)
                              :cache-write (:cache-write entry)
                              :request-cost (:request-cost entry)
                              :image-per-image (:image-per-image entry)
                              :image-per-megapixel (:image-per-megapixel entry)
                              :transcription-per-minute (:transcription-per-minute entry)
                              :tts-per-million-chars (:tts-per-million-chars entry)
                              :search-per-call (:search-per-call entry)
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
               (:request-cost norm) (assoc :request-cost (:request-cost norm))
               (:image-cost-per-image norm) (assoc :image-per-image (:image-cost-per-image norm))
               (:image-cost-per-megapixel norm) (assoc :image-per-megapixel (:image-cost-per-megapixel norm))
               (:transcription-cost-per-minute norm) (assoc :transcription-per-minute (:transcription-cost-per-minute norm))
               (:tts-cost-per-million-chars norm) (assoc :tts-per-million-chars (:tts-cost-per-million-chars norm))
               (:search-cost-per-call norm) (assoc :search-per-call (:search-cost-per-call norm)))]
    (registry/register-entry! provider model
                              (cond-> {}
                                (seq cost) (assoc :model/cost cost)
                                (:source-url norm) (assoc :model/source-url (:source-url norm))))))

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

(defn- per-million [n cost-per-m]
  (if (and n cost-per-m)
    (-> (bigdec n)
        (.multiply (bigdec cost-per-m))
        (.movePointLeft 6))
    0M))

(defn- safe-bigdec [x]
  (cond
    (nil? x) 0M
    (number? x) (try (bigdec x) (catch Exception _ 0M))
    :else 0M))

(defn- positive-count? [x]
  (and (number? x) (pos? x)))

(defn- token-pricing-present? [pricing]
  (some some?
        [(:input-cost-per-million pricing)
         (:output-cost-per-million pricing)
         (:cache-read-cost-per-million pricing)
         (:cache-write-cost-per-million pricing)]))

(defn- missing-token-rate-fields [usage pricing]
  (when (token-pricing-present? pricing)
    (cond-> []
      (and (positive-count? (:usage/input-tokens usage))
           (nil? (:input-cost-per-million pricing)))
      (conj :input-cost-per-million)
      (and (positive-count? (:usage/output-tokens usage))
           (nil? (:output-cost-per-million pricing)))
      (conj :output-cost-per-million)
      (and (positive-count? (:usage/cached-input-tokens usage))
           (nil? (:cache-read-cost-per-million pricing)))
      (conj :cache-read-cost-per-million)
      (and (positive-count? (:usage/cache-write-tokens usage))
           (nil? (:cache-write-cost-per-million pricing)))
      (conj :cache-write-cost-per-million))))

(defn- request-cost-amount [usage request-cost]
  (when (some? request-cost)
    (let [request-count (or (:usage/request-count usage) 1)]
      (.multiply (safe-bigdec request-cost)
                 (safe-bigdec request-count)))))

(defn estimate-cost
  "Compute cost from canonical Usage and a pricing-entry. Returns a
   cost-result map. Pure — no registry lookup."
  [usage pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [{:keys [input-cost-per-million output-cost-per-million
                  cache-read-cost-per-million cache-write-cost-per-million
                  request-cost]} pricing
          input-tokens (:usage/input-tokens usage 0)
          output-tokens (:usage/output-tokens usage 0)
          cache-read (:usage/cached-input-tokens usage 0)
          cache-write (:usage/cache-write-tokens usage 0)
          missing (missing-token-rate-fields usage pricing)
          pieces (cond-> []
                   (some? input-cost-per-million)
                   (conj (per-million input-tokens input-cost-per-million))
                   (some? output-cost-per-million)
                   (conj (per-million output-tokens output-cost-per-million))
                   (some? cache-read-cost-per-million)
                   (conj (per-million cache-read cache-read-cost-per-million))
                   (some? cache-write-cost-per-million)
                   (conj (per-million cache-write cache-write-cost-per-million))
                   (some? request-cost)
                   (conj (request-cost-amount usage request-cost)))
          amount (when (seq pieces) (reduce + 0M pieces))]
      (cond
        (seq missing)
        (cost-result amount :estimated (:source pricing)
                     "Incomplete token pricing data"
                     :notes [(str "Missing pricing fields: "
                                  (str/join ", " (map name missing)))])
        (some? amount)
        (let [request-count (or (:usage/request-count usage) 1)]
          (cost-result amount :actual (:source pricing)
                       (str "Usage: " input-tokens " in / " output-tokens
                            " out / " request-count " request(s)")))
        :else
        (cost-result nil :estimated (:source pricing)
                     "Incomplete pricing data")))))

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

(defn canonical-cost
  "Build a canonical :response/cost map for (provider, model, usage).
   Returns nil when usage is nil — caller should not stamp cost on a
   response that has no usage to attribute it to."
  [provider model usage]
  (when usage
    (let [pricing (get-pricing provider model)
          custom-fn (some-> (provider/get-provider provider)
                            :profile/cost-calculator)
          result (if custom-fn
                   (custom-fn {:provider provider :model model
                               :usage usage :pricing pricing})
                   (estimate-cost usage pricing))
          amount (:cost/amount-usd result)
          status (:cost/status result)
          src (:source pricing)
          src-url (:source-url pricing)]
      (cond
        (and (= status :actual) (some? amount))
        {:cost/usd amount
         :cost/estimated? true
         :cost/pricing-source (when src (name src))
         :cost/source-url src-url
         :cost/breakdown
         (cond-> {:input-tokens (:usage/input-tokens usage 0)
                  :output-tokens (:usage/output-tokens usage 0)}
           (some? (:usage/request-count usage))
           (assoc :request-count (:usage/request-count usage))
           (some? (:usage/cached-input-tokens usage))
           (assoc :cached-input-tokens (:usage/cached-input-tokens usage))
           (some? (:usage/cache-write-tokens usage))
           (assoc :cache-write-tokens (:usage/cache-write-tokens usage))
           (:input-cost-per-million pricing)
           (assoc :input-cost-per-million (:input-cost-per-million pricing))
           (:output-cost-per-million pricing)
           (assoc :output-cost-per-million (:output-cost-per-million pricing))
           (:cache-read-cost-per-million pricing)
           (assoc :cache-read-cost-per-million (:cache-read-cost-per-million pricing))
           (:cache-write-cost-per-million pricing)
           (assoc :cache-write-cost-per-million (:cache-write-cost-per-million pricing))
           (:request-cost pricing)
           (assoc :request-cost (:request-cost pricing))
           (:image-cost-per-image pricing)
           (assoc :image-cost-per-image (:image-cost-per-image pricing))
           (:image-cost-per-megapixel pricing)
           (assoc :image-cost-per-megapixel (:image-cost-per-megapixel pricing))
           (:transcription-cost-per-minute pricing)
           (assoc :transcription-cost-per-minute (:transcription-cost-per-minute pricing))
           (:tts-cost-per-million-chars pricing)
           (assoc :tts-cost-per-million-chars (:tts-cost-per-million-chars pricing))
           (:search-cost-per-call pricing)
           (assoc :search-cost-per-call (:search-cost-per-call pricing)))}

        :else
        {:cost/usd :unknown
         :cost/estimated? true
         :cost/pricing-source (when src (name src))
         :cost/source-url src-url
         :cost/reason (cond
                        (nil? pricing) "no pricing data for model"
                        :else (or (:cost/label result) "incomplete pricing data"))}))))

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
  "Augment a parsed canonical response with :response/cost and
   :response/cache, derived from its usage. Pure transform — does not
   touch the wire. When the response carries no :response/usage,
   stamps unknown cost and unknown cache (callers want consistent
   shape; honest unknowns are still surfaced)."
  [response provider-id model]
  (let [usage (:response/usage response)
        cost (canonical-cost provider-id model usage)
        cache (canonical-cache (or usage {}))]
    (cond-> response
      cost (assoc :response/cost cost)
      true (assoc :response/cache cache))))

;; ---------------------------------------------------------------------------
;; Per-modality cost helpers
;; ---------------------------------------------------------------------------

(defn embedding-cost
  "Cost for an embedding call. Embeddings only meter input tokens."
  [usage pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [tokens (:usage/input-tokens usage 0)
          amount (per-million tokens (:input-cost-per-million pricing))]
      (cost-result amount :actual (:source pricing)
                   (str "Embedding tokens: " tokens)))))

(defn image-cost
  "Cost for an image-generation call. Pricing may be per-image (DALL-E,
   ElevenLabs voice clone) or per-megapixel (Imagen, Stability).
   Args:
     :n-images   number of images produced
     :width      pixels (optional, used for per-megapixel pricing)
     :height     pixels (optional, used for per-megapixel pricing)"
  [{:keys [n-images width height]} pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [n (or n-images 1)
          per-image (:image-cost-per-image pricing)
          per-mp (:image-cost-per-megapixel pricing)
          megapixels (when (and width height)
                       (/ (* width height) 1000000.0))
          amount (cond
                   per-image (-> (bigdec n) (.multiply (bigdec per-image)))
                   (and per-mp megapixels)
                   (-> (bigdec n) (.multiply (bigdec megapixels))
                       (.multiply (bigdec per-mp)))
                   :else 0M)]
      (if (or per-image per-mp)
        (cost-result amount :actual (:source pricing)
                     (str "Images: " n
                          (when megapixels (str " @ " (format "%.2f" megapixels) " MP each"))))
        (cost-result nil :estimated (:source pricing)
                     "No image-cost pricing for model")))))

(defn transcription-cost
  "Cost for a speech-to-text call. Most providers price per-minute of
   audio input. duration-seconds may come from response :transcription/
   duration-seconds (verbose_json) or the caller's own metering."
  [{:keys [duration-seconds]} pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [minutes (when duration-seconds (/ duration-seconds 60.0))
          per-min (:transcription-cost-per-minute pricing)]
      (if (and minutes per-min)
        (let [amount (-> (bigdec minutes) (.multiply (bigdec per-min)))]
          (cost-result amount :actual (:source pricing)
                       (str "Duration: " (format "%.2f" minutes) " min")))
        (cost-result nil :estimated (:source pricing)
                     "Missing duration or per-minute pricing")))))

(defn tts-cost
  "Cost for a text-to-speech call. Most TTS providers (OpenAI, ElevenLabs)
   price per million output characters."
  [{:keys [characters]} pricing]
  (if-not pricing
    (cost-result nil :unknown :none "No pricing data")
    (let [chars (or characters 0)
          per-m (:tts-cost-per-million-chars pricing)]
      (if per-m
        (let [amount (per-million chars per-m)]
          (cost-result amount :actual (:source pricing)
                       (str "Characters: " chars)))
        (cost-result nil :estimated (:source pricing)
                     "No TTS pricing for model")))))
