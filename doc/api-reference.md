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
| `:request/tools` | Function tool definitions. |
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

`sdk/complete` validates the request, applies provider supported-parameter rules, builds the provider request, parses the response, and stamps cost/cache data from usage.

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

## Embeddings

```clojure
(sdk/embed provider-id request & {:keys [config]})
```

```clojure
{:embed/model "text-embedding-3-small"
 :embed/inputs ["first input" "second input"]
 :embed/dimensions 1536}
```

The result includes `:embed/vectors`, `:embed/model`, `:embed/provider`, dimensions when known, usage when reported, and the raw provider response.

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

The result includes ranked indices, scores, optional document echoes, usage when reported, and raw provider data.

## Image Generation

```clojure
(sdk/generate-image provider-id request & {:keys [config]})
```

```clojure
{:image/model "dall-e-3"
 :image/prompt "a product photo of a brass desk lamp"
 :image/size "1024x1024"
 :image/quality :hd
 :image/n 1}
```

Images may return URLs or base64 JSON depending on provider and request options.

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

The result includes text, optional language/duration/segments/words, and raw response data.

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
