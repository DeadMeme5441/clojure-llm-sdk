(ns llm.sdk.request-test
  "Coverage for the request preprocessor: drop+warn for
   canonical fields the provider doesn't support."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [llm.sdk.provider :as provider]
            [llm.sdk.request :as request]
            [llm.sdk.transport :as transport]))

(defn- with-captured-warn
  "Run f under a :warn-fn that collects messages into the given atom."
  [warnings f]
  (binding [request/*warn-fn* (fn [msg] (swap! warnings conj msg))]
    (f)))

;; ---------------------------------------------------------------------------
;; Opt-in semantics
;; ---------------------------------------------------------------------------

(deftest test-profile-without-supported-params-passes-through
  (testing "no :profile/supported-params on profile = no drop+warn"
    (let [warnings (atom [])
          profile {:profile/id :anything-goes}
          req {:request/model "m"
               :request/temperature 0.7
               :request/tools [{:type :function :function {:name "x"}}]
               :request/reasoning {:enabled true}}
          out (with-captured-warn warnings
                #(request/apply-supported-params profile req))]
      (is (= req out))
      (is (empty? @warnings)))))

;; ---------------------------------------------------------------------------
;; Drop+warn when explicit set is present
;; ---------------------------------------------------------------------------

(deftest test-drops-and-warns-on-unsupported-fields
  (let [warnings (atom [])
        profile {:profile/id :tight-provider
                 :profile/supported-params #{:request/temperature
                                             :request/max-tokens}}
        req {:request/model "m"
             :request/messages []
             :request/temperature 0.7
             :request/max-tokens 200
             :request/tools [{:type :function :function {:name "x"}}]
             :request/reasoning {:enabled true}}
        out (with-captured-warn warnings
              #(request/apply-supported-params profile req))]
    (testing "supported fields stay"
      (is (= 0.7 (:request/temperature out)))
      (is (= 200 (:request/max-tokens out))))
    (testing "unsupported fields removed"
      (is (nil? (:request/tools out)))
      (is (nil? (:request/reasoning out))))
    (testing "warning emitted once with both dropped fields"
      (is (= 1 (count @warnings)))
      (let [msg (first @warnings)]
        (is (str/includes? msg "tight-provider"))
        (is (str/includes? msg ":request/tools"))
        (is (str/includes? msg ":request/reasoning"))))))

(deftest test-supports-all-droppable-fields-no-warning
  (testing "profile with the full droppable set as :supported-params drops nothing"
    (let [warnings (atom [])
          profile {:profile/id :open-provider
                   :profile/supported-params request/droppable-canonical-fields}
          req {:request/model "m"
               :request/temperature 0.5
               :request/tools []
               :request/reasoning {:enabled true}}
          out (with-captured-warn warnings
                #(request/apply-supported-params profile req))]
      (is (= req out))
      (is (empty? @warnings)))))

(deftest test-non-droppable-fields-never-stripped
  (testing ":request/model :request/messages :request/metadata :request/provider-options pass through regardless"
    (let [warnings (atom [])
          profile {:profile/id :minimal
                   :profile/supported-params #{}} ; nothing is supported
          req {:request/model "must-stay"
               :request/messages [{:message/role :user :message/content "Hi"}]
               :request/metadata {:run-id "abc"}
               :request/provider-options {:foo :bar}
               :request/tools []}                  ; this is droppable
          out (with-captured-warn warnings
                #(request/apply-supported-params profile req))]
      (is (= "must-stay" (:request/model out)))
      (is (= 1 (count (:request/messages out))))
      (is (= {:run-id "abc"} (:request/metadata out)))
      (is (= {:foo :bar} (:request/provider-options out)))
      (is (nil? (:request/tools out)))
      (is (= 1 (count @warnings))))))

;; ---------------------------------------------------------------------------
;; Perplexity Agent supported-parameter preprocessing
;; ---------------------------------------------------------------------------

(deftest test-perplexity-tools-survive-preprocessing-and-build
  (let [warnings (atom [])
        profile (provider/get-provider :perplexity)
        canonical-tool
        {:type :function
         :function {:name "lookup"
                    :description "Look up a record"
                    :parameters {:type "object"
                                 :properties {:id {:type "string"}}
                                 :required ["id"]}
                    :strict true}}
        canonical-request
        {:request/model "perplexity/sonar"
         :request/messages
         [{:message/role :user :message/content "Look up record 42."}]
         :request/tools [canonical-tool]
         :request/provider-options {:perplexity {:web-search false}}}
        processed (with-captured-warn
                    warnings
                    #(request/apply-supported-params profile canonical-request))
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub-token")]
                (transport/build-request
                 ((:profile/transport-constructor profile))
                 profile
                 processed))]
    (is (empty? @warnings))
    (is (= [canonical-tool] (:request/tools processed)))
    (is (= [{:type "function"
             :name "lookup"
             :description "Look up a record"
             :parameters {:type "object"
                          :properties {:id {:type "string"}}
                          :required ["id"]}
             :strict true}]
           (get-in built [:body :tools])))))
