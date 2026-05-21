(ns llm.sdk.providers.openai-moderation
  "OpenAI Moderations adapter (T2-13).

   POST {base}/moderations. omni-moderation-latest (the default since
   Nov 2024) accepts multi-modal input — a vector of {:type :text|:image_url}
   maps as well as plain strings. text-moderation-* models are
   text-only.

   Response shape per the OpenAI Moderations API:
     {:id :model
      :results [{:flagged bool
                 :categories {category-name bool}
                 :category_scores {category-name float}
                 :category_applied_input_types {category-name [\"text\"|\"image\"]}}]}"
  (:require [llm.sdk.transport.moderate :as mt]
            [llm.sdk.provider :as provider]
            [llm.sdk.errors :as errors]))

;; ---------------------------------------------------------------------------
;; Request building
;; ---------------------------------------------------------------------------

(defn- canonical-input->openai [in]
  (cond
    (string? in) in

    (and (map? in) (= (:type in) :image_url))
    {:type "image_url" :image_url {:url (:image_url in)}}

    (and (map? in) (= (:type in) :text))
    {:type "text" :text (:text in)}

    :else in))

(defn build-moderation-request-openai
  [profile request]
  (let [model (or (:moderation/model request) "omni-moderation-latest")
        inputs (:moderation/inputs request)
        ;; OpenAI accepts a single string OR a vector of strings OR a
        ;; vector of multi-modal maps. Collapse a single string-only
        ;; input to a plain string (tighter payload, same result).
        input-payload (cond
                        (and (= 1 (count inputs)) (string? (first inputs)))
                        (first inputs)

                        :else
                        (mapv canonical-input->openai inputs))]
    {:method :post
     :url (str (:profile/base-url profile) "/moderations")
     :headers (provider/default-headers profile
                                        (provider/resolve-auth-token profile))
     :body {:model model
            :input input-payload}}))

;; ---------------------------------------------------------------------------
;; Response parsing
;; ---------------------------------------------------------------------------

(defn- ->full-name [k]
  ;; Cheshire keywordises JSON keys that contain '/' as namespaced
  ;; keywords (e.g. "violence/graphic" → :violence/graphic). Reassemble
  ;; the full original key name before normalising.
  (cond
    (keyword? k) (if-let [ns (namespace k)]
                   (str ns "/" (name k))
                   (name k))
    :else (str k)))

(defn- ->keyword [k]
  (-> (->full-name k)
      (.replace "/" "-")
      keyword))

(defn- ->bool-map [m]
  (when (map? m)
    (into {} (map (fn [[k v]] [(->keyword k) (boolean v)]) m))))

(defn- ->number-map [m]
  (when (map? m)
    (into {} (map (fn [[k v]] [(->keyword k) (double v)]) m))))

(defn- ->applied-types-map [m]
  (when (map? m)
    (into {}
          (map (fn [[k v]]
                 [(->keyword k)
                  (mapv keyword v)]))
          m)))

(defn- parse-result [r]
  (cond-> {:moderation/flagged? (boolean (:flagged r))}
    (:categories r)
    (assoc :moderation/categories (->bool-map (:categories r)))
    (:category_scores r)
    (assoc :moderation/scores (->number-map (:category_scores r)))
    (:category_applied_input_types r)
    (assoc :moderation/categories-applied
           (->applied-types-map (:category_applied_input_types r)))))

(defn parse-moderation-response-openai
  [profile raw]
  (let [results (mapv parse-result (:results raw))]
    {:moderation/id (:id raw)
     :moderation/provider (:profile/id profile)
     :moderation/model (:model raw)
     :moderation/results results
     :moderation/raw raw}))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(defn parse-moderation-error-openai
  [profile status body]
  (errors/classify-error (Exception. "OpenAI moderation API error")
                         :status status
                         :body body
                         :provider (:profile/id profile)))

;; ---------------------------------------------------------------------------
;; Transport record
;; ---------------------------------------------------------------------------

(defrecord OpenAIModerationTransport []
  mt/ModerationTransport
  (build-moderation-request [_ profile request]
    (build-moderation-request-openai profile request))
  (parse-moderation-response [_ profile raw]
    (parse-moderation-response-openai profile raw))
  (parse-moderation-error [_ profile status body]
    (parse-moderation-error-openai profile status body)))

(defn make-transport [] (->OpenAIModerationTransport))

;; ---------------------------------------------------------------------------
;; Attach
;; ---------------------------------------------------------------------------

(when-let [p (provider/get-provider :openai)]
  (provider/register-provider
   (-> p
       (assoc :profile/moderation-transport-constructor make-transport)
       (update :profile/capabilities (fnil conj #{}) :moderation))))
