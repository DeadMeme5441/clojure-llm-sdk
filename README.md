# clojure-llm-sdk

A production-quality Clojure SDK for LLM providers — LiteLLM-style canonicalization, but stricter, less lossy, and with first-class support for provider-specific replay state (thinking signatures, encrypted reasoning, assistant phases).

> **This is a provider SDK, not an agent framework.** It owns provider weirdness so your application doesn't have to. It does not import credential pools, CLI runtimes, plugin scanning, observability sinks, secret managers, or proxy/router/budget machinery — those are app concerns.

The SDK ships **27 provider profiles** and **five canonical modalities** (chat, embeddings, moderation, rerank, image generation), plus a `with-fallbacks` helper for sequential provider fallback. See [doc/litellm-parity-survey.md](doc/litellm-parity-survey.md) for the full design rationale and what was deliberately left out.

## Installation

Add to `deps.edn`:

```clojure
com.deadmeme5441/clojure-llm-sdk {:git/url "https://github.com/DeadMeme5441/clojure-llm-sdk"
                                   :git/sha "LATEST_SHA"}
```

## Quick Start

```clojure
(require '[llm.sdk :as sdk])

;; --- Chat ---
(sdk/complete
  :openai
  {:request/model "gpt-4o-mini"
   :request/messages [{:message/role :user :message/content "Hello, world!"}]})
;; => {:response/provider :openai :response/model "gpt-4o-mini"
;;     :response/parts [{:part/type :text :text "Hello! How can I help?"}]
;;     :response/finish-reason :stop
;;     :response/usage {:usage/input-tokens 9 :usage/output-tokens 8 ...}}

;; --- Streaming ---
(sdk/complete :openai req
              :stream? true
              :on-event (fn [ev] (println (:event/type ev))))

;; --- Embeddings ---
(sdk/embed
  :openai
  {:embed/model "text-embedding-3-small"
   :embed/inputs ["clojure" "lisp" "java"]})
;; => {:embed/provider :openai :embed/model "text-embedding-3-small"
;;     :embed/vectors [[...] [...] [...]] :embed/dimensions 1536 ...}

;; --- Moderation ---
(sdk/moderate
  :openai
  {:moderation/inputs ["text to classify"]})
;; => {:moderation/provider :openai :moderation/model "omni-moderation-latest"
;;     :moderation/results [{:moderation/flagged? false
;;                           :moderation/categories {...}
;;                           :moderation/scores {...}}]}

;; --- Rerank ---
(sdk/rerank
  :cohere
  {:rerank/model "rerank-english-v3.0"
   :rerank/query "lisp dialect on the JVM"
   :rerank/documents ["Python" "Clojure" "JavaScript"]
   :rerank/top-n 3
   :rerank/return-documents true})
;; => {:rerank/provider :cohere
;;     :rerank/results [{:rerank/index 1 :rerank/score 0.95 :rerank/document "Clojure"}
;;                      {:rerank/index 0 :rerank/score 0.34 :rerank/document "Python"}
;;                      {:rerank/index 2 :rerank/score 0.12 :rerank/document "JavaScript"}]}

;; --- Image generation ---
(sdk/generate-image
  :openai
  {:image/model "dall-e-3"
   :image/prompt "an orange tabby kitten in a sunlit window"
   :image/size "1024x1024"
   :image/quality :hd})
;; => {:image/provider :openai :image/model "dall-e-3"
;;     :image/images [{:image/url "https://..." :image/revised-prompt "..."}]}

;; --- Tools ---
(sdk/complete
  :openai
  {:request/model "gpt-4o"
   :request/messages [{:message/role :user :message/content "Weather in NYC?"}]
   :request/tools [{:type :function
                    :function {:name "get_weather"
                               :description "Get current weather"
                               :parameters {:type :object
                                            :properties {:location {:type :string}}}}}]})
;; => :response/finish-reason :tool-calls
;;    :response/tool-calls [{:tool-call/id "call_abc"
;;                           :tool-call/name "get_weather"
;;                           :tool-call/arguments "{\"location\":\"NYC\"}"}]

;; --- Fallbacks ---
(sdk/with-fallbacks
  [[:openai "gpt-4o"]
   [:anthropic "claude-haiku-4-5"]
   [:groq "llama-3.1-8b-instant"]]
  {:request/messages [{:message/role :user :message/content "Reply: ok"}]})
;; tries each in order; returns the first success; on all-fail throws
;; ex-info with :attempts (vector of classified errors per provider)

;; --- Cost estimation ---
(sdk/estimate-cost
  :openai "gpt-4o"
  {:usage/input-tokens 1000 :usage/output-tokens 500})
;; => {:cost/status :actual :cost/amount-usd 0.0075M ...}

;; --- Model metadata ---
(sdk/model-context-length :anthropic "claude-opus-4-7")  ;; => 200000
(sdk/model-capabilities :openai "gpt-4o")                ;; => #{:chat :tools :vision ...}
(count (sdk/list-models))                                ;; => 1000+ across every tier

;; --- Context caching (opt in per request) ---
(sdk/complete
  :anthropic
  {:request/model "claude-haiku-4-5"
   :request/messages [{:message/role :system :message/content "<long system prompt>"}
                      {:message/role :user :message/content "Hi"}]
   :request/cache {:ttl "5m"}})
;; Turn 2 usage: {:usage/cached-input-tokens 13824 :usage/cache-write-tokens 12 ...}
```

