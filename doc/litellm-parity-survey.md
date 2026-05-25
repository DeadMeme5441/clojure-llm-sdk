# LiteLLM Parity Survey

Survey of LiteLLM (Python, `litellm-ref/`) against our `clojure-llm-sdk`. Anchored to our identity as a **provider SDK**, not an agent framework. Out of scope (always): proxy server, budget manager, credential pools, MCP clients, observability sinks, secret managers, CLI runtime, plugin scanning.

LiteLLM paths are relative to `/Users/hvyasanakere/Projects/MyriadSelf/litellm-ref/`.

---

## Task 2 — Status as of May 2026

| # | Title | Status |
|---|---|---|
| T2-01 | Embeddings API + EmbedTransport + OpenAI embed adapter | ✅ shipped |
| T2-02 | Cohere chat adapter (native `/v2/chat` with citations) | ✅ shipped |
| T2-03 | OpenAI-compat alias mechanism + 5 new chat adapters (Mistral/Groq/Cerebras/Together/xAI) | ✅ shipped |
| T2-04 | `:part/type :citation` + Perplexity adapter (incl. stream `:stream/citation`) | ✅ shipped |
| T2-05 | Azure OpenAI deployment routing + `:profile/url-builder` hook | ✅ shipped |
| T2-06 | HuggingFace Inference Router alias | ✅ shipped |
| T2-07 | Embedding adapters — Cohere/Voyage/Mistral/Together/Jina | ✅ shipped |
| T2-08 | `sdk/with-fallbacks` helper (no credential pools) | ✅ shipped |
| T2-09 | Bedrock adapter — Converse API + SigV4 + binary event-stream + model-id mapping | ✅ shipped |
| T2-10 | Image generation API + OpenAI DALL-E adapter | ✅ shipped |
| T2-11 | Image gen adapters — Vertex Imagen 3/4 + Bedrock Titan / Stability SD | ✅ shipped |
| T2-12 | `:profile/supported-params` + drop/warn | ✅ shipped |
| T2-13 | Moderation API + OpenAI Moderations adapter | ✅ shipped |
| T2-14 | Audio transcription API + Whisper (OpenAI) + Groq Whisper | ✅ shipped |
| T2-15 | Audio TTS API + OpenAI TTS + ElevenLabs | ✅ shipped |
| T2-16 | Rerank API + Cohere/Voyage/Jina adapters | ✅ shipped |
| T2-17 | LiteLLM-derived pricing snapshot tier | ✅ shipped |
| T2-18 | Profile-level `:cost-calculator` override + per-modality cost fns | ✅ shipped |
| T2-19 | Aggregator profile aliases (11 providers) | ✅ shipped |
| T2-20 | Native Ollama adapter (`/api/chat` + `/api/embed`) | ✅ shipped |

**All 20 Task 2 issues shipped.** 453 tests / 1503 assertions green. The clojure-llm-sdk now spans seven modalities (chat / embed / moderate / rerank / image / transcribe / speak), 36 registered provider profiles, and three AWS-native pieces of infrastructure (SigV4 signer, `vnd.amazon.eventstream` decoder, Bedrock model-id mapping). End-to-end live-verified against Cohere (chat / embed / rerank), OpenAI (chat / embed / moderation), Anthropic, OpenRouter, DeepSeek, Kimi, Kimi Code, and Vertex AI.

---

## 1. Provider Parity Matrix

### Already covered — sanity check

| LiteLLM provider | Our adapter | Recommend |
|---|---|---|
| `openai/` | `:openai` (`openai_chat.clj`) | Already done; spot-check we handle Responses + Chat split (we do via `:codex`). |
| `anthropic/` | `:anthropic` (`anthropic.clj`) | Already done; we go beyond LiteLLM with OAuth auto-detect + thinking signatures. |
| `gemini/` | `:gemini-native` (`gemini_native.clj`) | Already done; we preserve `thoughtSignature`. |
| `vertex_ai/gemini/` | `:vertex-gemini` (`vertex_gemini.clj`) | Already done; OAuth via `gcloud`. |
| `openrouter/` | `:openrouter` (`openrouter.clj`) | Already done; envelope-cache layout is ours, not LiteLLM's. |
| `deepseek/` | `:deepseek` (OpenAI-compat alias) | Already done. |
| `moonshot/` (Kimi) | `:kimi` (OpenAI-compat alias) | Already done. |
| Codex Responses | `:codex`, `:codex-backend` (`codex.clj`) | Already done; we go beyond LiteLLM with backend OAuth + encrypted reasoning. |
| `bedrock/` | `:bedrock` (scaffolded) | **Finish it**: cache-point, Converse API, Anthropic-on-Bedrock model id mapping; LiteLLM's `bedrock/chat/converse_*` is the reference. |

