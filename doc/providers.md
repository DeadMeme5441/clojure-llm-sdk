# Providers

`clojure-llm-sdk` provides registered profiles across chat, embeddings, moderation, rerank, image generation, transcription, and text-to-speech.

Provider profiles define:

- provider id
- protocol family
- base URL and URL-building rules
- credential environment variables
- capabilities
- provider quirks such as dropped parameters, custom cost calculators, or required headers

## Chat Providers

| Provider | ID | Protocol | Auth |
|---|---|---|---|
| OpenAI | `:openai` | `openai-chat` | `OPENAI_API_KEY` |
| OpenRouter | `:openrouter` | `openrouter` | `OPENROUTER_API_KEY` |
| Anthropic Messages | `:anthropic` | `anthropic-messages` | `ANTHROPIC_API_KEY` |
| Anthropic OAuth | `:anthropic` | `anthropic-messages` | `CLAUDE_OAT_TOKEN` |
| Gemini Native | `:gemini-native` | `gemini-native` | `GEMINI_API_KEY` |
| Vertex Gemini | `:vertex-gemini` | `gemini-native` | ADC / `GOOGLE_OAUTH_ACCESS_TOKEN` |
| Vertex Anthropic (Claude) | `:vertex-anthropic` | `anthropic-messages` | ADC / `GOOGLE_OAUTH_ACCESS_TOKEN` |
| OpenAI Codex | `:codex` | `codex` | `OPENAI_API_KEY` |
| Codex Backend | `:codex-backend` | `codex` | Managed Codex CLI `auth.json` or caller-managed OAuth token/account |
| DeepSeek | `:deepseek` | `openai-chat` | `DEEPSEEK_API_KEY` |
| Kimi / Moonshot | `:kimi` | `openai-chat` | `MOONSHOT_API_KEY` |
| Kimi Code | `:kimi-code` | `openai-chat` | `KIMI_API_KEY` |
| Mistral | `:mistral` | `openai-chat` | `MISTRAL_API_KEY` |
| Groq | `:groq` | `openai-chat` | `GROQ_API_KEY` |
| Cerebras | `:cerebras` | `openai-chat` | `CEREBRAS_API_KEY` |
| Together | `:together` | `openai-chat` | `TOGETHER_API_KEY` |
| xAI | `:xai` | `openai-chat` | `XAI_API_KEY` |
| Z.AI | `:zai` | `zai-chat` | `ZAI_API_KEY` |
| HuggingFace Router | `:huggingface` | `openai-chat` | `HF_TOKEN` |
| Perplexity | `:perplexity` | `perplexity-agent` | `PERPLEXITY_API_KEY` |
| AWS Bedrock | `:bedrock` | `bedrock` | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` |
| Ollama Native | `:ollama-native` | `ollama-native` | none |
| Fake/Test | `:fake` | `fake` | none |

OpenAI-compatible aggregator aliases:

| Provider | ID | Auth |
|---|---|---|
| SambaNova | `:sambanova` | `SAMBANOVA_API_KEY` |
| DeepInfra | `:deepinfra` | `DEEPINFRA_TOKEN` |
| Nebius | `:nebius` | `NEBIUS_API_KEY` |
| Hyperbolic | `:hyperbolic` | `HYPERBOLIC_API_KEY` |
| Novita | `:novita` | `NOVITA_API_KEY` |
| FriendliAI | `:friendliai` | `FRIENDLI_TOKEN` |
| Featherless | `:featherless` | `FEATHERLESS_API_KEY` |
| Cloudflare Workers AI | `:cloudflare` | `CLOUDFLARE_API_TOKEN` |
| DashScope | `:dashscope` | `DASHSCOPE_API_KEY` |
| Volcengine ARK | `:volcengine` | `ARK_API_KEY` |

`clojure-llm-sdk` is not a proxy router. Aggregator aliases use one credential each and do not perform pool management, spend routing, cooldowns, or tenant isolation.

Provider-family namespaces are the sole implementations. For example, OpenAI chat is owned by `llm.sdk.providers.openai.chat`, Anthropic chat by `llm.sdk.providers.anthropic.chat`, OpenRouter by `llm.sdk.providers.openrouter.chat`, and Bedrock Converse by `llm.sdk.providers.bedrock.converse`. The former flat namespaces have been removed.

The tables below describe registered transport surfaces. A transport
capability is not a promise that every cataloged model supports that modality,
and a model capability does not establish live entitlement, regional
availability, or provider acceptance. Select and verify the model separately.

## Non-Chat Providers

### Embeddings

| Provider | ID | Wire shape | Auth |
|---|---|---|---|
| OpenAI | `:openai` | `/embeddings` | `OPENAI_API_KEY` |
| Gemini Native | `:gemini-native` | `models/{model}:batchEmbedContents` | `GEMINI_API_KEY` |
| OpenRouter | `:openrouter` | `/embeddings` | `OPENROUTER_API_KEY` |
| Azure OpenAI deployment | caller-defined | classic deployment `/embeddings` or `/openai/v1/embeddings` | `AZURE_OPENAI_API_KEY` |
| Mistral | `:mistral` | `/embeddings` | `MISTRAL_API_KEY` |
| Together | `:together` | `/embeddings` | `TOGETHER_API_KEY` |
| Voyage | `:voyage` | `/embeddings` | `VOYAGE_API_KEY` |
| Jina | `:jina` | `/embeddings` | `JINA_API_KEY` |
| Cohere | `:cohere` | `/embed` | `COHERE_API_KEY` |
| Nebius | `:nebius` | `/embeddings` | `NEBIUS_API_KEY` |
| Ollama Native | `:ollama-native` | `/api/embed` | none |

Gemini embeddings are native to `:gemini-native`, not a Vertex alias. They
preserve input order and accept only `:task-type` and `:title` under
`:embed/provider-options`, plus canonical `:embed/dimensions`. Azure rows refer
to profiles registered with `register-azure-deployment!`; both classic
deployment routing and `:api-style :v1` attach the embedding transport.

### Moderation

| Provider | ID | Auth |
|---|---|---|
| OpenAI | `:openai` | `OPENAI_API_KEY` |

### Rerank

| Provider | ID | Wire shape | Auth |
|---|---|---|---|
| Cohere | `:cohere` | `/rerank` | `COHERE_API_KEY` |
| Jina | `:jina` | `/rerank` | `JINA_API_KEY` |
| Voyage | `:voyage` | `/rerank` | `VOYAGE_API_KEY` |

Cohere rerank responses may contain unit-only
`{:response/usage {:usage/search-units n}}`. Missing token counts remain absent.

### Image Generation

| Provider | ID | Auth |
|---|---|---|
| OpenAI | `:openai` | `OPENAI_API_KEY` |
| OpenRouter | `:openrouter` | `OPENROUTER_API_KEY` |
| Vertex Gemini image generation | `:vertex-imagen` | ADC / `GOOGLE_OAUTH_ACCESS_TOKEN` |
| AWS Bedrock Image | `:bedrock` | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` |