## Supported Providers

### Chat

| Provider | ID | Protocol | Auth env var | Status |
|----------|-----|----------|--------------|--------|
| **OpenAI** | `:openai` | `openai-chat` | `OPENAI_API_KEY` | ✅ Full |
| **OpenRouter** | `:openrouter` | `openrouter` | `OPENROUTER_API_KEY` | ✅ Full |
| **Anthropic Messages** | `:anthropic` | `anthropic-messages` | `ANTHROPIC_API_KEY` | ✅ Full |
| **Anthropic OAuth** | `:anthropic` | `anthropic-messages` | `CLAUDE_OAT_TOKEN`¹ | ✅ Full |
| **Gemini Native** | `:gemini-native` | `gemini-native` | `GEMINI_API_KEY` | ✅ Full |
| **Vertex Gemini** | `:vertex-gemini` | `gemini-native` | `GOOGLE_APPLICATION_CREDENTIALS` | ✅ Full |
| **OpenAI Codex (Standard)** | `:codex` | `codex` | `OPENAI_API_KEY` | ✅ Full |
| **OpenAI Codex (Backend)** | `:codex-backend` | `codex` | `~/.codex/auth.json`² | ✅ Full |
| **DeepSeek** | `:deepseek` | `openai-chat` | `DEEPSEEK_API_KEY` | ✅ Full |
| **Kimi / Moonshot** | `:kimi` | `openai-chat` | `KIMI_API_KEY` | ✅ Full |
| **Mistral** | `:mistral` | `openai-chat` | `MISTRAL_API_KEY` | ✅ Full |
| **Groq** | `:groq` | `openai-chat` | `GROQ_API_KEY` | ✅ Full |
| **Cerebras** | `:cerebras` | `openai-chat` | `CEREBRAS_API_KEY` | ✅ Full |
| **Together** | `:together` | `openai-chat` | `TOGETHER_API_KEY` | ✅ Full |
| **xAI** | `:xai` | `openai-chat` | `XAI_API_KEY` | ✅ Full |
| **HuggingFace Router** | `:huggingface` | `openai-chat` | `HF_TOKEN` | ✅ Full |
| **Perplexity** | `:perplexity` | `perplexity-chat` | `PERPLEXITY_API_KEY` | ✅ Full (citations) |
| **Azure OpenAI** | user-defined³ | `openai-chat` (deployment) | `AZURE_OPENAI_API_KEY` | ✅ Full |
| **SambaNova** | `:sambanova` | `openai-chat` | `SAMBANOVA_API_KEY` | ✅ Alias |
| **DeepInfra** | `:deepinfra` | `openai-chat` | `DEEPINFRA_API_KEY` | ✅ Alias |
| **Lambda AI** | `:lambda` | `openai-chat` | `LAMBDA_API_KEY` | ✅ Alias |
| **Nebius** | `:nebius` | `openai-chat` | `NEBIUS_API_KEY` | ✅ Alias |
| **Hyperbolic** | `:hyperbolic` | `openai-chat` | `HYPERBOLIC_API_KEY` | ✅ Alias |
| **Novita** | `:novita` | `openai-chat` | `NOVITA_API_KEY` | ✅ Alias |
| **Friendliai** | `:friendliai` | `openai-chat` | `FRIENDLI_TOKEN` | ✅ Alias |
| **Featherless** | `:featherless` | `openai-chat` | `FEATHERLESS_API_KEY` | ✅ Alias |
| **Cloudflare Workers AI** | `:cloudflare` | `openai-chat` | `CLOUDFLARE_API_TOKEN`⁴ | ✅ Alias |
| **DashScope** | `:dashscope` | `openai-chat` | `DASHSCOPE_API_KEY` | ✅ Alias |
| **Volcengine ARK** | `:volcengine` | `openai-chat` | `ARK_API_KEY` | ✅ Alias |
| **AWS Bedrock** | `:bedrock` | `bedrock` | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` | 🏗 Scaffolded (T2-09) |
| **Fake/Test** | `:fake` | `fake` | None | ✅ Full |

¹ Auto-detected by token prefix (`sk-ant-*`, `eyJ*`, `cc-*`). Switches to Bearer auth + Claude Code identity.
² Reads OAuth tokens from the official [OpenAI Codex CLI](https://github.com/openai/codex) `~/.codex/auth.json`.
³ Azure profiles are registered per deployment via `(llm.sdk.providers.openai-chat/register-azure-deployment! ...)` — see [Azure section](#azure-openai-deployment-routing).
⁴ Cloudflare's base-url contains a `REPLACE-WITH-ACCOUNT-ID` placeholder users must substitute (the Workers AI surface is per-account scoped).

### Embeddings

| Provider | ID | Wire shape | Auth env var |
|---|---|---|---|
| OpenAI | `:openai` | `/embeddings` | `OPENAI_API_KEY` |
| Mistral | `:mistral` | `/embeddings` | `MISTRAL_API_KEY` |
| Together | `:together` | `/embeddings` | `TOGETHER_API_KEY` |
| Voyage | `:voyage` | `/embeddings` | `VOYAGE_API_KEY` |
| Jina | `:jina` | `/embeddings` | `JINA_API_KEY` |
| Cohere | `:cohere` | `/embed` (native) | `COHERE_API_KEY` |

### Moderation

| Provider | ID | Auth env var |
|---|---|---|
| OpenAI | `:openai` | `OPENAI_API_KEY` |

### Rerank

| Provider | ID | Wire shape | Auth env var |
|---|---|---|---|
| Cohere | `:cohere` | `/rerank` (top_n / results) | `COHERE_API_KEY` |
| Jina | `:jina` | `/rerank` (top_n / results) | `JINA_API_KEY` |
| Voyage | `:voyage` | `/rerank` (top_k / data) | `VOYAGE_API_KEY` |

### Image generation

| Provider | ID | Auth env var |
|---|---|---|
| OpenAI (DALL-E 2/3, gpt-image-1) | `:openai` | `OPENAI_API_KEY` |

## Environment Variables

| Variable | Used for |
|----------|----------|
| `OPENAI_API_KEY` | OpenAI (chat / embed / moderate / image), Codex (standard) |
| `ANTHROPIC_API_KEY` | Anthropic (API key) — `sk-ant-api03-*` |
| `CLAUDE_OAT_TOKEN` | Anthropic (OAuth) — `sk-ant-oat-*` or JWT `eyJ*` |
| `GEMINI_API_KEY` | Gemini Native |
| `GOOGLE_OAUTH_ACCESS_TOKEN` | Vertex Gemini — bearer override (`gcloud auth print-access-token`) |
| `GOOGLE_CLOUD_PROJECT` + `GOOGLE_CLOUD_LOCATION` | Vertex Gemini — project + region (`us-central1`, `global`, …) |
| `OPENROUTER_API_KEY` | OpenRouter |
| `DEEPSEEK_API_KEY` | DeepSeek |
| `KIMI_API_KEY` | Kimi / Moonshot |
| `MISTRAL_API_KEY` | Mistral (chat + embed) |
| `GROQ_API_KEY` | Groq |
| `CEREBRAS_API_KEY` | Cerebras |
| `TOGETHER_API_KEY` | Together (chat + embed) |
| `XAI_API_KEY` | xAI |
| `HF_TOKEN` | HuggingFace Router |
| `PERPLEXITY_API_KEY` | Perplexity |
| `COHERE_API_KEY` | Cohere (embed + rerank) |
| `VOYAGE_API_KEY` | Voyage (embed + rerank) |
| `JINA_API_KEY` | Jina (embed + rerank) |
| `AZURE_OPENAI_API_KEY` | Azure OpenAI deployments (default; per-deployment override via `register-azure-deployment!`) |
| `SAMBANOVA_API_KEY` / `DEEPINFRA_API_KEY` / `LAMBDA_API_KEY` / `NEBIUS_API_KEY` / `HYPERBOLIC_API_KEY` / `NOVITA_API_KEY` / `FRIENDLI_TOKEN` / `FEATHERLESS_API_KEY` / `CLOUDFLARE_API_TOKEN` / `DASHSCOPE_API_KEY` / `ARK_API_KEY` | Aggregator aliases (T2-19) |
| `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` + `AWS_REGION` | AWS Bedrock |

Create a `.env` file in your project root (gitignored by default):

```bash
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-api03-...
GEMINI_API_KEY=AIza...
OPENROUTER_API_KEY=sk-or-...
```

## Architecture

### Canonical schema

All chat providers translate to/from a canonical schema:

```clojure
;; Request
{:request/model "gpt-4o"
 :request/messages [{:message/role :user :message/content "Hello!"}]
 :request/tools [...]
 :request/temperature 0.7
 :request/cache {:ttl "5m"}}

