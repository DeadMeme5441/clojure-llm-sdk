# Provider Shape Audit

This document is the control surface for provider wire-shape correctness.
The SDK should not rely on a provider being "OpenAI-compatible" without
request, response, stream, or live evidence that the adapter still matches
the provider surface.

Coverage markers:

- `request-golden` - tests assert method, URL, headers, query params, and body.
- `response-fixture` - tests parse provider-shaped response fixtures or stubs.
- `stream-fixture` - tests replay SSE/stream/eventstream chunks.
- `live-smoke` - env-gated live test calls the real provider.
- `unchecked` - adapter behavior is inferred from shared code or docs only.

## Chat

| Provider | ID | Adapter family | Coverage | High-risk gaps |
|---|---:|---|---|---|
| OpenAI | `:openai` | OpenAI Chat Completions | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Broaden live tool/JSON-schema coverage after refactor. |
| Anthropic | `:anthropic` | Anthropic Messages | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Keep thinking signatures, OAuth headers, tool replay, and cache markers pinned. |
| Gemini Native | `:gemini-native` | Gemini REST | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Thought signatures, tool responses, cachedContent, and safety blocks need explicit fixtures. |
| Vertex Gemini | `:vertex-gemini` | Vertex REST wrapper over Gemini | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Region/global URL routing, ADC headers, and provider id preservation are critical. |
| OpenRouter | `:openrouter` | OpenAI-wire custom wrapper | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Provider routing, plugins, reasoning, envelope cache, embeddings, and provider-specific usage need fixtures. |
| Codex Responses | `:codex` | OpenAI Responses | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Encrypted reasoning/provider replay state must stay intact. |
| Codex Backend | `:codex-backend` | ChatGPT Codex backend | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | SSE-first non-streaming behavior, auth headers, and backend usage parsing are load-bearing. |
| Perplexity | `:perplexity` | OpenAI-wire custom wrapper | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Citation/search result extraction and final stream chunk flattening are critical. |
| Cohere | `:cohere` | Native Cohere chat | `request-golden`, `response-fixture`, `stream-fixture`, `live-smoke` | Documents, citations, tool calls, and v2 response variants need pinned fixtures. |
| Bedrock | `:bedrock` | AWS Bedrock Converse | `request-golden`, `response-fixture`, `stream-fixture` | Live proof depends on AWS env; eventstream and cachePoint fixtures are mandatory. |
| Ollama Native | `:ollama-native` | Ollama `/api/chat` | `request-golden`, `response-fixture`, `stream-fixture` | Local runtime live smoke is optional; NDJSON stream shape must be pinned. |
| Fake/Test | `:fake` | Deterministic test transport | `request-golden`, `response-fixture` | Keep as SDK-internal test fixture only; never count as provider parity. |
| DeepSeek | `:deepseek` | OpenAI-compatible alias | `request-golden`, `response-fixture` | Live smoke and provider-specific reasoning output drift. |
| Kimi / Moonshot | `:kimi` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Thinking fields and model catalog separation. |
| Kimi Code | `:kimi-code` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Required KimiCLI identity headers and prompt cache key. |
| Mistral | `:mistral` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Dropped penalty fields and JSON-schema compatibility. |
| Groq | `:groq` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Reasoning format and transcription alias interactions. |
| Cerebras | `:cerebras` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Model names and reasoning fields. |
| Together | `:together` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Chat and embedding model/provider ids. |
| xAI | `:xai` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Reasoning/cache routing fields. |
| HuggingFace Router | `:huggingface` | OpenAI-compatible alias | `request-golden`, `response-fixture`, `live-smoke` | Router model ids and tool support are model-dependent. |
| Aggregator aliases | `:sambanova`, `:deepinfra`, `:lambda`, `:nebius`, `:hyperbolic`, `:novita`, `:friendliai`, `:featherless`, `:cloudflare`, `:dashscope`, `:volcengine` | OpenAI-compatible aliases | `request-golden` | Mostly unchecked live behavior; keep claims conservative. |

## Embeddings

| Provider | ID | Adapter family | Coverage | High-risk gaps |
|---|---:|---|---|---|
| OpenAI | `:openai` | OpenAI `/embeddings` | `request-golden`, `response-fixture`, `live-smoke` | Dimensions override and multi-input ordering. |
| Cohere | `:cohere` | Cohere `/embed` | `request-golden`, `response-fixture`, `live-smoke` | v3 input type and embedding type variants. |
| Voyage | `:voyage` | OpenAI-compatible embeddings | `request-golden`, `response-fixture`, `live-smoke` | Input type/provider-options behavior. |
| Mistral | `:mistral` | OpenAI-compatible embeddings | `request-golden`, `live-smoke` | Add fixture response coverage. |
| Together | `:together` | OpenAI-compatible embeddings | `request-golden`, `live-smoke` | Add fixture response coverage. |
| Jina | `:jina` | OpenAI-compatible embeddings | `request-golden`, `live-smoke` | Add fixture response coverage. |
| OpenRouter | `:openrouter` | OpenAI-compatible embeddings with OpenRouter headers | `request-golden`, `response-fixture`, `live-smoke` | Model prefix stripping and billing-route pricing must stay explicit. |
| Nebius | `:nebius` | OpenAI-compatible embeddings | `request-golden`, `response-fixture` | Live smoke depends on Nebius credentials/model availability. |
| Ollama Native | `:ollama-native` | Ollama `/api/embed` | `request-golden`, `response-fixture` | Optional local live smoke. |

## Other Modalities