OpenAI, OpenRouter, and Bedrock image requests require callers to select an
explicit `:image/model`; the SDK does not choose a billable model for these
providers. OpenAI accepts its implemented GPT Image model families. OpenRouter
accepts the provider-qualified image model id advertised for its native
`/images` endpoint. Bedrock uses the selected id to choose its Amazon or
Stability request family; for example, `amazon.nova-canvas-v1:0` selects Nova
Canvas. Legacy serializers remain compatibility code, not recommendations or
availability claims. Model availability, regional enablement, and account
access remain the caller's responsibility. `:vertex-imagen` has its own Gemini
image-model validation and is not an alias for these transports.

### Audio Transcription

| Provider | ID | Auth |
|---|---|---|
| OpenAI Whisper | `:openai` | `OPENAI_API_KEY` |
| Groq Whisper | `:groq` | `GROQ_API_KEY` |

When the transcription endpoint reports duration rather than tokens, the
response uses canonical `:transcription/duration-seconds` and sparse
`{:response/usage {:usage/duration-seconds n}}`.

### Text-To-Speech

| Provider | ID | Auth |
|---|---|---|
| OpenAI TTS | `:openai` | `OPENAI_API_KEY` |
| ElevenLabs | `:elevenlabs` | `ELEVENLABS_API_KEY` |

## Provider Notes

### Anthropic OAuth

The Anthropic adapter auto-detects OAuth-style tokens by token prefix and switches from `x-api-key` to Bearer auth. OAuth mode also adds Claude Code identity headers and preserves thinking block signatures for replay.

### Vertex Anthropic (Claude on Vertex)

`:vertex-anthropic` serves Anthropic's Claude models through Google Vertex AI. It reuses the native Anthropic Messages request body, response parser, and streaming parser, so thinking blocks, tool use, and cache markers behave exactly as on `:anthropic`. Only the transport differs:

- Endpoint: `POST {location}-aiplatform.googleapis.com/v1/projects/{project}/locations/{location}/publishers/anthropic/models/{model}:rawPredict` for unary calls and `:streamRawPredict` for streaming.
- Auth: a GCP OAuth bearer token resolved through the same ADC chain as `:vertex-gemini`, in place of the `x-api-key` header.
- Body: the model id moves into the URL path; the body carries `anthropic_version: "vertex-2023-10-16"` and adds `stream: true` on streaming calls.

