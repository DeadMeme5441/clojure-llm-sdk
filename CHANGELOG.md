# Changelog

All notable user-visible changes are tracked here.

## 0.6.2

- Vertex Gemini streaming smoke now honors `GOOGLE_CLOUD_LOCATION`, accepts
  a model argument, and allows a larger output budget for thinking models.
- Corrected Vertex ADC setup guidance; the SDK reads credentials directly
  rather than invoking gcloud.

These Vertex changes were listed prematurely in the 0.6.1 notes; their
files are first included in 0.6.2.

## 0.6.1

- OpenRouter now surfaces readable structured reasoning: `reasoning.text`
  and `reasoning.summary` entries from `reasoning_details` become visible
  reasoning parts and stream reasoning deltas instead of metadata-only
  provider state. Encrypted or unknown entries remain provider metadata,
  native `reasoning`/`reasoning_content` still take precedence, and native
  `reasoning_details` replay on follow-up turns unchanged.

## 0.6.0

### Breaking changes

- Streaming `sdk/complete` without `:on-event` now returns a single-pass,
  single-consumer `llm.sdk.StreamHandle` implementing
  `Seqable`/`Closeable`/`IReduce` rather than a bare lazy sequence. `seq` and
  `reduce` consume the same cursor; consumed events are released and are not
  replayable. Use `with-open` for sequence-style consumption; `reduce` closes
  on completion, failure, and early `reduced` termination. Callback streaming
  still invokes the callback as events arrive and returns the accumulated
  canonical response.
- Removed the old flat provider namespaces. Require the provider-family owners,
  such as `llm.sdk.providers.openai.chat`,
  `llm.sdk.providers.anthropic.chat`, and
  `llm.sdk.providers.gemini.native`.
- `extra_body` now rejects string/keyword spelling collisions with protected
  or canonical wire fields instead of silently dropping or overriding them.
  Typed tool results are lowered from canonical `:tool-result` parts; providers
  without an error-status field explicitly reject `:tool-result/is-error true`.
- Provider registration now accepts only complete, validated profiles with at
  least one transport constructor. Operation capabilities are derived from
  those constructors rather than trusted from caller metadata.
- `llm.sdk.catalog/resolve-model` performs exact lookup only. Ambiguous bare ids
  throw `ExceptionInfo`; use a known `provider/model` prefix or the two-argument
  form. It no longer guesses by substring.
- Codex authentication helpers now live in `llm.sdk.providers.codex.auth`;
  the Responses codec no longer owns credential storage or refresh.

### Changed

- Built-in provider values are assembled and published atomically on the first
  registry lookup. Loading an adapter namespace no longer mutates the registry.
- Every modality uses the same injected `:http-client`, connect timeout, and
  request-timeout path. Runtime headers win last with case-insensitive matching;
  complete custom profiles support query-parameter auth, and GCP profiles honor
  a runtime `:auth-token`.
- Model entries expose winning-source provenance, revision, freshness,
  availability, all contributing sources, and the pricing source. A successful
  live refresh atomically replaces that provider's live slice; a failed refresh
  preserves the previous slice and marks it stale.
- Anthropic-style cache markers use a bare ephemeral marker for the default or
  explicit `"5m"` TTL and emit a wire TTL only for `"1h"`. Other TTLs,
  including `"30m"`, and negative/non-integer breakpoint counts are rejected.
- CI covers JDK 17 and 21 and runs Python snapshot-generator unit discovery.
  Releases use `clojure -T:build deploy`, which builds and deploys the same
  `RELEASE_VERSION`; there is no separate `:deploy` alias.
- ChatGPT OAuth supports both HTTPS/SSE and Responses WebSocket V2, with
  opt-in live `gpt-5.6-luna` coverage for blocking, callback, and pull-stream
  completion; typed tools and reasoning replay; structured output; media;
  connection reuse, reconnects, cancellation, and concurrent conversations.
- Managed Codex CLI file credentials refresh before expiry and recover once
  from authentication rejection. Rotated credentials are written atomically
  with owner-only permissions. Explicit caller-managed tokens bypass file
  storage and SDK refresh.
