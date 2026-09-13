# Python LiteLLM Provider Parity

This is the parity ledger for the providers currently registered by
`clojure-llm-sdk`. It is intentionally not a Python LiteLLM provider-count
target; unrelated providers are not added solely because the Python project
supports them.

Baselines:

- Python [BerriAI/LiteLLM](https://github.com/BerriAI/litellm) supplies the
  provider catalog and endpoint-family vocabulary used by the ledger below.
- The separate Clojure library
  [unravel-team/litellm-clj at `14bcdd949c0207d6c4988a3db887a1a7fa1c5522`](https://github.com/unravel-team/litellm-clj/commit/14bcdd949c0207d6c4988a3db887a1a7fa1c5522)
  is used only for the scoped implementation comparison in the next section.
- SDK scope is limited to providers exposed through `llm.sdk/list-providers`
  plus caller-registered Azure deployment profiles.

## Scoped Clojure Library Comparison

At the pinned `litellm-clj` commit, its direct provider code already exposed
Z.AI chat, Gemini embeddings, and Azure deployment embeddings. This SDK added
the corresponding missing provider and surfaces: `:zai`, embeddings on the
existing `:gemini-native` profile, and embeddings on Azure deployment profiles.
Azure profiles also accept the current explicit `:api-style :v1` route while
retaining the classic API-version route as the default.

Three implementation principles were borrowed and adapted rather than copying
that library's public API:

- OpenAI-compatible provider-native extras are flattened into the final wire
  body while transport-owned routing and canonical fields remain protected;
- assistant reasoning and tool state needed for a later turn remain replayable;
  and
- provider failures become structured, classified errors with retry semantics.

This SDK retains its richer namespaced canonical requests and typed response
parts, including provider replay data, citations, reasoning, safety details,
usage, cache, and cost. Provider-reported usage and parser-reported cost remain
authoritative. Missing usage or pricing remains explicitly unknown and is never
invented as zero. `litellm-clj` is not an SDK dependency, and no generated
provider client was added.

## SDK Surface Mapping

| SDK surface | Python LiteLLM endpoint family |
|---|---|
| `complete`, `streaming`, `tools`, `json-schema`, `reasoning` | `chat_completions` |
| `embedding` | `embeddings` |
| `rerank` | `rerank` |
| `image-generation` | `image_generations` |
| `transcription` | `audio_transcriptions` |
| `tts` | `audio_speech` |
| `moderation` | `moderations` |

Python LiteLLM endpoint families with no public SDK surface today are not
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

Image-generation parity means a constructor exists, not that the SDK selects a
billable model. OpenAI, OpenRouter, and Bedrock callers must provide an explicit
`:image/model`; model availability and account access remain provider concerns.

## Current Provider Ledger

| SDK provider | Python LiteLLM provider | Constructor-backed SDK parity | Remaining Python LiteLLM gap for this provider family |
|---|---|---|---|
| `:openai` | `openai` | chat, streaming, tools, JSON schema, reasoning, file attachments on Chat Completions, embeddings, moderation, image generation, transcription, TTS | general Responses public surface, text completions, image edits/variations, realtime, assistants, vector stores, batches, fine tuning, containers, RAG |
| caller-defined Azure deployment | `azure` | chat, streaming, tools, JSON schema, reasoning, embeddings; classic deployment routing by API version or explicit `/openai/v1` routing | No further Azure surface is claimed without a dedicated constructor and fixture evidence |
| `:anthropic` | `anthropic` | messages-backed chat, streaming, tools, JSON schema, thinking, OAuth/OAT, file/document attachments, native cache markers | count tokens, batches, file lifecycle APIs, skills, public raw messages/responses pass-through |
| `:bedrock` | `bedrock` | Converse chat, streaming eventstream, tools, JSON schema, reasoning, guardrails, cachePoint, multimodal file/document attachments, image generation, rerank | embeddings, count tokens, vector search/RAG, and provider-specific invoke transforms outside the implemented image families |
| `:cohere` | `cohere` | native v2 chat, streaming, tools, JSON schema, reasoning, citations, textual file/document attachments via `documents`, v2 embeddings, v2 rerank | responses/messages wrappers and a2a/interactions are not SDK surfaces |
| `:gemini-native` | `gemini` | native chat, streaming, tools, multimodal, file/document attachments, reasoning, cachedContent handling, native batch embeddings | count tokens, file lifecycle APIs, vector search/RAG, realtime, public generateContent pass-through |
| `:vertex-gemini` | `vertex_ai` | Vertex Gemini chat, streaming, tools, multimodal, file/document attachments, reasoning | embeddings, TTS, OCR, count tokens, fine tuning, RAG/vector stores, realtime, public generateContent pass-through |
| `:vertex-anthropic` | `vertex_ai` | Claude-on-Vertex messages chat, streaming, tools, JSON schema, thinking, file/document attachments, native cache markers (reuses Anthropic body/parse with GCP OAuth) | count tokens, batches, file lifecycle APIs, model listing, public rawPredict pass-through |
| `:vertex-imagen` | `vertex_ai` | Gemini image generation over Vertex `generateContent` (compatibility provider id) | image edits/videos and other Vertex AI endpoint families |
| `:openrouter` | `openrouter` | chat, streaming, tools, JSON schema, reasoning, provider routing, embeddings, image generation, live model/pricing lookup | responses, image edit, messages/a2a/interactions |
| `:perplexity` | `perplexity` | native Agent chat, streaming, tools, typed search output/citations, usage, and structured errors | dedicated search endpoint and other endpoint families not exposed through the Agent constructor |
| `:codex` | `openai` | OpenAI Responses-shaped Codex chat, streaming, tools, file attachments, reasoning, encrypted reasoning | not a general OpenAI Responses API surface |
| `:codex-backend` | `chatgpt` | ChatGPT backend Responses/SSE path, OAuth auth-json cache, tools, file attachments, reasoning | not a general ChatGPT automation surface |
| `:ollama-native` | `ollama` | native chat, streaming, tools, JSON schema, reasoning, multimodal input, embeddings | responses/messages/a2a/interactions wrappers |
| `:voyage` | `voyage` | dedicated text embeddings and rerank transports | contextualized and multimodal embedding endpoints require distinct canonical input shapes |
| `:jina` | `jina_ai` | dedicated dense/base64 embeddings and rerank transports | sparse, multivector, and multimodal outputs require distinct canonical result shapes |
| `:elevenlabs` | `elevenlabs` | TTS | Python LiteLLM also declares chat/messages/responses; this SDK treats ElevenLabs as a TTS provider only |
| `:mistral` | `mistral` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning, embeddings | OCR and audio transcription |
| `:groq` | `groq` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning, transcription | responses/messages/a2a/interactions |
| `:deepseek` | `deepseek` | OpenAI-compatible chat, streaming, tools, reasoning | responses/messages/a2a/interactions |
| `:kimi` | `moonshot` | OpenAI-compatible chat, streaming, tools, reasoning | responses/messages/a2a/interactions |
| `:kimi-code` | `anthropic` | Kimi Code OpenAI-compatible coding endpoint, streaming, tools, reasoning | Anthropic endpoint families are not implied by Kimi Code |
| `:cerebras` | `cerebras` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning | responses/messages/a2a/interactions |
| `:together` | `together_ai` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning, embeddings | responses/messages/a2a/interactions |
| `:xai` | `xai` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning | realtime and responses/messages/a2a/interactions |
| `:zai` | `zai` | OpenAI-compatible GLM chat, streaming, tools, reasoning and reasoning replay, multimodal image input, cached-token usage, structured errors | No additional Z.AI endpoint family is claimed without a dedicated constructor and fixture evidence |
| `:huggingface` | `huggingface` | OpenAI-compatible chat, streaming, tools, JSON schema | embeddings and rerank |
| `:sambanova` | `sambanova` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning | responses/messages/a2a/interactions |
| `:deepinfra` | `deepinfra` | OpenAI-compatible chat, streaming, tools, JSON schema, reasoning | responses/messages/a2a/interactions |
| `:nebius` | `nebius` | OpenAI-compatible chat, streaming, tools, JSON schema, embeddings | responses/messages/a2a/interactions |
| `:hyperbolic` | `hyperbolic` | OpenAI-compatible chat, streaming, tools | responses/messages/a2a/interactions |
| `:novita` | `novita` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:friendliai` | `friendliai` | OpenAI-compatible chat, streaming, tools | responses/messages/a2a/interactions |
| `:featherless` | `featherless_ai` | OpenAI-compatible chat, streaming, tools | responses/messages/a2a/interactions |
| `:cloudflare` | `cloudflare` | OpenAI-compatible chat, streaming, tools with caller-supplied account base URL | responses/messages/a2a/interactions; account-scoped model listing |
| `:dashscope` | `dashscope` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:volcengine` | `volcengine` | OpenAI-compatible chat, streaming, tools, JSON schema | responses/messages/a2a/interactions |
| `:fake` | none | deterministic test transport only | excluded from Python LiteLLM parity |

## Hard Rule

Provider coverage must stay constructor-backed. A provider row may not claim an
SDK surface unless the profile has the matching transport constructor and the
request can be built offline by `llm.sdk.provider-coverage-test`.
