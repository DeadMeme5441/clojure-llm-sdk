(ns llm.sdk.provider.builtins
  "Complete built-in provider values. Loading adapters has no registry effects."
  (:require [llm.sdk.providers.openai-compat.aliases :as aliases]
            [llm.sdk.providers.openai.chat :as openai]
            [llm.sdk.providers.openai.embeddings :as openai-embed]
            [llm.sdk.providers.openai.moderation :as moderation]
            [llm.sdk.providers.openai.image :as openai-image]
            [llm.sdk.providers.openai.transcribe :as transcribe]
            [llm.sdk.providers.openai.speak :as speak]
            [llm.sdk.providers.anthropic.chat :as anthropic]
            [llm.sdk.providers.anthropic.vertex :as vertex-anthropic]
            [llm.sdk.providers.gemini.native :as gemini]
            [llm.sdk.providers.gemini.embeddings :as gemini-embed]
            [llm.sdk.providers.gemini.vertex :as vertex-gemini]
            [llm.sdk.providers.gemini.imagen :as imagen]
            [llm.sdk.providers.codex.responses :as codex]
            [llm.sdk.providers.cohere.chat :as cohere]
            [llm.sdk.providers.cohere.embeddings :as cohere-embed]
            [llm.sdk.providers.cohere.rerank :as cohere-rerank]
            [llm.sdk.providers.voyage.embeddings :as voyage-embed]
            [llm.sdk.providers.voyage.rerank :as voyage-rerank]
            [llm.sdk.providers.jina.embeddings :as jina-embed]
            [llm.sdk.providers.openrouter.chat :as openrouter]
            [llm.sdk.providers.openrouter.embeddings :as openrouter-embed]
            [llm.sdk.providers.openrouter.image :as openrouter-image]
            [llm.sdk.providers.perplexity.chat :as perplexity]
            [llm.sdk.providers.bedrock.converse :as bedrock]
            [llm.sdk.providers.bedrock.image :as bedrock-image]
            [llm.sdk.providers.bedrock.rerank :as bedrock-rerank]
            [llm.sdk.providers.ollama.native :as ollama]
            [llm.sdk.providers.elevenlabs.tts :as elevenlabs]
            [llm.sdk.providers.zai.chat :as zai]
            [llm.sdk.providers.fake.chat :as fake]))

(defn- profile
  [id family url auth opts]
  (merge {:profile/id id
          :profile/protocol-family family
          :profile/base-url url
          :profile/auth-strategy auth
          :profile/supports-model-listing false
          :profile/capabilities #{}
          :profile/env-var-names []
          :profile/default-headers {}
          :profile/quirks {}}
         opts))

