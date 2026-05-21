(ns llm.sdk.registry-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.http :as http]
            [llm.sdk.models :as models]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.registry :as registry]))

;; ---------------------------------------------------------------------------
;; Sandbox each test: empty live/override stores, isolated mdev cache dir
;; ---------------------------------------------------------------------------

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "registry-test" "")]
    (.delete d)
    (.mkdirs d)
    d))

(defn isolate [f]
  (let [d (temp-dir)]
    (binding [mdev/*cache-dir* (.getPath d)]
      (mdev/reset-cache!)
      (registry/clear-live!)
      (registry/clear-overrides!)
      (try (f)
           (finally
             (doseq [c (.listFiles d)] (.delete c))
             (.delete d))))))

(use-fixtures :each isolate)

(defn- offline [f]
  ;; Block network — forces mdev to use bundled snapshot, forces
  ;; registry/refresh! to error if it tries.
  (with-redefs [http/request (fn [_] {:status 500 :body {:error "offline"}})]
    (f)))

;; ---------------------------------------------------------------------------
;; merge-entries semantics
;; ---------------------------------------------------------------------------

(deftest merge-entries-fills-fields-from-lower-tiers
  (let [mdev-entry {:model/id "gpt-4o"
                    :model/provider :openai
                    :model/source :models-dev
                    :model/context-length 128000
                    :model/cost {:input-per-million 2.5 :output-per-million 10.0}}
        live-entry {:model/id "gpt-4o"
                    :model/provider :openai
                    :model/source :live-models-api
                    :model/source-url "https://api.openai.com/v1/models"
                    :model/fetched-at (java.util.Date.)}
        merged (registry/merge-entries mdev-entry live-entry)]
    (is (= 128000 (:model/context-length merged))
        "context-length carries from models.dev")
    (is (= 2.5 (get-in merged [:model/cost :input-per-million]))
        "pricing carries from models.dev")
    (is (= "https://api.openai.com/v1/models" (:model/source-url merged))
        "source-url carries from live tier")
    (is (= :live-models-api (:model/source merged))
        "highest-precedence non-nil source tag wins")))

(deftest merge-entries-merges-cost-key-by-key
  (let [mdev-entry {:model/id "claude" :model/source :models-dev
                    :model/cost {:input-per-million 3.0 :output-per-million 15.0}}
        live-entry {:model/id "claude" :model/source :live-models-api
                    :model/cost {:cache-read-per-million 0.3 :cache-write-per-million 3.75}}
        merged (registry/merge-entries mdev-entry live-entry)]
    (is (= 3.0 (get-in merged [:model/cost :input-per-million])))
    (is (= 15.0 (get-in merged [:model/cost :output-per-million])))
    (is (= 0.3 (get-in merged [:model/cost :cache-read-per-million])))
    (is (= 3.75 (get-in merged [:model/cost :cache-write-per-million])))))

(deftest merge-entries-tolerates-nil
  (is (nil? (registry/merge-entries nil nil nil)))
  (let [only-mdev {:model/id "x" :model/source :models-dev :model/context-length 8}
        merged (registry/merge-entries nil only-mdev nil)]
    (is (= 8 (:model/context-length merged)))
    (is (= :models-dev (:model/source merged)))))

(deftest merge-entries-override-takes-top-precedence
  (let [mdev-entry {:model/id "x" :model/source :models-dev :model/context-length 100
                    :model/cost {:input-per-million 1.0}}
        live-entry {:model/id "x" :model/source :live-models-api}
        override {:model/id "x" :model/source :override :model/context-length 9999
                  :model/cost {:input-per-million 0.01}}
        merged (registry/merge-entries mdev-entry live-entry override)]
    (is (= 9999 (:model/context-length merged)))
    (is (= 0.01 (get-in merged [:model/cost :input-per-million])))
    (is (= :override (:model/source merged)))))

;; ---------------------------------------------------------------------------
;; Lookup precedence — uses bundled snapshot as lower tier
;; ---------------------------------------------------------------------------

(deftest lookup-from-offline-tier
  (offline
   (fn []
     (let [e (registry/lookup :openai "gpt-4o")]
       (is (some? e))
       (is (= :openai (:model/provider e)))
       ;; gpt-4o lives in both models.dev and the LiteLLM snapshot
       ;; offline tiers; either source tag is acceptable as long as
       ;; the entry didn't come from a live fetch or override.
       (is (contains? #{:models-dev :litellm-snapshot :bundled-snapshot}
                      (:model/source e))
           "no live, no override → an offline tier tag")
       (is (pos? (:model/context-length e)))))))

(deftest lookup-unknown-everywhere-returns-nil
  (offline
   (fn []
     (is (nil? (registry/lookup :openai "this-does-not-exist"))))))

(deftest lookup-live-overlays-models-dev
  (offline
   (fn []
     ;; Drive the live tier through refresh! with a mocked fetch-models.
     (with-redefs [models/fetch-models
                   (fn [pid]
                     [{:model/id "gpt-4o"
                       :model/provider pid
                       :model/source :live-models-api
                       :model/source-url "https://api.openai.com/v1/models"
                       :model/fetched-at (java.util.Date.)}])]
       (registry/refresh! :openai)
       (let [e (registry/lookup :openai "gpt-4o")]
         (is (= :live-models-api (:model/source e))
             "live tier wins over mdev for source tag")
         (is (pos? (:model/context-length e))
             "context-length still flows up from models.dev")
         (is (= "https://api.openai.com/v1/models" (:model/source-url e))))))))

(deftest lookup-override-wins-over-live-and-mdev
  (offline
   (fn []
     (with-redefs [models/fetch-models
                   (fn [pid]
                     [{:model/id "gpt-4o" :model/provider pid
                       :model/source :live-models-api
                       :model/context-length 4242}])]
       (registry/refresh! :openai)
       (registry/register-entry! :openai "gpt-4o"
                                 {:model/cost {:input-per-million 0.001}
                                  :model/context-length 1000000})
       (let [e (registry/lookup :openai "gpt-4o")]
         (is (= :override (:model/source e)))
         (is (= 1000000 (:model/context-length e)))
         (is (= 0.001 (get-in e [:model/cost :input-per-million]))))))))

;; ---------------------------------------------------------------------------
;; refresh! semantics
;; ---------------------------------------------------------------------------

(deftest refresh-unsupported-provider-returns-empty
  (offline
   (fn []
     (is (= [] (registry/refresh! :codex)) "no /models endpoint, no-op")
     (is (= [] (registry/refresh! :bedrock))))))

(deftest refresh-populates-live-store
  (with-redefs [models/fetch-models
                (fn [pid]
                  [{:model/id "claude-haiku-4-5" :model/provider pid
                    :model/source :live-models-api}
                   {:model/id "claude-sonnet-4-6" :model/provider pid
                    :model/source :live-models-api}])]
    (let [entries (registry/refresh! :anthropic)]
      (is (= 2 (count entries)))
      (let [snap (registry/snapshot)]
        (is (= 2 (:live-entries snap)))))))

(deftest refresh-all-aggregates-counts
  (offline
   (fn []
     (with-redefs [models/fetch-models
                   (fn [pid]
                     (case pid
                       :openai [{:model/id "gpt-4o" :model/provider pid
                                 :model/source :live-models-api}]
                       :anthropic [{:model/id "claude-haiku-4-5" :model/provider pid
                                    :model/source :live-models-api}]
                       (throw (ex-info "no creds" {:provider pid}))))]
       (let [result (registry/refresh-all!)]
         (is (= {:count 1} (:openai result)))
         (is (= {:count 1} (:anthropic result)))
         (is (some? (:error (:gemini-native result))) "failures don't abort other providers"))))))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(deftest list-by-provider-includes-mdev-and-live-union
  (offline
   (fn []
     (with-redefs [models/fetch-models
                   (fn [pid]
                     [{:model/id "novel-2099-only-in-live"
                       :model/provider pid
                       :model/source :live-models-api}])]
       (registry/refresh! :openai))
     (let [openai-models (registry/list-by-provider :openai)
           ids (set (map :model/id openai-models))]
       (is (contains? ids "gpt-4o") "models.dev contributes gpt-4o")
       (is (contains? ids "novel-2099-only-in-live")
           "live tier contributes its own")
       (is (every? #(= :openai (:model/provider %)) openai-models))))))

(deftest list-by-provider-merges-tiers-on-shared-id
  (offline
   (fn []
     (with-redefs [models/fetch-models
                   (fn [pid]
                     [{:model/id "gpt-4o" :model/provider pid
                       :model/source :live-models-api
                       :model/source-url "https://api.openai.com/v1/models"}])]
       (registry/refresh! :openai))
     (let [gpt4o (first (filter #(= "gpt-4o" (:model/id %))
                                (registry/list-by-provider :openai)))]
       (is (= :live-models-api (:model/source gpt4o)))
       (is (pos? (:model/context-length gpt4o)))
       (is (= "https://api.openai.com/v1/models" (:model/source-url gpt4o)))))))

(deftest list-all-spans-providers
  (offline
   (fn []
     (let [all (registry/list-all)
           providers (set (map :model/provider all))]
       (is (contains? providers :openai))
       (is (contains? providers :anthropic))
       (is (contains? providers :gemini-native))
       (is (> (count all) 50) "bundled snapshot has plenty of models")))))

(deftest known-providers-spans-tiers
  (offline
   (fn []
     (with-redefs [models/fetch-models
                   (fn [_] [])]
       (registry/refresh! :openai))
     (registry/register-entry! :custom-provider "test-model"
                               {:model/context-length 32000})
     (let [kps (registry/known-providers)]
       (is (contains? kps :openai))
       (is (contains? kps :anthropic))
       (is (contains? kps :custom-provider)
           "override-only providers surface")))))

;; ---------------------------------------------------------------------------
;; Override management
;; ---------------------------------------------------------------------------

(deftest register-entry-roundtrip
  (offline
   (fn []
     (registry/register-entry! :openai "gpt-9000"
                               {:model/context-length 1000000
                                :model/cost {:input-per-million 0.5}})
     (let [e (registry/lookup :openai "gpt-9000")]
       (is (= :override (:model/source e)))
       (is (= 1000000 (:model/context-length e)))
       (is (= 0.5 (get-in e [:model/cost :input-per-million])))
       (is (= :openai (:model/provider e)))
       (is (= "gpt-9000" (:model/id e)))))))

(deftest unregister-entry-removes-override
  (offline
   (fn []
     (registry/register-entry! :openai "ghost" {:model/context-length 1})
     (is (some? (registry/lookup :openai "ghost")))
     (registry/unregister-entry! :openai "ghost")
     (is (nil? (registry/lookup :openai "ghost"))))))