### Tier 1 — high value, port soon

| LiteLLM provider | Shape / quirks | Recommend |
|---|---|---|
| `cohere/chat/` + `chat/v2_transformation.py` | **Not OpenAI-compatible.** Custom `/v1/chat` and `/v2/chat` endpoints with `preamble`, `chat_history`, `connectors`, `documents`, `tool_results`, `citations` in response. Their `cohere_messages_pt_v2` rewrites our `:message/role :tool` into Cohere's `TOOL` role + `tool_call_id` plumbing. | **Yes (P1)**. Native adapter, not an OpenAI alias. Map `documents` and `citations` to our `:provider-state` parts. |
| `mistral/chat/transformation.py` | OpenAI-compatible with edits: drops `frequency_penalty`/`presence_penalty`, has `safe_prompt`, `random_seed`, and a magnet/JSON-mode quirk. Embeddings + OCR + audio also available. | **Yes (P1)**. Thin alias over `openai_chat.clj` + a quirks map. |
| `groq/chat/transformation.py` | OpenAI-compatible subclass; mostly identity. Custom `reasoning_format` for Llama 3.x reasoning models. Also has STT (`groq/stt/`). | **Yes (P1)**. Trivial alias; STT later. |
| `cerebras/chat.py` | OpenAI-compatible, dead-simple. 90-line file. `reasoning_effort` for supported models. | **Yes (P1)**. Trivial alias. |
| `together_ai/chat.py` + `embed.py` + `rerank/` | OpenAI-compatible chat. Has its own embeddings, rerank, and image-gen (FLUX, SDXL). | **Yes (P1)** for chat + embed. Rerank/images later. |
| `perplexity/chat/transformation.py` | OpenAI-compatible, but response carries `citations`, `search_results`, `num_search_queries`, citation-tokens, and Perplexity-specific usage extensions. | **Yes (P1)**. Important to surface citations as `:provider-state` or a new `:part/type :citation`. We don't have one — recommend extending the schema. |
| `huggingface/chat/transformation.py` | OpenAI-compatible router (`router.huggingface.co/v1`) with model URL prefix routing. Also `huggingface/embedding/` (TGI + Serverless Inference). | **Yes (P1)** for chat. TGI vs Serverless URL mangling is the only quirk; copy `_build_chat_completion_url`. |
| `azure/chat/`, `azure/azure.py` (58k LOC) | OpenAI shape but **deployment-routing**: URL is `{endpoint}/openai/deployments/{deployment}/chat/completions?api-version=YYYY-MM-DD`. Auth via `api-key:` header or AAD bearer. Has every modality and an `o_series_handler` for o-series. | **Yes (P1)**. New transport because URL & auth differ — but the body is OpenAI shape, so it's a one-day port if we treat deployment + api-version as profile fields. |
| `ollama/chat/`, `completion/` | OpenAI-compat-ish on newer versions (`/v1/chat/completions`); also has native `/api/chat` and `/api/generate`. Local-only, no auth. | **Yes (P1)** via OpenAI-compat URL. Native `/api/chat` only if someone needs vision/embeddings on older Ollama. |
| `vllm/completion/`, `hosted_vllm/` | OpenAI-compatible self-hosted inference; `vllm/passthrough/` for raw forwarding. | **Yes (P1)**. Trivial alias — base-URL override + bearer-or-none auth. |
| `xai/chat/`, `xai/responses/` | OpenAI-compatible (`api.x.ai/v1`). Already partially covered through `:codex` (Responses) — Grok via Codex transport with `extra_body.prompt_cache_key` works. Pure xAI chat alias missing. | **Yes (P1)**. Add a `:xai` alias profile pointing at `openai_chat.clj`. |