Pass the project and location through `:request/provider-options` under `:vertex`, or set `GOOGLE_CLOUD_PROJECT` / `GOOGLE_CLOUD_LOCATION`. Model ids are the Vertex Claude ids, for example `claude-opus-4-6`, `claude-sonnet-4-6`, and `claude-haiku-4-5`. See [provider-configuration.md](provider-configuration.md#vertex-anthropic-claude) for credentials, model availability, and a worked example.

### Kimi Code

`:kimi` and `:kimi-code` are intentionally separate:

- `:kimi` targets Moonshot's public API at `https://api.moonshot.cn/v1` and reads `MOONSHOT_API_KEY`.
- `:kimi-code` targets `https://api.kimi.com/coding/v1`, reads `KIMI_API_KEY`, uses the `kimi-for-coding` model, and sends KimiCLI-style non-secret identity headers.

### Perplexity Agent

`:perplexity` preserves the canonical `sdk/complete` entry point and response
shape while using Perplexity's native `POST /v1/agent` protocol. Canonical
namespaced messages become Agent `input` items. Callers select native,
provider-qualified Agent model ids. Canonical `:request/tools` supplies
function tools, while the adapter normalizes typed Agent messages, search
results, function calls, and URL annotations into canonical text, citation,
and tool-call parts. Web-search invocations use informational
`:usage/search-queries`, not Cohere's billable `:usage/search-units`; pricing
never substitutes query or request counts for search units. Other counters
remain absent when not reported. Provider-reported Agent cost is authoritative.

Grounded search is configured as an Agent `web_search` tool and is enabled by
default for this provider. Disable it only with
`{:request/provider-options {:perplexity {:web-search false}}}`. Search filters
and budgets use the `:web-search` map documented in
[provider-configuration.md](provider-configuration.md#perplexity-agent-api).
No sandbox or other action tool is enabled implicitly. Options from the former
chat-completions integration are rejected with migration guidance instead of
being dropped or forwarded to `/v1/agent`.

### Z.AI

`:zai` targets Z.AI's GLM chat-completions API at
`https://api.z.ai/api/paas/v4`, authenticates with Bearer `ZAI_API_KEY`, and
preserves the SDK's canonical provider id, response parts, reasoning replay,
usage, and structured errors. Model ids remain caller-supplied strings. The
profile supports chat, streaming, function tools, reasoning, and canonical
image-part input; it does not imply custom tools, file attachments, or video
support. Tool choice is limited to `:auto`, and JSON Schema response format is
rejected.

```clojure
(sdk/complete
  :zai
  {:request/model "glm-5.3"
   :request/messages [{:message/role :user
                       :message/content "Reply with ok"}]
   :request/reasoning {:enabled true}})
```

Native request options use exact kebab-case keys under the provider namespace:

```clojure
{:request/provider-options
 {:zai {:do-sample true
        :tool-stream true
        :request-id "request-42"
        :user-id "user-42"}}}
```

Unknown or misplaced keys are rejected. The example model id above is not an
availability claim.

The SDK does not invent a Z.AI price table. Cost remains unknown unless the
caller supplies a pricing override.

### Gemini Native Embeddings

The existing `:gemini-native` profile also supports `sdk/embed`; it is not a
Vertex embedding alias. Requests use Gemini's
`models/{model}:batchEmbedContents` endpoint and preserve input order. Use
`:embed/dimensions` for output dimensionality and Gemini options `:task-type`
and `:title` directly under `:embed/provider-options`. Missing provider usage
remains absent rather than being synthesized as zero.

### Typed Tool Results

Tool messages may carry one canonical `:part/type :tool-result` with
`:tool-result/id`, `:tool-result/name`, `:tool-result/content`, and optional
`:tool-result/is-error`. Anthropic, Gemini, and Bedrock preserve the error
status in their native representation. Every implemented provider shape
without an error-status field rejects `:tool-result/is-error true` rather than
silently sending failure as success. Conflicting or mixed result
representations are rejected before a request is sent.

### OpenRouter

OpenRouter accepts routing preferences directly under
`:request/provider-options :provider`, Pareto configuration under `:pareto`,
and `:metadata-level` for its metadata header. Canonical reasoning remains
under `:request/reasoning`. For additional OpenAI-compatible wire extensions,
the exact shared escape-hatch key is
`:request/provider-options {:extra_body {...}}`. String and keyword spellings
are normalized for collision detection; native keys are forwarded without
renaming only when they do not collide with protected or canonical wire fields.
JSON field case is otherwise preserved. A collision throws instead of being
silently discarded or overriding canonical data.

### Azure OpenAI

Azure deployment profiles support chat and embeddings through
`llm.sdk.providers.openai.chat/register-azure-deployment!`. That provider-family
namespace is the sole implementation.

The default classic style requires `:api-version` and routes through
`/openai/deployments/{deployment}/chat/completions` and
`/openai/deployments/{deployment}/embeddings`. Set `:api-style :v1` to use
`/openai/v1/chat/completions` and `/openai/v1/embeddings` without an API-version
query parameter. In v1 mode the registered deployment is written to the body
as `model`. API-key-header and Bearer authentication behavior is unchanged.

```clojure
(require '[llm.sdk.providers.openai.chat :as openai-chat])

(openai-chat/register-azure-deployment!
  {:id :azure-embedding
   :endpoint "https://my-resource.openai.azure.com"
   :deployment "text-embedding-3-small"
   :api-style :v1})

(sdk/embed
  :azure-embedding
  {:embed/model "text-embedding-3-small"
   :embed/inputs ["clojure" "lisp"]})
```

For the classic route, omit `:api-style` and supply `:api-version`, for example
`"2024-08-01-preview"`.

### Custom OpenAI-Compatible Providers

Register private OpenAI-compatible endpoints with `register-alias!`. See [provider-configuration.md](provider-configuration.md#custom-openai-compatible-providers).
