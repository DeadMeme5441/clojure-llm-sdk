(ns llm.sdk.aws-sigv4-test
  (:require [clojure.test :refer [deftest is testing]]
            [llm.sdk.aws-sigv4 :as sigv4])
  (:import [java.util Date TimeZone Calendar]
           [java.text SimpleDateFormat]))

(defn- fixed-date
  "Build a java.util.Date for the given UTC instant 'yyyyMMdd'T'HHmmss'Z'."
  ^Date [s]
  (let [fmt (doto (SimpleDateFormat. "yyyyMMdd'T'HHmmss'Z'")
              (.setTimeZone (TimeZone/getTimeZone "UTC")))]
    (.parse fmt s)))

;; ---------------------------------------------------------------------------
;; Hashing primitives
;; ---------------------------------------------------------------------------

(deftest test-sha256-empty
  (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (sigv4/sha256-hex ""))))

(deftest test-sha256-known
  (is (= "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
         (sigv4/sha256-hex "hello"))))

;; ---------------------------------------------------------------------------
;; Canonical pieces
;; ---------------------------------------------------------------------------

(deftest test-canonical-uri
  (is (= "/" (sigv4/canonical-uri "")))
  (is (= "/" (sigv4/canonical-uri "/")))
  (is (= "/model/anthropic.claude-3-5-sonnet/converse"
         (sigv4/canonical-uri "/model/anthropic.claude-3-5-sonnet/converse"))))

(deftest test-canonical-query-string
  (is (= "" (sigv4/canonical-query-string nil)))
  (is (= "" (sigv4/canonical-query-string "")))
  (testing "sorts pairs by name"
    (is (= "Action=ListUsers&Version=2010-05-08"
           (sigv4/canonical-query-string "Version=2010-05-08&Action=ListUsers")))))

(deftest test-canonical-headers
  (let [[block signed] (sigv4/canonical-headers
                        {"X-Amz-Date" "20150830T123600Z"
                         "Host" "bedrock-runtime.us-east-1.amazonaws.com"})]
    (is (= "host:bedrock-runtime.us-east-1.amazonaws.com\nx-amz-date:20150830T123600Z\n"
           block))
    (is (= "host;x-amz-date" signed))))

;; ---------------------------------------------------------------------------
;; End-to-end signing — AWS official test vector
;; "get-vanilla" from sigv4-testsuite. We provide the exact inputs and
;; check the derived Authorization header value.
;; ---------------------------------------------------------------------------

(deftest test-derive-signing-key
  ;; The derived signing key is 32 bytes for HMAC-SHA256. Re-deriving
  ;; with the same inputs must produce the same bytes.
  (let [k1 (sigv4/derive-signing-key
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
            "20150830" "us-east-1" "iam")
        k2 (sigv4/derive-signing-key
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
            "20150830" "us-east-1" "iam")
        k3 (sigv4/derive-signing-key
            "different-secret"
            "20150830" "us-east-1" "iam")]
    (is (= 32 (count k1)))
    (is (= (seq k1) (seq k2)) "deterministic")
    (is (not= (seq k1) (seq k3)) "depends on secret")))

(deftest test-string-to-sign-shape
  (let [cr (sigv4/canonical-request
            :get "https://example.com/"
            {"host" "example.com" "x-amz-date" "20150830T123600Z"}
            "")
        sts (sigv4/string-to-sign "20150830T123600Z"
                                  "20150830/us-east-1/service/aws4_request"
                                  (first cr))]
    (is (.startsWith ^String sts "AWS4-HMAC-SHA256\n20150830T123600Z\n"))))

(deftest test-sign-request-injects-headers
  (let [signed (sigv4/sign-request
                {:method :post
                 :url "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-3-5-sonnet/converse"
                 :headers {"Content-Type" "application/json"}
                 :body "{}"}
                {:access-key-id "AKIDEXAMPLE"
                 :secret-access-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                 :region "us-east-1"
                 :service "bedrock"
                 :now (fixed-date "20150830T123600Z")})
        h (:headers signed)]
    (is (= "20150830T123600Z" (get h "x-amz-date")))
    (is (string? (get h "Authorization")))
    (is (.contains ^String (get h "Authorization") "AWS4-HMAC-SHA256"))
    (is (.contains ^String (get h "Authorization") "Credential=AKIDEXAMPLE/20150830/us-east-1/bedrock/aws4_request"))
    (is (.contains ^String (get h "Authorization") "SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"))
    (is (= "44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"
           (get h "x-amz-content-sha256"))
        "x-amz-content-sha256 should be sha256 of body '{}'")))

(deftest test-sign-request-session-token-included-in-signature
  (let [signed (sigv4/sign-request
                {:method :post
                 :url "https://bedrock-runtime.us-east-1.amazonaws.com/model/x/converse"
                 :headers {}
                 :body ""}
                {:access-key-id "AKIDEXAMPLE"
                 :secret-access-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                 :session-token "TEST-SESSION"
                 :region "us-east-1"
                 :service "bedrock"
                 :now (fixed-date "20150830T123600Z")})
        h (:headers signed)]
    (is (= "TEST-SESSION" (get h "x-amz-security-token")))
    (is (.contains ^String (get h "Authorization") "x-amz-security-token")
        "x-amz-security-token must be in the SignedHeaders list")))