;; Response
{:response/id "resp_123"
 :response/provider :openai
 :response/model "gpt-4o"
 :response/parts [{:part/type :text :text "Hi!"}
                  {:part/type :reasoning :reasoning/text "Let me think..."}
                  {:part/type :tool-call
                   :tool-call/id "call_1"
                   :tool-call/name "get_weather"
                   :tool-call/arguments "{\"location\":\"NYC\"}"}
                  {:part/type :citation :citation/url "https://..." :citation/title "..."}]
 :response/tool-calls [...]          ; extracted for convenience
 :response/finish-reason :stop       ; :stop :length :tool-calls :content-filter :incomplete
 :response/usage {:usage/input-tokens 10
                  :usage/output-tokens 5
                  :usage/total-tokens 15}
 :response/provider-data {...}}      ; provider-specific replay state
```

Part types: `:text`, `:image`, `:file`, `:tool-call`, `:tool-result`, `:reasoning`, `:safety`, `:citation`, `:provider-state`, `:unknown/provider-native`.

### Per-modality protocols

Each modality has its own narrow protocol — no god-protocol. New modalities slot in alongside existing ones without touching the chat surface.

| Modality | Protocol | Driver | Public fn |
|---|---|---|---|
| Chat | `llm.sdk.transport/Transport` | `llm.sdk/complete` | `sdk/complete` |
| Embeddings | `llm.sdk.transport.embed/EmbedTransport` | `llm.sdk.embed/embed` | `sdk/embed` |
| Moderation | `llm.sdk.transport.moderate/ModerationTransport` | `llm.sdk.moderate/moderate` | `sdk/moderate` |
| Rerank | `llm.sdk.transport.rerank/RerankTransport` | `llm.sdk.rerank/rerank` | `sdk/rerank` |
| Image generation | `llm.sdk.transport.image/ImageTransport` | `llm.sdk.image/generate-image` | `sdk/generate-image` |

Each provider profile carries the constructor for any modality it supports:

```clojure
:profile/transport-constructor            ; chat (required)
:profile/embed-transport-constructor      ; optional
:profile/moderation-transport-constructor ; optional
:profile/rerank-transport-constructor     ; optional
:profile/image-transport-constructor      ; optional
```

Calling `sdk/embed` on a provider whose profile lacks `:profile/embed-transport-constructor` throws an `ex-info` with a clear message rather than failing downstream.

### Provider-specific replay state

A key design principle: **provider-specific replay state must survive round-trips**:

- **Anthropic**: `reasoning_details` (thinking blocks with signatures)
- **Codex**: `codex_reasoning_items` (encrypted reasoning), `codex_message_items` (assistant phases)
- **Gemini**: `thought_signature` (for tool-call replay)
- **Perplexity**: `search_results` / `citations` / `citation_tokens` (web-search state)

This state is stored in `:response/provider-data` and (for chat) can be threaded back into subsequent requests via `:message/provider-data`. Citations from Perplexity and other web-search providers surface as `:part/type :citation` parts in `:response/parts`.

### Streaming

Streaming uses a canonical event taxonomy:

| Event | Description |
|-------|-------------|
| `:stream/start` | Request began |
| `:stream/content-delta` | Text chunk |
| `:stream/reasoning-delta` | Reasoning/thinking chunk |
| `:stream/tool-call-start` | Tool call began |
| `:stream/tool-call-delta` | Tool call args chunk |
| `:stream/tool-call-end` | Tool call completed |
| `:stream/citation` | Citation surfaced mid-stream (Perplexity etc.) |
| `:stream/usage` | Usage data |
| `:stream/provider-state` | Provider-specific replay state |
| `:stream/error` | Provider error |
| `:stream/end` | Stream finished |

Events reduce into an accumulator that builds the final canonical response. Citation events fold into `Response.parts` as `:part/type :citation` parts.

`parse-stream-event` may return a single event map, a vector of events (for SSE lines that pack multiple semantic events — Perplexity's final chunk carries citations + usage + finish_reason together), or `nil` to skip the line.

### Sequential fallbacks

`sdk/with-fallbacks` tries each `[provider model]` pair in order; returns the first successful response. If every attempt fails it throws an `ex-info` carrying an `:attempts` vector of classified failures.

```clojure
(sdk/with-fallbacks
  [[:openai "gpt-4o"] [:anthropic "claude-haiku-4-5"]]
  request
  {:on-attempt (fn [m] (log/warnf "fallback: %s" m))}) ; optional callback per failure
