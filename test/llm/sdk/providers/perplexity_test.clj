(ns llm.sdk.providers.perplexity-test
  "Coverage for the Perplexity adapter (T2-04) and the CitationPart /
   stream/citation schema additions.

   Live smoke lives in llm.sdk.live-perplexity-test."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [llm.sdk.schema :as schema]
            [llm.sdk.provider :as provider]
            [llm.sdk.stream :as stream]
            [llm.sdk.transport :as transport]
            [llm.sdk.usage :as usage]
            [llm.sdk.providers.perplexity :as ppx]))

(defn- load-fixture [path]
  (-> (io/resource path) slurp (json/parse-string true)))

;; ---------------------------------------------------------------------------
;; CitationPart schema
;; ---------------------------------------------------------------------------

(deftest test-citation-part-validates-against-part-schema
  (testing "minimal CitationPart (url only)"
    (is (schema/validate-part
         {:part/type :citation
          :citation/url "https://example.com"})))
  (testing "full CitationPart"
    (is (schema/validate-part
         {:part/type :citation
          :citation/url "https://example.com"
          :citation/title "Title"
          :citation/snippet "Snippet"
          :citation/text-range [10 24]
          :citation/source-id "src-1"})))
  (testing "missing :citation/url fails"
    (is (not (schema/validate-part
              {:part/type :citation})))))

(deftest test-response-with-citation-parts-validates
  (is (schema/validate-response
       {:response/provider :perplexity
        :response/model "sonar"
        :response/parts [{:part/type :text :text "Answer."}
                         {:part/type :citation
                          :citation/url "https://example.com/a"
                          :citation/title "A"}]
        :response/finish-reason :stop})))

;; ---------------------------------------------------------------------------
;; Usage schema additions
;; ---------------------------------------------------------------------------

(deftest test-usage-includes-citation-fields
  (is (schema/validate-usage
       {:usage/input-tokens 10
        :usage/output-tokens 20
        :usage/citation-tokens 256
        :usage/search-queries 1}))
  (testing "citation fields are optional"
    (is (schema/validate-usage
         {:usage/input-tokens 10 :usage/output-tokens 20}))))

(deftest test-normalize-perplexity-usage
  (let [u (usage/normalize-usage :perplexity
                                 {:prompt_tokens 12
                                  :completion_tokens 18
                                  :total_tokens 30
                                  :citation_tokens 256
                                  :num_search_queries 1})]
    (is (= 12 (:usage/input-tokens u)))
    (is (= 18 (:usage/output-tokens u)))
    (is (= 30 (:usage/total-tokens u)))
    (is (= 256 (:usage/citation-tokens u)))
    (is (= 1 (:usage/search-queries u))))
  (testing "citation fields absent on a non-Perplexity OpenAI-compat response"
    (let [u (usage/normalize-usage :openai
                                   {:prompt_tokens 10
                                    :completion_tokens 5
                                    :total_tokens 15})]
      (is (nil? (:usage/citation-tokens u)))
      (is (nil? (:usage/search-queries u))))))

;; ---------------------------------------------------------------------------
;; Profile registration
;; ---------------------------------------------------------------------------

(deftest test-perplexity-profile-registered
  (let [p (provider/get-provider :perplexity)]
    (is (some? p))
    (is (= "https://api.perplexity.ai" (:profile/base-url p)))
    (is (= ["PERPLEXITY_API_KEY"] (:profile/env-var-names p)))
    (is (= :bearer (:profile/auth-strategy p)))
    (is (fn? (:profile/transport-constructor p)))
    (is (contains? (:profile/capabilities p) :web-search))))

;; ---------------------------------------------------------------------------
;; Request building — delegates to openai-chat shape
;; ---------------------------------------------------------------------------

(deftest test-build-request-shape
  (let [t (ppx/make-transport)
        profile (provider/get-provider :perplexity)
        built (with-redefs [provider/resolve-auth-token
                            (constantly "stub-token")]
                (transport/build-request
                 t profile
                 {:request/model "sonar"
                  :request/messages [{:message/role :user
                                      :message/content "What is Clojure?"}]
                  :request/max-tokens 100}))]
    (is (= "https://api.perplexity.ai/chat/completions" (:url built)))
    (is (= "Bearer stub-token" (get-in built [:headers "Authorization"])))
    (is (= "sonar" (get-in built [:body :model])))
    (is (= 100 (get-in built [:body :max_tokens])))))

;; ---------------------------------------------------------------------------
;; Response parsing — citations and search_results
;; ---------------------------------------------------------------------------