- WebSocket connection-limit and eligible authentication rejections recover
  once on a fresh connection before any provider event is observed. Partial
  generations, ambiguous network failures, and ordinary rate limits are not replayed.

### Fixed

- Unknown usage remains absent, unknown cost remains unknown, and
  provider-reported token totals and costs remain authoritative during
  normalization, streaming accumulation, and final response stamping.
- Codex protects canonical model/input/store/stream fields, lowers typed
  function/custom tool results and inline images, and requests encrypted replay
  state even when reasoning effort is left at the model default.
- HTTP streamed bodies now enforce inactivity deadlines after response headers
  arrive; stalled SSE and binary reads close instead of waiting indefinitely.

## 0.5.0

### Breaking changes

This is not a drop-in upgrade from 0.4.x. The main SDK entry points and
namespaced request keys remain, but callers must account for these changes:

- Perplexity now uses the Agent API. Use native provider/model IDs such as
  `perplexity/sonar` and migrate Sonar-specific provider options.
- OpenAI, OpenRouter, and Bedrock image generation require explicit
  `:image/model`; retired or implicit model defaults are no longer selected.
- Usage may omit unreported input/output token counts. Transcription can
  report duration, and reranking can report `:usage/search-units`. Do not
  interpret missing counters or unknown costs as zero.
- Accumulated streaming errors throw with `:partial-response` in exception
  data. Terminal events follow trailing metadata; premature EOF is incomplete.
- Migrate persisted Gemini provider state to the appropriate `:gemini-native`
  or `:vertex-gemini` identity rather than the old `:gemini` tag.
- Unsupported native content/reasoning combinations now fail explicitly.
  `extra_body` cannot override canonical model/messages/stream; Cohere and
  Voyage rerank documents must be strings; Mistral embeddings reject
  `:embed/user`. Bedrock pagination uses canonical `:rerank/next-token`.

### Changed

- Add the three scoped surfaces identified by the pinned `litellm-clj`
  comparison: Z.AI chat, native Gemini batch embeddings, and embeddings for
  caller-registered Azure OpenAI deployments, including explicit Azure v1
  routing.
- Refresh the bundled LiteLLM and models.dev catalogs and extend registry-backed
  pricing across reported text, image, audio, cache, duration, request, and
  rerank billing dimensions. Missing usage or rates remain unknown.

- Add persistent Responses WebSockets for ChatGPT OAuth (`:codex-backend`) with
  `:config {:transport :websocket}`. HTTP/SSE remains the default: live
  `gpt-6-astra` / low-effort benchmarks favored SSE for first-output latency.
  WebSockets use the official streaming payload, isolated concurrent leases,
  bounded buffers, inactivity timeouts and explicit disposal. API-key providers
  are unchanged.
- Automatically continue matching completed WebSocket conversations with
  `previous_response_id` and delta-only input. Prefer the correct cached
  connection; send full history after edits, configuration changes or reconnects.
  Recover a rejected automatic response ID once only before generation starts.
  Disable continuation with `:config {:incremental? false}`.

### Fixed

- Align provider request, response, streaming, usage, file-attachment, stop
  sequence, structured-error, and Cohere v2 embedding/rerank handling with the
  current provider wire shapes while preserving provider-native replay and
  typed output data.
- Preserve response/parser-reported costs instead of overwriting them with
  estimates, and avoid inventing required token totals or substituting
  text-token rates for separately billed image and audio usage.

- Deliver stream deltas without `mapcat` read-ahead that could block an event
  until additional provider data arrived.
- Close streaming response bodies when an event callback throws.
- Preserve Codex backend request rules for custom endpoint URLs, and apply
  configured headers to OAuth requests.
- Preserve completed assistant message metadata for exact continuation without
  duplicating its canonical text or overriding user edits.
- Use streamed completed output items when the backend's terminal output array
  is empty, preventing duplicate output in incremental requests.
- Avoid redundant JSON serialization/deserialization when parsing buffered
  Codex responses.

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
