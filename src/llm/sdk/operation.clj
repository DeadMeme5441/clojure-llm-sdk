(ns llm.sdk.operation
  "Shared synchronous request lifecycle for non-chat SDK operations."
  (:require [llm.sdk.retry :as retry]
            [llm.sdk.http :as http]
            [llm.sdk.provider :as provider]))

(defn run
  "Resolve a provider, validate and build an operation request, execute its
   HTTP effect, dispatch provider errors, and parse a successful response.

   Modality-specific wire formats, signing, parsing, and response validation
   remain in each operation/transport; this function owns only their common
   synchronous lifecycle."
  [{:keys [provider-id request config validate-request explain-request
           invalid-error-type invalid-message constructor-key
           unsupported-message build-request request-effect sign-request
           parse-response parse-error transport-error-message
           api-error-message retry]}]
  (let [profile (some-> (provider/get-provider provider-id)
                        (provider/apply-runtime-config config))
        profile (or profile
                    (throw (ex-info "Unknown provider"
                                    {:provider provider-id})))
        _ (when-not (validate-request request)
            (throw (ex-info invalid-message
                            {:error/type invalid-error-type
                             :schema/explain (explain-request request)})))
        constructor (or (get profile constructor-key)
                        (throw (ex-info unsupported-message
                                        {:provider provider-id})))
        transport (constructor)
        native-request (build-request transport profile request)
        native-request (provider/apply-http-options profile native-request)
        native-request (if sign-request
                         (sign-request profile native-request)
                         native-request)
        response (retry/request! (or request-effect http/request)
                                 native-request
                                 #(parse-error transport profile %1 %2)
                                 provider-id retry
                                 :transport-error-message transport-error-message
                                 :api-error-message api-error-message)]
    (parse-response transport profile response)))
