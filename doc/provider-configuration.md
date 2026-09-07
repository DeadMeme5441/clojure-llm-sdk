# Provider Configuration

Provider profiles describe how the SDK authenticates, builds URLs, parses responses, and normalizes provider quirks. Most applications only need to choose a provider id, set the matching credential, and pass the provider's model id.

## Provider Ids

Inspect the registered providers at runtime:

```clojure
(sdk/list-providers)
(sdk/provider-profile :openai)
```

The profile exposes auth strategy, base URL, supported capabilities, model-listing support, and transport constructors. Treat it as read-only unless you are registering a custom provider.

## Credentials

The SDK reads provider credentials from environment variables. It does not load `.env` files directly.

Important chat credentials:

| Provider | ID | Credential |
|---|---|---|
| OpenAI | `:openai` | `OPENAI_API_KEY` |
| Anthropic API key | `:anthropic` | `ANTHROPIC_API_KEY` |
| Anthropic OAuth | `:anthropic` | `CLAUDE_OAT_TOKEN` |
| Gemini Native | `:gemini-native` | `GEMINI_API_KEY` |
| Vertex Gemini | `:vertex-gemini` | ADC or `GOOGLE_OAUTH_ACCESS_TOKEN` |
| Vertex Anthropic | `:vertex-anthropic` | ADC or `GOOGLE_OAUTH_ACCESS_TOKEN` |
| OpenRouter | `:openrouter` | `OPENROUTER_API_KEY` |
| DeepSeek | `:deepseek` | `DEEPSEEK_API_KEY` |
| Moonshot Kimi | `:kimi` | `MOONSHOT_API_KEY` |
| Kimi Code | `:kimi-code` | `KIMI_API_KEY` |
| Mistral | `:mistral` | `MISTRAL_API_KEY` |
| Groq | `:groq` | `GROQ_API_KEY` |
| Cerebras | `:cerebras` | `CEREBRAS_API_KEY` |
| Together | `:together` | `TOGETHER_API_KEY` |
| xAI | `:xai` | `XAI_API_KEY` |
| HuggingFace Router | `:huggingface` | `HF_TOKEN` |
| Perplexity | `:perplexity` | `PERPLEXITY_API_KEY` |

See [.env.example](../.env.example) for the full credential template across chat, embeddings, rerank, audio, image, and AWS providers.

## Per-Call Runtime Config

Applications can override provider configuration for a single call without changing the global provider registry. Every public modality accepts `:config`:

```clojure
(sdk/complete
  :openai
  {:request/model "gpt-4o-mini"
   :request/messages [{:message/role :user
                       :message/content "Hi"}]}
  :config {:api-key "sk-..."
           :base-url "https://api.openai.com/v1"
           :headers {"X-App" "my-service"}
           :connect-timeout-ms 5000
           :timeout-ms 60000})
```

Supported config keys:

| Key | Meaning |
|---|---|
| `:api-key` / `:auth-token` | Auth token for this call. |
| `:base-url` | Provider base URL override. |
| `:headers` | Extra headers merged into the provider defaults. |
| `:http-client` | Caller-managed hato Java HTTP client. |
| `:connect-timeout-ms` | HTTP connect timeout; WebSocket upgrade timeout. |
| `:timeout-ms` | HTTP request timeout; WebSocket server/consumer inactivity timeout (default 120000 ms). |
| `:transport` | ChatGPT OAuth only: `:sse` (default) or `:websocket`. |
| `:incremental?` | ChatGPT OAuth WebSockets: automatic continuation is enabled; `false` always sends full history. |

## ChatGPT OAuth WebSockets

`:codex-backend` reads the official Codex CLI OAuth credentials. HTTP/SSE is the
default: live `gpt-6-astra` / low-effort conversation benchmarks had lower
first-output latency over SSE, even after WebSocket connection/history reuse.
This is a measured default, not a guarantee for every model or network.

Select `:config {:transport :websocket}` to use
`wss://chatgpt.com/backend-api/codex/responses`. Both buffered `complete` and
`:stream? true` then use `response.create` with `stream: true`, matching the
official Codex client. The API-key `:codex` and `:openai` transports are unchanged.

