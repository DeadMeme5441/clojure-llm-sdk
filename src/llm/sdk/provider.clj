(ns llm.sdk.provider
  "Provider registry and per-call runtime configuration."
  (:require [llm.sdk.provider.auth :as auth]
            [llm.sdk.provider.registry :as registry]))

(def register-provider registry/register-provider)
(def get-provider registry/get-provider)
(def list-providers registry/list-providers)
(def provider-ids registry/provider-ids)

(def resolve-auth-token auth/resolve-auth-token)
(def auth-headers auth/auth-headers)
(def default-headers auth/default-headers)
(def apply-runtime-config auth/apply-runtime-config)
(def apply-http-options auth/apply-http-options)
