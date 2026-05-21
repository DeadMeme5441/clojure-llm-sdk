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
   [:usage/total-tokens {:optional true} int?]
   [:usage/request-count {:optional true} int?]
   [:usage/provider-raw {:optional true} map?]])

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
   [:error [:map [:event/type [:= :stream/error]] [:error/error any?]]]
   [:end [:map [:event/type [:= :stream/end]] [:event/finish-reason {:optional true} [:enum :stop :length :tool-calls :content-filter :incomplete :unknown]]]]])

;; ---------------------------------------------------------------------------
;; Provider profile
;; ---------------------------------------------------------------------------

(def AuthStrategy
  [:enum :bearer :api-key-header :api-key-query :oauth :gcp-oauth :aws-sigv4 :none])

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
   [:profile/transport-constructor ifn?]])

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

(defn explain-request [x] (m/explain Request x))
(defn explain-response [x] (m/explain Response x))
