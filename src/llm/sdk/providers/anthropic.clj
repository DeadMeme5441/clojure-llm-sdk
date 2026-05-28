(ns llm.sdk.providers.anthropic
  "Compatibility shim. Implementation lives in llm.sdk.providers.anthropic.chat."
  (:require [llm.sdk.providers.anthropic.chat :as impl]))

(def make-transport impl/make-transport)
(def build-request-anthropic impl/build-request-anthropic)
(def parse-response-anthropic impl/parse-response-anthropic)
(def parse-stream-event-anthropic impl/parse-stream-event-anthropic)
(def parse-error-anthropic impl/parse-error-anthropic)
(def oauth-token? impl/oauth-token?)
(def sanitize-system-for-oauth @#'impl/sanitize-system-for-oauth)
(def mcp-prefix-tools @#'impl/mcp-prefix-tools)
(def mcp-prefix-tool-names-in-messages @#'impl/mcp-prefix-tool-names-in-messages)
