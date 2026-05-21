(ns llm.sdk.providers.fake-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.provider :as provider]
            [llm.sdk.transport :as transport]
            [llm.sdk.providers.fake :as fake]))

(deftest test-fake-transport-request
  (let [t (fake/make-fake-transport)
        profile (provider/get-provider :fake)
        req {:request/model "fake-model"
             :request/messages [{:message/role :user :message/content "Hi"}]}
        built (transport/build-request t profile req)]
    (is (= :post (:method built)))
    (is (= "https://fake.local/fake/chat" (:url built)))))

(deftest test-fake-transport-response
  (let [t (fake/make-fake-transport)
        profile (provider/get-provider :fake)
        resp (transport/parse-response t profile {})]
    (is (= :fake (:response/provider resp)))
    (is (= "fake-model" (:response/model resp)))
    (is (= :stop (:response/finish-reason resp)))))

(deftest test-fake-transport-custom-response
  (let [t (fake/make-fake-transport
           :response-fn (fn [_]
                          {:response/provider :fake
                           :response/model "custom"
                           :response/parts [{:part/type :text :text "Custom"}]
                           :response/finish-reason :length}))
        profile (provider/get-provider :fake)
        resp (transport/parse-response t profile {})]
    (is (= "custom" (:response/model resp)))
    (is (= :length (:response/finish-reason resp)))))
