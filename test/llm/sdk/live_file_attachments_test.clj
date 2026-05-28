(ns llm.sdk.live-file-attachments-test
  "Live file/document attachment checks.

   These are opt-in live tests. They make real provider calls and are gated by
   the relevant credentials in the environment.

  Run narrowly:
     source .env && clojure -M:live-test -n llm.sdk.live-file-attachments-test"
  (:require [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [llm.sdk :as sdk]
            [llm.sdk.provider :as provider]))

(def ^:private secret-code "SDK-LIVE-FILE-42")

(defn- env? [k]
  (boolean (not-empty (System/getenv k))))

(defn- gcloud-auth-available? []
  (try
    (zero? (:exit (sh/sh "gcloud" "auth" "print-access-token")))
    (catch Exception _ false)))

(defn- gcp-creds? []
  (and (env? "GOOGLE_CLOUD_PROJECT")
       (or (env? "GOOGLE_OAUTH_ACCESS_TOKEN")
           (env? "GOOGLE_APPLICATION_CREDENTIALS")
           (gcloud-auth-available?))))

(defn- pdf-escape [s]
  (-> s
      (str/replace "\\" "\\\\")
      (str/replace "(" "\\(")
      (str/replace ")" "\\)")))

(defn- tiny-pdf [text]
  (let [stream (str "BT /F1 12 Tf 72 720 Td (" (pdf-escape text) ") Tj ET")
        objects [(str "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
                 (str "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n")
                 (str "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
                      "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n")
                 (str "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n")
                 (str "5 0 obj\n<< /Length " (count (.getBytes stream "UTF-8")) " >>\n"
                      "stream\n" stream "\nendstream\nendobj\n")]
        header "%PDF-1.4\n"
        offsets (loop [remaining objects
                       offset (count (.getBytes header "UTF-8"))
                       acc []]
                  (if-let [obj (first remaining)]
                    (recur (rest remaining)
                           (+ offset (count (.getBytes obj "UTF-8")))
                           (conj acc offset))
                    acc))
        body (apply str objects)
        xref-offset (+ (count (.getBytes header "UTF-8"))
                       (count (.getBytes body "UTF-8")))]
    (str header
         body
         "xref\n0 6\n"
         "0000000000 65535 f \n"
         (apply str (map #(format "%010d 00000 n \n" %) offsets))
         "trailer\n<< /Size 6 /Root 1 0 R >>\n"
         "startxref\n" xref-offset "\n%%EOF\n")))

(defn- file-message
  ([] (file-message :text))
  ([kind]
   {:message/role :user
    :message/content
    [{:part/type :text
      :text (str "Read the attached file. What is the secret_code? "
                 "Answer with the code only.")}
     {:part/type :file
      :file/name (case kind
                   :pdf "clojure-llm-sdk-live-file.pdf"
                   "clojure-llm-sdk-live-file.txt")
      :file/mime-type (case kind
                        :pdf "application/pdf"
                        "text/plain")
      :file/content (case kind
                      :pdf (tiny-pdf (str "secret_code: " secret-code))
                      (str "secret_code: " secret-code "\n"))}]}))

(defn- response-text [resp]
  (->> (:response/parts resp)
       (keep :text)
       (str/join "\n")
       str/trim))

(defn- assert-secret-code [resp]
  (let [text (response-text resp)]
    (is (str/includes? text secret-code)
        (str "Expected " secret-code " in response text: " text))))

(deftest ^:live live-codex-responses-file-data
  (when (env? "OPENAI_API_KEY")
    (testing "OpenAI Responses/Codex input_file via file_data"
      (let [resp (sdk/complete
                  :codex
                  {:request/model "gpt-4.1-mini"
                   :request/messages [(file-message)]
                   :request/max-tokens 32
                   :request/temperature 0})]
        (is (= :codex (:response/provider resp)))
        (assert-secret-code resp)))))

(deftest ^:live live-openai-chat-file-data
  (when (env? "OPENAI_API_KEY")
    (testing "OpenAI Chat Completions file attachment via file_data"
      (let [resp (sdk/complete
                  :openai
                  {:request/model "gpt-4.1-mini"
                   :request/messages [(file-message :pdf)]
                   :request/max-tokens 32
                   :request/temperature 0})]
        (is (= :openai (:response/provider resp)))
        (assert-secret-code resp)))))

(deftest ^:live live-anthropic-file-text-source
  (when (env? "ANTHROPIC_API_KEY")
    (testing "Anthropic Messages document block via text source"
      (let [resp (sdk/complete
                  :anthropic
                  {:request/model "claude-sonnet-4-20250514"
                   :request/messages [(file-message)]
                   :request/max-tokens 32
                   :request/temperature 0})]
        (is (= :anthropic (:response/provider resp)))
        (assert-secret-code resp)))))

(deftest ^:live live-anthropic-oat-file-text-source
  (when (env? "CLAUDE_OAT_TOKEN")
    (testing "Anthropic OAuth/OAT document block via text source"
      (let [oauth-profile {:profile/id :anthropic-oat-file-live
                           :profile/protocol-family :anthropic-messages
                           :profile/base-url "https://api.anthropic.com/v1"
                           :profile/auth-strategy :bearer
                           :profile/env-var-names ["CLAUDE_OAT_TOKEN"]
                           :profile/capabilities #{:chat :streaming :tools
                                                   :json-schema :reasoning
                                                   :cache :thinking-blocks
                                                   :file-attachments}
                           :profile/default-headers {"anthropic-version" "2023-06-01"}
                           :profile/transport-constructor
                           (fn [] ((requiring-resolve 'llm.sdk.providers.anthropic/make-transport)))}
            _ (provider/register-provider oauth-profile)
            resp (sdk/complete
                  :anthropic-oat-file-live
                  {:request/model "claude-sonnet-4-20250514"
                   :request/messages [(file-message)]
                   :request/max-tokens 32
                   :request/temperature 0})]
        (is (= :anthropic (:response/provider resp)))
        (assert-secret-code resp)))))

(deftest ^:live live-gemini-native-inline-file-data
  (when (env? "GEMINI_API_KEY")
    (testing "Gemini Native inlineData file part"
      (let [resp (sdk/complete
                  :gemini-native
                  {:request/model "gemini-2.5-flash"
                   :request/messages [(file-message)]
                   :request/max-tokens 128
                   :request/temperature 0})]
        (is (= :gemini-native (:response/provider resp)))
        (assert-secret-code resp)))))

(deftest ^:live live-vertex-gemini-inline-file-data
  (when (gcp-creds?)
    (testing "Vertex Gemini inlineData file part"
      (let [resp (sdk/complete
                  :vertex-gemini
                  {:request/model "gemini-2.5-flash"
                   :request/messages [(file-message)]
                   :request/max-tokens 128
                   :request/temperature 0})]
        (is (= :vertex-gemini (:response/provider resp)))
        (assert-secret-code resp)))))

(deftest ^:live live-cohere-text-file-as-document
  (when (env? "COHERE_API_KEY")
    (testing "Cohere canonical text file part maps to native documents"
      (let [resp (sdk/complete
                  :cohere
                  {:request/model "command-r-08-2024"
                   :request/messages [(file-message)]
                   :request/max-tokens 80
                   :request/temperature 0
                   :request/provider-options
                   {:cohere {:citation_options {:mode "FAST"}}}})]
        (is (= :cohere (:response/provider resp)))
        (assert-secret-code resp)))))
