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
 :response/usage {...}
 :response/cost {...}
 :response/cache {...}
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
| Anthropic | `llm.sdk.providers.anthropic.chat` |
| Gemini / Vertex | `llm.sdk.providers.gemini.native`, `.vertex`, `.imagen` |
| Cohere | `llm.sdk.providers.cohere.chat`, `.embeddings`, `.rerank` |
| Bedrock | `llm.sdk.providers.bedrock.converse`, `.image` |
| Codex | `llm.sdk.providers.codex.responses` |
| Local / Aggregators | `llm.sdk.providers.ollama.native`, `llm.sdk.providers.openrouter.chat`, `llm.sdk.providers.perplexity.chat`, `llm.sdk.providers.openai_compat.aliases` |
| Other modalities | `llm.sdk.providers.voyage.rerank`, `llm.sdk.providers.elevenlabs.tts`, `llm.sdk.providers.fake.chat` |

The older flat namespaces, such as `llm.sdk.providers.openai-chat` and `llm.sdk.providers.anthropic`, are compatibility shims. New SDK code should depend on the family owner namespaces directly.

Provider registry and auth implementation live in `llm.sdk.provider.registry`, `llm.sdk.provider.auth`, and `llm.sdk.provider.builtins`. Cache implementation lives in `llm.sdk.cache.markers`, `llm.sdk.cache.policy`, and `llm.sdk.cache.request`. The aggregate namespaces `llm.sdk.provider` and `llm.sdk.cache` remain public compatibility surfaces.

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

## Provider-Specific Replay State

Provider replay state must survive canonicalization. The SDK preserves it in `:response/provider-data` and, when needed, accepts it again through `:message/provider-data`.

Examples:

- Anthropic thinking block signatures
- Codex encrypted reasoning items and assistant phases
- Gemini thought signatures
- Perplexity citations and search results
- Tool-call ids and provider-native call metadata

This is why the SDK does not reduce every provider response to plain text.

## Streaming

Streaming providers emit different wire formats, but the SDK normalizes them into stream events:

| Event | Meaning |
|---|---|
| `:stream/start` | Request began. |
| `:stream/content-delta` | Text delta. |
| `:stream/reasoning-delta` | Reasoning or thinking delta. |
| `:stream/tool-call-start` | Tool call began. |
| `:stream/tool-call-delta` | Tool call arguments delta. |
| `:stream/tool-call-end` | Tool call completed. |
| `:stream/citation` | Citation surfaced mid-stream. |
| `:stream/usage` | Usage data. |
| `:stream/provider-state` | Provider replay state. |
| `:stream/error` | Provider stream error. |
| `:stream/end` | Stream finished. |

The stream reducer builds a final canonical response from those events.

## Retry And Fallbacks

`sdk/complete` is one-shot by default. Pass `:retry true` or a retry policy map to retry classified transient failures such as timeouts, 429s, and provider 5xx responses. Streaming requests are not retried because partially consumed streams cannot be safely resumed by the SDK.

`sdk/with-fallbacks` tries explicit `[provider model]` pairs in order and returns the first success. It does not manage credential pools, cooldowns, budgets, tenant state, or weighted routing.

## Unsupported Parameter Handling

Profiles may declare `:profile/supported-params`. When present, the request preprocessor drops unsupported canonical fields for that provider and emits a warning through `llm.sdk.request/*warn-fn*`.

This keeps strict providers from returning avoidable 400s while still making parameter loss visible to applications.
