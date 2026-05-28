(ns llm.sdk.providers.openai.audio
  "OpenAI audio provider family namespace."
  (:require [llm.sdk.providers.openai.speak :as speak]
            [llm.sdk.providers.openai.transcribe :as transcribe]))

(def make-speak-transport speak/make-transport)
(def make-transcribe-transport transcribe/make-transport)
