(ns llm.sdk.providers.codex
  "Compatibility shim. Implementation lives in llm.sdk.providers.codex.responses."
  (:require [llm.sdk.providers.codex.responses :as impl]))

(def make-transport impl/make-transport)
(declare codex-auth-file-path)

(defn read-codex-auth []
  (with-redefs-fn {#'impl/codex-auth-file-path codex-auth-file-path}
    impl/read-codex-auth))

(defn codex-backend-auth-headers []
  (with-redefs-fn {#'impl/codex-auth-file-path codex-auth-file-path}
    impl/codex-backend-auth-headers))

(defn codex-backend-available? []
  (with-redefs-fn {#'impl/codex-auth-file-path codex-auth-file-path}
    impl/codex-backend-available?))
(def build-request-codex impl/build-request-codex)
(def parse-response-codex impl/parse-response-codex)
(def parse-stream-event-codex impl/parse-stream-event-codex)
(def parse-error-codex impl/parse-error-codex)
(def codex-auth-cache @#'impl/codex-auth-cache)
(def codex-auth-file-path @#'impl/codex-auth-file-path)
(def deterministic-call-id @#'impl/deterministic-call-id)
(def derive-responses-function-call-id @#'impl/derive-responses-function-call-id)
