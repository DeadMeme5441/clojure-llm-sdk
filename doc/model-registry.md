# Model Registry And Pricing

The model registry powers `sdk/list-models`, `sdk/model-info`, `sdk/model-capabilities`, `sdk/model-context-length`, and cost estimation. It is designed to answer useful questions offline while still allowing live provider catalogs and caller overrides.

## Lookup Precedence

Higher tiers win:

| Tier | Source | Purpose |
|---|---|---|
| Override | `sdk/register-model-info` | Caller data for private deployments or newly released models. |
| Live | Supported provider model-list endpoint | Models visible to the configured account when a live refresh is run. |
| LiteLLM snapshot | `resources/litellm-snapshot.json` | Filtered pricing, context, and capability metadata for addressable provider ids; not proof of live availability. |
| models.dev | `resources/models-dev-snapshot.json`, network API, and optional cache | Public catalog fallback for metadata and pricing. |

The returned model entry carries `:model/source` so callers can see which tier answered.

Registry presence means that a source knows metadata for a provider/model pair.
It does not guarantee that the provider still serves the model, that a region
offers it, or that the current account can use it.

## Common Queries

```clojure
(sdk/list-models)
(sdk/list-models :openai)
(sdk/model-info :openai "gpt-4o")
(sdk/model-capabilities :openai "gpt-4o")
(sdk/model-context-length :openai "gpt-4o")
```

Single-argument lookup scans providers in a stable preference order. Prefer two-argument lookup when the model id is ambiguous across providers.

## Live Refresh

```clojure
(sdk/refresh-models! :provider :openai)
(sdk/refresh-models!)
```

Live refresh requires provider credentials and only runs for providers with a
supported model-list endpoint. It reports only what that endpoint exposes to
the configured account at that moment; failures are returned per provider
instead of aborting the whole refresh. Offline snapshot entries are not
silently promoted to live availability.

## Overrides

Use overrides for private models, self-hosted endpoints, or pricing data that has not reached public catalogs yet:

```clojure
(sdk/register-model-info
  :acme "magic-7"
  {:model/context-length 32000
   :model/max-output-tokens 4096
   :model/capabilities #{:chat :tools}
   :model/cost {:input-per-million 0.5
                :output-per-million 2.0
                :cache-read-per-million 0.1
                :cache-write-per-million 0.6
                :request-cost 0.005}})
```

Overrides are in-memory. Applications that need durable custom catalogs should register them during process startup.

## Cost Estimation

```clojure
(sdk/estimate-cost
  :openai "gpt-4o"
  {:usage/input-tokens 1000
   :usage/output-tokens 500
   :usage/cached-input-tokens 200})
```

Cost results are explicit about uncertainty:

```clojure
{:cost/usd 0.00625M
 :cost/estimated? true
 :cost/pricing-source "litellm-snapshot"
 :cost/breakdown {...}}
```

If pricing is unavailable or incomplete for any reported billable dimension,
`:cost/usd` is `:unknown`. The SDK never substitutes zero or a text-token rate
for an unknown image, audio, cache, duration, request, or rerank rate. It also
never double counts cached tokens: normalized input tokens are uncached input,
while cache reads and cache writes are priced from their own fields when the
registry has those rates. Provider- or parser-reported response cost remains
authoritative over an estimate.

The normalized `:model/cost` map uses kebab-case keys. Bundled JSON stores the
same keys in snake_case. Entries include only rates supplied by their source;
missing rates are omitted:

```clojure
{:input-per-million 2.5
 :output-per-million 10.0
 :image-input-per-million 3.0
 :image-output-per-million 12.0
 :audio-input-per-million 4.0
 :audio-output-per-million 16.0
 :cache-read-per-million 1.25
 :cache-write-per-million 3.75
 :image-cache-read-per-million 0.3
 :audio-cache-read-per-million 0.4
 :request-cost 0.005
 :image-per-image 0.04
 :image-per-megapixel 0.02
 :transcription-per-minute 0.006
 :tts-per-million-chars 15.0
 :search-per-call 0.005
 :rerank-per-search-unit 0.002}
```

The legacy/runtime pricing-entry representation exposes the same new dimensions
as `:image-input-cost-per-million`, `:image-output-cost-per-million`,
`:audio-input-cost-per-million`, `:audio-output-cost-per-million`,
`:image-cache-read-cost-per-million`, `:audio-cache-read-cost-per-million`, and
`:rerank-cost-per-search-unit`.

The general input/output rates apply to text or undifferentiated token totals.
Dedicated image and audio token counters require their matching rates. Canonical
usage may omit input or output totals when a provider does not report them;
absence is not normalized to zero. Cohere rerank billing uses canonical
`:usage/search-units`; it is not a count of search queries. Transcription uses
`:usage/duration-seconds`, with `:transcription/duration-seconds` as the
modality-specific field.

`sdk/estimate-cost` covers registry-backed chat-style usage. The modality
drivers and helpers in `llm.sdk.pricing` attribute embedding, rerank, image,
transcription, and text-to-speech costs when the required usage and rates are
available.

## Cache Attribution

Provider usage normalizers only emit cache counters when providers actually report them. `sdk/complete` uses those counters to stamp:

```clojure
{:cache/status :hit
 :cache/cached-tokens 200
 :cache/cache-write-tokens :unknown}
```

If a provider is silent about cache statistics, `:cache/status` is `:unknown`. This lets applications distinguish "the provider said zero cached tokens" from "the provider did not report cache information."

## Snapshot Refresh

The bundled files carry `_meta.source_url` and `_meta.source_revision`.
The 2026-09-13 refresh is pinned to immutable public revisions:

- [LiteLLM `b1a61f510c90ce7e4533e89247c941fa201ada4f`](https://github.com/BerriAI/litellm/blob/b1a61f510c90ce7e4533e89247c941fa201ada4f/model_prices_and_context_window.json):
  1,497 retained models across 18 SDK provider ids.
- [models.dev `a2a673950c6e09af0dbcb4744116401fc1ee0048`](https://github.com/anomalyco/models.dev/tree/a2a673950c6e09af0dbcb4744116401fc1ee0048):
  872 retained models across 17 mapped provider ids. The `google-vertex`
  subset is intentionally limited to its 21 Gemini-native ids.

The counts describe bundled metadata, not models verified live. The generator
filters both sources to provider ids the SDK can address, retains only rates
actually supplied by each source, and builds models.dev data from the pinned
repository TOML rather than the mutable API response:

```bash
python3 scripts/build_litellm_snapshot.py
```

For a controlled alternate source, the first argument, `LITELLM_SOURCE`, or
`LITELLM_REPO` may name a LiteLLM pricing JSON URL, file, or checkout.
`MODELS_DEV_SOURCE` may name a models.dev repository archive or checkout.
