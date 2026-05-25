(ns llm.sdk-test
  "Tests for the public llm.sdk surface — specifically the
   registry-backed list-models / model-capabilities / model-info /
   refresh-models! API."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [llm.sdk :as sdk]
            [llm.sdk.http :as http]
            [llm.sdk.models :as models]
            [llm.sdk.models-dev :as mdev]
            [llm.sdk.registry :as registry]))

(defn- temp-dir ^java.io.File []
  (let [d (java.io.File/createTempFile "sdk-test" "")]
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

(deftest complete-validates-canonical-request-before-network
  (let [ex (try
             (sdk/complete :openai {:request/model "gpt-4o-mini"})
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :schema/invalid-request
           (get-in (ex-data ex) [:error/type])))))

(deftest complete-streaming-classifies-http-errors
  (with-redefs [http/sse-response
                (fn [_]
                  {:status 401
                   :headers {}
                   :body {:error {:message "Invalid API key"
                                  :type "invalid_request_error"}}})]
    (let [ex (try
               (sdk/complete :openai
                             {:request/model "gpt-4o-mini"
                              :request/messages [{:message/role :user
                                                  :message/content "hi"}]}
                             :stream? true)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= 401 (:status (ex-data ex))))
      (is (= :auth (get-in (ex-data ex) [:error :error/reason]))))))

;; ---------------------------------------------------------------------------
;; list-models
;; ---------------------------------------------------------------------------

(deftest list-models-no-arg-returns-distinct-ids
  (offline
   (fn []
     (let [ms (sdk/list-models)]
       (is (> (count ms) 30))
       (is (= (count ms) (count (set ms))))
       (is (some #(= "gpt-4o" %) ms))
       (is (some #(re-find #"claude" %) ms))))))

(deftest list-models-with-provider-returns-entries
  (offline
   (fn []
     (let [openai-models (sdk/list-models :openai)
           anthropic-models (sdk/list-models :anthropic)]
       (is (every? #(= :openai (:model/provider %)) openai-models))
       (is (every? #(= :anthropic (:model/provider %)) anthropic-models))
       (is (some #(= "gpt-4o" (:model/id %)) openai-models))))))

;; ---------------------------------------------------------------------------
;; model-capabilities + model-context-length + model-info
;; ---------------------------------------------------------------------------

(deftest model-capabilities-includes-tools-for-gpt4o
  (offline
   (fn []
     (let [caps (sdk/model-capabilities "gpt-4o")]
       (is (set? caps))
       (is (contains? caps :tools))))))

(deftest model-capabilities-provider-aware-form
  (offline
   (fn []
     (let [caps (sdk/model-capabilities :openai "gpt-4o")]
       (is (contains? caps :tools))))))

(deftest model-context-length-returns-int
  (offline
   (fn []
     (is (pos? (sdk/model-context-length "gpt-4o")))
     (is (pos? (sdk/model-context-length :anthropic "claude-opus-4-7"))))))

(deftest model-info-includes-cost-source-context
  (offline
   (fn []
     (let [m (sdk/model-info :openai "gpt-4o")]
       (is (some? m))
       (is (pos? (:model/context-length m)))
       (is (pos? (get-in m [:model/cost :input-per-million])))
       (is (contains? #{:models-dev :litellm-snapshot :bundled-snapshot}
                      (:model/source m))
           "no live refresh, no override → an offline tier source")))))

;; ---------------------------------------------------------------------------
;; refresh-models! — live tier population
;; ---------------------------------------------------------------------------

(deftest refresh-models-single-provider
  (with-redefs [models/fetch-models
                (fn [pid]
                  [{:model/id "gpt-new-2099" :model/provider pid
                    :model/source :live-models-api}])]
    (let [result (sdk/refresh-models! :provider :openai)]
      (is (= {:count 1} (:openai result)))
      (let [m (sdk/model-info :openai "gpt-new-2099")]
        (is (some? m))
        (is (= :live-models-api (:model/source m)))))))

(deftest refresh-models-all-providers
  (with-redefs [models/fetch-models
                (fn [pid]
                  (if (#{:codex :bedrock :fake} pid)
                    (throw (ex-info "no /models" {:provider pid :error :unsupported}))
                    [{:model/id (str "stub-" (name pid))
                      :model/provider pid
                      :model/source :live-models-api}]))]
    (let [result (sdk/refresh-models!)
          successes (count (filter (fn [[_ v]] (:count v)) result))]
      (is (>= successes 5))
      (testing "live entries land in the registry"
        (is (some? (sdk/model-info :openai "stub-openai")))
        (is (some? (sdk/model-info :anthropic "stub-anthropic")))))))

;; ---------------------------------------------------------------------------
;; register-model-info — override roundtrip
;; ---------------------------------------------------------------------------

(deftest register-model-info-roundtrip
  (offline
   (fn []
     (sdk/register-model-info :custom-llm "magic-9000"
                              {:model/context-length 9000
                               :model/capabilities #{:chat :tools}
                               :model/cost {:input-per-million 0.5
                                            :output-per-million 2.0}})
     (let [m (sdk/model-info :custom-llm "magic-9000")]
       (is (= 9000 (:model/context-length m)))
       (is (contains? (:model/capabilities m) :tools)))
     (let [r (sdk/estimate-cost :custom-llm "magic-9000"
                                {:usage/input-tokens 2000
                                 :usage/output-tokens 1000})]
       (is (= :actual (:cost/status r)))))))

;; ---------------------------------------------------------------------------
;; estimate-cost — works across providers without manual registration
;; ---------------------------------------------------------------------------

(deftest estimate-cost-covers-every-snapshot-provider
  (offline
   (fn []
     (doseq [[pid mid] [[:openai "gpt-4o"]
                        [:anthropic "claude-opus-4-7"]
                        [:gemini-native "gemini-2.5-pro"]
                        [:deepseek "deepseek-chat"]]]
       (let [r (sdk/estimate-cost pid mid {:usage/input-tokens 1000
                                           :usage/output-tokens 500})]
         (is (= :actual (:cost/status r))
             (str "expected pricing for " pid "/" mid))
         (is (pos? (:cost/amount-usd r))))))))
