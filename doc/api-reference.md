# API Reference

The public API lives in `llm.sdk`. Provider ids are keywords such as `:openai`, `:anthropic`, `:kimi-code`, or `:cohere`.

## Chat

```clojure
(sdk/complete provider-id request & {:keys [stream? on-event retry config]})
```

Minimum request:

```clojure
{:request/model "gpt-4o-mini"
 :request/messages [{:message/role :user
                     :message/content "Hello"}]}
```

Common request keys:

| Key | Meaning |
|---|---|
| `:request/model` | Provider model id. |
| `:request/messages` | Ordered messages with `:message/role` and `:message/content`. |
| `:request/tools` | Function or custom tool definitions. Custom tools are supported by OpenAI/Codex and by explicitly opted-in OpenAI-compatible aliases. |
| `:request/tool-choice` | Provider-neutral tool choice when supported. |
| `:request/temperature`, `:request/top-p` | Sampling controls. |
| `:request/max-tokens` | Output token limit. |
| `:request/stop` | Stop sequence or sequences. |
| `:request/response-format` | JSON/object/schema response format when supported. |
| `:request/cache` | Provider context-caching policy. |
| `:request/provider-options` | Provider-specific escape hatch. |

Options:

| Option | Meaning |
|---|---|
| `:stream? true` | Return a single-pass, resource-owning stream handle instead of a blocking response. |
| `:on-event fn` | Invoke the callback incrementally for every event, then return the accumulated response. |
| `:retry true` | Use the default retry policy for retryable transient failures. |
| `:retry {...}` | Merge caller policy into the default retry policy. |
| `:config {...}` | Per-call runtime configuration for auth, base URL, headers, HTTP client, and timeouts. |

