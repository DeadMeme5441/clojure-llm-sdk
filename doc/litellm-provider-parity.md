# LiteLLM Provider Parity

This is the parity ledger for the providers currently registered by
`clojure-llm-sdk`. It is intentionally not a LiteLLM provider-count target.
The SDK should not add unrelated providers just because LiteLLM has them.

Baseline:

- LiteLLM clone: `/tmp/litellm`
- Endpoint matrix: `/tmp/litellm/provider_endpoints_support.json`
- Scope: providers registered by `llm.sdk/list-providers`

## SDK Surface Mapping

| SDK surface | LiteLLM endpoint family |
|---|---|
| `complete`, `streaming`, `tools`, `json-schema`, `reasoning` | `chat_completions` |
| `embedding` | `embeddings` |
| `rerank` | `rerank` |
| `image-generation` | `image_generations` |
| `transcription` | `audio_transcriptions` |
| `tts` | `audio_speech` |
| `moderation` | `moderations` |

LiteLLM endpoint families with no public SDK surface today are not counted as
implemented parity: raw provider-native `messages`/`responses` pass-through,
`a2a`, `interactions`, `batches`, file lifecycle APIs, `count_tokens`,
`search`, `ocr`, `realtime`, `vector_stores`, `assistants`, `fine_tuning`,
`rag`, `containers`, image edits, and image variations. If those become SDK
goals, they need first-class public functions, transports, schemas, fixtures,
and live probes.

File/document attachment is different from file lifecycle management. The
canonical `:part/type :file` message part is an SDK surface and is serialized
for transports with provider-native file/document input support:

- OpenAI Responses / Codex: `input_file`
- Anthropic Messages: `document` content block
- Gemini / Vertex Gemini: `fileData` or `inlineData`
- Bedrock Converse: `document` content block

OpenAI Chat Completions and Cohere chat reject `:file` parts explicitly rather
than stringifying maps into prompts.

## Current Provider Ledger

| SDK provider | LiteLLM provider | Constructor-backed SDK parity | Remaining LiteLLM gap for this provider family |
|---|---|---|---|
| `:openai` | `openai` | chat, streaming, tools, JSON schema, reasoning, file attachments on Chat Completions, embeddings, moderation, image generation, transcription, TTS | general Responses public surface, text completions, image edits/variations, realtime, assistants, vector stores, batches, fine tuning, containers, RAG |
| `:anthropic` | `anthropic` | messages-backed chat, streaming, tools, JSON schema, thinking, OAuth/OAT, file/document attachments, native cache markers | count tokens, batches, file lifecycle APIs, skills, public raw messages/responses pass-through |
| `:bedrock` | `bedrock` | Converse chat, streaming eventstream, tools, guardrails, cachePoint, file/document attachments, image generation, rerank | embeddings, count tokens, invoke-model variants, vector search/RAG, provider-specific invoke transforms |
| `:cohere` | `cohere` | native chat, streaming, tools, citations, textual file/document attachments via `documents`, embeddings, rerank | responses/messages wrappers and a2a/interactions are not SDK surfaces; keep v1/v2 embed/rerank variants fixture-backed |
| `:gemini-native` | `gemini` | native chat, streaming, tools, multimodal, file/document attachments, reasoning, cachedContent handling | count tokens, file lifecycle APIs, vector search/RAG, realtime, public generateContent pass-through |
| `:vertex-gemini` | `vertex_ai` | Vertex Gemini chat, streaming, tools, multimodal, file/document attachments, reasoning | embeddings, TTS, OCR, count tokens, fine tuning, RAG/vector stores, realtime, public generateContent pass-through |
| `:vertex-imagen` | `vertex_ai` | image generation | image edits/videos and other Vertex AI endpoint families |
| `:openrouter` | `openrouter` | chat, streaming, tools, JSON schema, reasoning, provider routing, embeddings, image generation, live model/pricing lookup | responses, image edit, messages/a2a/interactions |
| `:perplexity` | `perplexity` | chat, streaming, JSON schema, web-search-shaped response/citations | dedicated search endpoint, responses/messages/a2a/interactions |
| `:codex` | `openai` | OpenAI Responses-shaped Codex chat, streaming, tools, file attachments, reasoning, encrypted reasoning | not a general OpenAI Responses API surface |
| `:codex-backend` | `chatgpt` | ChatGPT backend Responses/SSE path, OAuth auth-json cache, tools, file attachments, reasoning | not a general ChatGPT automation surface |
| `:ollama-native` | `ollama` | native chat, streaming, tools, multimodal, embeddings | responses/messages/a2a/interactions wrappers |
| `:voyage` | `voyage` | embeddings, rerank | contextual embedding variants need fixture/live coverage |
| `:jina` | `jina_ai` | embeddings, rerank | LiteLLM only declares embeddings in endpoint matrix; rerank is kept because Jina exposes it and fixtures cover it |
| `:elevenlabs` | `elevenlabs` | TTS | LiteLLM also declares chat/messages/responses; this SDK currently treats ElevenLabs as a voice provider only |
| `:mistral` | `mistral` | OpenAI-compatible chat, streaming, tools, JSON schema, embeddings | OCR and audio transcription |
| `:groq` | `groq` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning, transcription | responses/messages/a2a/interactions |
| `:deepseek` | `deepseek` | OpenAI-compatible chat, streaming, tools, reasoning | responses/messages/a2a/interactions |
| `:kimi` | `moonshot` | OpenAI-compatible chat, streaming, tools, reasoning | responses/messages/a2a/interactions |
| `:kimi-code` | `anthropic` | Kimi Code OpenAI-compatible coding endpoint, streaming, tools, reasoning, CLI identity headers | Anthropic endpoint families are not implied by Kimi Code |
| `:cerebras` | `cerebras` | OpenAI-compatible chat, streaming, tools, reasoning | responses/messages/a2a/interactions |
| `:together` | `together_ai` | OpenAI-compatible chat, streaming, tools, JSON schema, embeddings | responses/messages/a2a/interactions |
| `:xai` | `xai` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning | realtime and responses/messages/a2a/interactions |
| `:huggingface` | `huggingface` | OpenAI-compatible chat, streaming, tools, JSON schema | embeddings and rerank |
| `:sambanova` | `sambanova` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:deepinfra` | `deepinfra` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:lambda` | `lambda_ai` | OpenAI-compatible chat, streaming, JSON schema | responses/messages/a2a/interactions |
| `:nebius` | `nebius` | OpenAI-compatible chat, streaming, tools, JSON schema, embeddings | responses/messages/a2a/interactions |
| `:hyperbolic` | `hyperbolic` | OpenAI-compatible chat, streaming, JSON schema | responses/messages/a2a/interactions |
| `:novita` | `novita` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:friendliai` | `friendliai` | OpenAI-compatible chat, streaming, JSON schema | responses/messages/a2a/interactions |
| `:featherless` | `featherless_ai` | OpenAI-compatible chat, streaming, JSON schema | responses/messages/a2a/interactions |
| `:cloudflare` | `cloudflare` | OpenAI-compatible chat, streaming, JSON schema with caller-supplied account base URL | responses/messages/a2a/interactions; account-scoped model listing |
| `:dashscope` | `dashscope` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:volcengine` | `volcengine` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:fake` | none | deterministic test transport only | excluded from LiteLLM parity |

## Hard Rule

Provider coverage must stay constructor-backed. A provider row may not claim an
SDK surface unless the profile has the matching transport constructor and the
request can be built offline by `llm.sdk.provider-coverage-test`.