```clojure
(sdk/complete
  :codex-backend
  {:request/model "gpt-6-astra"
   :request/messages [{:message/role :user :message/content "Hello"}]
   :request/reasoning {:enabled true :effort :low}
   :request/cache {:enabled? true :scope-id "conversation-42"}}
  :stream? true
  :on-event prn
  :config {:transport :websocket :connect-timeout-ms 10000 :timeout-ms 120000})
```

Fully consumed successful responses return their connection to a pool of up to
eight idle sockets, expiring after 60 seconds. Connections are isolated by URL,
headers (including OAuth credentials, account and cache scope), HTTP client and
timeout configuration. Concurrent calls lease separate connections rather than
waiting for another response. Keep `:scope-id` stable within a conversation for
prompt-cache affinity; changing handshake headers prevents connection reuse.

WebSocket follow-ups automatically send `previous_response_id` and only new input
when the full canonical history exactly extends the previous request plus its
completed output. Instructions, tools, model and other generation settings must
remain unchanged. The pool prefers the connection holding that conversation's
baseline. History edits, compaction, unsupported output shapes and reconnects
fall back to a full request; the SDK never guesses which messages to omit.

Preserve `:response/provider-data` as `:message/provider-data` on assistant
messages, alongside canonical content and tool calls. This retains encrypted
reasoning and message phase needed for exact continuation. Replayed assistant
message items are not duplicated, and edited canonical text wins over stale
provider text. `:config {:incremental? false}` disables history optimization.

Only the latest completed request/output is retained per connection, and only
after its terminal bytes have been consumed. Streamed `response.output_item.done`
items are authoritative: the Codex backend can leave the terminal `output` array
empty. Unchanged system instructions and tool definitions are still sent on every
turn, as required by the protocol. Smaller payloads do not guarantee lower
model-generation latency.

Partial responses are never automatically replayed, even with `:retry true`.
An automatically generated continuation rejected with `previous_response_not_found`
may resend the full original request once, only before any lifecycle or generation
event has arrived. Explicit caller-provided response IDs and partial generations
are never retried by this recovery path.
Handshake/provider errors and premature disconnects throw structured exceptions;
legitimate incomplete responses retain their canonical finish reason. Buffers and
message sizes are bounded. Abandoned lazy streams expire on inactivity; prefer
`:on-event` for deterministic cleanup, including when your callback throws.

To use HTTP/SSE explicitly, pass `:config {:transport :sse}`. There is no silent
fallback after a WebSocket failure. A caller-managed `:http-client` must be a
`java.net.http.HttpClient` (as returned by hato). To dispose active and idle sockets:

```clojure
(require '[llm.sdk.websocket :as websocket])
(websocket/close-connections!)
```