For ChatGPT OAuth, `:config` accepts `:transport :sse` (default) or
`:transport :websocket`, plus optional caller-managed `:auth-token` and
`:account-id`. Managed CLI credentials refresh automatically. See
[OAuth transports and recovery](provider-configuration.md#chatgpt-oauth)
for the bounded pre-generation recovery rules.

`sdk/complete` validates the request, applies provider supported-parameter rules, builds the provider request, and parses the response. It preserves an adapter-supplied `:response/cost` (including provider-reported cost); only when cost is absent does the SDK estimate it from canonical usage and known pricing. Cache status is stamped from reported cache usage.

Message content can be a string or a vector of canonical parts. File/document
attachments use `:part/type :file`:

```clojure
{:message/role :user
 :message/content [{:part/type :file
                    :file/name "brief.pdf"
                    :file/data "JVBERi0x"
                    :file/mime-type "application/pdf"}
                   {:part/type :text
                    :text "Summarize this."}]}
```

Supported file sources are `:file/id`, `:file/url`, base64 `:file/data`, raw
`:file/bytes`, or textual `:file/content`. OpenAI Chat, OpenAI
Responses/Codex, Anthropic, Gemini/Vertex Gemini, and Bedrock Converse
serialize files natively. Cohere maps textual `:file/content` into native
top-level `documents`. OpenAI file data is emitted as the required
`data:<mime>;base64,...` URI. Providers without native file input support
reject `:file` parts explicitly.

Runtime config is profile-local for that call and does not mutate the provider registry:

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

All seven modality drivers use the same HTTP path. Without an injected client,
they share one lazily created connection pool with a 30-second connect timeout.
`:timeout-ms` is the per-request deadline and defaults to 120 seconds.
`:connect-timeout-ms` builds a client with that connect timeout unless
`:http-client` is supplied. Runtime headers are merged case-insensitively and
win over defaults, adapter headers, and auth headers.

### Streaming chat

With `:stream? true` and no `:on-event`, `sdk/complete` returns an
`llm.sdk.StreamHandle` implementing `Seqable`, `java.io.Closeable`,
`clojure.lang.IReduce`, and `clojure.lang.IReduceInit`. Use `with-open` for
sequence-style consumption:

```clojure
(with-open [events (sdk/complete :openai request :stream? true)]
  (doseq [event events]
    (consume event)))
```

Reducing the handle closes it after completion, failure, or early `reduced`
termination. Sequence operations that abandon the stream before EOF must close
the handle explicitly; normal EOF closes it automatically.

The handle is single-pass and single-consumer. `seq` and `reduce` advance the
same cursor, consumed events are released, and a handle is neither replayable
nor safe for concurrent consumers. Retain your own event data if it is needed
after consumption.

| Event | Significant fields |
|---|---|
| `:stream/reasoning-delta` | Optional `:event/delta`, `:event/index`, `:event/encrypted`, and `:reasoning/signature`. Indexed updates accumulate into separate signed reasoning parts. |
| `:stream/tool-call-start` | `:tool-call/index`, id, name, and optional `:tool-call/provider-data`, including custom or provider-native tool identity that must survive on the accumulated tool-call part. |
| `:stream/usage` | `:usage`, a sparse cumulative map whose fields are all optional in an individual event. |
| `:stream/citation` | URL is optional when `:citation/source-id` or `:citation/provider-data` identifies the source; title, snippet, text range, dates, and source metadata may also be present. |
| `:stream/provider-state` | Provider-keyed replay data merged into the aggregate `:response/provider-data`. |
| `:stream/error` | Error value under `:error/error`. In the single-pass event interface this remains data; consuming it alone does not throw. |
| `:stream/end` | Exactly one terminal event, delivered after trailing usage and provider metadata. |

Usage events are cumulative snapshots, never additive deltas. A reported
counter replaces the prior value for that key, while omitted keys retain their
latest values. Every usage counter is optional, and a provider may report only
a subset. A provider-reported `:usage/total-tokens` is authoritative.
Cache-read and cache-write tokens are separate from canonical uncached input,
and reasoning tokens may overlap output, so none of those counters is blindly
added to a derived token total.

With `:on-event`, the callback sees every event as it arrives and
`sdk/complete` returns the accumulated canonical response after the terminal
event. If any `:stream/error` was accumulated, final accumulation throws
`ExceptionInfo`. Its `ex-data` includes `:error`, `:stream/error`, `:provider`,
and `:partial-response`; the partial response contains canonical content, tool
calls, usage, and provider replay state accumulated from the event sequence.
The handle closes even when the callback or reduction throws.

To replay prior assistant output, preserve reasoning signatures, encrypted
reasoning, custom/provider-native tool-call metadata under
`:tool-call/provider-data`, citation source metadata, and relevant
`:response/provider-data` when constructing the next canonical message.

### Tool results

A tool message may carry one typed result, keeping routing and status together:

```clojure
{:message/role :tool
 :message/content
 [{:part/type :tool-result
   :tool-result/id "call_1"
   :tool-result/name "get_weather"
   :tool-result/content "{\"temperature\":72}"
   :tool-result/is-error false}]}
```

The typed part cannot be mixed with other content, and its id or name cannot
conflict with message-level routing fields. Anthropic, Gemini, and Bedrock
lower the error status to their native field. Providers whose wire format has
no error-status field reject `:tool-result/is-error true` with
`:error/type :provider/unsupported-tool-result-error` instead of silently
converting failure to success.

## Embeddings

```clojure
(sdk/embed provider-id request & {:keys [config]})
```

```clojure
{:embed/model "text-embedding-3-small"
 :embed/inputs ["first input" "second input"]
 :embed/dimensions 1536}
```

The result includes `:embed/vectors`, `:embed/model`, `:embed/provider`, dimensions when known, usage when reported, and the raw provider response. `:gemini-native` uses `models/{model}:batchEmbedContents`; its `:embed/provider-options` supports `:task-type` and `:title`. Azure profiles registered with `register-azure-deployment!` support embeddings over both classic deployment routes and `:api-style :v1`.

## Moderation

```clojure
(sdk/moderate provider-id request & {:keys [config]})
```

```clojure
{:moderation/model "omni-moderation-latest"
 :moderation/inputs ["text to classify"]}
```

The result includes provider-normalized flagged status, categories, category scores, and raw response data.

## Rerank

```clojure
(sdk/rerank provider-id request & {:keys [config]})
```

```clojure
{:rerank/model "rerank-english-v3.0"
 :rerank/query "JVM Lisp"
 :rerank/documents ["Python" "Clojure" "JavaScript"]
 :rerank/top-n 3
 :rerank/return-documents true}
```

The result includes ranked indices, scores, optional document echoes, usage when reported, and raw provider data. Cohere may report a unit-only usage map containing `:usage/search-units`; input/output token counters are not invented.

## Image Generation

```clojure
(sdk/generate-image provider-id request & {:keys [config]})
```

```clojure
{:image/model "gpt-image-1.5"
 :image/prompt "a product photo of a brass desk lamp"
 :image/size "1024x1024"
 :image/quality :high
 :image/n 1}
```

Images may return URLs or base64 JSON depending on provider and request options. OpenAI, OpenRouter, and Bedrock require an explicit `:image/model`; the SDK does not select a billable default for them. A serializer accepting an id does not establish current provider availability, regional enablement, or account access.

## Audio Transcription

```clojure
(sdk/transcribe provider-id request & {:keys [config]})
```

```clojure
{:transcribe/model "whisper-1"
 :transcribe/file (java.io.File. "clip.wav")
 :transcribe/language "en"
 :transcribe/response-format :verbose_json}
```

The result includes text, optional language/duration/segments/words, and raw response data. When a provider reports duration as its billing unit, the canonical response carries both `:transcription/duration-seconds` and unit-only usage under `:response/usage :usage/duration-seconds`; token counters remain absent unless reported.

## Text To Speech

```clojure
(sdk/speak provider-id request & {:keys [config]})
```

```clojure
{:speak/model "tts-1"
 :speak/voice "alloy"
 :speak/input "Hello"
 :speak/format :mp3}
```

The result returns audio bytes plus content type.

## Fallbacks

```clojure
(sdk/with-fallbacks
  [[:openai "gpt-4o"]
   [:anthropic "claude-haiku-4-5"]]
  {:request/messages [{:message/role :user
                       :message/content "Reply: ok"}]})
```

The helper tries each `[provider model]` pair in order and returns the first successful response. If every attempt fails, it throws `ex-info` with an `:attempts` vector of classified provider errors.

## Provider And Model Discovery

```clojure
(sdk/list-providers)
(sdk/provider-profile :openai)
(sdk/list-models)
(sdk/list-models :openai)
(sdk/model-info :openai "gpt-4o")
(sdk/model-capabilities :openai "gpt-4o")
(sdk/model-context-length :openai "gpt-4o")
(sdk/refresh-models! :provider :openai)
```

For conservative routing, use `llm.sdk.catalog/resolve-model`. It accepts an
exact model id, a known `provider/model` prefix, or the two-argument
`(resolve-model provider model-id)` form. A bare id present under multiple
providers throws `ExceptionInfo` with `:error :catalog/ambiguous-model`; no
substring guessing is performed.

See [model-registry.md](model-registry.md) for the registry precedence rules and cost APIs.

## Cost And Cache Helpers

```clojure
(sdk/estimate-cost :openai "gpt-4o"
                   {:usage/input-tokens 1000
                    :usage/output-tokens 500})

(sdk/canonical-cache {:usage/cached-input-tokens 42})
```

`sdk/complete` calls these internally for chat responses. They are also public for after-the-fact attribution.

An existing `:response/cost` from an adapter is authoritative and is never
replaced by registry estimation. Missing usage or missing rates remain unknown;
the SDK does not manufacture token counts, substitute text rates for another
modality, or turn an unknown billable dimension into zero cost.
