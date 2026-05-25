# clojure-llm-sdk

[![CI](https://github.com/DeadMeme5441/clojure-llm-sdk/actions/workflows/ci.yml/badge.svg)](https://github.com/DeadMeme5441/clojure-llm-sdk/actions/workflows/ci.yml)

A production-quality Clojure SDK for LLM providers: one canonical API for chat, embeddings, moderation, rerank, image generation, audio transcription, and text-to-speech.

This is a provider SDK, not an agent framework or proxy server. It owns provider wire-format differences so your application does not have to. It does not include credential pools, budget routing, plugin loading, vector stores, MCP clients, observability sinks, or secret managers.

## Installation

Add the library to `deps.edn`:

```clojure
{:deps {com.deadmeme5441/clojure-llm-sdk
        {:git/url "https://github.com/DeadMeme5441/clojure-llm-sdk"
         :git/sha "LATEST_SHA"}}}
```

Then require the public namespace:

```clojure
(require '[llm.sdk :as sdk])
```

## Quick Start

```clojure
(sdk/complete
  :openai
  {:request/model "gpt-4o-mini"
   :request/messages [{:message/role :user
                       :message/content "Reply with the single word: ok"}]})
```

Responses use the same canonical shape across providers:

```clojure
{:response/provider :openai
 :response/model "gpt-4o-mini"
 :response/parts [{:part/type :text
                   :text "ok"}]
 :response/finish-reason :stop
 :response/usage {...}
 :response/cost {...}
 :response/cache {...}
 :response/provider-data {...}
 :response/raw {...}}
```

Streaming uses the same request shape:

```clojure
(sdk/complete
  :openai
  {:request/model "gpt-4o-mini"
   :request/messages [{:message/role :user
                       :message/content "Count to three"}]}
  :stream? true
  :on-event (fn [event]
              (when (= :stream/content-delta (:event/type event))
                (print (:delta event)))))
```

## Documentation

Library docs:

- [Getting started](doc/getting-started.md) - install, configure credentials, make chat requests, stream responses, and run tool-call turns.
- [API reference](doc/api-reference.md) - public functions and request shapes for each modality.
- [Providers](doc/providers.md) - supported provider ids, modalities, credentials, and provider-specific notes.
- [Provider configuration](doc/provider-configuration.md) - credentials, Kimi Code, Vertex, Azure deployments, custom aliases, and live model listing.
- [Canonical response contract](doc/canonical-response.md) - response parts, provider replay state, usage, cost, cache, and streaming semantics.
- [Context caching](doc/context-caching.md) - provider-native cache markers and honest cache attribution.
- [Model registry and pricing](doc/model-registry.md) - model metadata lookup, live refresh, overrides, and cost estimation.
- [Architecture](doc/architecture.md) - provider SDK boundaries and extension points.
- [LiteLLM parity](doc/litellm-parity-survey.md) - what this SDK borrows from LiteLLM and what it intentionally leaves out.

Project docs:

- [Contributing](CONTRIBUTING.md)
- [Security](SECURITY.md)
- [Changelog](CHANGELOG.md)

## Supported Surface

The SDK currently registers 36 provider profiles across seven canonical modalities.

| Modality | Public function | Providers |
|---|---|---|
| Chat | `sdk/complete` | OpenAI, Anthropic, Gemini, Vertex, OpenRouter, Codex, DeepSeek, Kimi, Kimi Code, Mistral, Groq, Cerebras, Together, xAI, HuggingFace Router, Perplexity, Bedrock, Ollama, and aggregator aliases |
| Embeddings | `sdk/embed` | OpenAI, Cohere, Voyage, Mistral, Together, Jina, Ollama |
| Moderation | `sdk/moderate` | OpenAI |
| Rerank | `sdk/rerank` | Cohere, Voyage, Jina |
| Image generation | `sdk/generate-image` | OpenAI, Vertex Imagen, Bedrock image models |
| Audio transcription | `sdk/transcribe` | OpenAI Whisper, Groq Whisper |
| Text-to-speech | `sdk/speak` | OpenAI TTS, ElevenLabs |

See [Providers](doc/providers.md) for the full provider matrix and credential list.

## Credentials

Credentials are read from environment variables. The SDK does not load `.env` files itself.

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-api03-...
export GEMINI_API_KEY=AIza...
export KIMI_API_KEY=...
```

Use [.env.example](.env.example) as a non-secret template for local live smoke tests.

Some provider names are intentionally distinct:

- `:kimi` uses Moonshot's public API and reads `MOONSHOT_API_KEY`.
- `:kimi-code` uses Kimi Code's coding endpoint and reads `KIMI_API_KEY`.
- `:vertex-gemini` uses Google Application Default Credentials or `GOOGLE_OAUTH_ACCESS_TOKEN`.
- `:codex-backend` reads OAuth data from the official Codex CLI auth file.

## Design Guarantees

- Canonical response parts preserve text, images, tool calls, citations, reasoning, safety data, and provider-native state.
- Provider-specific replay data is not flattened away. Thinking signatures, encrypted reasoning items, Gemini thought signatures, citations, and tool-call ids stay available for later turns.
- Unknown cost and cache data are explicit. Missing pricing returns `:cost/usd :unknown`; missing cache telemetry returns `:cache/status :unknown`.
- Default tests are offline-only. Live provider calls are opt-in.
- Custom OpenAI-compatible providers can be registered without writing a new transport.

## Examples

Embeddings:

```clojure
(sdk/embed
  :openai
  {:embed/model "text-embedding-3-small"
   :embed/inputs ["clojure" "lisp" "java"]})
```

Rerank:

```clojure
(sdk/rerank
  :cohere
  {:rerank/model "rerank-english-v3.0"
   :rerank/query "lisp dialect on the JVM"
   :rerank/documents ["Python" "Clojure" "JavaScript"]
   :rerank/top-n 3})
```

Fallbacks:

```clojure
(sdk/with-fallbacks
  [[:openai "gpt-4o"]
   [:anthropic "claude-haiku-4-5"]
   [:groq "llama-3.1-8b-instant"]]
  {:request/messages [{:message/role :user
                       :message/content "Reply: ok"}]})
```

Custom OpenAI-compatible alias:

```clojure
(require '[llm.sdk.providers.openai-chat :as openai-chat])

(openai-chat/register-alias!
  {:id :my-private-llm
   :base-url "https://llm.example.com/v1"
   :env-var-names ["MY_PRIVATE_LLM_KEY"]
   :capabilities #{:chat :streaming :tools}})
```

## Validation

The public CI workflow runs:

```bash
clj-kondo --lint src test
clojure -M:test
clojure -T:build jar
```

Live tests are explicit:

```bash
cp .env.example .env
set -a; source .env; set +a
clojure -M:live-test
```

## License

MIT