```

Terminal errors (auth, quota, content-filter) fall through to the next provider just like transient ones — the caller asked for a fallback chain, so the SDK respects that intent. **No credential pools, no cooldowns, no TPM/RPM tracking, no budget routing** — those are credential-pool features and stay the calling application's concern.

### Supported-params drop+warn

Profiles can declare `:profile/supported-params` (a set of canonical request keys). When set, `sdk/complete` strips any droppable canonical field present in the request but missing from the set, and emits a warning. Perplexity is the first profile to opt in — its `/chat/completions` rejects `:request/tools`, `:request/tool-choice`, `:request/reasoning`, and `:request/cache`, so these are silently dropped (with a warning) instead of letting the provider 400.

The warn function is the dynamic var `llm.sdk.request/*warn-fn*`, defaulting to one line per warning on `*err*`. Apps with a real logger rebind it.

## Provider details

### Anthropic OAuth

The Anthropic adapter **auto-detects OAuth tokens** by format:

- `sk-ant-*` (but not `sk-ant-api*`) → setup tokens / managed keys
- `eyJ*` → JWTs from Anthropic OAuth flow
- `cc-*` → Claude Code OAuth access tokens

When OAuth is detected, the adapter automatically:
- Switches from `x-api-key` to `Authorization: Bearer`
- Prepends Claude Code system prompt identity
- Sanitizes product name references
- Prefixes tool names with `mcp_`
- Adds OAuth beta headers (`claude-code-20250219`, `oauth-2025-04-20`)
- Sets `user-agent: claude-cli/2.1.74 (external, cli)` and `x-app: cli`

```clojure
;; Uses ANTHROPIC_API_KEY (regular API key)
(sdk/complete :anthropic req)

;; Uses CLAUDE_OAT_TOKEN (OAuth — auto-detected)
(sdk/complete :anthropic req)
```

### Codex (OpenAI Responses API)

Two distinct endpoints:

**Standard API** (`:codex`) — `api.openai.com/v1/responses`:
```clojure
(sdk/complete :codex
              {:request/model "gpt-4o-mini"
               :request/messages [...]})
```

**Codex Backend** (`:codex-backend`) — `chatgpt.com/backend-api/codex`:
- Reads OAuth tokens from `~/.codex/auth.json` (shared with official Codex CLI)
- Requires Cloudflare bypass headers (`originator: codex_cli_rs`, `ChatGPT-Account-ID` from JWT)
- Supports models like `gpt-5.5`

```clojure
(sdk/complete :codex-backend
              {:request/model "gpt-5.5"
               :request/messages [...]})
```

### OpenRouter

OpenRouter-specific quirks handled automatically:
- `extra_body.provider` — provider preferences routing
- `extra_body.plugins` — Pareto Code router plugin (`openrouter/pareto-code`)
- `extra_body.reasoning` — reasoning config in extra_body (not top-level)
- `HTTP-Referer` and `X-Title` headers for API attribution

```clojure
(sdk/complete :openrouter
              {:request/model "openai/gpt-4o-mini"
               :request/provider-options
               {:provider {:order ["Together" "Fireworks"]}
                :pareto {:min-coding-score 0.8}}})
```

### Perplexity (citations)

Perplexity's `/chat/completions` returns `citations` and (newer API) `search_results` alongside the response. The adapter extracts them into canonical `:part/type :citation` parts on `:response/parts`:

```clojure
(sdk/complete
  :perplexity
  {:request/model "sonar"
   :request/messages [{:message/role :user :message/content "Who made Clojure?"}]})
;; :response/parts =>
;;   [{:part/type :text :text "Rich Hickey created Clojure in 2007..."}
;;    {:part/type :citation :citation/url "https://..." :citation/title "Wikipedia"}
;;    {:part/type :citation :citation/url "https://..." :citation/title "Clojure.org"}]
```

`citation_tokens` and `num_search_queries` surface on `:response/usage` as `:usage/citation-tokens` and `:usage/search-queries`. In stream mode citations arrive as `:stream/citation` events that the reducer folds into `:response/parts`.

### Azure OpenAI (deployment routing)

Azure routes by deployment name in the URL rather than model name in the body. Register one profile per deployment:

```clojure
(require '[llm.sdk.providers.openai-chat :as openai-chat])

(openai-chat/register-azure-deployment!
  {:id :azure-gpt4o-prod
   :endpoint "https://my-resource.openai.azure.com"
   :deployment "gpt-4o-prod-deployment"
   :api-version "2024-08-01-preview"
   :env-var-names ["AZURE_OPENAI_API_KEY"]})

(sdk/complete :azure-gpt4o-prod
              {:request/model "ignored-by-azure"      ; routing is by deployment
               :request/messages [...]})
```

The URL becomes `{endpoint}/openai/deployments/{deployment}/chat/completions?api-version=...`. Default auth uses the `api-key` header; pass `:auth-strategy :bearer` for AAD bearer tokens.

The underlying `:profile/url-builder` hook is profile-driven — Azure today, HuggingFace TGI tomorrow, anything custom whenever you need it. Pass `:profile/url-builder (fn [profile request path] ...)` on any profile to override URL construction without forking the body builder.

### HuggingFace Router

The Inference Router at `router.huggingface.co/v1` is plain OpenAI-compat — model name lives in the request body (e.g. `"meta-llama/Llama-3.3-70B-Instruct"`) and HF dispatches to inference partners internally. TGI / self-hosted users register their own profile with a custom base-url.

### Aggregator aliases

Eleven long-tail aggregators (SambaNova, DeepInfra, Lambda, Nebius, Hyperbolic, Novita, Friendliai, Featherless, Cloudflare, DashScope, Volcengine) are registered as plain OpenAI-compat aliases — same wire shape as `:openai`, different base-url and env var. `:cloudflare`'s base-url ships with a `REPLACE-WITH-ACCOUNT-ID` placeholder; substitute your Workers AI account id before use.

### User-defined aliases

Register a custom OpenAI-compat provider in one call:

```clojure
(require '[llm.sdk.providers.openai-chat :as openai-chat])

(openai-chat/register-alias!
  {:id :my-private-llm
   :base-url "https://llm.internal.example/v1"
   :env-var-names ["INTERNAL_LLM_KEY"]
   :capabilities #{:chat :streaming :tools}
   :quirks {:drops #{:frequency_penalty :presence_penalty}}})
```

The `:drops` quirk strips request body keys the upstream provider rejects, including any present in `:extra_body`.

## Context caching

Each provider ships its own caching contract. The SDK canonicalizes the
caller-facing shape (`:request/cache`) and emits the right wire format
per adapter — `cache_control` markers for Anthropic, envelope-layout
markers for OpenRouter Claude/Qwen, `prompt_cache_key` for OpenAI/Codex,
`cachedContent` references for Gemini, `cachePoint` sentinels for
Bedrock. Cache statistics surface uniformly on the response under
`:usage/cached-input-tokens` and `:usage/cache-write-tokens`.

### Request shape

```clojure
:request/cache
{:enabled?          true                  ; default true when the map is present
 :ttl               "5m"                  ; or "1h" (Anthropic only)
 :strategy          :auto                 ; :auto | :system-and-3 | :explicit | :none
 :scope-id          "session-1234"        ; cache routing key (OpenAI/Codex/xAI)
 :cached-content-id "cachedContents/xyz"  ; Gemini explicit cache resource name
 :breakpoints       4                     ; max cache_control markers (Anthropic)
 :tools-cache?      true}                 ; also cache the tools[] schema
```

Omit `:request/cache` entirely and nothing changes — no markers, no
key, no `cachedContent`, no `cachePoint`. Caching is purely opt-in.

### Per-provider matrix

| Provider | Strategy | Mechanism | Live-verified |
|---|---|---|---|
| `:anthropic` (native) | `system-and-3` (native layout) | `cache_control` on system blocks, last N messages, optional tools[] | ✅ 13.8k tokens cached, 99.9% hit on turn 2 |
| `:openrouter` (Claude/Qwen) | `system-and-3` (envelope layout) | `cache_control` on outer message dicts + `extra_body.prompt_cache_key` | ✅ 13.8k tokens cached, 92% cost reduction on turn 2 |
| `:openrouter` (other) | `prompt-key` | `extra_body.prompt_cache_key` (forwarded to upstream that supports it) | — |
| `:openai`, `:deepseek`, `:kimi` | `prompt-key` | top-level `prompt_cache_key` | ✅ accepted |
| `:codex` (api.openai.com) | `prompt-key` | top-level `prompt_cache_key` | — |
| `:codex` (api.x.ai) | `prompt-key` | `extra_body.prompt_cache_key` + `x-grok-conv-id` header | unit-tested |
| `:codex-backend` (chatgpt.com) | `prompt-key` | top-level `prompt_cache_key` + `session_id`/`x-client-request-id` extra headers | — |
| `:gemini-native`, `:vertex-gemini` | `implicit` (server-side, no opt-in) | reads `usageMetadata.cachedContentTokenCount` | ✅ Gemini 2.5 Flash @ us-central1: 12,261 tokens cached |
| `:vertex-gemini` (with `:cached-content-id`) | `explicit` | `cachedContent: projects/.../cachedContents/...` body field | — |
| `:bedrock` | `cache-point` | `{:cachePoint {:type "default"}}` blocks after system + final user | unit-tested |

### Inspect which strategy will fire

```clojure
(sdk/cache-strategy :openrouter "anthropic/claude-sonnet-4")
;; => {:strategy :system-and-3, :layout :envelope, :reason "openrouter envelope"}

(sdk/cache-strategy :vertex-gemini "gemini-2.5-flash")
;; => {:strategy :implicit, :layout nil, :reason "vertex gemini implicit only"}
```

### Notes on Vertex / Gemini

Gemini caching is **server-side implicit** by default — the SDK sends no
opt-in field and Google fires the cache when it sees an identical
prefix within "a short amount of time" (Google's wording). Cache hits
surface in `usageMetadata.cachedContentTokenCount`. Empirically:

- **Regional routing** (e.g. `location=us-central1`) fires the cache
  reliably on GA models like `gemini-2.5-flash`.
- **`location=global`** is the only routing for newer Gemini 3.x and
  preview models, but global routing serves ON_DEMAND traffic without
  request affinity, so cache hits are inconsistent until those models
  ship to regional endpoints. See [python-genai #2113](https://github.com/googleapis/python-genai/issues/2113).
- The SDK has no opt-in to flip — implicit caching is purely Google's
  call. `:cached-content-id` is available if you want to pre-create a
  CachedContent resource and reference it explicitly.

## Model registry

A four-tier registry powers every `list-models` / `model-capabilities` /
`model-context-length` / `estimate-cost` call. The SDK never falls back
to "we don't know" for any provider it ships an adapter for — even
cold, before any network call.

### Tiers (highest precedence first)

| Tier | Source | When it answers |
|---|---|---|
| Override | `sdk/register-model-info` (caller-injected) | Custom endpoints whose pricing/context the public registries don't know |
| Live | per-provider `/v*/models` endpoint | Populated lazily by `sdk/refresh-models!`; authoritative for "what does this account currently see" |
| LiteLLM snapshot | `resources/litellm-snapshot.json` (1040 entries / 235 KB / 18 providers) | Filtered snapshot of LiteLLM's community-curated catalog; strongest for Bedrock variants and aggregators |
| models.dev | `https://models.dev/api.json` | 4000+ models across 132+ providers. Cached on disk at `~/.clojure-llm-sdk/models-dev-cache.json` (1h by mtime). Falls back to bundled `resources/models-dev-snapshot.json`. |

