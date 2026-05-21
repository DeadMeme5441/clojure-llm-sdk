(ns llm.sdk.models-dev-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.http :as http]
            [llm.sdk.models :as models]
            [llm.sdk.models-dev :as mdev]))

;; ---------------------------------------------------------------------------
;; Test isolation — sandboxed disk cache + reset in-mem cache
;; ---------------------------------------------------------------------------

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "clojure-llm-sdk-test" "")]
    (.delete d)
    (.mkdirs d)
    d))

(def ^:dynamic *tmp-cache-dir* nil)

(defn with-sandboxed-cache [f]
  (let [d (temp-dir)]
    (binding [mdev/*cache-dir* (.getPath d)
              *tmp-cache-dir* d]
      (mdev/reset-cache!)
      (try
        (f)
        (finally
          ;; best-effort cleanup
          (doseq [c (.listFiles d)] (.delete c))
          (.delete d))))))

(use-fixtures :each with-sandboxed-cache)

(defn- offline
  "Run body with the network mocked to always fail (status 500). Forces
   the cache hierarchy to fall through to bundled snapshot."
  [f]
  (with-redefs [http/request (fn [_] {:status 500 :body {:error "offline"}})]
    (f)))

;; ---------------------------------------------------------------------------
;; Bundled snapshot
;; ---------------------------------------------------------------------------

(deftest bundled-snapshot-loads
  (offline
   (fn []
     (let [{:keys [data source]} (mdev/fetch-all)]
       (is (= :bundled source) "no network + no disk → bundled snapshot")
       (is (map? data))
       (testing "snapshot has the providers we mapped"
         (is (contains? data :openai))
         (is (contains? data :anthropic))
         (is (contains? data :google))
         (is (contains? data :openrouter)))))))

;; ---------------------------------------------------------------------------
;; Normalization
;; ---------------------------------------------------------------------------

(deftest normalize-entry-maps-all-fields
  (let [raw {:id "gpt-4o"
             :name "GPT-4o"
             :family "gpt"
             :attachment true
             :reasoning false
             :tool_call true
             :structured_output true
             :temperature true
             :modalities {:input ["text" "image" "pdf"] :output ["text"]}
             :limit {:context 128000 :output 16384}
             :cost {:input 2.5 :output 10 :cache_read 1.25}}
        e (mdev/normalize-entry :openai "gpt-4o" raw nil)]
    (is (= "gpt-4o" (:model/id e)))
    (is (= :openai (:model/provider e)))
    (is (= :models-dev (:model/source e)))
    (is (= "gpt" (:model/family e)))
    (is (= "GPT-4o" (:model/display-name e)))
    (is (= 128000 (:model/context-length e)))
    (is (= 16384 (:model/max-output-tokens e)))
    (testing "capabilities include the boolean + modality flags"
      (let [caps (:model/capabilities e)]
        (is (contains? caps :chat))
        (is (contains? caps :streaming))
        (is (contains? caps :tools))
        (is (contains? caps :vision))
        (is (contains? caps :pdf))
        (is (contains? caps :json-schema))
        (is (not (contains? caps :reasoning)))))
    (testing "cost map normalized to our keys, per-million floats"
      (is (= 2.5 (get-in e [:model/cost :input-per-million])))
      (is (= 10.0 (get-in e [:model/cost :output-per-million])))
      (is (= 1.25 (get-in e [:model/cost :cache-read-per-million])))
      (is (nil? (get-in e [:model/cost :cache-write-per-million]))))))

(deftest normalize-entry-flags-reasoning-when-set
  (let [raw {:id "claude-opus-4-1"
             :reasoning true
             :tool_call true
             :modalities {:input ["text"] :output ["text"]}
             :limit {:context 200000}
             :cost {:input 15 :output 75 :cache_read 1.5 :cache_write 18.75}}
        e (mdev/normalize-entry :anthropic "claude-opus-4-1" raw nil)]
    (is (contains? (:model/capabilities e) :reasoning))
    (is (= 18.75 (get-in e [:model/cost :cache-write-per-million])))))

(deftest normalize-entry-flags-embedding-models
  (let [raw {:id "text-embedding-3-small"
             :modalities {:input ["text"] :output ["text"]}
             :limit {:context 8192}}
        e (mdev/normalize-entry :openai "text-embedding-3-small" raw nil)]
    (is (= #{:embedding} (:model/capabilities e))
        "embedding-named models drop :chat/:streaming")))

(deftest normalize-entry-passes-malli-schema
  (let [raw {:id "gpt-4o-mini"
             :name "GPT-4o mini"
             :family "gpt"
             :tool_call true
             :modalities {:input ["text"] :output ["text"]}
             :limit {:context 128000 :output 16384}
             :cost {:input 0.15 :output 0.6 :cache_read 0.075}}
        e (mdev/normalize-entry :openai "gpt-4o-mini" raw nil)]
    (is (models/validate-model-entry e)
        (str "Failed schema: " (pr-str e) "\n"
             "Explain: " (pr-str (models/explain-model-entry e))))))

;; ---------------------------------------------------------------------------
;; Lookup + list-models against the bundled snapshot
;; ---------------------------------------------------------------------------

(deftest lookup-known-openai-model-from-snapshot
  (offline
   (fn []
     (let [e (mdev/lookup :openai "gpt-4o")]
       (is (some? e))
       (is (= "gpt-4o" (:model/id e)))
       (is (= :openai (:model/provider e)))
       (is (= :models-dev (:model/source e)))
       (is (pos? (:model/context-length e)))
       (is (pos? (get-in e [:model/cost :input-per-million])))))))

(deftest lookup-unknown-model-returns-nil
  (offline
   (fn []
     (is (nil? (mdev/lookup :openai "this-model-does-not-exist-anywhere"))))))

(deftest lookup-honours-codex-aliasing
  (offline
   (fn []
     (testing ":codex routes through OpenAI's catalog"
       (is (some? (mdev/lookup :codex "gpt-4o")))
       (is (= :codex (:model/provider (mdev/lookup :codex "gpt-4o"))))))))

(deftest list-models-returns-many
  (offline
   (fn []
     (let [es (mdev/list-models :openai)]
       (is (> (count es) 10))
       (is (every? #(= :openai (:model/provider %)) es))
       (is (every? #(= :models-dev (:model/source %)) es))
       (is (every? models/validate-model-entry es))))))

(deftest list-models-anthropic-includes-claude-family
  (offline
   (fn []
     (let [es (mdev/list-models :anthropic)
           ids (set (map :model/id es))]
       (is (some #(re-find #"claude" %) ids))))))

(deftest known-providers-reports-snapshot-coverage
  (offline
   (fn []
     (let [providers (mdev/known-providers)]
       (is (contains? providers :openai))
       (is (contains? providers :anthropic))
       (is (contains? providers :gemini-native))
       (is (contains? providers :openrouter))))))

;; ---------------------------------------------------------------------------
;; Cache hierarchy
;; ---------------------------------------------------------------------------

(deftest fresh-in-mem-cache-skips-network
  (let [net-calls (atom 0)
        canned-tree {:openai {:id "openai"
                              :models {:fake-model {:id "fake-model"
                                                    :limit {:context 1234}}}}}]
    (with-redefs [http/request
                  (fn [_]
                    (swap! net-calls inc)
                    {:status 200 :body canned-tree})]
      (let [r1 (mdev/fetch-all)
            r2 (mdev/fetch-all)
            r3 (mdev/fetch-all)]
        (is (= :network (:source r1)))
        (is (= :network (:source r2)) "served from in-mem the 2nd time, retains source tag")
        (is (= :network (:source r3)))
        (is (= 1 @net-calls) "network was hit exactly once")))))

(deftest refresh-force-bypasses-in-mem
  (let [net-calls (atom 0)
        canned-tree {:openai {:models {}}}]
    (with-redefs [http/request
                  (fn [_]
                    (swap! net-calls inc)
                    {:status 200 :body canned-tree})]
      (mdev/fetch-all)
      (mdev/refresh!)
      (mdev/refresh!)
      (is (= 3 @net-calls)
          "first fetch-all + 2 forced refreshes = 3 network hits"))))

(deftest disk-cache-write-on-network-success
  (let [canned-tree {:openai {:models {}}}]
    (with-redefs [http/request
                  (fn [_] {:status 200 :body canned-tree})]
      (mdev/fetch-all)
      (is (.exists (io/file *tmp-cache-dir* "models-dev-cache.json"))
          "network success persists data to disk cache"))))

(deftest network-failure-falls-back-to-bundled
  (offline
   (fn []
     (let [{:keys [source data]} (mdev/fetch-all)]
       (is (= :bundled source))
       (is (map? data))
       (is (seq data))))))

(deftest cache-expiry-triggers-refetch
  (let [hits (atom 0)]
    (with-redefs [http/request
                  (fn [_]
                    (swap! hits inc)
                    {:status 200 :body {:openai {:models {}}}})]
      ;; First fetch populates in-mem
      (mdev/fetch-all)
      (is (= 1 @hits))
      ;; Force expiry via TTL=0 — next call should re-fetch
      (binding [mdev/*ttl-ms* 0]
        (mdev/fetch-all)
        (is (= 2 @hits)
            "TTL=0 expires in-mem AND disk → second network hit")))))

(deftest network-failure-with-stale-disk-uses-disk
  (let [stale-tree {:openai {:models {:stale-only-model {:id "stale-only-model"
                                                          :limit {:context 9999}}}}}]
    ;; Seed the disk cache directly, then run with network mocked to fail
    (binding [mdev/*ttl-ms* 0] ; immediately stale
      (let [f (io/file *tmp-cache-dir* "models-dev-cache.json")]
        (.mkdirs (.getParentFile f))
        (spit f (json/generate-string stale-tree)))
      (with-redefs [http/request (fn [_] (throw (ex-info "boom" {})))]
        (let [{:keys [source data]} (mdev/fetch-all)]
          (is (= :disk source) "stale disk preferred to bundled when present")
          (is (some? (-> data :openai :models :stale-only-model))))))))
