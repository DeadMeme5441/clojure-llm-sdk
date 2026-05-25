# Model Registry And Pricing

The model registry powers `sdk/list-models`, `sdk/model-info`, `sdk/model-capabilities`, `sdk/model-context-length`, and cost estimation. It is designed to answer useful questions offline while still allowing live provider catalogs and caller overrides.

## Lookup Precedence

Higher tiers win:

| Tier | Source | Purpose |
|---|---|---|
| Override | `sdk/register-model-info` | Caller data for private deployments or newly released models. |
| Live | Provider `/models` endpoint | What the current account can see right now. |
| LiteLLM snapshot | `resources/litellm-snapshot.json` | Broad pricing/context coverage for providers the SDK supports. |
| models.dev | `resources/models-dev-snapshot.json` plus optional cache | Large public model catalog fallback. |

The returned model entry carries `:model/source` so callers can see which tier answered.

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

Live refresh requires provider credentials and only runs for providers with supported model-list endpoints. Failures are returned per provider instead of aborting the whole refresh.

## Overrides

Use overrides for private models, self-hosted endpoints, or pricing data that has not reached public catalogs yet:

```clojure
(sdk/register-model-info
  :acme "magic-7"
  {:model/context-length 32000
   :model/max-output-tokens 4096
   :model/capabilities #{:chat :tools}
   :model/cost {:input-per-million 0.5
                :output-per-million 2.0}})
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

If pricing is unavailable or incomplete, `:cost/usd` is `:unknown`. The SDK never substitutes zero for unknown cost.

## Cache Attribution

Provider usage normalizers only emit cache counters when providers actually report them. `sdk/complete` uses those counters to stamp:

```clojure
{:cache/status :hit
 :cache/cached-tokens 200
 :cache/cache-write-tokens :unknown}
```

If a provider is silent about cache statistics, `:cache/status` is `:unknown`. This lets applications distinguish "the provider said zero cached tokens" from "the provider did not report cache information."

## Snapshot Refresh

The LiteLLM snapshot can be rebuilt from a local LiteLLM checkout:

```bash
python3 scripts/build_litellm_snapshot.py /path/to/litellm
```

The script filters LiteLLM data down to providers and fields used by this SDK.
