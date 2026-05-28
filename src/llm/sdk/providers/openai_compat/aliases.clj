(ns llm.sdk.providers.openai-compat.aliases
  "Data-only OpenAI-compatible provider alias specs.

   These providers share the OpenAI chat-completions wire shape. Adapter code
   may still apply provider quirks from the profile, but the registry should
   not need one hand-written register-provider call per alias.")

(def chat-alias-specs
  [{:id :deepseek
    :base-url "https://api.deepseek.com/v1"
    :env-var-names ["DEEPSEEK_API_KEY"]
    :capabilities #{:chat :streaming :tools :reasoning}
    :quirks {:thinking-explicit true
             :reasoning-content-echo true}}
   {:id :kimi
    :base-url "https://api.moonshot.cn/v1"
    :env-var-names ["MOONSHOT_API_KEY"]
    :capabilities #{:chat :streaming :tools :reasoning}
    :quirks {:thinking-explicit true}}
   {:id :kimi-code
    :base-url "https://api.kimi.com/coding/v1"
    :env-var-names ["KIMI_API_KEY"]
    :capabilities #{:chat :streaming :tools :reasoning}
    :default-headers :kimi-code
    :supports-model-listing? false
    :quirks {:thinking-explicit true}}
   {:id :mistral
    :base-url "https://api.mistral.ai/v1"
    :env-var-names ["MISTRAL_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}
    :quirks {:drops #{:frequency_penalty :presence_penalty}}}
   {:id :groq
    :base-url "https://api.groq.com/openai/v1"
    :env-var-names ["GROQ_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}
    :quirks {:reasoning-format :raw}}
   {:id :cerebras
    :base-url "https://api.cerebras.ai/v1"
    :env-var-names ["CEREBRAS_API_KEY"]
    :capabilities #{:chat :streaming :tools :reasoning}
    :quirks {:reasoning-effort true}}
   {:id :together
    :base-url "https://api.together.xyz/v1"
    :env-var-names ["TOGETHER_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :xai
    :base-url "https://api.x.ai/v1"
    :env-var-names ["XAI_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema :reasoning}}
   {:id :huggingface
    :base-url "https://router.huggingface.co/v1"
    :env-var-names ["HF_TOKEN"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :sambanova
    :base-url "https://api.sambanova.ai/v1"
    :env-var-names ["SAMBANOVA_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :deepinfra
    :base-url "https://api.deepinfra.com/v1/openai"
    :env-var-names ["DEEPINFRA_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :lambda
    :base-url "https://api.lambda.ai/v1"
    :env-var-names ["LAMBDA_API_KEY"]
    :capabilities #{:chat :streaming :json-schema}}
   {:id :nebius
    :base-url "https://api.studio.nebius.com/v1"
    :env-var-names ["NEBIUS_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :hyperbolic
    :base-url "https://api.hyperbolic.xyz/v1"
    :env-var-names ["HYPERBOLIC_API_KEY"]
    :capabilities #{:chat :streaming :json-schema}}
   {:id :novita
    :base-url "https://api.novita.ai/v3/openai"
    :env-var-names ["NOVITA_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :friendliai
    :base-url "https://api.friendli.ai/serverless/v1"
    :env-var-names ["FRIENDLI_TOKEN"]
    :capabilities #{:chat :streaming :json-schema}}
   {:id :featherless
    :base-url "https://api.featherless.ai/v1"
    :env-var-names ["FEATHERLESS_API_KEY"]
    :capabilities #{:chat :streaming :json-schema}}
   {:id :cloudflare
    ;; Cloudflare Workers AI's OpenAI-compat surface lives under
    ;; /accounts/{account_id}/ai/v1. Callers must configure the account-scoped
    ;; base-url via overrides because the SDK cannot ship a working default.
    :base-url "https://api.cloudflare.com/client/v4/accounts/REPLACE-WITH-ACCOUNT-ID/ai/v1"
    :env-var-names ["CLOUDFLARE_API_TOKEN"]
    :capabilities #{:chat :streaming :json-schema}
    :supports-model-listing? false}
   {:id :dashscope
    :base-url "https://dashscope-intl.aliyuncs.com/compatible-mode/v1"
    :env-var-names ["DASHSCOPE_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}
   {:id :volcengine
    :base-url "https://ark.cn-beijing.volces.com/api/v3"
    :env-var-names ["ARK_API_KEY"]
    :capabilities #{:chat :streaming :tools :json-schema}}])

(def chat-alias-ids
  (mapv :id chat-alias-specs))

(def model-listing-alias-ids
  (->> chat-alias-specs
       (remove #(false? (:supports-model-listing? %)))
       (mapv :id)))
