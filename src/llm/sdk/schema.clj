(ns llm.sdk.schema
  "Canonical request/response schemas for the LLM SDK.
   All provider adapters translate to/from these shapes."
  (:require [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Content parts
;; ---------------------------------------------------------------------------

(def TextPart
  [:map
   [:part/type [:= :text]]
   [:text string?]])

(def ImagePart
  [:map
   [:part/type [:= :image]]
   [:image/url string?]
   [:image/detail {:optional true} [:enum :auto :low :high]]])

(def FilePart
  [:map
   [:part/type [:= :file]]
   [:file/name string?]
   [:file/content string?]
   [:file/mime-type {:optional true} string?]])

(def ToolCallPart
  [:map
   [:part/type [:= :tool-call]]
   [:tool-call/id string?]
   [:tool-call/name string?]
   [:tool-call/arguments string?]
   [:tool-call/provider-data {:optional true} map?]])

(def ToolResultPart
  [:map
   [:part/type [:= :tool-result]]
   [:tool-result/id string?]
   [:tool-result/name string?]
   [:tool-result/content string?]
   [:tool-result/is-error {:optional true} boolean?]])

(def ReasoningPart
  [:map
   [:part/type [:= :reasoning]]
   [:reasoning/text string?]
   [:reasoning/encrypted {:optional true} boolean?]
   [:reasoning/signature {:optional true} string?]])

(def SafetyPart
  [:map
   [:part/type [:= :safety]]
   [:safety/category string?]
   [:safety/severity [:or string? keyword?]]
   [:safety/blocked boolean?]
   [:safety/details {:optional true} map?]])

(def CitationPart
  "A search-result / cited-source surfaced by a provider. Perplexity
   emits these inline; Anthropic web-search and Cohere documents will
   reuse the same shape under T2-02 + later issues."
  [:map
   [:part/type [:= :citation]]
   [:citation/url string?]
   [:citation/title {:optional true} string?]
   [:citation/snippet {:optional true} string?]
   [:citation/text-range {:optional true} [:tuple int? int?]]
   [:citation/source-id {:optional true} string?]])

(def ProviderStatePart
  [:map
   [:part/type [:= :provider-state]]
   [:provider-state/provider keyword?]
   [:provider-state/data map?]])

(def UnknownProviderPart
  [:map
   [:part/type [:= :unknown/provider-native]]
   [:unknown/provider keyword?]
   [:unknown/data any?]])

(def Part
  [:orn
   [:text TextPart]
   [:image ImagePart]
   [:file FilePart]
   [:tool-call ToolCallPart]
   [:tool-result ToolResultPart]
   [:reasoning ReasoningPart]
   [:safety SafetyPart]
   [:citation CitationPart]
   [:provider-state ProviderStatePart]
   [:unknown/provider-native UnknownProviderPart]])

;; ---------------------------------------------------------------------------
;; Messages
;; ---------------------------------------------------------------------------

(def Message
  [:map
   [:message/role [:enum :system :developer :user :assistant :tool]]
   [:message/content {:optional true} [:or string? [:vector Part]]]
   [:message/tool-calls {:optional true} [:vector ToolCallPart]]
   [:message/name {:optional true} string?]
   [:message/provider-data {:optional true} map?]])

;; ---------------------------------------------------------------------------
;; Tools
;; ---------------------------------------------------------------------------

(def ToolFunction
  [:map
   [:type [:= :function]]
   [:function
    [:map
     [:name string?]
     [:description {:optional true} string?]
     [:parameters {:optional true} map?]
     [:strict {:optional true} boolean?]]]])

;; ---------------------------------------------------------------------------
;; Request
;; ---------------------------------------------------------------------------

(def Request
  [:map
   [:request/model string?]
   [:request/messages [:vector Message]]
   [:request/tools {:optional true} [:vector ToolFunction]]
   [:request/tool-choice {:optional true}
    [:or [:enum :auto :none :required]
         [:map [:type [:= :function]] [:function [:map [:name string?]]]]]]
   [:request/temperature {:optional true} number?]
   [:request/top-p {:optional true} number?]
   [:request/max-tokens {:optional true} int?]
   [:request/stop {:optional true} [:or string? [:vector string?]]]
   [:request/response-format {:optional true}
    [:map
     [:type [:enum :text :json_schema :json_object]]
     [:name {:optional true} string?]
     [:description {:optional true} string?]
     [:strict {:optional true} boolean?]
     [:json-schema {:optional true} map?]]]
   [:request/reasoning {:optional true}
    [:map
     [:enabled {:optional true} boolean?]
     [:effort {:optional true} [:enum :minimal :low :medium :high :xhigh]]
     [:budget {:optional true} int?]]]
   [:request/cache {:optional true}
    [:map
     [:enabled? {:optional true} boolean?]
     [:ttl {:optional true} [:enum "5m" "1h"]]
     [:strategy {:optional true} [:enum :auto :system-and-3 :explicit :none]]
     [:scope-id {:optional true} string?]
     [:cached-content-id {:optional true} string?]
     [:breakpoints {:optional true} int?]
     [:tools-cache? {:optional true} boolean?]]]
   [:request/metadata {:optional true} map?]
   [:request/provider-options {:optional true} map?]])

;; ---------------------------------------------------------------------------
;; Usage
;; ---------------------------------------------------------------------------

(def Usage
  [:map
   [:usage/input-tokens int?]
   [:usage/output-tokens int?]
   [:usage/reasoning-tokens {:optional true} int?]
   [:usage/cached-input-tokens {:optional true} int?]
   [:usage/cache-write-tokens {:optional true} int?]
   [:usage/image-tokens {:optional true} int?]
   [:usage/audio-tokens {:optional true} int?]
   [:usage/file-tokens {:optional true} int?]
   [:usage/citation-tokens {:optional true} int?]
   [:usage/search-queries {:optional true} int?]
   [:usage/total-tokens {:optional true} int?]
   [:usage/request-count {:optional true} int?]
   [:usage/provider-raw {:optional true} map?]])

;; ---------------------------------------------------------------------------
;; Embeddings request / response
;; ---------------------------------------------------------------------------

(def EmbedRequest
  [:map
   [:embed/model string?]
   [:embed/inputs [:vector string?]]
   [:embed/dimensions {:optional true} int?]
   [:embed/encoding-format {:optional true} [:enum :float :base64]]
   [:embed/user {:optional true} string?]
   [:embed/provider-options {:optional true} map?]])

(def EmbedResponse
  [:map
   [:embed/id {:optional true} string?]
   [:embed/provider keyword?]
   [:embed/model string?]
   [:embed/vectors [:vector [:vector number?]]]
   [:embed/dimensions {:optional true} int?]
   [:response/usage {:optional true} Usage]
   [:embed/provider-data {:optional true} map?]
   [:embed/raw {:optional true} any?]])

;; ---------------------------------------------------------------------------
;; Image generation request / response (T2-10)
;; ---------------------------------------------------------------------------

(def ImageGenRequest
  [:map
   [:image/model {:optional true} string?]
   [:image/prompt string?]
   [:image/n {:optional true} int?]
   [:image/size {:optional true} string?]
   [:image/quality {:optional true} [:enum :standard :hd :low :medium :high :auto]]
   [:image/style {:optional true} [:enum :vivid :natural]]
   [:image/response-format {:optional true} [:enum :url :b64_json]]
   [:image/user {:optional true} string?]
   [:image/provider-options {:optional true} map?]])

(def Image
  [:map
   [:image/url {:optional true} string?]
   [:image/b64 {:optional true} string?]
   [:image/revised-prompt {:optional true} string?]])

(def ImageGenResponse
  [:map
   [:image/id {:optional true} string?]
   [:image/provider keyword?]
   [:image/model string?]
   [:image/images [:vector Image]]
   [:image/created {:optional true} int?]
   [:response/usage {:optional true} Usage]
   [:image/raw {:optional true} any?]])

;; ---------------------------------------------------------------------------
;; Rerank request / response (T2-16)
;; ---------------------------------------------------------------------------

(def RerankRequest
  [:map
   [:rerank/model string?]
   [:rerank/query string?]
   [:rerank/documents [:vector string?]]
   [:rerank/top-n {:optional true} int?]
   [:rerank/return-documents {:optional true} boolean?]
   [:rerank/provider-options {:optional true} map?]])

(def RerankResult
  [:map
   [:rerank/index int?]
   [:rerank/score number?]
   [:rerank/document {:optional true} string?]])

(def RerankResponse
  [:map
   [:rerank/id {:optional true} string?]
   [:rerank/provider keyword?]
   [:rerank/model string?]
   [:rerank/results [:vector RerankResult]]
   [:response/usage {:optional true} Usage]
   [:rerank/raw {:optional true} any?]])

;; ---------------------------------------------------------------------------
;; Moderation request / response (T2-13)
;; ---------------------------------------------------------------------------

(def ModerationRequest
  [:map
   [:moderation/model {:optional true} string?]
   [:moderation/inputs [:vector
                        [:or string?
                         [:map
                          [:type [:enum :text :image_url]]
                          [:text {:optional true} string?]
                          [:image_url {:optional true} string?]]]]]
   [:moderation/provider-options {:optional true} map?]])

(def ModerationResult
  [:map
   [:moderation/flagged? boolean?]
   [:moderation/categories {:optional true} [:map-of keyword? boolean?]]
   [:moderation/scores {:optional true} [:map-of keyword? number?]]
   [:moderation/categories-applied {:optional true} [:map-of keyword? [:vector keyword?]]]])

(def ModerationResponse
  [:map
   [:moderation/id {:optional true} string?]
   [:moderation/provider keyword?]
   [:moderation/model string?]
   [:moderation/results [:vector ModerationResult]]
   [:moderation/raw {:optional true} any?]])

;; ---------------------------------------------------------------------------
;; Cost (canonical :response/cost)
;;
;; Honesty rule: never substitute $0 for unknown. When pricing or usage
;; is missing, :cost/usd is the keyword :unknown — not 0M.
;; ---------------------------------------------------------------------------

(def Cost
  [:map
   [:cost/usd [:or number? [:= :unknown]]]
   [:cost/estimated? boolean?]
   [:cost/pricing-source {:optional true} [:or string? keyword? nil?]]
   [:cost/source-url {:optional true} [:or string? nil?]]
   [:cost/breakdown {:optional true} map?]
   [:cost/reason {:optional true} string?]])

;; ---------------------------------------------------------------------------
;; Cache (canonical :response/cache)
;;
;; Honesty rule: never substitute 0 for unknown. When the provider did
;; not report cache stats, :cache/status is :unknown and the token counts
;; are the keyword :unknown — not 0.
;; ---------------------------------------------------------------------------

(def Cache
  [:map
   [:cache/status [:enum :hit :miss :unknown]]
   [:cache/cached-tokens [:or int? [:= :unknown]]]
   [:cache/cache-write-tokens {:optional true} [:or int? [:= :unknown]]]])

;; ---------------------------------------------------------------------------
;; Response
;; ---------------------------------------------------------------------------

(def Response
  [:map
   [:response/id {:optional true} string?]
   [:response/provider keyword?]
   [:response/model string?]
   [:response/parts [:vector Part]]
   [:response/tool-calls {:optional true} [:vector ToolCallPart]]
   [:response/finish-reason [:enum :stop :length :tool-calls :content-filter
                            :incomplete :unknown]]
   [:response/usage {:optional true} Usage]
   [:response/cost {:optional true} Cost]
   [:response/cache {:optional true} Cache]
   [:response/provider-data {:optional true} map?]
   [:response/raw {:optional true} any?]])

;; ---------------------------------------------------------------------------
;; Stream events
;; ---------------------------------------------------------------------------

(def StreamEvent
  [:orn
   [:start [:map [:event/type [:= :stream/start]] [:event/request-id {:optional true} string?]]]
   [:content-delta [:map [:event/type [:= :stream/content-delta]] [:event/delta string?]]]
   [:reasoning-delta [:map [:event/type [:= :stream/reasoning-delta]] [:event/delta string?] [:event/encrypted {:optional true} boolean?]]]
   [:tool-call-start [:map [:event/type [:= :stream/tool-call-start]] [:tool-call/index int?] [:tool-call/id string?] [:tool-call/name string?]]]
   [:tool-call-delta [:map [:event/type [:= :stream/tool-call-delta]] [:tool-call/index int?] [:tool-call/arguments-delta string?]]]
   [:tool-call-end [:map [:event/type [:= :stream/tool-call-end]] [:tool-call/index int?]]]
   [:usage [:map [:event/type [:= :stream/usage]] [:usage Usage]]]
   [:provider-state [:map [:event/type [:= :stream/provider-state]] [:provider-state/provider keyword?] [:provider-state/data map?]]]
   [:citation [:map
               [:event/type [:= :stream/citation]]
               [:citation/url string?]
               [:citation/title {:optional true} string?]
               [:citation/snippet {:optional true} string?]]]
   [:error [:map [:event/type [:= :stream/error]] [:error/error any?]]]
   [:end [:map [:event/type [:= :stream/end]] [:event/finish-reason {:optional true} [:enum :stop :length :tool-calls :content-filter :incomplete :unknown]]]]])

;; ---------------------------------------------------------------------------
;; Provider profile
;; ---------------------------------------------------------------------------

(def AuthStrategy
  [:enum :bearer :api-key-header :api-key-query :oauth :oauth-external
   :gcp-oauth :aws-sigv4 :none])

(def ProviderProfile
  [:map
   [:profile/id keyword?]
   [:profile/protocol-family keyword?]
   [:profile/base-url string?]
   [:profile/auth-strategy AuthStrategy]
   [:profile/auth-header-name {:optional true} string?]
   [:profile/auth-query-param {:optional true} string?]
   [:profile/default-headers {:optional true} map?]
   [:profile/supports-model-listing boolean?]
   [:profile/default-max-tokens {:optional true} int?]
   [:profile/fixed-temperature {:optional true} [:or number? [:= :omit]]]
   [:profile/capabilities {:optional true} [:set keyword?]]
   [:profile/env-var-names {:optional true} [:vector string?]]
   [:profile/quirks {:optional true} map?]
   [:profile/transport-constructor {:optional true} ifn?]
   ;; Optional adapter hooks
   [:profile/embed-transport-constructor {:optional true} ifn?]
   [:profile/moderation-transport-constructor {:optional true} ifn?]
   [:profile/rerank-transport-constructor {:optional true} ifn?]
   [:profile/image-transport-constructor {:optional true} ifn?]
   [:profile/transcribe-transport-constructor {:optional true} ifn?]
   [:profile/speak-transport-constructor {:optional true} ifn?]
   [:profile/url-builder {:optional true} ifn?]
   [:profile/supported-params {:optional true} [:set keyword?]]])

;; ---------------------------------------------------------------------------
;; Validators
;; ---------------------------------------------------------------------------

(def validate-request (m/validator Request))
(def validate-response (m/validator Response))
(def validate-usage (m/validator Usage))
(def validate-part (m/validator Part))
(def validate-message (m/validator Message))
(def validate-stream-event (m/validator StreamEvent))
(def validate-provider-profile (m/validator ProviderProfile))
(def validate-embed-request (m/validator EmbedRequest))
(def validate-embed-response (m/validator EmbedResponse))
(def validate-moderation-request (m/validator ModerationRequest))
(def validate-moderation-response (m/validator ModerationResponse))
(def validate-rerank-request (m/validator RerankRequest))
(def validate-rerank-response (m/validator RerankResponse))
(def validate-image-gen-request (m/validator ImageGenRequest))
(def validate-image-gen-response (m/validator ImageGenResponse))

(defn explain-request [x] (m/explain Request x))
(defn explain-response [x] (m/explain Response x))
(defn explain-embed-request [x] (m/explain EmbedRequest x))
(defn explain-embed-response [x] (m/explain EmbedResponse x))
(defn explain-moderation-request [x] (m/explain ModerationRequest x))
(defn explain-moderation-response [x] (m/explain ModerationResponse x))
(defn explain-rerank-request [x] (m/explain RerankRequest x))
(defn explain-rerank-response [x] (m/explain RerankResponse x))
(defn explain-image-gen-request [x] (m/explain ImageGenRequest x))
(defn explain-image-gen-response [x] (m/explain ImageGenResponse x))