### Tier 2 — useful but lower priority

| LiteLLM provider | One-liner | Recommend |
|---|---|---|
| `fireworks_ai/chat/` + embed + audio | OpenAI-compatible host with FireFunction tool models; OSS Llama / Mixtral / Qwen serving. | **Later (P2)**. Trivial alias. |
| `databricks/chat/` | OpenAI-compatible; serves DBRX + foundation-model endpoints; OAuth via workspace tokens. | **Later (P2)**. Enterprise customers want this. |
| `nvidia_nim/chat/` + embed + rerank | OpenAI-compatible NIM containers. Enterprise on-prem. | **Later (P2)**. Trivial alias once we have embeddings. |
| `ai21/chat/` | OpenAI-compatible Jamba endpoints. | **Later (P2)**. Trivial. |
| `replicate/chat/` | Custom polling-based API (`prediction`/`output` long poll). Very different shape. | **Later (P3)**. Mostly used for image gen, less for chat. |
| `sagemaker/chat/`, `completion/`, `embedding/` | AWS-signed, very heavy. Custom inference endpoint URLs per user. | **Later (P2)** — pairs with Bedrock work since both need AWS sigv4 which is already in our Bedrock scaffolding. |
| `sambanova/`, `anyscale`, `deepinfra`, `lambda_ai`, `nebius`, `hyperbolic`, `novita`, `cloudflare`, `friendliai`, `featherless_ai`, `dashscope`, `volcengine`, `infinity` | All OpenAI-compatible aggregator clones. | **Later (P3)**, one map entry each in a registry. Not worth dedicated adapter files; ship as profile aliases over `openai_chat.clj`. |
| `aws_polly/`, `elevenlabs/`, `deepgram/` | TTS / STT specialists. | **Later (P2)** when we add audio. |
| `voyage/`, `jina_ai/` | Embeddings specialists (strong rerank). | **Later (P2)** when we add embeddings. |
| `watsonx/`, `azure_ai/` (separate from azure-openai) | Enterprise, niche, OpenAI-shape-ish. | **Later (P2)**. |
| `predibase/`, `clarifai/`, `petals/`, `nlp_cloud/`, `gigachat/`, `baseten/`, `triton/` | OpenAI-compatible or near-it. | **Later (P3)**. |

### Tier 3 — skip outright

| LiteLLM provider | Reason to skip |
|---|---|
| `a2a/`, `langgraph/`, `manus/`, `assistants/`, `responses/mcp/` | Agent-framework integrations. Not our problem. |
| `deprecated_providers/palm`, `aleph_alpha`, `maritalk` | Dead. |
| `brave/`, `tavily/`, `exa_ai/`, `serper/`, `searchapi/`, `searxng/`, `google_pse/`, `duckduckgo/`, `dataforseo/`, `firecrawl/`, `linkup/` | Search APIs. App concern. |
| `pg_vector/`, `milvus/`, `s3_vectors/`, `ragflow/`, `vector_store*` | Vector DBs. App concern. |
| `recraft/`, `runwayml/`, `topaz/`, `stability/`, `black_forest_labs/`, `fal_ai/`, `morph/`, `v0/`, `aiml/`, `lemonade/`, `lm_studio/`, `llamafile/`, `docker_model_runner/`, `compactifai/`, `parallel_ai/`, `vercel_ai_gateway/`, `wandb/`, `heroku/`, `snowflake/`, `sap/`, `scaleway/`, `ovhcloud/`, `bytez/`, `gradient_ai/`, `meta_llama/`, `cometapi/`, `galadriel/`, `chatgpt/`, `aiohttp_openai/`, `pass_through/`, `passthrough/`, `litellm_proxy/`, `github/`, `github_copilot/`, `empower/`, `minimax/`, `zai/`, `nscale/`, `reducto/`, `xinference/`, `dashscope/` (if not requested), `volcengine/` (if not requested) | Niche, dead, duplicative, image-gen-only without users, or proxy-shaped. Skip until a user files a request. |

---

## 2. Feature Parity Matrix

