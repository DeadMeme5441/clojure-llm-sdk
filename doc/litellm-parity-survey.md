# Python LiteLLM Design Survey

`clojure-llm-sdk` is inspired by LiteLLM's broad provider coverage, but it is intentionally scoped as a Clojure library rather than a proxy server.

The goal is to provide the provider-abstraction pieces that applications need directly:

- canonical request and response shapes
- provider-specific wire-format adapters
- usage normalization
- cost and cache attribution
- model metadata lookup
- streaming normalization
- provider-specific replay state preservation

## Comparison Baselines

Python LiteLLM supplies design ideas, provider vocabulary, and one upstream
pricing source; it is not a provider-count target. The separate Clojure library
[`unravel-team/litellm-clj` at `14bcdd949c0207d6c4988a3db887a1a7fa1c5522`](https://github.com/unravel-team/litellm-clj/commit/14bcdd949c0207d6c4988a3db887a1a7fa1c5522)
was used only for a scoped implementation comparison. That comparison led to
three constructor-backed additions here: `:zai` chat, embeddings on
`:gemini-native`, and embeddings on caller-registered Azure deployment
profiles. It does not make `litellm-clj` a dependency or imply that every model
is currently available to every account.

The detailed, constructor-backed mapping and its explicit exclusions live in
[the provider parity ledger](litellm-provider-parity.md). Most verification is
offline request/response fixture coverage; current account access and model
availability require opt-in live checks and are not inferred from a registry
entry.

## What This SDK Covers

| Area | Coverage |
|---|---|
| Chat completions | OpenAI, Anthropic, Gemini, Vertex, Z.AI, OpenRouter, Codex, DeepSeek, Kimi, Kimi Code, Mistral, Groq, Cerebras, Together, xAI, HuggingFace Router, Perplexity, Bedrock, Ollama, and aggregator aliases. |
| Embeddings | OpenAI, Gemini Native, OpenRouter, caller-registered Azure deployments, Cohere, Voyage, Mistral, Together, Jina, Nebius, and Ollama. |
| Moderation | OpenAI. |
| Rerank | Cohere, Voyage, Jina. |
| Image generation | OpenAI, OpenRouter, Vertex Gemini image generation (`:vertex-imagen`), and Bedrock image models. |
| Audio transcription | OpenAI Whisper, Groq Whisper. |
| Text-to-speech | OpenAI TTS, ElevenLabs. |
| Model metadata | Bundled LiteLLM and models.dev snapshots, supported live provider catalogs, and caller overrides; a catalog entry is not an availability claim. |
| Cost estimation | Registry-backed calculation for reported billable dimensions, with missing usage or rates left explicitly unknown. |
| Context caching | Provider-native cache markers and cache telemetry normalization. |
| Streaming | Canonical stream event taxonomy and final response reduction. |
| Fallbacks | Sequential caller-provided fallback list. |

## Design Choices Borrowed From LiteLLM

LiteLLM demonstrates that a useful provider abstraction needs more than a single OpenAI-compatible request builder. This SDK adopts the same broad lessons:

- Provider adapters should own provider wire-format quirks.
- Each modality should have a dedicated transformation path.
- OpenAI-compatible providers should share an implementation instead of duplicating adapters.
- URL construction should be configurable for deployment-routed providers such as Azure.
- Provider-specific cost math should be injectable.
- Unsupported provider parameters should be visible and handled before the provider returns a preventable error.

The Clojure implementation keeps these ideas as small library-level protocols and provider profiles.

## What This SDK Does Not Cover

The following features belong in applications or infrastructure above the SDK:

- proxy servers and OpenAI-compatible gateways
- tenant-aware routing
- credential pools and key cooldowns
- spend budgets and billing ledgers
- secret managers
- observability vendor integrations
- Redis or semantic response caches
- vector stores and RAG frameworks
- MCP clients and agent tool runtimes
- prompt management systems
- guardrail policy engines
- fine-tuning, files, and batch job administration
- realtime WebSocket audio sessions

The SDK stops at provider request/response behavior. Applications remain responsible for product policy, persistence, routing, tenancy, and observability.

## Provider Adapter Strategy

Adapters fall into three categories:

1. Native transports for providers with distinct APIs, such as Anthropic, Gemini, Cohere, Bedrock, Ollama, Perplexity, and Codex Responses.
2. OpenAI-compatible aliases for providers that accept Chat Completions shape with different base URLs, credentials, or small parameter quirks.
3. Per-modality transports for embeddings, moderation, rerank, images, transcription, and text-to-speech.

This keeps the public API stable while allowing individual provider behavior to evolve independently.