(defn profiles
  "Assemble all built-ins before publishing any of them to the registry."
  []
  (let [google-env ["GOOGLE_APPLICATION_CREDENTIALS" "GOOGLE_OAUTH_ACCESS_TOKEN"
                    "GOOGLE_CLOUD_PROJECT" "GOOGLE_CLOUD_LOCATION"]
        aws-region (or (System/getenv "AWS_REGION")
                       (System/getenv "AWS_DEFAULT_REGION") "us-east-1")
        anthropic-caps #{:chat :streaming :tools :json-schema :reasoning :cache
                         :thinking-blocks :file-attachments}
        gemini-caps #{:chat :streaming :tools :multimodal :reasoning
                      :file-attachments :json-schema :cache}
        codex-caps #{:chat :streaming :tools :json-schema :multimodal :cache
                     :reasoning :encrypted-reasoning :file-attachments}]
    (into
     (mapv (fn [spec]
             (cond-> (openai/build-alias-profile spec)
               (#{:mistral :together :nebius} (:id spec))
               (assoc :profile/embed-transport-constructor openai-embed/make-transport)
               (= :groq (:id spec))
               (assoc :profile/transcribe-transport-constructor transcribe/make-transport)))
           aliases/chat-alias-specs)
     [(profile :openai :openai-chat "https://api.openai.com/v1" :bearer
               {:profile/env-var-names ["OPENAI_API_KEY"]
                :profile/supports-model-listing true
                :profile/capabilities #{:chat :streaming :tools :json-schema
                                        :reasoning :cache :multimodal :file-attachments}
                :profile/transport-constructor openai/make-transport
                :profile/embed-transport-constructor openai-embed/make-transport
                :profile/moderation-transport-constructor moderation/make-transport
                :profile/image-transport-constructor openai-image/make-transport
                :profile/transcribe-transport-constructor transcribe/make-transport
                :profile/speak-transport-constructor speak/make-transport})
      (profile :anthropic :anthropic-messages "https://api.anthropic.com/v1" :api-key-header
               {:profile/auth-header-name "x-api-key"
                :profile/env-var-names ["ANTHROPIC_API_KEY" "CLAUDE_OAT_TOKEN"]
                :profile/supports-model-listing true
                :profile/capabilities anthropic-caps
                :profile/default-headers {"anthropic-version" "2023-06-01"}
                :profile/transport-constructor anthropic/make-transport})
      (profile :vertex-anthropic :anthropic-messages
               "https://us-central1-aiplatform.googleapis.com" :gcp-oauth
               {:profile/env-var-names google-env
                :profile/capabilities anthropic-caps
                :profile/transport-constructor vertex-anthropic/make-transport})
      (profile :gemini-native :gemini-native
               "https://generativelanguage.googleapis.com/v1beta" :api-key-header
               {:profile/auth-header-name "x-goog-api-key"
                :profile/env-var-names ["GEMINI_API_KEY"]
                :profile/supports-model-listing true
                :profile/capabilities gemini-caps
                :profile/transport-constructor gemini/make-transport
                :profile/embed-transport-constructor gemini-embed/make-transport})
      (profile :vertex-gemini :gemini-native
               "https://us-central1-aiplatform.googleapis.com" :gcp-oauth
               {:profile/env-var-names google-env
                :profile/supports-model-listing true
                :profile/capabilities gemini-caps
                :profile/transport-constructor vertex-gemini/make-transport})
      (profile :vertex-imagen :gemini-native "https://aiplatform.googleapis.com" :gcp-oauth
               {:profile/env-var-names google-env
                :profile/image-transport-constructor imagen/make-transport})
      (profile :codex :codex "https://api.openai.com/v1" :bearer
               {:profile/env-var-names ["OPENAI_API_KEY"]
                :profile/capabilities codex-caps
                :profile/transport-constructor codex/make-transport})
      (profile :codex-backend :codex "https://chatgpt.com/backend-api/codex" :oauth-external
               {:profile/capabilities codex-caps
                :profile/transport-constructor codex/make-transport})
      (profile :openrouter :openrouter "https://openrouter.ai/api/v1" :bearer
               {:profile/env-var-names ["OPENROUTER_API_KEY"]
                :profile/supports-model-listing true
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning
                                        :cache :multimodal :provider-routing}
                :profile/quirks {:provider-preferences true :pareto-router true}
                :profile/transport-constructor openrouter/make-transport
                :profile/embed-transport-constructor openrouter-embed/make-transport
                :profile/image-transport-constructor openrouter-image/make-transport})
      (profile :perplexity :perplexity-agent "https://api.perplexity.ai" :bearer
               {:profile/env-var-names ["PERPLEXITY_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema
                                        :web-search :reasoning :multimodal}
                :profile/supported-params #{:request/tools :request/temperature
                                            :request/top-p :request/max-tokens
                                            :request/response-format :request/reasoning}
                :profile/transport-constructor perplexity/make-transport
                :profile/cost-calculator perplexity/perplexity-cost-calculator})
      (profile :cohere :cohere "https://api.cohere.com/v1" :bearer
               {:profile/env-var-names ["COHERE_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning
                                        :citations :file-attachments :multimodal}
                :profile/transport-constructor cohere/make-transport
                :profile/embed-transport-constructor cohere-embed/make-transport
                :profile/rerank-transport-constructor cohere-rerank/make-transport})
      (profile :voyage :openai-embed "https://api.voyageai.com/v1" :bearer
               {:profile/env-var-names ["VOYAGE_API_KEY"]
                :profile/embed-transport-constructor voyage-embed/make-transport
                :profile/rerank-transport-constructor voyage-rerank/make-transport})
      (profile :jina :openai-embed "https://api.jina.ai/v1" :bearer
               {:profile/env-var-names ["JINA_API_KEY"]
                :profile/embed-transport-constructor jina-embed/make-transport
                :profile/rerank-transport-constructor cohere-rerank/make-transport})
      (profile :bedrock :bedrock
               (str "https://bedrock-runtime." aws-region ".amazonaws.com") :aws-sigv4
               {:profile/aws-service "bedrock"
                :profile/aws-region aws-region
                :profile/env-var-names ["AWS_ACCESS_KEY_ID" "AWS_SECRET_ACCESS_KEY" "AWS_REGION"]
                :profile/capabilities #{:chat :streaming :tools :json-schema :reasoning
                                        :guardrails :cache :multimodal :file-attachments}
                :profile/binary-stream :aws-eventstream
                :profile/transport-constructor bedrock/make-transport
                :profile/image-transport-constructor bedrock-image/make-transport
                :profile/rerank-transport-constructor bedrock-rerank/make-transport})
      (profile :ollama-native :ollama-native
               (or (System/getenv "OLLAMA_BASE_URL") "http://localhost:11434") :none
               {:profile/capabilities #{:chat :streaming :tools :multimodal :json-schema :reasoning}
                :profile/transport-constructor ollama/make-transport
                :profile/embed-transport-constructor ollama/make-embed-transport})
      (profile :elevenlabs :elevenlabs "https://api.elevenlabs.io" :api-key-header
               {:profile/auth-header-name "xi-api-key"
                :profile/env-var-names ["ELEVENLABS_API_KEY"]
                :profile/speak-transport-constructor elevenlabs/make-transport})
      (profile :zai :zai-chat "https://api.z.ai/api/paas/v4" :bearer
               {:profile/env-var-names ["ZAI_API_KEY"]
                :profile/capabilities #{:chat :streaming :tools :reasoning :multimodal}
                :profile/quirks {:reasoning-replay-field :reasoning_content}
                :profile/supported-params #{:request/tools :request/tool-choice
                                            :request/temperature :request/top-p
                                            :request/max-tokens :request/stop
                                            :request/response-format :request/reasoning}
                :profile/transport-constructor zai/make-transport})
      (profile :fake :fake "https://fake.local" :none
               {:profile/transport-constructor fake/make-fake-transport})])))