| Feature | LiteLLM entry point | We have it? | Recommend |
|---|---|---|---|
| `completion` / `acompletion` / streaming | `main.py:1085`, `main.py:394`, `utils.py:CustomStreamWrapper` | **Yes**, via `sdk/complete` + `:stream?`. We're synchronous; that's fine, callers can wrap with `core.async`. | Keep as-is. |
| `embedding` / `aembedding` | `main.py:4760`, `main.py:4640`; per-provider `embed/` folders | **No.** | **Port (P1).** Briefing says yes. Add `sdk/embed` returning `{:embedding/vectors [[...]] :usage ...}`; protocol extension `embed-request/parse`. Start with OpenAI, Cohere, Voyage, Mistral, Together. |
| `image_generation` / `aimage_generation` | `main.py` (lazy import via `images/main.py`); per-provider `image_generation/` folders | **No.** | **Port (P2).** Briefing says yes. DALL-E 3, Imagen 3/4 (Vertex), Bedrock Titan/Stable Diffusion. Replicate later. New `sdk/generate-image` returning `{:image/urls [...] :image/b64 [...]}`. |
| `transcription` / `atranscription` (STT) | `main.py:6544`, `main.py:6474` | **No.** | **Later (P2)** per briefing. Whisper (OpenAI), Groq, Deepgram, Fireworks. Multipart upload is the main wire-level thing to get right. |
| `speech` / `aspeech` (TTS) | `main.py:6823`, `main.py:6782` | **No.** | **Later (P2)** per briefing. OpenAI TTS, ElevenLabs, Polly. Return binary blob. |
| `responses` (OpenAI Responses API) | `responses/main.py:901`, `responses/main.py:416` | **Yes**, via `:codex` adapter — we cover Responses + encrypted reasoning. | Keep as-is. Consider adding a dedicated `sdk/respond` thin alias if Responses semantics diverge further (background tasks, MCP tools). |
| `batch_completion` (multi-prompt in one call) | `batch_completion/` | Partially — caller can `map sdk/complete`. | **Skip.** App concern; we don't add value over `pmap` / `core.async`. |
| Batches API (`/v1/batches` cold queue) | `batches/main.py` | **No.** | **Later (P3).** Niche; needs files API + polling. Defer until a user asks. |
| Files API (`/v1/files`) | `files/` | **No.** | **Later (P3).** Pair with Batches when we do it. |
| Rerank | `rerank_api/`; `cohere/rerank/`, `voyage/`, `jina_ai/` | **No.** | **Later (P2).** Same shape across Cohere/Voyage/Jina; tiny addition once we have embeddings. Add `sdk/rerank` returning ranked indices + scores. |
| Moderation | `main.py:6365`, `main.py:6398` | **No.** | **Yes (P2).** Briefing says recommend. OpenAI moderations + Google content safety, both shallow. New `sdk/moderate` returning `{:flagged? :categories {...}}`. |
| Audio realtime / WebSocket | `realtime_api/` | **No.** | **Skip for now.** Out of scope; protocol is heavy (WS streaming + audio framing). Park indefinitely. |
| OCR | `ocr/`; `mistral/ocr/`, `vertex_ai/ocr/` | **No.** | **Skip.** App concern. |
| Video gen | `videos/`, `runwayml/`, `vertex_ai` video | **No.** | **Skip.** Niche, fast-moving. |
| Assistants API | `assistants/main.py` | **No.** | **Skip.** Agent-framework surface; OpenAI-only; orthogonal to our identity. |
| Vector stores | `vector_stores/`, `vector_store_files/` | **No.** | **Skip.** Vector DB integrations are app concerns. |
| Knowledge bases / RAG | `rag/`, search providers | **No.** | **Skip.** App concern. |
| Router / fallbacks / cooldowns | `router.py` (12k LOC), `router_utils/`, `router_strategy/` | Partial — we have `retry.clj` and `rate_limit.clj`. No fallback. | **Port a tiny slice (P1).** Just a `sdk/with-fallbacks` helper that takes `[[provider model] [provider model] ...]` and a request, tries in order, classifies errors via our existing `errors.clj`, and short-circuits on success. **Do not** port deployment pools, weighted shuffle, cooldown caches, latency tracking, TPM/RPM limits, budget routing, tag routing, complexity routing — those are credential-pool features. Reference: `router_utils/fallback_event_handlers.py:85` `run_async_fallback`. |
| Response caching (request-memoization) | `caching/caching.py` (Redis, S3, GCS, disk, semantic Qdrant, in-mem) | **No** — and we shouldn't. Our `cache.clj` is **provider context caching** (Anthropic cache_control, Gemini cachedContent, OpenAI `prompt_cache_key`), which is different and we already do better. | **Skip.** Response-memoization is an app concern; the user owns it with their KV store of choice. |
| Prompt caching / prompt-management | `litellm/types/prompts/init_prompts.py`, `prompt_management/` | **No.** | **Skip.** App concern. |
| Spend tracking / budgets | `budget_manager.py`, `router_strategy/budget_limiter.py` | **No.** | **Skip permanently.** App concern. |
| Cost calculator | `cost_calculator.py:1093` `completion_cost`, `cost_calculator.py:288` `cost_per_token` | **Partial.** We have `pricing.clj` + `usage.clj`. LiteLLM's per-modality dispatch (image, OCR, rerank, transcription, batch with 50%-off, realtime stream) is richer. | **Port pieces (P2).** When embeddings / image / audio land, port their per-call cost formulas. Reference: `cost_calculator.py:2017` `default_image_cost_calculator`, `cost_calculator.py:2009` `transcription_cost`, `cost_calculator.py:2200` `batch_cost_calculator`. |
| Pricing snapshot | `model_prices_and_context_window_backup.json` (1.5 MB) | **Yes** (live + hardcoded). | Use LiteLLM's snapshot JSON as a sync source for our `catalog.clj`/`pricing.clj` registry. Don't replicate their schema field-for-field; pull what we need. |
| Mock / fake provider | `main.py:787` `mock_completion`; mock-completion params | **Yes** (`:fake`). | Keep as-is. Consider richer canned-response generators later. |
| `count_tokens` API | `main.py:7819` `acount_tokens`; `count_tokens/` folders | **No.** | **Later (P3).** Anthropic + Vertex have native count-tokens endpoints; ours would be a thin pass-through. Niche. |
| `health_check` per deployment | `main.py:7284` `ahealth_check` | **No.** | **Skip.** App concern (routing helpers don't need it). |
| `stream_chunk_builder` (reassemble streamed chunks) | `main.py:7514` | **Yes** — we already do this in `stream.clj`. | Keep. |
| Anthropic-Messages adapter for any provider | `anthropic_interface/`, `bridges/anthropic_messages/` | **No.** | **Skip.** App concern; if a user wants Anthropic shape on a non-Anthropic provider, that's a bridge layer above us. |
| Guardrails / safety filters | `guardrail_translation/` | **No.** | **Skip.** App concern. Moderation API alone is enough. |
| Adapter / passthrough endpoints | `passthrough/`, `pass_through/`, `aadapter_*` | **No.** | **Skip.** Proxy concern. |
| Fine-tuning API | `fine_tuning/` | **No.** | **Skip.** Once-a-year operation, app handles. |

---

## 3. Implementation-Pattern Lessons Worth Borrowing

1. **`BaseConfig` per modality** (`llms/base_llm/chat/transformation.py:81`, `embedding/transformation.py:18`, `image_generation/transformation.py:21`, `audio_transcription/transformation.py:38`, `text_to_speech/transformation.py:37`). One ABC per modality with `validate_environment`, `get_complete_url`, `transform_request`, `transform_response`, `get_error_class`. **Adopt:** mirror this as `Transport`, `EmbeddingTransport`, `ImageTransport`, `AudioTransport` Clojure protocols so our adapters scale per modality without one monster protocol.

2. **`get_supported_openai_params(model)`** (e.g. `perplexity/chat/transformation.py:36`, `cerebras/chat.py:53`). Each provider explicitly lists which OpenAI params it accepts so unsupported ones get dropped. **Adopt:** add a `supported-params` set to the provider profile so we can warn-or-drop on unsupported request fields instead of letting the provider 400.

3. **`map_openai_params` per provider** (`cerebras/chat.py:79`, `mistral/chat/transformation.py`). Coerces canonical fields → provider-specific names (e.g. `max_completion_tokens` → `max_tokens`). **Adopt:** we already do this ad-hoc in each adapter; formalize as a profile-level rename map for trivial cases, fall through to bespoke code for hard ones.

4. **OpenAI-compat-by-subclassing** (`groq/chat/transformation.py:43` subclasses `OpenAILikeChatConfig`; `cerebras/chat.py:13` subclasses `OpenAIGPTConfig`). The aggregator providers are 30-line files. **Adopt:** our `openai_chat.clj` should expose a profile-construction helper so adding Groq/Cerebras/Together/etc. is one defrecord + one `provider/register-provider!` call, not a copy-paste of the adapter.

5. **`get_complete_url` is a separate hook** (`huggingface/chat/transformation.py:85`). URL construction is decoupled from body construction. **Adopt:** when we do Azure (deployment-routing) and HF (model-URL routing), this hook is what lets one adapter handle both.

6. **Provider-specific cost calc** (`together_ai/cost_calculator.py`, `perplexity/cost_calculator.py`, `xai/cost_calculator.py`, `vertex_ai/cost_calculator.py`). Each provider can override pricing math (e.g. Perplexity adds citation-token pricing). **Adopt:** when we land richer pricing, allow a profile-level `:cost-calculator` fn override; default to the catalog lookup.

7. **`ModelResponseIterator` per provider** (`cohere/common_utils.py` `ModelResponseIterator`). Stream parsing is per-provider but the iterator interface is uniform. **Adopt:** our `parse-stream-event` is the equivalent and is already cleaner. No change.

8. **`_lazy_imports_registry.py`** (51k LOC). LiteLLM lazy-loads provider clients to keep startup fast even with 100+ providers. **Skip.** Clojure namespace loading is already lazy enough; we don't need this for a 20-30-provider SDK.

9. **Pattern-match deployment routing** (`router_utils/pattern_match_deployments.py`). LiteLLM lets you match `gpt-4*` to a pool. **Skip.** Pool-routing is what we don't do. The model-id-to-provider mapping we need is much simpler and lives in `catalog.clj`.

10. **`responses_api_bridge_check`** (`main.py:951`). Detects when a Chat Completions request should be silently upgraded to Responses (for `o1`/`gpt-5`). **Adopt the idea, not the code:** add a model-capability flag `:requires-responses-api?` and let callers either error or transparently dispatch to the Codex transport.

---

## 4. Proposed Prioritised Task 2 Backlog

| # | Title | Priority | Type | Why / DoD | Deps |
|---|---|---|---|---|---|
| T2-01 | Add embeddings public API + protocol + OpenAI adapter | P1 | feature | New `sdk/embed`, `EmbedTransport` protocol, OpenAI embeddings adapter. Returns `{:embedding/vectors [[...]] :response/usage ...}`. Live fixture for `text-embedding-3-small`. | — |
| T2-02 | Add Cohere chat adapter (native, not OpenAI-compat) | P1 | feature | New native adapter handling `chat_history`, `documents`, `tool_results`, citations. Citations land in `:response/provider-data` or new `:part/type :citation`. | T2-04 (schema extension) |
| T2-03 | Add OpenAI-compat alias mechanism + Mistral / Groq / Cerebras / Together / xAI / vLLM / Ollama / Fireworks / Databricks / NVIDIA NIM aliases | P1 | feature | Single profile-construction helper over `openai_chat.clj`; each alias is ~20 LOC: base-url, env-var name, model prefix, supported-params, quirks map. Ship 5+ adapters in one PR. | — |
| T2-04 | Extend canonical schema: `:part/type :citation` + Perplexity adapter | P1 | feature | New `CitationPart` with `:citation/url`, `:citation/title`, `:citation/text-range`. Perplexity adapter maps `citations` + `search_results` into this. Cohere and Anthropic web-search can reuse. | — |
| T2-05 | Add Azure OpenAI adapter (deployment-routing) | P1 | feature | New transport that respects `{endpoint}/openai/deployments/{deployment}/chat/completions?api-version=...`. Profile fields: `:azure/endpoint`, `:azure/deployment`, `:azure/api-version`. Both api-key and AAD-bearer auth. | T2-03 (alias mechanism not required, but helpful) |
| T2-06 | Add Hugging Face chat adapter (router URL routing) | P1 | feature | Lifts `_build_chat_completion_url` from `huggingface/chat/transformation.py:85`. Same body as OpenAI, model URL mangling differs. Both Serverless Inference and TGI. | T2-03 |
| T2-07 | Add embedding adapters: Cohere, Voyage, Mistral, Together, Jina | P2 | feature | Cohere has its own `embed` endpoint shape (`texts`, `input_type`); rest are OpenAI-compat. Live fixtures per. | T2-01 |
| T2-08 | Add `sdk/with-fallbacks` helper (no credential pools) | P1 | feature | `(sdk/with-fallbacks [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]] req)` tries in order, uses `errors.clj` classification, never reads pools. Reference `router_utils/fallback_event_handlers.py:85`. | — |
| T2-09 | Finish Bedrock adapter (Converse API + model id mapping) | P1 | feature | Currently scaffolded. Finish Converse body, Anthropic-on-Bedrock model id mapping (`anthropic.claude-3-5-sonnet-20240620-v1:0`), tool calls, stream parsing, AWS SigV4 wired up. | — |
| T2-10 | Image generation public API + OpenAI DALL-E 3 adapter | P2 | feature | New `sdk/generate-image`, `ImageTransport` protocol, OpenAI image gen returning `{:image/urls [...]} `or b64. | — |
| T2-11 | Image gen adapters: Vertex Imagen + Bedrock Titan | P2 | feature | Native Imagen 3/4 on Vertex (reuses our `:vertex-gemini` auth) + Bedrock Titan image. | T2-10, T2-09 |
| T2-12 | Add `:supported-params` set to ProviderProfile + dropping/warning | P2 | task | Mirror LiteLLM's `get_supported_openai_params`. When request has unsupported fields, drop with a `clojure.tools.logging/warn`. | — |
| T2-13 | Moderation API + OpenAI Moderations adapter | P2 | feature | New `sdk/moderate`. Returns `{:flagged? :categories {...} :scores {...}}`. | — |
| T2-14 | Audio transcription public API + Whisper (OpenAI) + Groq STT | P2 | feature | New `sdk/transcribe`. Multipart upload. Returns `{:transcription/text :transcription/segments}`. | — |
| T2-15 | Audio TTS public API + OpenAI TTS + ElevenLabs | P2 | feature | New `sdk/speak`. Returns binary `byte-array` + content-type. | — |
| T2-16 | Rerank API + Cohere / Voyage / Jina adapters | P2 | feature | New `sdk/rerank`. Returns ranked indices + scores. | T2-07 |
| T2-17 | Replace hardcoded pricing with LiteLLM-snapshot-derived registry | P2 | task | Pull `model_prices_and_context_window_backup.json` (1.5 MB), strip to fields we use, ship as edn resource. CI job to refresh. | — |
| T2-18 | Profile-level `:cost-calculator` override + per-modality cost fns | P3 | task | Mirror Perplexity citation-token + xAI / Together overrides. Adds `embedding-cost`, `image-cost`, `transcription-cost` to `pricing.clj`. | T2-17, modality landings |
| T2-19 | Aggregator profile aliases: SambaNova, DeepInfra, Lambda AI, Nebius, Hyperbolic, Novita, Friendliai, Featherless, Cloudflare Workers AI, DashScope, Volcengine | P3 | task | One edn registry, no new adapter code. Same alias mechanism as T2-03. | T2-03 |
| T2-20 | Native Ollama adapter (`/api/chat` + `/api/embeddings`) for older Ollama / embeddings | P3 | feature | Only if a user reports they need it; the OpenAI-compat path via T2-03 covers most use cases. | T2-01, T2-03 |

**Suggested sequencing**: T2-01, T2-03, T2-04, T2-05, T2-06 first (chat reach, embeddings unlock). Then T2-02, T2-08, T2-09, T2-07, T2-12, T2-17 (Cohere, fallbacks, finish Bedrock, embedding adapters, polish). Then T2-10, T2-11, T2-13, T2-16 (image + moderation + rerank). Audio (T2-14, T2-15) last per the briefing. T2-18, T2-19, T2-20 opportunistic.

---

## 5. Explicit Non-Goals

We will not port these. Reasoning per item; all anchor to "we are a provider SDK, not an agent framework."

- **Proxy server / OpenAI-compatible gateway** (`proxy/`, `proxy_auth/`, `proxy/proxy_cli.py`). Identity-defining: we are a library, not a server. If a user wants a gateway, they wrap our library in their own service.
- **Budget manager / spend tracking** (`budget_manager.py`, `router_strategy/budget_limiter.py`). Per-tenant cost accounting is the application's job. We surface `:response/usage`; whoever instruments their app does the rest.
- **Credential pools / deployment routing** (`router.py:Router.set_model_list`, `router_strategy/*`, `router_utils/cooldown_handlers.py`). The whole point of LiteLLM's Router is multi-key load-balancing with cooldowns, TPM/RPM enforcement, weighted shuffle, latency-aware routing, lowest-cost routing, tag-based routing, complexity-based routing. **All of this is credential-pool plumbing**, which is precisely what the briefing forbids. We ship a 50-line `with-fallbacks` instead and stop.
- **MCP clients** (`experimental_mcp_client/`, `responses/mcp/`). MCP is an agent-tooling concern. Our job ends at "the request/response carries the tool-call parts."
- **Observability sinks** (`integrations/` — Langfuse, Helicone, Datadog, Lago, Prometheus, S3, Sentry, OpenTelemetry, Phoenix, Arize, Galileo, etc.). The app's logging stack instruments around our SDK; we don't import 30 vendors.
- **Secret managers** (`secret_managers/` — AWS Secrets Manager, Google Secret Manager, Azure Key Vault, HashiCorp Vault). We read env vars + accept `:auth-token` overrides; that's the contract.
- **Redis everywhere** (`_redis.py`, `_redis_credential_provider.py`, `caching/redis_*`). Coupling a provider SDK to Redis is wrong. Our `cache.clj` does not need Redis — it emits provider-native cache markers per-request.
- **Response-memoization caching layer** (`caching/caching_handler.py`, semantic Qdrant cache, S3/GCS/disk caches). Memoizing `(prompt, model) → response` is an app concern. Our `cache.clj` is about provider context caching (cache_control, cachedContent, prompt_cache_key), which is a fundamentally different feature.
- **Assistants API** (`assistants/`). Agent surface, OpenAI-only, mostly deprecated in favor of Responses. Skip.
- **Guardrails / prompt management** (`guardrail_translation/`, `prompt_management/`, `policy_templates_backup.json`). App concern; we don't own the policy graph.
- **Pass-through / adapter endpoints** (`passthrough/`, `pass_through/`, `litellm_proxy/`, `adapter_completion`). Proxy-shaped routing. Skip.
- **Setup wizard / `litellm` CLI** (`setup_wizard.py`, `proxy/proxy_cli.py`). We have zero runtime CLI surface.
- **Plugin scanning** (`_lazy_imports*.py` registry-driven auto-discovery of custom LLMs from env). We register adapters explicitly via `require` at the top of `sdk.clj`. No scanning, no plugin protocol.
- **Realtime / WebSocket** (`realtime_api/`). Heavy, fast-moving, and most callers want HTTP. Park indefinitely.
- **A2A / LangGraph / Manus / interactions / skills bridges** (`a2a_protocol/`, `langgraph/`, `manus/`, `interactions/`, `skills/`). Agent-framework integrations.
- **OCR / Video / Fine-tuning / Files / Batches at the start** (`ocr/`, `videos/`, `fine_tuning/`, `files/`, `batches/`). All P3-or-later; defer until a user files a request.

---

## Executive Summary

If we ship the top five Task 2 issues (T2-01 embeddings + OpenAI; T2-02 Cohere; T2-03 OpenAI-compat alias mechanism with 5+ chat aliases; T2-04 citation part + Perplexity; T2-05 Azure OpenAI), the SDK goes from "9 chat providers, no other modalities" to "14+ chat providers (including the biggest enterprise one, Azure), our first non-chat modality (embeddings) covering at least OpenAI, and a clean alias mechanism so the next 10 OpenAI-compat hosts are 20 LOC each." That's the inflection point where we become a credible LiteLLM substitute for the 80% of users who just want canonical provider abstraction without the proxy/router/budget machinery. Audio, image, moderation, and rerank then follow on the same pattern; the underlying transport protocol scales per modality because each is its own narrow interface, not a god-protocol.
