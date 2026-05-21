(ns llm.sdk.request
  "Request preprocessing applied by llm.sdk/complete before the provider
   transport sees the request. Currently: drop+warn for canonical
   request fields the provider doesn't support.

   The supported-params set lives on the profile as
   :profile/supported-params (introduced in T2-12). When set, any
   canonical droppable field present in the request but absent from
   the set is removed before the transport builds the body, and one
   warning is emitted per call.

   When :profile/supported-params is NOT set on a profile, requests
   pass through unchanged — providers opt in to the drop+warn
   behaviour by populating the set.")

(def droppable-canonical-fields
  "Canonical request fields that may meaningfully be dropped before
   reaching a provider. :request/model and :request/messages are not
   in this set — they're required and validated upstream. Free-form
   fields (:request/metadata, :request/provider-options) are also not
   here since callers may have provider-specific payloads inside them."
  #{:request/tools :request/tool-choice
    :request/temperature :request/top-p
    :request/max-tokens :request/stop
    :request/response-format
    :request/reasoning
    :request/cache})

(def ^:dynamic *warn-fn*
  "Function invoked with a single warning string for each
   apply-supported-params call that drops at least one field. Defaults
   to writing to *err* on its own line; override (binding-style) to
   route through your logger of choice."
  (fn [^String msg]
    (binding [*out* *err*] (println msg))))

(defn apply-supported-params
  "If the profile carries :profile/supported-params, drop any canonical
   droppable field present in the request but not in the set. Emits
   one warning summarising every dropped field. Returns the (possibly
   mutated) request."
  [profile request]
  (if-let [supported (:profile/supported-params profile)]
    (let [dropped (->> droppable-canonical-fields
                       (filter #(and (contains? request %)
                                     (not (contains? supported %))))
                       vec)]
      (when (seq dropped)
        (*warn-fn*
         (format
          "[llm.sdk] Provider %s does not support %s — dropping before request"
          (:profile/id profile)
          dropped)))
      (apply dissoc request dropped))
    request))
