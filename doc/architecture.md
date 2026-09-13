# Architecture

`clojure-llm-sdk` is a provider SDK. It translates canonical Clojure request maps into provider wire formats and translates provider responses back into canonical maps.

It is not a proxy server, agent framework, model router, or credential-pool manager.

## Canonical Chat Contract

Chat requests use one canonical shape:

```clojure
{:request/model "gpt-4o"
 :request/messages [{:message/role :user
                     :message/content "Hello"}]
 :request/tools [...]
 :request/temperature 0.7
 :request/cache {:ttl "5m"}}
```

Chat responses use one canonical shape:

```clojure
{:response/id "resp_123"
 :response/provider :openai
 :response/model "gpt-4o"
 :response/parts [{:part/type :text
                   :text "Hi"}
                  {:part/type :tool-call
                   :tool-call/id "call_1"
                   :tool-call/name "get_weather"
                   :tool-call/arguments "{\"location\":\"NYC\"}"}
                  {:part/type :citation
                   :citation/url "https://example.com"}]
 :response/finish-reason :stop
 :response/usage {...}                 ; optional and sparse, including unit-only usage
 :response/cost {...}                  ; optional; provider-reported cost wins
 :response/cache {...}                 ; canonical hit/miss/unknown when stamped
 :response/provider-data {...}
 :response/raw {...}}
```

See [canonical-response.md](canonical-response.md) for the exact response contract.

## Per-Modality Protocols

Each modality has a narrow protocol and driver:

| Modality | Public function | Protocol |
|---|---|---|
| Chat | `sdk/complete` | `llm.sdk.transport/Transport` |
| Embeddings | `sdk/embed` | `llm.sdk.transport.embed/EmbedTransport` |
| Moderation | `sdk/moderate` | `llm.sdk.transport.moderate/ModerationTransport` |
| Rerank | `sdk/rerank` | `llm.sdk.transport.rerank/RerankTransport` |
| Image generation | `sdk/generate-image` | `llm.sdk.transport.image/ImageTransport` |
| Audio transcription | `sdk/transcribe` | `llm.sdk.transport.transcribe/TranscribeTransport` |
| Text-to-speech | `sdk/speak` | `llm.sdk.transport.speak/SpeakTransport` |

Provider profiles attach constructors for the transports they support. Calling a modality on a provider without the matching transport returns a clear `ex-info` error instead of failing downstream.

## Provider Implementation Ownership

Provider implementations live under provider-family namespaces. These namespaces own request building, response parsing, streaming events, provider-native replay state, usage normalization, cache handling, and provider-specific registration helpers:

| Family | Owner namespaces |
|---|---|
| OpenAI | `llm.sdk.providers.openai.chat`, `.embeddings`, `.moderation`, `.image`, `.speak`, `.transcribe`, `.audio` |
| Anthropic | `llm.sdk.providers.anthropic.chat`, `.vertex` |
| Gemini / Vertex | `llm.sdk.providers.gemini.native`, `.embeddings`, `.vertex`, `.imagen` |
| Cohere | `llm.sdk.providers.cohere.chat`, `.embeddings`, `.rerank` |
| Bedrock | `llm.sdk.providers.bedrock.converse`, `.image`, `.rerank` |
| Codex | `llm.sdk.providers.codex.responses` |
| Z.AI | `llm.sdk.providers.zai.chat` |
| Local / Aggregators | `llm.sdk.providers.ollama.native`, `llm.sdk.providers.openrouter.chat`, `.embeddings`, `.image`, `llm.sdk.providers.perplexity.chat`, `llm.sdk.providers.openai-compat.aliases` |
| Other modalities | `llm.sdk.providers.voyage.embeddings`, `.rerank`, `llm.sdk.providers.jina.embeddings`, `llm.sdk.providers.elevenlabs.tts`, `llm.sdk.providers.fake.chat` |

The older flat namespaces, such as `llm.sdk.providers.openai-chat` and `llm.sdk.providers.anthropic`, are compatibility shims. New SDK code should depend on the family owner namespaces directly.

Provider registry and auth implementation live in `llm.sdk.provider.registry`, `llm.sdk.provider.auth`, and `llm.sdk.provider.builtins`. Cache implementation lives in `llm.sdk.cache.markers`, `llm.sdk.cache.policy`, and `llm.sdk.cache.request`. The aggregate namespaces `llm.sdk.provider` and `llm.sdk.cache` remain public compatibility surfaces.

Profile capabilities answer whether a transport can express a surface; model
capabilities answer whether a particular model is cataloged for that surface.
Neither implies live entitlement, regional availability, or that a provider
currently accepts a model id. Callers should keep transport selection and
model selection distinct.

