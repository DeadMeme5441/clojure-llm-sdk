# Changelog

All notable user-visible changes are tracked here.

## 0.4.5

### Fixed

- Emit exactly one terminal stream event while preserving the provider finish
  reason and adding a fallback terminal event only when the provider omits one.
- Preserve user-only ChatGPT OAuth input when Codex backend instructions use
  the default, retain encrypted reasoning for replay, and report completed
  function calls with the canonical `:tool-calls` finish reason.

## 0.4.4

### Changed

- Rechecked all built-in providers against current official API schemas and
  aligned capabilities, model-listing claims, usage fields, media metadata,
  rerank results, and provider-specific response data.
- Removed the unsupported Lambda hosted alias. No authoritative public schema
  exists for its configured endpoint. Custom OpenAI-compatible Lambda
  deployments remain available through provider registration.
- Extended canonical schemas for nullable moderation categories, transcription
  language and log-probability data, video token usage, and returned rerank
  embeddings.

### Fixed

- Send required streaming flags for OpenAI Chat and Codex Responses, and
  normalize legacy OpenAI function calls in buffered and streamed responses.
- Correct Anthropic system-message routing, beta headers, thinking signatures,
  and terminal SSE events for native and Vertex transports.
- Preserve current OpenRouter reasoning and cost details, Perplexity citation
  metadata, Gemini image MIME types and reasoning usage, Bedrock pagination and
  response metadata, and non-float embedding payloads without false float
  coercion.
- Align Groq and xAI reasoning requests, Kimi structured-output capabilities,
  ElevenLabs output formats, Volcengine model-listing behavior, and the Fake
  provider's deterministic chat-only capability.

## 0.3.4

### Changed

- Refreshed every built-in provider adapter against current official wire
  schemas, including request/response/stream parsing, usage and cost metadata,
  tools, structured output, reasoning, caching, media, citations, and reranking.
- Migrated Cohere embedding and rerank to v2, Perplexity Sonar to `/v1/sonar`,
  OpenRouter image generation to `/images`, and Vertex image generation from
  discontinued Imagen endpoints to `gemini-2.5-flash-image` over
  `generateContent`.
- Added dedicated OpenRouter, Voyage, and Jina embedding transports; refreshed
  OpenAI-compatible provider endpoints and capabilities from official sources.
- Removed Kimi Code CLI identity spoofing. Third-party clients now send their
  own normal identity with Bearer authentication.
- Refreshed the bundled LiteLLM model/pricing snapshot and filter discontinued
  Imagen model IDs from future refreshes.

### Fixed

- Preserve provider-reported streaming and image costs instead of replacing
  them with estimates.
- Correct Anthropic cache-token accounting and retain current provider-native
  blocks rather than silently dropping unknown response content.

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
