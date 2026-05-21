# clojure-llm-sdk

A production-quality Clojure SDK for LLM providers — LiteLLM-style canonicalization, but stricter, less lossy, and with first-class support for provider-specific replay state (thinking signatures, encrypted reasoning, assistant phases).

> **This is a provider SDK, not an agent framework.** It owns provider weirdness so your application doesn't have to. It does not import RLM concepts, credential pools, or plugin scanning — those are app concerns.

## Installation

Add to `deps.edn`:

```clojure
com.deadmeme5441/clojure-llm-sdk {:git/url "https://github.com/DeadMeme5441/clojure-llm-sdk"
                                   :git/sha "LATEST_SHA"}
```

## Quick Start

```clojure
(require '[llm.sdk :as sdk])

;; Simple completion
(let [req {:request/model "gpt-4o-mini"
           :request/messages [{:message/role :user
n                               :message/content "Hello, world!"}]}
      resp (sdk/complete :openai req)]
  (println (:response/parts resp)))
;; => [{:part/type :text, :text "Hello! How can I help you today?"}]

;; With tools
(let [req {:request/model "gpt-4o"
           :request/messages [{:message/role :user
n                               :message/content "What's the weather in NYC?"}]
           :request/tools [{:type :function
                            :function {:name "get_weather"
                                       :description "Get current weather"
                                       :parameters {:type :object
                                                    :properties {:location {:type :string}}}}}]}
      resp (sdk/complete :openai req)]
  (when (= :tool-calls (:response/finish-reason resp))
    (println (:response/tool-calls resp))))
;; => [{:tool-call/id "call_abc", :tool-call/name "get_weather",
;;      :tool-call/arguments "{\"location\":\"NYC\"}"}]

;; Streaming
(sdk/complete :openai req
              :stream? true
              :on-event (fn [ev] (println (:event/type ev))))
;; => :stream/start
;; => :stream/content-delta
;; => :stream/content-delta
;; => :stream/end

;; Cost estimation
(let [usage {:usage/input-tokens 1000
             :usage/output-tokens 500}
      cost (sdk/estimate-cost :openai "gpt-4o" usage)]
  (println (:cost/amount-usd cost)))
;; => 0.0075M
```

## Supported Providers

| Provider | ID | Protocol | Auth | Status |
|----------|-----|----------|------|--------|
| **OpenAI** | `:openai` | `openai-chat` | `OPENAI_API_KEY` | ✅ Full |
| **OpenRouter** | `:openrouter` | `openrouter` | `OPENROUTER_API_KEY` | ✅ Full |
| **DeepSeek** | `:deepseek` | `openai-chat` | `DEEPSEEK_API_KEY` | ✅ Full |
| **Kimi / Moonshot** | `:kimi` | `openai-chat` | `KIMI_API_KEY` | ✅ Full |
| **Anthropic Messages** | `:anthropic` | `anthropic-messages` | `ANTHROPIC_API_KEY` | ✅ Full |
| **Anthropic OAuth** | `:anthropic` | `anthropic-messages` | `CLAUDE_OAT_TOKEN`¹ | ✅ Full |
| **Gemini Native** | `:gemini-native` | `gemini-native` | `GEMINI_API_KEY` | ✅ Full |
| **Vertex Gemini** | `:vertex-gemini` | `gemini-native` | `GOOGLE_APPLICATION_CREDENTIALS` | ✅ Full |
| **OpenAI Codex (Standard)** | `:codex` | `codex` | `OPENAI_API_KEY` | ✅ Full |
| **OpenAI Codex (Backend)** | `:codex-backend` | `codex` | `~/.codex/auth.json`² | ✅ Full |
| **AWS Bedrock** | `:bedrock` | `bedrock` | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` | 🏗 Scaffolded |
| **Fake/Test** | `:fake` | `fake` | None | ✅ Full |

¹ Auto-detected by token prefix (`sk-ant-*`, `eyJ*`, `cc-*`). Switches to Bearer auth + Claude Code identity.  
² Reads OAuth tokens from the official [OpenAI Codex CLI](https://github.com/openai/codex) `~/.codex/auth.json`.

## Environment Variables

| Variable | Provider | Notes |
|----------|----------|-------|
| `OPENAI_API_KEY` | OpenAI, Codex (standard) | |
| `ANTHROPIC_API_KEY` | Anthropic (API key) | `sk-ant-api03-*` |
| `CLAUDE_OAT_TOKEN` | Anthropic (OAuth) | Setup token `sk-ant-oat-*` or JWT `eyJ*` |
| `GEMINI_API_KEY` | Gemini Native | |
| `OPENROUTER_API_KEY` | OpenRouter | |
| `DEEPSEEK_API_KEY` | DeepSeek | |
| `KIMI_API_KEY` | Kimi / Moonshot | |
| `GOOGLE_APPLICATION_CREDENTIALS` | Vertex Gemini | GCP service account JSON path |
| `VERTEX_PROJECT` + `VERTEX_LOCATION` | Vertex Gemini | Project routing |

Create a `.env` file in your project root (gitignored by default):

```bash
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-api03-...
CLAUDE_OAT_TOKEN=sk-ant-oat-...
OPENROUTER_API_KEY=sk-or-...
```

## Architecture

### Canonical Schema

All providers translate to/from a canonical schema:

```clojure
;; Request
{:request/model "gpt-4o"
 :request/messages [{:message/role :user
n                     :message/content "Hello!"}]
 :request/tools [...]
 :request/temperature 0.7}

