# Canonical response contract

An ordinary non-streaming `sdk/complete` call returns a map shaped like this;
the callback-based streaming form returns the same shape after accumulation.
Adapters fill in parts, optional usage, provider-data, and sometimes an
authoritative provider-reported cost. The SDK preserves an existing
`:response/cost`; only when it is absent does central stamping estimate cost
from canonical usage and known pricing. Cache stamping retains the same honest
unknown semantics across providers.

```clojure
{:response/id "resp_..."
 :response/provider :vertex-gemini
 :response/model "gemini-2.5-pro"
 :response/parts [<Part> ...]
 :response/tool-calls [<ToolCallPart> ...]   ; optional, only when non-empty
 :response/finish-reason :stop                ; :stop|:length|:tool-calls|:content-filter|:incomplete|:unknown
 :response/usage     {<Usage>}                ; optional — every counter is optional
 :response/cost      {<Cost>}                 ; optional — reported or estimated when attributable
 :response/cache     {<Cache>}                ; stamped by sdk/complete
 :response/provider-data {...}                ; provider-specific replay state
 :response/raw       {...}}                   ; provider response body, verbatim
```

The Malli schemas live in `llm.sdk.schema` (`Response`, `Usage`, `Cost`,
`Cache`).

## Replay-significant parts

Canonical parts retain opaque data that a provider requires on a later turn;
canonicalization must not reduce that data to display text alone.

- A reasoning part may carry `:reasoning/signature` alongside
  `:reasoning/text` and optional `:reasoning/encrypted`. During streaming,
  `:event/index` identifies the reasoning block being updated and
  `:reasoning/signature` is retained on that block in the final
  `:response/parts`. The index is stream-local; the accumulated part keeps
  the ordinary canonical reasoning shape.
- A tool-call part may carry `:tool-call/provider-data`, including opaque
  metadata needed to distinguish or replay a custom or provider-native tool
  call. Metadata supplied by tool-call start events is merged across updates
  and retained in both `:response/tool-calls` and the corresponding ordered
  tool-call part.
- A citation does not require a URL when the provider identifies the source
  another way. It may carry `:citation/source-id` or
  `:citation/provider-data`, plus descriptive metadata such as
  `:citation/title`, `:citation/snippet`, `:citation/text-range`,
  `:citation/date`, `:citation/last-updated`, and `:citation/source`. A
  citation must have at least one of `:citation/url`, `:citation/source-id`,
  or `:citation/provider-data`.

Top-level provider replay state remains in `:response/provider-data`. Callers
that construct a later assistant message must preserve replay-significant
canonical parts in `:message/content` or `:message/tool-calls` and carry
relevant response-wide state in `:message/provider-data`; otherwise a provider
may be unable to validate or replay the prior response.

## Usage

Canonical usage is sparse. Token-metered responses may look like:

```clojure
:response/usage
{:usage/input-tokens         123       ; OPTIONAL — uncached text/general input
 :usage/output-tokens         45       ; OPTIONAL
 :usage/total-tokens         168       ; OPTIONAL — authoritative when reported
 :usage/cached-input-tokens   42       ; OPTIONAL — present iff reported
 :usage/cache-write-tokens     8       ; OPTIONAL — same rule
 :usage/reasoning-tokens     100       ; OPTIONAL — may overlap output
 :usage/citation-tokens       12       ; OPTIONAL
 :usage/search-queries         1       ; OPTIONAL — informational, e.g. Perplexity
 :usage/image-tokens          20       ; OPTIONAL
 :usage/audio-tokens          10       ; OPTIONAL
 :usage/request-count          1       ; OPTIONAL
 :usage/provider-raw {...}}             ; OPTIONAL raw provider usage envelope
```

A provider may instead report only its billable unit. Cohere rerank uses the
ordinary `Usage` schema, while transcription responses accept the dedicated
duration form through `TranscriptionUsage`:

```clojure
{:usage/search-units 1}          ; Cohere rerank search
{:usage/duration-seconds 12.5}   ; transcription audio duration
```

`:usage/search-queries` is distinct informational activity metadata. It is not
a substitute for Cohere's billable `:usage/search-units`.

Canonical response usage does not require input or output tokens. Unit-only
usage is valid, and the SDK never inserts token counters merely to satisfy a
common shape. The presence/absence rule applies to every field: absent means
the provider did not report it, whereas an explicit zero means the provider
reported zero.

`StreamUsage` is also sparse because providers can report cumulative counters
in separate events. Each new value is the latest cumulative value for that
key, not an increment to add to the previous event; missing keys retain their
earlier values.

A provider-reported `:usage/total-tokens` is authoritative. Canonical
`:usage/input-tokens` excludes tokens already represented by
`:usage/cached-input-tokens` or `:usage/cache-write-tokens`, so those cache
counters are not added again. Reasoning tokens may overlap output tokens and
are likewise not blindly added to a derived total.

## Cost

