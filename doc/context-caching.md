# Context Caching

Context caching is opt-in at the canonical request layer. The SDK turns `:request/cache` into the right provider-specific mechanism while preserving honest cache attribution on the response.

This is provider context caching, not response memoization. The SDK does not store prompt-response pairs in Redis, disk, S3, or vector stores.

## Request Shape

```clojure
:request/cache
{:enabled?          true
 :ttl               "5m"
 :strategy          :auto
 :scope-id          "session-1234"
 :cached-content-id "cachedContents/xyz"
 :breakpoints       4
 :tools-cache?      true}
```

Keys:

| Key | Meaning |
|---|---|
| `:enabled?` | Defaults to true when `:request/cache` is present. |
| `:ttl` | Provider TTL, currently meaningful for Anthropic-style cache markers. |
| `:strategy` | `:auto`, `:system-and-3`, `:explicit`, or `:none`. |
| `:scope-id` | Cache routing key for prompt-key providers. |
| `:cached-content-id` | Explicit Gemini CachedContent resource id. |
| `:breakpoints` | Maximum Anthropic `cache_control` markers. |
| `:tools-cache?` | Whether tool schemas may receive cache markers. |

Omit `:request/cache` entirely to send no cache markers or cache routing keys.

## Provider Matrix

| Provider | Strategy | Wire mechanism |
|---|---|---|
| `:anthropic` | `system-and-3` | `cache_control` on system blocks, selected messages, and optional tools. |
| `:openrouter` Claude/Qwen | `system-and-3` envelope | `cache_control` on message envelopes plus `prompt_cache_key`. |
| `:openrouter` other models | `prompt-key` | `extra_body.prompt_cache_key` when upstream supports it. |
| `:openai`, `:deepseek`, `:kimi` | `prompt-key` | top-level `prompt_cache_key`. |
| `:codex` | `prompt-key` | top-level `prompt_cache_key` for OpenAI Responses. |
| `:codex-backend` | `prompt-key` | `prompt_cache_key` plus backend session headers. |
| `:gemini-native`, `:vertex-gemini` | implicit | Provider reports `usageMetadata.cachedContentTokenCount` when it fires. |
| `:vertex-gemini` with `:cached-content-id` | explicit | `cachedContent` body field. |
| `:bedrock` | cache-point | `{:cachePoint {:type "default"}}` blocks. |

Inspect the strategy without sending a request:

```clojure
(sdk/cache-strategy :openrouter "anthropic/claude-sonnet-4")
(sdk/cache-strategy :vertex-gemini "gemini-2.5-flash")
```

## Response Attribution

Cache data is stamped under `:response/cache`:

```clojure
{:cache/status :hit
 :cache/cached-tokens 13824
 :cache/cache-write-tokens :unknown}
```

Honesty rules:

- `:hit` means the provider reported positive cached-input tokens.
- `:miss` means the provider explicitly reported zero cached-input tokens.
- `:unknown` means the provider did not report cache stats.
- Unknown cache values are the keyword `:unknown`, never `0`.

The same information is available from usage fields such as `:usage/cached-input-tokens` and `:usage/cache-write-tokens` when providers report them.

## Gemini And Vertex Notes

Gemini implicit caching is controlled by Google. The SDK sends no opt-in field unless `:cached-content-id` is provided. Regional Vertex routing tends to produce more consistent cache hits than `global` routing for models that support regional endpoints.

Use explicit `cachedContent` resources when you need stronger control over Gemini cache reuse.