;; Response
{:response/id "resp_123"
 :response/provider :openai
 :response/model "gpt-4o"
 :response/parts [{:part/type :text :text "Hi!"}
                  {:part/type :reasoning :reasoning/text "Let me think..."}
                  {:part/type :tool-call
                   :tool-call/id "call_1"
                   :tool-call/name "get_weather"
                   :tool-call/arguments "{\"location\":\"NYC\"}"}]
 :response/tool-calls [...]          ; extracted for convenience
 :response/finish-reason :stop       ; :stop :length :tool-calls :content-filter :incomplete
 :response/usage {:usage/input-tokens 10
                  :usage/output-tokens 5
                  :usage/total-tokens 15}
 :response/provider-data {...}}      ; provider-specific replay state
```

### Provider-Specific Replay State

A key design principle: **provider-specific replay state must survive round-trips**:

- **Anthropic**: `reasoning_details` (thinking blocks with signatures)
- **Codex**: `codex_reasoning_items` (encrypted reasoning), `codex_message_items` (assistant phases)
- **Gemini**: `thought_signature` (for tool-call replay)

This state is stored in `:response/provider-data` and can be threaded back into subsequent requests via `:message/provider-data`.

### Transport Protocol

Each provider implements the `Transport` protocol:

```clojure
(defprotocol Transport
  (build-request [this profile request])
  (parse-response [this profile raw])
  (parse-stream-event [this profile line])
  (parse-error [this profile status body])
  (normalize-usage [this profile raw])
  (request-capabilities [this]))
```

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
| `:stream/usage` | Usage data |
| `:stream/error` | Provider error |
| `:stream/end` | Stream finished |

Events reduce into an accumulator that builds the final canonical response.

## Provider Details

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

## Testing

```bash
# Unit tests (fast, no network)
clj -M:test

# Live integration tests (requires API keys, costs money)
# Set env vars first, then:
clj -M:test -n llm.sdk.live-adapters-test

# Or run all tests including live ones:
source .env && clj -M:test
```

Live tests are gated by credential availability and skipped cleanly when missing. They make minimal API calls (single-token "pong" prompts) to keep costs negligible.

**Current test status:** 78 tests, 222 assertions, all passing.

## Project Structure

```
src/llm/sdk/
  schema.clj          # Canonical request/response/part/stream-event schemas (Malli)
  provider.clj        # Provider profile registry and auth resolution
  transport.clj       # Transport protocol definition and helpers
  http.clj            # Thin HTTP layer (hato)
  stream.clj          # Streaming event taxonomy and reducer
  usage.clj           # Usage normalization across providers
  pricing.clj         # Cost estimation with hardcoded + live pricing
  errors.clj          # Error classification and retry logic
  retry.clj           # Jittered backoff retry policy
  rate_limit.clj      # Rate limit tracking
  catalog.clj         # Model catalog and capabilities
  core.clj            # Public API (complete, stream, etc.)
  providers/
    openai_chat.clj   # OpenAI Chat Completions
    openrouter.clj    # OpenRouter (wraps OpenAI Chat)
    anthropic.clj     # Anthropic Messages (with OAuth auto-detect)
    gemini_native.clj # Gemini Native API
    vertex_gemini.clj # Vertex AI Gemini
    codex.clj         # OpenAI Responses API + Codex Backend
    bedrock.clj       # AWS Bedrock
    fake.clj          # Test provider
test/llm/sdk/
  providers/          # Per-adapter unit tests
  live_test.clj       # Legacy live tests
  live_adapters_test.clj # Live tests for all adapters
```

## Design Decisions

See [doc/design.md](doc/design.md) for detailed architecture decisions, including:
- Why a part/event tree instead of `{:content "..." :tool_calls [...]}`
- Why provider-specific replay state is correctness-critical
- Why auth/runtime/CLI are app concerns, not SDK concerns
- How the transport registry works
- What was copied from Hermes and what was not

## Acknowledgements

This SDK draws heavily from the [Hermes Agent](https://github.com/nousresearch/hermes-agent) reference implementation — specifically the error taxonomy, usage normalization dimensions, provider profile structure, stream event types, thinking block signature rules, and finish reason mappings. What was NOT copied: credential pools, CLI runtime, plugin scanning, Langfuse sinks, macOS Keychain integration, OAuth PKCE flow — these are app concerns.

## License

MIT