```clojure
:response/cost
{:cost/usd            0.0012M           ; OR :unknown
 :cost/estimated?     true              ; false for provider-reported cost
 :cost/pricing-source "models-dev"      ; registry tier or provider report
 :cost/source-url     "https://..."     ; source URL when known
 :cost/breakdown      {:input-tokens 1000       ; canonical totals when present
                       :output-tokens 500
                       :text-input-tokens 800    ; routed counts when distinguishable
                       :image-input-tokens 200
                       :audio-input-tokens 0
                       :text-output-tokens 100
                       :image-output-tokens 400
                       :audio-output-tokens 0
                       :cached-input-tokens 50
                       :text-cached-input-tokens 0
                       :image-cached-input-tokens 50
                       :audio-cached-input-tokens 0
                       :search-units 1
                       :duration-seconds 12.5
                       :input-cost-per-million 2.5
                       :output-cost-per-million 10.0
                       :cache-read-cost-per-million 1.25
                       :image-input-cost-per-million 10.0
                       :image-output-cost-per-million 40.0
                       :audio-input-cost-per-million 32.0
                       :audio-output-cost-per-million 64.0
                       :image-cache-read-cost-per-million 2.5
                       :audio-cache-read-cost-per-million 8.0
                       :rerank-cost-per-search-unit 0.002
                       :transcription-cost-per-minute 0.006}}
```

The breakdown is sparse: it contains the usage dimensions and rates used for
that result, not every possible field shown above. Existing specialized rates,
such as per-image, per-megapixel, per-minute, per-character, request, and cache
write rates, remain available where the catalog supplies them.

Precedence and honesty rules:

- An adapter-supplied `:response/cost` is authoritative. This includes costs
  reported by providers such as OpenRouter or Perplexity and streamed costs
  carried on a usage event. Central stamping never replaces it with a registry
  estimate.
- When no authoritative cost exists, estimation requires reported usage and a
  matching rate for every positive billable dimension. Missing usage leaves
  cost absent; missing pricing or a required rate yields
  `:cost/usd :unknown` with `:cost/reason`, never `0` or `0M`.
- Text/general rates are not substitutes for image or audio token rates.
  Likewise, search units and duration require their own applicable rates;
  search-query or request counts are not substituted for search units.
- `:cost/estimated?` is `true` for SDK calculations and `false` for a provider-
  reported amount.
- `:cost/pricing-source` identifies the registry tier or provider report that
  supplied the numbers. `nil` means no pricing source was found.

Usage normalizers keep cache math non-overlapping: `:usage/input-tokens` is the
uncached input count, while `:usage/cached-input-tokens` and
`:usage/cache-write-tokens` are separate billable lines when reported.

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

Three rules are applied without exception:

1. **Never substitute `0` for an unknown usage or cache count.** If the
   provider did not surface a counter, the SDK leaves it absent. For cache
   status this becomes `:unknown`, preserving the distinction from an explicit
   zero and therefore `:miss`.

2. **Never invent token usage for a unit-metered operation.** Search units and
   audio duration stand on their own; absent input/output tokens stay absent.
   Informational search-query or request counts do not become search units.

3. **Never substitute `$0` for an unknown cost.** If usage, pricing, or a rate
   for a reported billable dimension is missing, cost stays absent or carries
   `:cost/usd :unknown`. Callers aggregating spend do not silently undercount.

These rules are enforced in:
- `llm.sdk.usage` normalizers, which omit fields the provider did not carry.
- `llm.sdk.pricing/canonical-cost`, which returns
  `{:cost/usd :unknown ...}` when attributable pricing is incomplete.
- `llm.sdk.pricing/canonical-cache`, which returns
  `{:cache/status :unknown :cache/cached-tokens :unknown}` when usage is silent.
- modality drivers and
  `llm.sdk.pricing/stamp-response-cost-and-cache`, which preserve an existing
  authoritative `:response/cost` before considering estimation.

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

With `:stream? true` and no callback, `sdk/complete` returns a lazy sequence
of canonical stream events. Realizing that sequence performs the read. A
`:stream/error` is therefore an event value in this mode; merely consuming
the lazy stream does not turn that event into an exception.

With `:stream? true :on-event cb`, the SDK consumes the stream, calls `cb` for
each event, and then accumulates the events into the canonical response
returned by `sdk/complete`. The same accumulation is available through
`llm.sdk.stream/events->response`. If an accumulated stream contains a
`:stream/error`, accumulation throws `ExceptionInfo` rather than returning a
successful response. Its `ex-data` contains the classified `:error`, the
original `:stream/error`, the `:provider`, and a `:partial-response` containing
the canonical state accumulated from the event sequence.
This differs intentionally from the lazy event interface, where callers
observe and decide how to handle the error event themselves.

Indexed `:stream/reasoning-delta` events update independent reasoning blocks;
an optional `:reasoning/signature` survives on the resulting reasoning part.
Tool-call start and argument-delta events are reduced into both
`:response/tool-calls` and an ordered `:part/type :tool-call` entry in
`:response/parts`; optional `:tool-call/provider-data` survives that reduction.
Provider-state events are merged into `:response/provider-data`. Non-URL
citation events retain their source identifiers and provider metadata in
canonical citation parts.

Stream usage events follow the sparse cumulative rules described above. The
SDK emits exactly one terminal `:stream/end`, after any trailing usage or
provider metadata, so the final aggregate includes those trailers. There are
no separate cost or cache event types. Provider-reported cost may
accompany a usage event and remains authoritative on the aggregate response.
When no such cost exists, cost/cache stamping runs only after the complete
usage envelope is available.
