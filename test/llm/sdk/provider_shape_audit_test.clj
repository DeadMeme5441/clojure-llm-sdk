(ns llm.sdk.provider-shape-audit-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [llm.sdk :as sdk]))

(deftest audit-matrix-covers-every-registered-provider
  (let [doc (slurp "doc/provider-shape-audit.md")
        missing (->> (sdk/list-providers)
                     sort
                     (remove #(str/includes? doc (str "`" (pr-str %) "`")))
                     vec)]
    (is (= [] missing)
        "Every registered provider id must appear in doc/provider-shape-audit.md.")))

(deftest litellm-parity-ledger-covers-every-registered-provider
  (let [doc (slurp "doc/litellm-provider-parity.md")
        missing (->> (sdk/list-providers)
                     sort
                     (remove #(str/includes? doc (str "`" (pr-str %) "`")))
                     vec)]
    (is (= [] missing)
        "Every registered provider id must appear in doc/litellm-provider-parity.md.")))
