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
| `:stream? true` | Return stream events instead of a single blocking response. |
| `:on-event fn` | Callback invoked for every stream event. |
| `:retry true` | Use the default retry policy for retryable transient failures. |
| `:retry {...}` | Merge caller policy into the default retry policy. |
| `:config {...}` | Per-call runtime configuration for auth, base URL, headers, HTTP client, and timeouts. |

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

### Streaming chat

With `:stream? true` and no `:on-event`, `sdk/complete` returns a lazy sequence
of canonical events. Realize or reduce it to perform the stream read:

| Event | Significant fields |
|---|---|
| `:stream/reasoning-delta` | Optional `:event/delta`, `:event/index`, `:event/encrypted`, and `:reasoning/signature`. Indexed updates accumulate into separate signed reasoning parts. |
| `:stream/tool-call-start` | `:tool-call/index`, id, name, and optional `:tool-call/provider-data`, including custom or provider-native tool identity that must survive on the accumulated tool-call part. |
| `:stream/usage` | `:usage`, a sparse cumulative map whose fields are all optional in an individual event. |
| `:stream/citation` | URL is optional when `:citation/source-id` or `:citation/provider-data` identifies the source; title, snippet, text range, dates, and source metadata may also be present. |
| `:stream/provider-state` | Provider-keyed replay data merged into the aggregate `:response/provider-data`. |
| `:stream/error` | Error value under `:error/error`. In the lazy interface this remains an event; consuming it alone does not throw. |
| `:stream/end` | Exactly one terminal event, delivered after trailing usage and provider metadata. |

Usage events are cumulative snapshots, never additive deltas. A reported
counter replaces the prior value for that key, while omitted keys retain their
latest values. Every usage counter is optional, and a provider may report only
a subset. A provider-reported `:usage/total-tokens` is authoritative.
Cache-read and cache-write tokens are separate from canonical uncached input,
and reasoning tokens may overlap output, so none of those counters is blindly
added to a derived token total.

With `:on-event`, the callback sees every event and `sdk/complete` then returns
the accumulated canonical response. If any `:stream/error` was accumulated,
that final accumulation throws `ExceptionInfo`. Its `ex-data` includes
`:error`, `:stream/error`, `:provider`, and `:partial-response`; the partial
response contains the canonical content, tool calls, usage, and provider
replay state accumulated from the event sequence.

To replay prior assistant output, preserve reasoning signatures, encrypted
reasoning, custom/provider-native tool-call metadata under
`:tool-call/provider-data`, citation source metadata, and relevant
`:response/provider-data` when constructing the next canonical message.

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