`sdk/model-info` returns the merged entry: higher tiers fill in
provenance (`:model/source-url`, `:model/fetched-at`), lower tiers
contribute static metadata (`:model/context-length`,
`:model/cost`, `:model/capabilities`). The returned `:model/source`
tag identifies the highest-precedence tier that contributed.

### Examples

```clojure
;; First time: served from an offline tier, no network call.
(sdk/model-info :openai "gpt-4o")
;; => {:model/id "gpt-4o" :model/provider :openai
;;     :model/context-length 128000 :model/max-output-tokens 16384
;;     :model/cost {:input-per-million 2.5 :output-per-million 10.0
;;                  :cache-read-per-million 1.25}
;;     :model/source :litellm-snapshot ...}

;; Refresh one provider — hits the live /models endpoint
(sdk/refresh-models! :provider :openai)
;; => {:openai {:count 73}}

;; After refresh, live tier wins for source / source-url; pricing still
;; comes up from lower tiers (OpenAI's /models doesn't expose cost)
(sdk/model-info :openai "gpt-4o")
;; => {... :model/source :live-models-api
;;     :model/source-url "https://api.openai.com/v1/models"
;;     :model/cost {:input-per-million 2.5 ...}}

;; Refresh every supported provider in one call
(sdk/refresh-models!)
;; => {:openai {:count 73} :anthropic {:count 12} :gemini-native {:count 60}
;;     :openrouter {:count 312} :deepseek {:count 5} :kimi {:count 4}
;;     :mistral {:count 38} :groq {:count 22} ...}

;; Register custom data for a private endpoint
(sdk/register-model-info :acme "magic-7"
  {:model/context-length 32000
   :model/capabilities #{:chat :tools}
   :model/cost {:input-per-million 0.5 :output-per-million 2.0}})

(sdk/estimate-cost :acme "magic-7"
                   {:usage/input-tokens 1000 :usage/output-tokens 100})
;; => {:cost/status :actual :cost/amount-usd 0.0007M ...}
```

