# Providers

`clojure-llm-sdk` ships 36 registered provider profiles across chat, embeddings, moderation, rerank, image generation, transcription, and text-to-speech.

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
| OpenAI Codex | `:codex` | `codex` | `OPENAI_API_KEY` |
| Codex Backend | `:codex-backend` | `codex` | `~/.codex/auth.json` |
| DeepSeek | `:deepseek` | `openai-chat` | `DEEPSEEK_API_KEY` |
| Kimi / Moonshot | `:kimi` | `openai-chat` | `MOONSHOT_API_KEY` |
| Kimi Code | `:kimi-code` | `openai-chat` | `KIMI_API_KEY` |
| Mistral | `:mistral` | `openai-chat` | `MISTRAL_API_KEY` |
| Groq | `:groq` | `openai-chat` | `GROQ_API_KEY` |
| Cerebras | `:cerebras` | `openai-chat` | `CEREBRAS_API_KEY` |
| Together | `:together` | `openai-chat` | `TOGETHER_API_KEY` |
| xAI | `:xai` | `openai-chat` | `XAI_API_KEY` |
| HuggingFace Router | `:huggingface` | `openai-chat` | `HF_TOKEN` |
| Perplexity | `:perplexity` | `perplexity-chat` | `PERPLEXITY_API_KEY` |
| AWS Bedrock | `:bedrock` | `bedrock` | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` |
| Ollama Native | `:ollama-native` | `ollama-native` | none |
| Fake/Test | `:fake` | `fake` | none |

OpenAI-compatible aggregator aliases:

| Provider | ID | Auth |
|---|---|---|
| SambaNova | `:sambanova` | `SAMBANOVA_API_KEY` |
| DeepInfra | `:deepinfra` | `DEEPINFRA_API_KEY` |
| Lambda AI | `:lambda` | `LAMBDA_API_KEY` |
| Nebius | `:nebius` | `NEBIUS_API_KEY` |
| Hyperbolic | `:hyperbolic` | `HYPERBOLIC_API_KEY` |
| Novita | `:novita` | `NOVITA_API_KEY` |
| FriendliAI | `:friendliai` | `FRIENDLI_TOKEN` |
| Featherless | `:featherless` | `FEATHERLESS_API_KEY` |
| Cloudflare Workers AI | `:cloudflare` | `CLOUDFLARE_API_TOKEN` |
| DashScope | `:dashscope` | `DASHSCOPE_API_KEY` |
| Volcengine ARK | `:volcengine` | `ARK_API_KEY` |

`clojure-llm-sdk` is not a proxy router. Aggregator aliases use one credential each and do not perform pool management, spend routing, cooldowns, or tenant isolation.

## Non-Chat Providers

### Embeddings

| Provider | ID | Wire shape | Auth |
|---|---|---|---|
| OpenAI | `:openai` | `/embeddings` | `OPENAI_API_KEY` |
| Mistral | `:mistral` | `/embeddings` | `MISTRAL_API_KEY` |
| Together | `:together` | `/embeddings` | `TOGETHER_API_KEY` |
| Voyage | `:voyage` | `/embeddings` | `VOYAGE_API_KEY` |
| Jina | `:jina` | `/embeddings` | `JINA_API_KEY` |
| Cohere | `:cohere` | `/embed` | `COHERE_API_KEY` |
| Ollama Native | `:ollama-native` | `/api/embed` | none |

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

### Image Generation

| Provider | ID | Auth |
|---|---|---|
| OpenAI | `:openai` | `OPENAI_API_KEY` |
| Vertex Imagen | `:vertex-imagen` | ADC / `GOOGLE_OAUTH_ACCESS_TOKEN` |
| AWS Bedrock Image | `:bedrock` | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` |

### Audio Transcription

| Provider | ID | Auth |
|---|---|---|
| OpenAI Whisper | `:openai` | `OPENAI_API_KEY` |
| Groq Whisper | `:groq` | `GROQ_API_KEY` |

### Text-To-Speech

| Provider | ID | Auth |
|---|---|---|
| OpenAI TTS | `:openai` | `OPENAI_API_KEY` |
| ElevenLabs | `:elevenlabs` | `ELEVENLABS_API_KEY` |

## Provider Notes

### Anthropic OAuth

The Anthropic adapter auto-detects OAuth-style tokens by token prefix and switches from `x-api-key` to Bearer auth. OAuth mode also adds Claude Code identity headers and preserves thinking block signatures for replay.

### Kimi Code

`:kimi` and `:kimi-code` are intentionally separate:

- `:kimi` targets Moonshot's public API at `https://api.moonshot.cn/v1` and reads `MOONSHOT_API_KEY`.
- `:kimi-code` targets `https://api.kimi.com/coding/v1`, reads `KIMI_API_KEY`, uses the `kimi-for-coding` model, and sends KimiCLI-style non-secret identity headers.

### Perplexity

Perplexity citations and search results are normalized into `:part/type :citation` parts. Citation tokens and search query counts surface in `:response/usage` when the provider reports them.

### OpenRouter

OpenRouter supports provider routing options through `:request/provider-options`, including `extra_body.provider`, `extra_body.plugins`, and reasoning configuration.

### Azure OpenAI

Azure routes by deployment name in the URL. Register one provider id per deployment with `llm.sdk.providers.openai-chat/register-azure-deployment!`. See [provider-configuration.md](provider-configuration.md#azure-openai-deployments).

### Custom OpenAI-Compatible Providers

Register private OpenAI-compatible endpoints with `register-alias!`. See [provider-configuration.md](provider-configuration.md#custom-openai-compatible-providers).
