# Changelog

All notable user-visible changes are tracked here.

## 0.2.4

### Fixed

- Preserved the first user message for `:codex-backend` requests that rely on
  the default backend instructions instead of providing an explicit leading
  system message.

## 0.2.1

### Added

- Documented the `:vertex-anthropic` provider across the README supported-surface
  table, the provider matrix (`doc/providers.md`), the configuration guide
  (`doc/provider-configuration.md`), and the LiteLLM parity / shape-audit ledgers,
  including credentials, model ids, and the region-gating (HTTP 404) caveat.

## 0.2.0

### Added

- Added `:vertex-anthropic`, a provider that serves Anthropic's Claude models
  through Google Vertex AI. It reuses the native Anthropic Messages request body,
  response parser, and streaming parser, and swaps in Vertex-specific transport:
  the `:rawPredict` / `:streamRawPredict` endpoints with the model in the URL path,
  GCP OAuth bearer auth via the existing ADC chain (instead of `x-api-key`), and an
  `anthropic_version` body field. Authenticates with the same GCP credentials as
  `:vertex-gemini`. Thinking blocks, tool use, file/document attachments, and native
  cache markers behave as on `:anthropic`.
- Added `scripts/vertex_anthropic_smoke.clj`, a live smoke script mirroring
  `scripts/vertex_stream_smoke.clj`.

## 0.1.0

First published release to Clojars as
`net.clojars.deadmeme5441/clojure-llm-sdk`. Consume it with
`{:mvn/version "0.1.0"}` instead of a git SHA.

### Added

- Published to Clojars with a tag-driven release workflow: pushing a `v*`
  tag builds, tests, and deploys the artifact automatically.
- Replaced the LiteLLM snapshot refresh script's hardcoded local-checkout
  path with a direct HTTPS fetch of the upstream pricing file.
- Added `:kimi-code`, an OpenAI Chat Completions compatible provider for Kimi Code at `https://api.kimi.com/coding/v1`.
- Added proper public library docs for getting started, API reference, provider configuration, and model registry behavior.
- Added GitHub CI for lint, unit tests, and jar build.
- Added `.env.example`, contribution policy, security policy, issue templates, PR template, and Dependabot configuration.

### Changed

- Split Moonshot Kimi and Kimi Code credentials: `:kimi` now reads `MOONSHOT_API_KEY`; `:kimi-code` reads `KIMI_API_KEY`.
- Added generated tool/cache ignore rules so local clj-kondo and LSP caches do not appear as candidate repo files.