## Provider Profiles

Provider profiles are registered by `llm.sdk.provider.builtins` and carry:

- provider id
- protocol family
- base URL
- auth strategy
- credential environment variable names
- capabilities
- default headers
- supported model listing flag
- provider quirks
- optional transport constructors
- optional URL builder
- optional cost calculator

OpenAI-compatible providers reuse the OpenAI Chat Completions transport owned by `llm.sdk.providers.openai.chat`. Providers with native shapes, such as Anthropic, Gemini, Cohere, Bedrock, and Ollama, use dedicated transports in their own family namespaces.

Perplexity is not an OpenAI-compatible alias: `:perplexity` owns the native
Agent API request/output and streaming lifecycle. Z.AI similarly owns its
validated native options and finish-reason handling while reusing the shared
OpenAI chat codec where the wire shape is compatible.

## Provider-Specific Replay State

Provider replay state must survive canonicalization. The SDK preserves
response-wide state in `:response/provider-data` and, when needed, accepts it
again through `:message/provider-data`. Replay-significant data attached to a
specific item stays with that canonical item: reasoning signatures remain on
reasoning parts, and custom or provider-native tool metadata remains in
`:tool-call/provider-data`. Citation source metadata remains on citation
parts.

Examples:

- Anthropic thinking block signatures
- Codex encrypted reasoning items and assistant phases
- Gemini thought signatures
- Perplexity citations and search results
- Tool-call ids and provider-native call metadata

This is why the SDK does not reduce every provider response to plain text.
Callers replaying an assistant response must carry the relevant canonical part
metadata and `:response/provider-data` forward; dropping opaque metadata can
make an otherwise identical-looking follow-up invalid to the provider.

## Streaming

Streaming providers emit different wire formats, but the SDK normalizes them into stream events:

| Event | Meaning |
|---|---|
| `:stream/start` | Request began. |
| `:stream/content-delta` | Text delta. |
| `:stream/reasoning-delta` | Reasoning or thinking delta; optional `:event/index` selects a block and optional `:reasoning/signature` is replay-significant. |
| `:stream/tool-call-start` | Tool call began; may include `:tool-call/provider-data`. |
| `:stream/tool-call-delta` | Tool call arguments delta. |
| `:stream/tool-call-end` | Tool call completed. |
| `:stream/citation` | Citation surfaced mid-stream; a source id or provider metadata may stand in for a URL. |
| `:stream/usage` | Sparse cumulative usage update. |
| `:stream/provider-state` | Provider replay state. |
| `:stream/error` | Provider stream error value. |
| `:stream/end` | Sole terminal event, emitted after trailing usage and metadata. |

The stream reducer builds a final canonical response from those events.
Reasoning deltas with the same index update the same reasoning part, tool
metadata is retained on the accumulated tool call, and provider-state maps are
merged into `:response/provider-data`.

Stream usage updates are cumulative snapshots, not deltas: a newly reported
counter replaces that counter, while omitted counters retain their previous
values. All counters are optional, including input/output tokens. A
provider-reported token total remains authoritative. Separately counted
cache-read and cache-write tokens are excluded from canonical uncached input,
and reasoning may overlap output, so those values are not blindly added to a
derived total.

Without `:on-event`, `sdk/complete` exposes the events as a lazy sequence, so
`:stream/error` remains observable data when the sequence is realized. With
`:on-event`, the SDK consumes and accumulates the sequence. Accumulation fails
with `ExceptionInfo` when an error event occurred; its `ex-data` includes the
classified error, original stream error, provider, and the canonical
`:partial-response` accumulated from the event sequence. The terminal
`:stream/end` is normalized to occur exactly once and only after trailing
usage or provider metadata has been consumed.

If a usage event carries provider-reported cost, the reducer retains it on the
aggregate response. Later registry estimation runs only when no
`:response/cost` already exists.

## Retry And Fallbacks

`sdk/complete` is one-shot by default. Pass `:retry true` or a retry policy map to retry classified transient failures such as timeouts, 429s, and provider 5xx responses. Streaming requests are not retried because partially consumed streams cannot be safely resumed by the SDK.

`sdk/with-fallbacks` tries explicit `[provider model]` pairs in order and returns the first success. It does not manage credential pools, cooldowns, budgets, tenant state, or weighted routing.

## Unsupported Parameter Handling

Profiles may declare `:profile/supported-params`. When present, the request preprocessor drops unsupported canonical fields for that provider and emits a warning through `llm.sdk.request/*warn-fn*`.

This keeps strict providers from returning avoidable 400s while still making parameter loss visible to applications.