Wire protocol references: [Codex WebSocket endpoint](https://github.com/openai/codex/blob/main/codex-rs/codex-api/src/endpoint/responses_websocket.rs)
and [Codex client protocol selection](https://github.com/openai/codex/blob/main/codex-rs/core/src/client.rs).

## Kimi And Kimi Code

There are two separate providers:

- `:kimi` targets Moonshot's public OpenAI-compatible API at `https://api.moonshot.cn/v1` and reads `MOONSHOT_API_KEY`.
- `:kimi-code` targets Kimi Code's coding endpoint at `https://api.kimi.com/coding/v1` and reads `KIMI_API_KEY`.

Kimi Code uses the OpenAI Chat Completions wire shape with the `kimi-for-coding` model and sends KimiCLI-style non-secret identity headers in addition to Bearer auth. Do not use a Kimi Code key against `:kimi`; the endpoints and credential expectations are different.

```clojure
(sdk/complete
  :kimi-code
  {:request/model "kimi-for-coding"
   :request/messages [{:message/role :user
                       :message/content "Reply with ok"}]})
```

## Vertex Gemini

Vertex Gemini uses Application Default Credentials. Resolution order:

1. Request-level provider option for a bearer token.
2. `GOOGLE_OAUTH_ACCESS_TOKEN`.
3. `GOOGLE_APPLICATION_CREDENTIALS` service-account or authorized-user file.
4. The gcloud well-known ADC file.
5. GCP metadata server.

Set `GOOGLE_CLOUD_PROJECT` and optionally `GOOGLE_CLOUD_LOCATION`. The default location is `us-central1`.

```bash
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=my-project
```

```clojure
(sdk/complete
  :vertex-gemini
  {:request/model "gemini-2.5-pro"
   :request/messages [{:message/role :user
                       :message/content "Hi"}]})
```

## Vertex Anthropic (Claude)

`:vertex-anthropic` runs Anthropic's Claude models on Vertex AI. It authenticates with the same GCP Application Default Credentials chain as `:vertex-gemini` (request provider option, `GOOGLE_OAUTH_ACCESS_TOKEN`, `GOOGLE_APPLICATION_CREDENTIALS`, the gcloud ADC file, then the metadata server). There is no separate Anthropic key; do not set `ANTHROPIC_API_KEY` for this provider.

Set `GOOGLE_CLOUD_PROJECT`, and the location through `GOOGLE_CLOUD_LOCATION` or a per-call provider option. The default location is `us-central1`, but Claude on Vertex is region-gated: confirm the model is served in your chosen location. The region-less `global` endpoint is often the broadest, and several projects only have Claude enabled there.

```bash
gcloud auth application-default login
export GOOGLE_CLOUD_PROJECT=my-project
export GOOGLE_CLOUD_LOCATION=global
```

```clojure
(sdk/complete
  :vertex-anthropic
  {:request/model "claude-sonnet-4-6"
   :request/messages [{:message/role :user
                       :message/content "Hi"}]
   :request/max-tokens 256
   ;; project/location can also come from GOOGLE_CLOUD_* env vars
   :request/provider-options {:vertex {:project "my-project"
                                       :location "global"}}})
```

Model ids are the Vertex Claude ids, such as `claude-opus-4-6`, `claude-sonnet-4-6`, and `claude-haiku-4-5`; a pinned `@`-dated id like `claude-haiku-4-5@20251001` is preserved into the URL path. A request against a region that does not serve the requested model returns an HTTP 404 from the Vertex frontend rather than a model-not-found body, so a 404 usually means "wrong region," not "wrong model id."

The provider reuses the native Anthropic Messages shaping, so thinking blocks, tool use, file/document attachments, and native cache markers behave exactly as on `:anthropic`.

## Azure OpenAI Deployments

Azure routes by deployment name in the URL. Register a provider profile per deployment:

```clojure
(require '[llm.sdk.providers.openai.chat :as openai-chat])

(openai-chat/register-azure-deployment!
  {:id :azure-gpt4o-prod
   :endpoint "https://my-resource.openai.azure.com"
   :deployment "gpt-4o-prod"
   :api-version "2024-08-01-preview"
   :env-var-names ["AZURE_OPENAI_API_KEY"]})

(sdk/complete
  :azure-gpt4o-prod
  {:request/model "ignored-by-azure"
   :request/messages [{:message/role :user
                       :message/content "Hi"}]})
```

Default Azure auth uses the `api-key` header. Use `:auth-strategy :bearer` for AAD bearer tokens.

The legacy namespace `llm.sdk.providers.openai-chat` remains as a compatibility shim.

## Custom OpenAI-Compatible Providers

For a provider that accepts OpenAI Chat Completions shape, register an alias:

```clojure
(require '[llm.sdk.providers.openai.chat :as openai-chat])

(openai-chat/register-alias!
  {:id :my-private-llm
   :base-url "https://llm.example.com/v1"
   :env-var-names ["MY_LLM_KEY"]
   :capabilities #{:chat :streaming :tools}
   :quirks {:drops #{:frequency_penalty :presence_penalty}}})
```

The alias reuses the OpenAI-compatible request builder, response parser, streaming parser, and usage normalizer.

## Live Model Listing

Some providers expose a compatible `/models` endpoint. Refresh one provider:

```clojure
(sdk/refresh-models! :provider :openai)
```

Refresh every supported provider:

```clojure
(sdk/refresh-models!)
```

Providers without a stable model-list endpoint, such as `:kimi-code`, still participate in the offline registry through bundled snapshots when available.
