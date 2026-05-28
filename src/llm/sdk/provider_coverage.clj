(ns llm.sdk.provider-coverage
  "Declared per-provider SDK coverage.

   This is not marketing metadata. It is an internal contract matrix used by
   tests to keep provider support honest across request/response shape, SDK
   surface area, caching, usage metrics, pricing, model listing, auth, errors,
   and live-smoke coverage.")

(defn- cov
  [& {:keys [surfaces cache metrics pricing models request response stream
             auth errors live-smoke notes]}]
  {:surfaces surfaces
   :context-cache cache
   :metrics metrics
   :pricing pricing
   :models models
   :request-shape request
   :response-shape response
   :stream-shape stream
   :auth auth
   :errors errors
   :live-smoke live-smoke
   :notes notes})

(defn- openai-compat
  [& {:keys [surfaces cache pricing models live-smoke notes]
      :or {surfaces #{:complete :streaming}
           cache #{:prompt-key}
           pricing #{:registry-snapshot :user-override}
           models #{:live-models-api :openai-style-live-models}
           live-smoke :env-gated}}]
  (cov :surfaces surfaces
       :cache cache
       :metrics #{:openai-usage :canonical-chat-stamp}
       :pricing pricing
       :models models
       :request #{:openai-chat-completions-golden}
       :response #{:openai-chat-completions-fixture}
       :stream #{:openai-sse-fixture}
       :auth #{:bearer}
       :errors #{:shared-classifier}
       :live-smoke live-smoke
       :notes notes))

(def provider-coverage
  {:openai
   (cov :surfaces #{:complete :streaming :tools :json-schema :reasoning
                    :file-attachments :embedding :moderation
                    :image-generation :transcription :tts}
        :cache #{:prompt-key :canonical-cache-stamp}
        :metrics #{:openai-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :openrouter-live-route :user-override}
        :models #{:live-models-api :models-dev :bundled-snapshot}
        :request #{:openai-chat-completions-golden :embeddings-golden
                   :openai-chat-file-golden :moderation-golden :image-golden
                   :multipart-transcribe-golden :tts-golden}
        :response #{:openai-chat-completions-fixture :embeddings-fixture
                    :moderation-fixture :image-fixture :transcription-fixture
                    :tts-bytes-fixture}
        :stream #{:openai-sse-fixture}
        :auth #{:bearer :runtime-override}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :anthropic
   (cov :surfaces #{:complete :streaming :tools :json-schema :reasoning
                    :thinking-blocks :file-attachments}
        :cache #{:system-and-3-native :system-blocks :tools-cache
                 :canonical-cache-stamp}
        :metrics #{:anthropic-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :user-override}
        :models #{:live-models-api :models-dev :bundled-snapshot}
        :request #{:anthropic-messages-golden :anthropic-document-golden}
        :response #{:anthropic-messages-fixture}
        :stream #{:anthropic-sse-fixture}
        :auth #{:api-key-header :claude-oauth-token :runtime-override}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :gemini-native
   (cov :surfaces #{:complete :streaming :tools :multimodal :reasoning
                    :file-attachments}
        :cache #{:implicit-provider-cache :explicit-cached-content
                 :canonical-cache-stamp}
        :metrics #{:gemini-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :openrouter-live-route :user-override}
        :models #{:live-models-api :models-dev :bundled-snapshot}
        :request #{:gemini-native-golden :gemini-file-golden}
        :response #{:gemini-native-fixture}
        :stream #{:gemini-sse-fixture}
        :auth #{:api-key-header :runtime-override}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :vertex-gemini
   (cov :surfaces #{:complete :streaming :tools :multimodal :reasoning
                    :file-attachments}
        :cache #{:implicit-provider-cache :explicit-cached-content
                 :canonical-cache-stamp}
        :metrics #{:gemini-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :openrouter-live-route :user-override}
        :models #{:live-models-api :models-dev :bundled-snapshot}
        :request #{:vertex-gemini-golden :gemini-file-golden}
        :response #{:vertex-gemini-fixture}
        :stream #{:gemini-sse-fixture}
        :auth #{:gcp-adc :oauth-bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :openrouter
   (cov :surfaces #{:complete :streaming :tools :json-schema :reasoning
                    :provider-routing :embedding :image-generation}
        :cache #{:system-and-3-envelope :prompt-key :canonical-cache-stamp}
        :metrics #{:openai-usage :openrouter-provider-usage
                   :embedding-usage :image-response-metadata
                   :canonical-chat-stamp}
        :pricing #{:openrouter-live-models :models-dev :litellm-snapshot
                   :user-override}
        :models #{:live-models-api :models-dev :bundled-snapshot}
        :request #{:openrouter-golden :openai-chat-completions-golden
                   :embeddings-golden :openrouter-image-golden}
        :response #{:openrouter-fixture :openai-chat-completions-fixture
                    :embeddings-fixture :openrouter-image-fixture}
        :stream #{:openai-sse-fixture}
        :auth #{:bearer :optional-for-models}
        :errors #{:shared-classifier}
        :live-smoke :env-gated
        :notes "OpenRouter is first-class, not an alias; pricing is billing-route pricing.")

   :codex
   (cov :surfaces #{:complete :streaming :tools :reasoning
                    :encrypted-reasoning :file-attachments}
        :cache #{:prompt-key :canonical-cache-stamp}
        :metrics #{:codex-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :user-override}
        :models #{:snapshot-only}
        :request #{:responses-api-golden :responses-file-golden}
        :response #{:responses-api-fixture}
        :stream #{:responses-sse-fixture}
        :auth #{:bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :codex-backend
   (cov :surfaces #{:complete :streaming :tools :reasoning
                    :encrypted-reasoning :file-attachments}
        :cache #{:prompt-key :canonical-cache-stamp}
        :metrics #{:codex-backend-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :user-override}
        :models #{:snapshot-only}
        :request #{:codex-backend-golden :responses-file-golden}
        :response #{:codex-backend-sse-fixture}
        :stream #{:codex-backend-sse-fixture}
        :auth #{:codex-cli-auth-json :oauth-external}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :perplexity
   (cov :surfaces #{:complete :streaming :json-schema :web-search :citations}
        :cache #{:none :canonical-cache-stamp}
        :metrics #{:perplexity-usage :search-usage :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :openrouter-live-route :user-override}
        :models #{:snapshot-only}
        :request #{:perplexity-golden}
        :response #{:perplexity-citation-fixture}
        :stream #{:perplexity-final-chunk-fixture}
        :auth #{:bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :cohere
   (cov :surfaces #{:complete :streaming :tools :citations
                    :file-attachments :embedding :rerank}
        :cache #{:none :canonical-cache-stamp}
        :metrics #{:cohere-chat-usage :cohere-embed-usage :cohere-rerank-usage
                   :canonical-chat-stamp}
        :pricing #{:models-dev :litellm-snapshot :openrouter-live-route :user-override}
        :models #{:snapshot-only}
        :request #{:cohere-chat-golden :cohere-document-golden
                   :cohere-embed-golden :cohere-rerank-golden}
        :response #{:cohere-chat-fixture :cohere-embed-fixture :cohere-rerank-fixture}
        :stream #{:cohere-sse-fixture}
        :auth #{:bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :bedrock
   (cov :surfaces #{:complete :streaming :tools :guardrails
                    :file-attachments :image-generation :rerank}
        :cache #{:cache-point :canonical-cache-stamp}
        :metrics #{:bedrock-usage :bedrock-rerank-usage :canonical-chat-stamp}
        :pricing #{:litellm-snapshot :user-override}
        :models #{:snapshot-only}
        :request #{:bedrock-converse-golden :bedrock-document-golden
                   :bedrock-image-golden :bedrock-rerank-golden}
        :response #{:bedrock-converse-fixture :bedrock-image-fixture :bedrock-rerank-fixture}
        :stream #{:aws-eventstream-fixture}
        :auth #{:aws-sigv4}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :ollama-native
   (cov :surfaces #{:complete :streaming :tools :embedding :multimodal}
        :cache #{:none :canonical-cache-stamp}
        :metrics #{:ollama-chat-usage :ollama-embed-usage :canonical-chat-stamp}
        :pricing #{:unknown :user-override}
        :models #{:local-runtime-only}
        :request #{:ollama-chat-golden :ollama-embed-golden}
        :response #{:ollama-chat-fixture :ollama-embed-fixture}
        :stream #{:ollama-ndjson-fixture}
        :auth #{:none}
        :errors #{:shared-classifier}
        :live-smoke :optional-local)

   :fake
   (cov :surfaces #{:complete :streaming :tools}
        :cache #{:none :canonical-cache-stamp}
        :metrics #{:synthetic-usage :canonical-chat-stamp}
        :pricing #{:unknown}
        :models #{:unsupported-test-provider}
        :request #{:fake-golden}
        :response #{:fake-fixture}
        :stream #{:none}
        :auth #{:none}
        :errors #{:synthetic}
        :live-smoke :not-applicable)

   :deepseek (openai-compat :surfaces #{:complete :streaming :tools :reasoning})
   :kimi (openai-compat :surfaces #{:complete :streaming :tools :reasoning})
   :kimi-code (openai-compat :surfaces #{:complete :streaming :tools :reasoning}
                             :models #{:snapshot-only}
                             :live-smoke :env-gated
                             :notes "Requires Kimi CLI identity headers.")
   :mistral (openai-compat :surfaces #{:complete :streaming :tools :json-schema :embedding}
                           :notes "Penalty fields are dropped by provider quirk.")
   :groq (openai-compat :surfaces #{:complete :streaming :tools :json-schema
                                    :reasoning :transcription})
   :cerebras (openai-compat :surfaces #{:complete :streaming :tools :reasoning})
   :together (openai-compat :surfaces #{:complete :streaming :tools :json-schema :embedding})
   :xai (openai-compat :surfaces #{:complete :streaming :tools :json-schema :reasoning}
                       :cache #{:prompt-key :canonical-cache-stamp})
   :huggingface (openai-compat :surfaces #{:complete :streaming :tools :json-schema})
   :sambanova (openai-compat :surfaces #{:complete :streaming :tools :json-schema})
   :deepinfra (openai-compat :surfaces #{:complete :streaming :tools :json-schema})
   :lambda (openai-compat :surfaces #{:complete :streaming :json-schema})
   :nebius (openai-compat :surfaces #{:complete :streaming :tools :json-schema :embedding})
   :hyperbolic (openai-compat :surfaces #{:complete :streaming :json-schema})
   :novita (openai-compat :surfaces #{:complete :streaming :tools :json-schema})
   :friendliai (openai-compat :surfaces #{:complete :streaming :json-schema})
   :featherless (openai-compat :surfaces #{:complete :streaming :json-schema})
   :cloudflare (openai-compat :surfaces #{:complete :streaming :json-schema}
                              :models #{:requires-account-scoped-base-url}
                              :live-smoke :manual-config)
   :dashscope (openai-compat :surfaces #{:complete :streaming :tools :json-schema})
   :volcengine (openai-compat :surfaces #{:complete :streaming :tools :json-schema})

   :voyage
   (cov :surfaces #{:embedding :rerank}
        :cache #{:not-applicable}
        :metrics #{:embedding-usage :rerank-response-metrics}
        :pricing #{:models-dev :litellm-snapshot :user-override}
        :models #{:snapshot-only}
        :request #{:openai-compatible-embed-golden :voyage-rerank-golden}
        :response #{:openai-compatible-embed-fixture :voyage-rerank-fixture}
        :stream #{:not-applicable}
        :auth #{:bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :jina
   (cov :surfaces #{:embedding :rerank}
        :cache #{:not-applicable}
        :metrics #{:embedding-usage :jina-rerank-usage}
        :pricing #{:models-dev :litellm-snapshot :user-override}
        :models #{:snapshot-only}
        :request #{:openai-compatible-embed-golden :cohere-shape-rerank-golden}
        :response #{:openai-compatible-embed-fixture :cohere-shape-rerank-fixture}
        :stream #{:not-applicable}
        :auth #{:bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :vertex-imagen
   (cov :surfaces #{:image-generation}
        :cache #{:not-applicable}
        :metrics #{:image-response-metadata}
        :pricing #{:models-dev :litellm-snapshot :user-override}
        :models #{:snapshot-only}
        :request #{:vertex-imagen-golden}
        :response #{:vertex-imagen-fixture}
        :stream #{:not-applicable}
        :auth #{:gcp-adc :oauth-bearer}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)

   :elevenlabs
   (cov :surfaces #{:tts}
        :cache #{:not-applicable}
        :metrics #{:audio-response-metadata}
        :pricing #{:user-override}
        :models #{:unsupported}
        :request #{:elevenlabs-tts-golden}
        :response #{:tts-bytes-fixture}
        :stream #{:not-applicable}
        :auth #{:api-key-header}
        :errors #{:shared-classifier}
        :live-smoke :env-gated)})

(defn coverage-for [provider-id]
  (get provider-coverage provider-id))