### Refreshing the LiteLLM snapshot

The bundled snapshot is a filtered version of LiteLLM's [model_prices_and_context_window.json](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json). To refresh:

```bash
python3 scripts/build_litellm_snapshot.py [path-to-litellm-repo]
# Writes resources/litellm-snapshot.json
```

The script keeps only providers we have SDK adapters for, strips entries to the fields the registry uses, and handles a few quirks: Bedrock keeps its region prefix in model ids (`us.anthropic.claude-...`); OpenRouter keeps its nested ids (`anthropic/claude-3-haiku`); Perplexity routing-fictional entries (`perplexity/anthropic/...`) are skipped.

### Cache invalidation

Forced refresh is the caller's responsibility — no background timer.
The 1h in-memory + disk TTL is per the models.dev request shape.
Tests run with the cache dir bound to a sandbox so they never touch
`~/.clojure-llm-sdk`.

## Testing

```bash
# Unit tests (fast, no network)
clj -M:test

# Live integration tests (env-gated, real money for chat/image/audio)
# Most live tests are gated on the matching API key being in the env;
# they skip cleanly when missing.
source .env && clj -M:test

# Run only the live namespaces (currently needs an explicit -n; the
# default :live-test regex has a pre-existing bug)
source .env && clj -M:live-test -n llm.sdk.live-models-test
source .env && clj -M:live-test -n llm.sdk.live-embed-test
source .env && clj -M:live-test -n llm.sdk.live-moderation-test
source .env && clj -M:live-test -n llm.sdk.live-rerank-test
source .env && clj -M:live-test -n llm.sdk.live-alias-chat-test
source .env && clj -M:live-test -n llm.sdk.live-perplexity-test
source .env && clj -M:live-test -n llm.sdk.live-azure-test
```

