# Contributing

`clojure-llm-sdk` is a provider SDK. Keep changes inside that boundary: request/response canonicalization, provider wire shapes, model metadata, usage/cost normalization, and narrow per-modality transports. Do not add agent frameworks, proxy servers, credential pools, secret managers, observability sinks, plugin scanners, vector stores, MCP clients, or budget routers.

## Local Setup

Prerequisites:

- JDK 17 or newer
- Clojure CLI
- `clj-kondo`

Run the default verification set before opening a PR:

```bash
clj-kondo --lint src test
clojure -M:test
clojure -T:build jar
```

The default test alias is offline-only. It excludes `^:live` and `^:integration`, so exported API keys must not make `clojure -M:test` perform network or paid calls.

## Live Tests

Live tests are opt-in:

```bash
cp .env.example .env
# fill only the providers you intend to test
set -a; source .env; set +a
clojure -M:live-test
```

Every `deftest` in `test/**/live_*.clj` must carry `^:live` or `^:integration`. Provider keys are independent. For example, `:kimi` uses `MOONSHOT_API_KEY`, while `:kimi-code` uses `KIMI_API_KEY`.

Live tests should be cheap, deterministic smoke tests. Do not add expensive image generation or long-context tests to the default live suite; document those as manual checks instead.

## Provider Changes

Provider additions should include:

- A provider profile in `llm.sdk.provider` or a registration helper under the relevant provider namespace.
- A transport implementation or reuse of an existing OpenAI-compatible transport.
- Unit tests for URL, auth, headers, body shape, response parsing, streaming where relevant, error parsing, and usage normalization.
- Golden fixtures for non-trivial provider payloads.
- Model registry wiring when the provider appears in `models.dev`, LiteLLM snapshot data, or a live `/models` endpoint.
- README provider table and environment-variable updates.
- A live smoke test when credentials are available and the call is safe.

Provider-specific replay state must survive round trips through `:response/provider-data` and `:message/provider-data`. Do not flatten reasoning signatures, encrypted reasoning items, citations, tool-call ids, or native phase markers into plain text.

## Documentation

Update public docs in the same change when behavior changes:

- README for user-facing setup, providers, env vars, and examples.
- `doc/canonical-response.md` for canonical response/usage/cost/cache semantics.
- `doc/litellm-parity-survey.md` when provider parity or non-goals change.
- `CHANGELOG.md` for user-visible changes.

## Secrets

Never commit `.env`, credential JSON, OAuth tokens, provider responses containing secrets, or generated logs with authorization headers. Use `.env.example` for documentation and redact issue/PR output.