(deftest test-parse-response-with-search-results
  (testing "search_results yields rich CitationParts (title + snippet)"
    (let [t (ppx/make-transport)
          profile (provider/get-provider :perplexity)
          raw (load-fixture "fixtures/perplexity_response.json")
          resp (transport/parse-response t profile raw)
          parts (:response/parts resp)
          citation-parts (filter #(= :citation (:part/type %)) parts)]
      (is (= :perplexity (:response/provider resp)))
      (is (= 2 (count citation-parts)))
      (is (= "Clojure Programming Language Overview"
             (:citation/title (first citation-parts))))
      (is (= "Clojure is a modern, dynamic, and functional Lisp on the JVM."
             (:citation/snippet (first citation-parts))))
      (is (= 256 (get-in resp [:response/usage :usage/citation-tokens])))
      (is (= 1 (get-in resp [:response/usage :usage/search-queries]))))))

(deftest test-parse-response-with-url-only-citations
  (testing "fallback to :citations array (URL-only) when no :search_results"
    (let [t (ppx/make-transport)
          profile (provider/get-provider :perplexity)
          raw {:id "x" :model "sonar"
               :citations ["https://example.com/a" "https://example.com/b"]
               :choices [{:message {:content "Answer."}
                          :finish_reason "stop"}]
               :usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}}
          resp (transport/parse-response t profile raw)
          citation-parts (filter #(= :citation (:part/type %)) (:response/parts resp))]
      (is (= 2 (count citation-parts)))
      (is (= "https://example.com/a" (:citation/url (first citation-parts))))
      (is (nil? (:citation/title (first citation-parts)))))))

(deftest test-parse-response-without-citations
  (testing "response without citations is just a text part"
    (let [t (ppx/make-transport)
          profile (provider/get-provider :perplexity)
          raw {:id "x" :model "sonar"
               :choices [{:message {:content "Answer."}
                          :finish_reason "stop"}]
               :usage {:prompt_tokens 5 :completion_tokens 5 :total_tokens 10}}
          resp (transport/parse-response t profile raw)]
      (is (empty? (filter #(= :citation (:part/type %)) (:response/parts resp))))
      (is (= 1 (count (filter #(= :text (:part/type %)) (:response/parts resp))))))))

;; ---------------------------------------------------------------------------
;; Stream parsing
;; ---------------------------------------------------------------------------

(deftest test-stream-content-delta
  (let [t (ppx/make-transport)
        profile (provider/get-provider :perplexity)
        line "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"
        ev (transport/parse-stream-event t profile line)]
    (is (= :stream/content-delta (:event/type ev)))
    (is (= "Hi" (:event/delta ev)))))

(deftest test-stream-final-chunk-emits-citation-usage-end
  (testing "final SSE chunk with citations, usage, and finish returns a vec of events"
    (let [t (ppx/make-transport)
          profile (provider/get-provider :perplexity)
          line (str "data: "
                    (json/generate-string
                     {:choices [{:delta {} :finish_reason "stop"}]
                      :search_results [{:url "https://example.com/a"
                                        :title "A"
                                        :snippet "Snip-a"}
                                       {:url "https://example.com/b"
                                        :title "B"
                                        :snippet "Snip-b"}]
                      :usage {:prompt_tokens 5
                              :completion_tokens 5
                              :total_tokens 10
                              :citation_tokens 50
                              :num_search_queries 1}}))
          evs (transport/parse-stream-event t profile line)]
      (is (sequential? evs) "returns a vector of events")
      (is (= 4 (count evs))
          "two citations + one usage + one end")
      (is (every? #(= :stream/citation (:event/type %)) (take 2 evs)))
      (is (= "https://example.com/a" (:citation/url (first evs))))
      (is (= "A" (:citation/title (first evs))))
      (is (= :stream/usage (:event/type (nth evs 2))))
      (is (= 50 (get-in (nth evs 2) [:usage :usage/citation-tokens])))
      (is (= :stream/end (:event/type (nth evs 3))))
      (is (= :stop (:event/finish-reason (nth evs 3)))))))

(deftest test-stream-citation-event-validates
  (let [ev {:event/type :stream/citation
            :citation/url "https://example.com"
            :citation/title "Title"
            :citation/snippet "Snippet"}]
    (is (schema/validate-stream-event ev))))

;; ---------------------------------------------------------------------------
;; events->response folds citation events into Response.parts
;; ---------------------------------------------------------------------------

(deftest test-events-fold-citations-into-parts
  (let [events [{:event/type :stream/start}
                {:event/type :stream/content-delta :event/delta "Hi"}
                {:event/type :stream/citation
                 :citation/url "https://example.com"
                 :citation/title "Example"}
                {:event/type :stream/end :event/finish-reason :stop}]
        resp (stream/events->response events :perplexity "sonar")
        citation-parts (filter #(= :citation (:part/type %)) (:response/parts resp))]
    (is (= 1 (count citation-parts)))
    (is (= "https://example.com" (:citation/url (first citation-parts))))
    (is (= "Example" (:citation/title (first citation-parts))))))

;; ---------------------------------------------------------------------------
;; Error parsing
;; ---------------------------------------------------------------------------

(deftest test-parse-error-401
  (let [t (ppx/make-transport)
        profile (provider/get-provider :perplexity)
        err (transport/parse-error
             t profile 401 {:error {:message "Bad key"}})]
    (is (= :auth (:error/reason err)))))