| Modality | Provider | ID | Coverage | High-risk gaps |
|---|---|---:|---|---|
| Moderation | OpenAI | `:openai` | `request-golden`, `response-fixture`, `live-smoke` | Multi-modal moderation shape should stay explicit. |
| Rerank | Cohere | `:cohere` | `request-golden`, `response-fixture`, `live-smoke` | v2 and v3 field compatibility. |
| Rerank | Voyage | `:voyage` | `request-golden`, `response-fixture`, `live-smoke` | Score/document response variants. |
| Rerank | Jina | `:jina` | `request-golden`, `response-fixture`, `live-smoke` | Provider id tagging and document return shape. |
| Rerank | Bedrock | `:bedrock` | `request-golden`, `response-fixture` | Bedrock Agent Runtime `/rerank` requires SigV4 and model ARN routing. |
| Image | OpenAI | `:openai` | `request-golden`, `response-fixture` | gpt-image-1 usage and b64-only behavior need live proof. |
| Image | OpenRouter | `:openrouter` | `request-golden`, `response-fixture` | Uses chat completions with `message.images`, not `/images/generations`. |
| Image | Vertex Imagen | `:vertex-imagen` | `request-golden`, `response-fixture` | ADC/project routing and Imagen 3/4 differences. |
| Image | Bedrock | `:bedrock` | `request-golden`, `response-fixture` | Titan vs Stability request variants. |
| Transcription | OpenAI | `:openai` | `request-golden`, `response-fixture` | Multipart boundary and verbose JSON variants. |
| Transcription | Groq | `:groq` | `request-golden`, `response-fixture` | Groq endpoint/base URL and model aliases. |
| TTS | OpenAI | `:openai` | `request-golden`, `response-fixture` | Raw byte response headers. |
| TTS | ElevenLabs | `:elevenlabs` | `request-golden`, `response-fixture` | Voice id URL path and output_format query. |

## File Attachments

File/document attachments are SDK request-shape surfaces, distinct from file
lifecycle APIs such as upload/list/delete. Canonical `:part/type :file`
content parts are provider-native only where the underlying API has a stable
attachment shape:

| Provider | ID | Wire Shape | Coverage | High-risk gaps |
|---|---:|---|---|---|
| OpenAI Chat | `:openai` | Chat Completions `file` content part with `file_data` or `file_id` | `request-golden`, `live-smoke` | File data must be a `data:<mime>;base64,...` URI. |
| Codex Responses | `:codex`, `:codex-backend` | Responses `input_file` with `file_data`, `file_id`, or `file_url` | `request-golden`, `live-smoke` | File data must be a `data:<mime>;base64,...` URI. |
| Anthropic | `:anthropic` | Messages `document` block with Files API, URL, base64, or text source | `request-golden` | File IDs require the `files-api-2025-04-14` beta header. |
| Gemini Native | `:gemini-native` | `fileData` URI or `inlineData` base64 part | `request-golden` | URI sources must already be provider-accessible. |
| Vertex Gemini | `:vertex-gemini` | Gemini `fileData` URI or `inlineData` base64 part under Vertex routing | `request-golden` | GCS URI permissions are outside SDK serialization. |
| Bedrock | `:bedrock` | Converse `document` content block with bytes, text, or S3 source | `request-golden` | Document names/formats are sanitized before signing. |
| Cohere | `:cohere` | Textual `:file/content` maps to native top-level `documents` | `request-golden`, `live-smoke` | Cohere does not fetch file IDs or decode binary data; those fail explicitly. |
| OpenAI-compatible aliases | aliases such as `:deepseek`, `:kimi-code`, `:mistral` | Unsupported for canonical `:file` content parts | `request-golden` | These adapters fail explicitly instead of inheriting OpenAI-only file support. |

## Refactor Gate

Before moving a provider family, the corresponding rows above must have
request and response coverage. For streaming providers, stream fixtures must
also exist. Live tests are useful proof, but fixtures remain the required
offline regression net.

## Capability Coverage Gate

The detailed per-provider SDK surface matrix lives in
`src/llm/sdk/provider_coverage.clj` and is enforced by
`test/llm/sdk/provider_coverage_test.clj`.

Every registered provider must explicitly declare coverage for:

- public SDK surfaces: complete/chat, streaming, embeddings, moderation,
  rerank, image generation, transcription, and TTS as applicable
- request shape and response shape fixtures
- stream shape, including explicit `:not-applicable` or `:none`
- context-cache behavior, including honest `:none` or `:not-applicable`
- usage/metrics normalization and canonical response stamping
- pricing/cost source, including `:unknown` only where intentionally unknown
- model listing source, including live `/models`, snapshot-only, local-only,
  or unsupported
- auth path and structured error classification
- live smoke status

Generated provider clients are not part of the production SDK architecture.
Official OpenAPI, Discovery, Smithy, or SDK-derived examples may be stored as
spec snapshots and used to create or validate fixtures.

## Current Logic Audit

The provider-family rewrite includes a direct implementation read across the
provider owners, not only a metadata coverage pass:

- OpenAI-compatible, OpenRouter, Perplexity, Cohere, Bedrock, Gemini, Anthropic,
  Codex, and Ollama chat builders were checked for request shape, stream event
  flattening, usage normalization, cache handling, and error classification.
- Provider-native cache logic stays provider-specific: Anthropic uses native
  `cache_control`, OpenRouter uses envelope markers where upstream Claude/Qwen
  cache semantics apply, Gemini accepts explicit `cachedContent`, Bedrock uses
  Converse `cachePoint`, and unsupported providers explicitly report no cache
  strategy.
- Cost attribution is wired in the public drivers for chat, embeddings, rerank,
  image generation, transcription, and TTS. Unknown pricing remains explicit and
  is not treated as zero.
- Stop-sequence handling is pinned across Anthropic, Gemini, Cohere, Bedrock,
  and Ollama so a single string is one stop sequence rather than a vector of
  characters.
