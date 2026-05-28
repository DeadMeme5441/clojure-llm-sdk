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
| `:connect-timeout-ms` | HTTP connect timeout. |
| `:timeout-ms` | HTTP request timeout. |

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
