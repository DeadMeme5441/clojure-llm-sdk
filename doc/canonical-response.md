# Canonical response contract

`sdk/complete` always returns a map shaped like this. Adapters fill in
parts, usage, and provider-data; the SDK stamps `:response/cost` and
`:response/cache` centrally so every provider gets identical honesty
semantics.

```clojure
{:response/id "resp_..."
 :response/provider :vertex-gemini
 :response/model "gemini-2.5-pro"
 :response/parts [<Part> ...]
 :response/tool-calls [<ToolCallPart> ...]   ; optional, only when non-empty
 :response/finish-reason :stop                ; :stop|:length|:tool-calls|:content-filter|:incomplete|:unknown
 :response/usage     {<Usage>}                ; optional — present when provider reported usage
 :response/cost      {<Cost>}                 ; optional — stamped when usage present
 :response/cache     {<Cache>}                ; always present after sdk/complete
 :response/provider-data {...}                ; provider-specific replay state
 :response/raw       {...}}                   ; provider response body, verbatim
```

The Malli schemas live in `llm.sdk.schema` (`Response`, `Usage`, `Cost`,
`Cache`).

## Usage

```clojure
:response/usage
{:usage/input-tokens     123       ; uncached input tokens billed at input rate
 :usage/output-tokens     45
 :usage/total-tokens     168
 :usage/cached-input-tokens 42       ; OPTIONAL — present iff provider reported it
 :usage/cache-write-tokens   8       ; OPTIONAL — same rule
 :usage/reasoning-tokens   100       ; OPTIONAL — same rule
 :usage/citation-tokens     12       ; OPTIONAL — Perplexity
 :usage/search-queries       1       ; OPTIONAL — Perplexity
 :usage/request-count        1
 :usage/provider-raw {...}}          ; raw provider usage envelope
```

The presence/absence rule for cache, reasoning, citation, and search
fields is load-bearing — see "Honest unknown semantics" below.

## Cost

```clojure
:response/cost
{:cost/usd            0.0012M           ; OR :unknown
 :cost/estimated?     true              ; always true — this is an estimate
 :cost/pricing-source "models-dev"      ; name of pricing tier (registry / models-dev / litellm-snapshot / bundled-snapshot / user-override)
 :cost/source-url     "https://..."     ; URL pricing was sourced from, when known
 :cost/breakdown      {:input-tokens 1000
                       :output-tokens 500
                       :cached-input-tokens 200      ; only if usage carried it
                       :cache-write-tokens 50        ; only if usage carried it
                       :request-count 1
                       :input-cost-per-million 2.5
                       :output-cost-per-million 10.0
                       :cache-read-cost-per-million 1.25
                       :cache-write-cost-per-million 3.75
                       :request-cost 0.005
                       :image-cost-per-image 0.04
                       :image-cost-per-megapixel 0.02
                       :transcription-cost-per-minute 0.006
                       :tts-cost-per-million-chars 15.0
                       :search-cost-per-call 0.005}
 :cost/reason         "no pricing data for model"}  ; only when :cost/usd is :unknown
```

Honesty rule:

- `:cost/usd` is the keyword `:unknown` (not `0`, not `0M`) when the
  registry has no pricing for `(provider, model)`, when usage is
  missing, or when the pricing entry is incomplete for a reported
  billable dimension. For example, if a provider reports cached-input
  tokens but the registry has no cache-read rate, the SDK will not
  silently charge those cached tokens as normal input or as zero.
- `:cost/estimated?` is always `true`. The SDK does not bill users; it
  computes from token counts and listed per-million rates.
- `:cost/pricing-source` names which tier of the registry produced the
  numbers. `nil` means no pricing at all was found.

Usage normalizers keep cache math non-overlapping: `:usage/input-tokens`
is the uncached input count, while `:usage/cached-input-tokens` and
`:usage/cache-write-tokens` are separate billable lines when the provider
reports them.

## Cache

```clojure
:response/cache
{:cache/status :hit                 ; :hit | :miss | :unknown
 :cache/cached-tokens 200           ; integer when known, otherwise :unknown
 :cache/cache-write-tokens 50}      ; integer when known, otherwise :unknown
```

Honesty rule:

- `:cache/status :hit` — provider reported a positive cached-input-token count.
- `:cache/status :miss` — provider explicitly reported `0` cached tokens.
- `:cache/status :unknown` — provider did not report cache stats at all.
- `:cache/cached-tokens` is the keyword `:unknown` (not `0`) when the
  status is `:unknown`. This is the distinction between "provider said
  0" and "provider was silent."

The usage normalizers (`llm.sdk.usage`) include `:usage/cached-input-tokens`
only when the raw provider payload contained a cache field. That
absence is how `:unknown` propagates upward.

## Honest unknown semantics

Two rules, applied without exception:

1. **Never substitute `0` for an unknown cache count.** If the provider
   did not surface cache stats, the SDK surfaces `:unknown`. Callers
   trying to decide whether caching is working can distinguish "the
   request was uncached" (`:miss`) from "we don't know" (`:unknown`).

2. **Never substitute `$0` for an unknown cost.** If pricing is missing
   for the model, `:cost/usd` is `:unknown`. Callers aggregating spend
   across providers see explicit unknown markers instead of silently
   undercounting.

These rules are enforced in:
- `llm.sdk.usage/normalize-*-usage` — omit cache/reasoning/citation
  fields when raw didn't carry them.
- `llm.sdk.pricing/canonical-cost` — return `{:cost/usd :unknown ...}`
  when pricing is missing.
- `llm.sdk.pricing/canonical-cache` — return `{:cache/status :unknown
  :cache/cached-tokens :unknown}` when usage is silent.
- `llm.sdk.pricing/stamp-response-cost-and-cache` — central stamping
  invoked by `sdk/complete`.

## After-the-fact attribution

The same canonical shapes are available outside the request path:

```clojure
(sdk/canonical-cost :openai "gpt-4o"
                    {:usage/input-tokens 1000
                     :usage/output-tokens 500})
;; => {:cost/usd 0.0075M :cost/estimated? true :cost/pricing-source "models-dev" ...}

(sdk/canonical-cache {:usage/cached-input-tokens 0})
;; => {:cache/status :miss :cache/cached-tokens 0 :cache/cache-write-tokens :unknown}

(sdk/canonical-cache {})
;; => {:cache/status :unknown :cache/cached-tokens :unknown}
```

## Streaming

When `sdk/complete` is called with `:stream? true :on-event cb`, the
final aggregate response returned (after the event seq is consumed) is
stamped with the same `:response/cost` and `:response/cache` shapes —
the reducer in `llm.sdk.stream` produces the parts/usage/finish-reason,
and the stamping happens after.

Tool-call start and argument-delta events are reduced into both
`:response/tool-calls` and an ordered `:part/type :tool-call` entry in
`:response/parts`. Provider-state events are merged into
`:response/provider-data` so replay data such as Anthropic signatures,
Gemini thought signatures, and encrypted reasoning handles can be kept
for the next turn.

The raw event stream itself does not carry cost or cache events. Cost
and cache are post-hoc computations that need the full usage envelope,
which arrives near the end of the stream.