Live tests are gated by credential availability and skipped cleanly when missing. They make minimal API calls (single-token "ok" prompts, fractional-cent embed calls) to keep costs negligible. DALL-E live image-gen tests are intentionally not bundled — at ~$0.04 each they're more expensive than every other live smoke combined, so they're documented as manual smokes instead.

**Current test status:** 371 tests, 1127 assertions, all passing.

## Project structure

```
src/llm/sdk/
  schema.clj              # Canonical request/response/part/stream-event schemas (Malli)
                          # incl. embed / moderation / rerank / image-gen schemas
  provider.clj            # Provider profile registry + auth resolution
  transport.clj           # Chat Transport protocol + helpers
  http.clj                # Thin HTTP layer (hato)
  stream.clj              # Streaming event taxonomy + reducer (incl. :stream/citation)
  usage.clj               # Usage normalization across providers
  models.clj              # Per-provider /v*/models live fetchers + ModelEntry schema
  models_dev.clj          # models.dev breadth registry loader (3-tier cache)
  litellm_snapshot.clj    # LiteLLM-derived snapshot tier (T2-17)
  registry.clj            # Unified registry — override > live > litellm-snapshot > models.dev
  pricing.clj             # Cost estimation, registry-backed
  catalog.clj             # Model catalog and capabilities, registry-backed
  cache.clj               # Provider-agnostic cache markers, layouts, and policy
  errors.clj              # Error classification
  retry.clj               # Jittered backoff retry policy
  rate_limit.clj          # Rate limit tracking
  fallbacks.clj           # sdk/with-fallbacks (T2-08)
  request.clj             # Request preprocessor — :profile/supported-params drop+warn (T2-12)
  embed.clj               # sdk/embed driver (T2-01)
  moderate.clj            # sdk/moderate driver (T2-13)
  rerank.clj              # sdk/rerank driver (T2-16)
  image.clj               # sdk/generate-image driver (T2-10)
  sdk.clj                 # Public API (complete, embed, moderate, rerank,
                          # generate-image, with-fallbacks, ...)
  transport/
    embed.clj             # EmbedTransport protocol (T2-01)
    moderate.clj          # ModerationTransport protocol (T2-13)
    rerank.clj            # RerankTransport protocol (T2-16)
    image.clj             # ImageTransport protocol (T2-10)
  providers/
    openai_chat.clj       # OpenAI Chat Completions + alias mechanism (build-alias-profile,
                          # register-alias!, register-azure-deployment!)
    openai_embed.clj      # OpenAI /embeddings (also attached to mistral/together/voyage/jina)
    openai_moderation.clj # OpenAI /moderations
    openai_image.clj      # OpenAI /images/generations
    openrouter.clj        # OpenRouter (wraps OpenAI Chat)
    anthropic.clj         # Anthropic Messages (with OAuth auto-detect)
    gemini_native.clj     # Gemini Native API
    vertex_gemini.clj     # Vertex AI Gemini
    codex.clj             # OpenAI Responses API + Codex Backend
    perplexity.clj        # Perplexity chat with citation extraction (T2-04)
    cohere_embed.clj      # Cohere /embed (native shape, T2-07)
    cohere_rerank.clj     # Cohere & Jina shared rerank shape (T2-16)
    voyage_rerank.clj     # Voyage rerank (top_k / data shape, T2-16)
    bedrock.clj           # AWS Bedrock (scaffolded — see T2-09)
    fake.clj              # Test provider
resources/
  models-dev-snapshot.json    # Bundled offline snapshot (models.dev)
  litellm-snapshot.json       # T2-17 LiteLLM snapshot tier
scripts/
  build_litellm_snapshot.py   # Refresh resources/litellm-snapshot.json
test/llm/sdk/                 # Mirror of src structure for unit tests
  live_*.clj                  # Env-gated live integration tests
test-resources/fixtures/      # Golden JSON fixtures for adapter parsing
doc/
  litellm-parity-survey.md    # Full design rationale + 20-issue Task 2 backlog
```

## Acknowledgements

This SDK draws heavily from the [Hermes Agent](https://github.com/nousresearch/hermes-agent) reference implementation — error taxonomy, usage normalization dimensions, provider profile structure, stream event types, thinking block signature rules, finish reason mappings. The LiteLLM team's [community-curated model pricing catalog](https://github.com/BerriAI/litellm/blob/main/model_prices_and_context_window.json) powers the snapshot tier of the registry.

What was deliberately not copied from either: credential pools, CLI runtimes, plugin scanning, observability sinks (Langfuse / Helicone / Datadog), secret managers, proxy/router servers, budget managers, MCP clients, Assistants API, vector store integrations. Those are app concerns; this SDK ends at "the request/response carries the provider's wire shape, canonicalized."

See [doc/litellm-parity-survey.md](doc/litellm-parity-survey.md) for the full rationale and the explicit non-goals list.

## License

MIT
