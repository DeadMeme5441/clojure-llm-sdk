(ns llm.sdk.catalog
  "Model catalog and capabilities."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Catalog entries
;; ---------------------------------------------------------------------------

(def ^:private catalog
  (atom
   {"gpt-4o" {:model/id "gpt-4o"
               :model/provider :openai
               :model/context-length 128000
               :model/capabilities #{:chat :streaming :tools :vision :json-schema :cache}}
    "gpt-4o-mini" {:model/id "gpt-4o-mini"
                   :model/provider :openai
                   :model/context-length 128000
                   :model/capabilities #{:chat :streaming :tools :vision :json-schema}}
    "gpt-4.1" {:model/id "gpt-4.1"
               :model/provider :openai
               :model/context-length 1047576
               :model/capabilities #{:chat :streaming :tools :vision :json-schema :cache}}
    "o3" {:model/id "o3"
          :model/provider :openai
          :model/context-length 200000
          :model/capabilities #{:chat :streaming :tools :reasoning :json-schema}}
    "o3-mini" {:model/id "o3-mini"
               :model/provider :openai
               :model/context-length 200000
               :model/capabilities #{:chat :streaming :tools :reasoning :json-schema}}
    "claude-opus-4-7" {:model/id "claude-opus-4-7"
                       :model/provider :anthropic
                       :model/context-length 200000
                       :model/capabilities #{:chat :streaming :tools :thinking-blocks :cache}}
    "claude-sonnet-4-6" {:model/id "claude-sonnet-4-6"
                         :model/provider :anthropic
                         :model/context-length 200000
                         :model/capabilities #{:chat :streaming :tools :thinking-blocks :cache}}
    "claude-haiku-4-5" {:model/id "claude-haiku-4-5"
                        :model/provider :anthropic
                        :model/context-length 200000
                        :model/capabilities #{:chat :streaming :tools :cache}}
    "gemini-3.1-pro-preview" {:model/id "gemini-3.1-pro-preview"
                              :model/provider :google
                              :model/context-length 1048576
                              :model/capabilities #{:chat :streaming :tools :vision :multimodal :reasoning :cache}}
    "gemini-3.5-flash" {:model/id "gemini-3.5-flash"
                        :model/provider :google
                        :model/context-length 1048576
                        :model/capabilities #{:chat :streaming :tools :vision :multimodal :reasoning :cache}}
    "gemini-3.1-flash-lite-preview" {:model/id "gemini-3.1-flash-lite-preview"
                                      :model/provider :google
                                      :model/context-length 1048576
                                      :model/capabilities #{:chat :streaming :tools :vision :multimodal :cache}}
    "gemini-2.5-pro" {:model/id "gemini-2.5-pro"
                      :model/provider :google
                      :model/context-length 1048576
                      :model/capabilities #{:chat :streaming :tools :vision :multimodal :reasoning}}
    "gemini-2.5-flash" {:model/id "gemini-2.5-flash"
                        :model/provider :google
                        :model/context-length 1048576
                        :model/capabilities #{:chat :streaming :tools :vision :multimodal :reasoning}}
    "gemini-2.0-flash" {:model/id "gemini-2.0-flash"
                        :model/provider :google
                        :model/context-length 1048576
                        :model/capabilities #{:chat :streaming :tools :vision :multimodal}}
    "deepseek-chat" {:model/id "deepseek-chat"
                     :model/provider :deepseek
                     :model/context-length 64000
                     :model/capabilities #{:chat :streaming :tools}}
    "deepseek-reasoner" {:model/id "deepseek-reasoner"
                         :model/provider :deepseek
                         :model/context-length 64000
                         :model/capabilities #{:chat :streaming :tools :reasoning}}}))

;; ---------------------------------------------------------------------------
;; API
;; ---------------------------------------------------------------------------

(defn register-model
  "Register a model entry in the catalog."
  [model-id entry]
  (swap! catalog assoc model-id entry))

(defn get-model
  "Look up a model by exact id."
  [model-id]
  (get @catalog model-id))

(defn list-models
  "List all model ids in the catalog."
  []
  (keys @catalog))

(defn models-by-provider
  "Return all model entries for a given provider keyword."
  [provider]
  (->> @catalog
       vals
       (filter #(= (:model/provider %) provider))))

(defn model-capable?
  "Check if a model supports a given capability keyword."
  [model-id capability]
  (when-let [m (get-model model-id)]
    (contains? (:model/capabilities m) capability)))

(defn context-length
  "Get context length for a model, or nil if unknown."
  [model-id]
  (:model/context-length (get-model model-id)))

(defn resolve-model
  "Fuzzy-match a model name against the catalog.
   Tries exact match, then provider-prefixed match, then substring."
  [model-name]
  (or (get-model model-name)
      (let [without-prefix (second (re-find #"^[^/]+/(.+)$" model-name))]
        (when without-prefix
          (get-model without-prefix)))
      (->> @catalog
           vals
           (filter #(str/includes? model-name (:model/id %)))
           first)))
