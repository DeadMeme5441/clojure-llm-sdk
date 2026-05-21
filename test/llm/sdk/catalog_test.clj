(ns llm.sdk.catalog-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk.catalog :as catalog]
            [llm.sdk.http :as http]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.registry :as registry]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "catalog-test" "")]
    (.delete d) (.mkdirs d) d))

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
  (with-redefs [http/request (fn [_] {:status 500 :body {:error "offline"}})]
    (f)))

;; ---------------------------------------------------------------------------
;; Single-arg lookups — backwards-compat with the old hardcoded catalog
;; ---------------------------------------------------------------------------

(deftest get-model-by-id-finds-known
  (offline
   (fn []
     (let [m (catalog/get-model "gpt-4o")]
       (is (some? m))
       (is (= "gpt-4o" (:model/id m)))
       (is (= :openai (:model/provider m)))))))

(deftest get-model-by-id-returns-nil-for-unknown
  (offline
   (fn []
     (is (nil? (catalog/get-model "this-model-does-not-exist"))))))

(deftest get-model-two-arg-provider-aware
  (offline
   (fn []
     (let [m (catalog/get-model :anthropic "claude-opus-4-7")]
       (is (some? m))
       (is (= "claude-opus-4-7" (:model/id m)))
       (is (= :anthropic (:model/provider m)))))))

(deftest context-length-returns-int
  (offline
   (fn []
     (is (pos? (catalog/context-length "gpt-4o")))
     (is (pos? (catalog/context-length :anthropic "claude-opus-4-7")))
     (is (nil? (catalog/context-length "unknown"))))))

(deftest max-output-tokens-returns-int
  (offline
   (fn []
     (let [m (catalog/max-output-tokens "gpt-4o")]
       (is (or (pos? m) (nil? m)))))))

;; ---------------------------------------------------------------------------
;; Capability checks
;; ---------------------------------------------------------------------------

(deftest model-capable-tools
  (offline
   (fn []
     (is (true? (catalog/model-capable? "gpt-4o" :tools))
         "models.dev marks gpt-4o tool_call=true"))))

(deftest model-capable-unknown-cap-is-false
  (offline
   (fn []
     (is (false? (catalog/model-capable? "gpt-4o" :flying-cars))))))

(deftest model-capable-unknown-model-is-false
  (offline
   (fn []
     (is (false? (catalog/model-capable? "ghost-model" :tools))))))

;; ---------------------------------------------------------------------------
;; Listing
;; ---------------------------------------------------------------------------

(deftest list-models-returns-many-distinct-ids
  (offline
   (fn []
     (let [ms (catalog/list-models)]
       (is (> (count ms) 20))
       (is (= (count ms) (count (set ms))) "ids are distinct")
       (is (some #(= "gpt-4o" %) ms))
       (is (some #(re-find #"claude" %) ms))))))

(deftest models-by-provider-uses-sdk-keyword
  (offline
   (fn []
     (let [openai (catalog/models-by-provider :openai)
           anthropic (catalog/models-by-provider :anthropic)]
       (is (every? #(= :openai (:model/provider %)) openai))
       (is (every? #(= :anthropic (:model/provider %)) anthropic))
       (is (> (count openai) 5))
       (is (> (count anthropic) 3))))))

;; ---------------------------------------------------------------------------
;; Fuzzy resolve
;; ---------------------------------------------------------------------------

(deftest resolve-model-exact
  (offline
   (fn []
     (let [m (catalog/resolve-model "gpt-4o")]
       (is (some? m))
       (is (= "gpt-4o" (:model/id m)))))))

(deftest resolve-model-strips-provider-prefix
  (offline
   (fn []
     (let [m (catalog/resolve-model "anthropic/claude-opus-4-7")]
       (is (some? m))
       (is (= "claude-opus-4-7" (:model/id m)))))))

;; ---------------------------------------------------------------------------
;; register-model — override roundtrip
;; ---------------------------------------------------------------------------

(deftest register-model-via-three-arg
  (offline
   (fn []
     (catalog/register-model :openai "gpt-fake"
                             {:model/context-length 99
                              :model/capabilities #{:chat :vision}})
     (let [m (catalog/get-model "gpt-fake")]
       (is (= 99 (:model/context-length m)))
       (is (= :override (:model/source m))))
     (is (true? (catalog/model-capable? :openai "gpt-fake" :vision))))))

(deftest register-model-via-two-arg-with-provider-key
  (offline
   (fn []
     (catalog/register-model "gpt-faker"
                             {:model/provider :openai
                              :model/context-length 33
                              :model/capabilities #{:chat}})
     (let [m (catalog/get-model :openai "gpt-faker")]
       (is (= 33 (:model/context-length m)))))))

(deftest register-model-two-arg-without-provider-throws
  (offline
   (fn []
     (is (thrown? clojure.lang.ExceptionInfo
                  (catalog/register-model "no-provider" {:model/context-length 1}))))))
