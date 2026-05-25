# Getting Started

This guide is for application authors using `clojure-llm-sdk` as a library. The SDK normalizes provider wire formats into one Clojure surface while preserving provider-specific replay state such as reasoning signatures, encrypted reasoning items, citations, and tool-call ids.

## Install

Add the library to `deps.edn`:

```clojure
{:deps {com.deadmeme5441/clojure-llm-sdk
        {:git/url "https://github.com/DeadMeme5441/clojure-llm-sdk"
         :git/sha "LATEST_SHA"}}}
```

Then require the public namespace:

```clojure
(require '[llm.sdk :as sdk])
```

## Configure Credentials

Set only the credentials for providers you call. For local live smoke tests, copy the template:

```bash
cp .env.example .env
```

Example shell setup:

```bash
export OPENAI_API_KEY=sk-...
export ANTHROPIC_API_KEY=sk-ant-api03-...
export KIMI_API_KEY=...
```

The SDK does not load `.env` files by itself. Your application or shell should load them before invoking the library.

## First Chat Request

```clojure
(sdk/complete
  :openai
  {:request/model "gpt-4o-mini"
   :request/messages [{:message/role :user
                       :message/content "Reply with the single word: ok"}]})
```

The response is canonical:

```clojure
{:response/provider :openai
 :response/model "gpt-4o-mini"
 :response/parts [{:part/type :text
                   :text "ok"}]
 :response/finish-reason :stop
 :response/usage {...}
 :response/cost {...}
 :response/cache {...}
 :response/raw {...}}
```

See [canonical-response.md](canonical-response.md) for the full response contract.

## Streaming

Pass `:stream? true` and optionally `:on-event`:

```clojure
(sdk/complete
  :openai
  {:request/model "gpt-4o-mini"
   :request/messages [{:message/role :user
                       :message/content "Count to three"}]}
  :stream? true
  :on-event (fn [event]
              (when (= :stream/content-delta (:event/type event))
                (print (:event/delta event)))))
```

Stream events use a provider-neutral taxonomy: content deltas, reasoning deltas, tool-call deltas, citations, usage, provider-state, errors, and end events. The final response is still stamped with usage, cost, and cache data when the provider reports enough information.

## Tool Calls

Tools use OpenAI-style function tool shape at the canonical layer:

```clojure
(def weather-tool
  {:type :function
   :function {:name "get_weather"
              :description "Get current weather for a location"
              :parameters {:type :object
                           :required ["location"]
                           :properties {:location {:type :string}}}}})

(def first-turn
  (sdk/complete
    :openai
    {:request/model "gpt-4o"
     :request/messages [{:message/role :user
                         :message/content "Weather in New York?"}]
     :request/tools [weather-tool]}))
```

When `:response/finish-reason` is `:tool-calls`, execute the requested tool in your application and send a tool result message:

```clojure
(let [call (first (:response/tool-calls first-turn))]
  (sdk/complete
    :openai
    {:request/model "gpt-4o"
     :request/messages
     [{:message/role :user
       :message/content "Weather in New York?"}
      {:message/role :assistant
       :message/content ""
       :message/tool-calls (:response/tool-calls first-turn)}
      {:message/role :tool
       :message/tool-call-id (:tool-call/id call)
       :message/content "{\"temperature\":72,\"condition\":\"clear\"}"}]
     :request/tools [weather-tool]}))
```

The SDK preserves provider-specific tool-call ids and replay fields so multi-turn requests can be reconstructed correctly.

## Other Modalities

The same public namespace exposes non-chat modalities:

```clojure
(sdk/embed :openai
           {:embed/model "text-embedding-3-small"
            :embed/inputs ["clojure" "lisp"]})

(sdk/moderate :openai
              {:moderation/inputs ["text to classify"]})

(sdk/rerank :cohere
            {:rerank/model "rerank-english-v3.0"
             :rerank/query "JVM Lisp"
             :rerank/documents ["Python" "Clojure" "JavaScript"]})

(sdk/generate-image :openai
                    {:image/model "dall-e-3"
                     :image/prompt "a product photo of a brass desk lamp"})

(sdk/transcribe :openai
                {:transcribe/model "whisper-1"
                 :transcribe/file (java.io.File. "clip.wav")})

(sdk/speak :openai
           {:speak/model "tts-1"
            :speak/voice "alloy"
            :speak/input "Hello"})
```

See [api-reference.md](api-reference.md) for the request keys each modality accepts.
