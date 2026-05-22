(ns llm.sdk.aws-sigv4
  "AWS Signature V4 signing for Bedrock + any other AWS service.
   Implements the canonical-request → string-to-sign → derived-key →
   signature flow defined in:
     https://docs.aws.amazon.com/general/latest/gr/sigv4-create-canonical-request.html

   Public entry point is sign-request — give it an unsigned request map
   {:method :url :headers :body} plus credentials + region + service and
   it returns the same map with Authorization + x-amz-date +
   x-amz-content-sha256 + x-amz-security-token (when present) injected.

   No external AWS SDK dep — uses JDK crypto only."
  (:require [clojure.string :as str]
            [cheshire.core :as json])
  (:import [java.net URI URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

;; ---------------------------------------------------------------------------
;; Hashing + HMAC
;; ---------------------------------------------------------------------------

(defn- sha256-bytes
  ^bytes [^bytes data]
  (.digest (MessageDigest/getInstance "SHA-256") data))

(defn- bytes->hex
  ^String [^bytes data]
  (let [sb (StringBuilder.)]
    (doseq [b data]
      (.append sb (format "%02x" (bit-and b 0xff))))
    (.toString sb)))

(defn sha256-hex
  "Hex-encoded SHA-256 of a string or byte array."
  [data]
  (let [bs (if (string? data)
             (.getBytes ^String data StandardCharsets/UTF_8)
             data)]
    (bytes->hex (sha256-bytes bs))))

(defn- hmac-sha256
  ^bytes [^bytes key ^String data]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (.init mac (SecretKeySpec. key "HmacSHA256"))
    (.doFinal mac (.getBytes data StandardCharsets/UTF_8))))

;; ---------------------------------------------------------------------------
;; Date formatting
;; ---------------------------------------------------------------------------

(defn- date-formats []
  (let [tz (TimeZone/getTimeZone "UTC")
        amz (SimpleDateFormat. "yyyyMMdd'T'HHmmss'Z'")
        date (SimpleDateFormat. "yyyyMMdd")]
    (.setTimeZone amz tz)
    (.setTimeZone date tz)
    [amz date]))

(defn- format-times
  "Return [amz-date short-date] strings for the given java.util.Date."
  [^Date d]
  (let [[amz date] (date-formats)]
    [(.format amz d) (.format date d)]))

;; ---------------------------------------------------------------------------
;; URI handling
;; ---------------------------------------------------------------------------

(defn- url-encode-segment
  "RFC 3986 encode a single path segment (keeps unreserved chars, percent-
   encodes the rest)."
  [^String s]
  (-> (URLEncoder/encode s "UTF-8")
      (.replace "+" "%20")
      (.replace "*" "%2A")
      (.replace "%7E" "~")))

(defn canonical-uri
  "Canonical URI per SigV4: each path segment URL-encoded, slashes preserved.
   Empty path is canonicalized to '/'."
  [^String path]
  (if (or (nil? path) (str/blank? path) (= path "/"))
    "/"
    (let [segs (str/split path #"/" -1)
          encoded (mapv url-encode-segment segs)]
      (str/join "/" encoded))))

(defn canonical-query-string
  "Canonical query string: name=value pairs URL-encoded and sorted by
   name, then by value. Accepts a map or a raw query string."
  [query]
  (cond
    (nil? query) ""
    (string? query)
    (if (str/blank? query)
      ""
      (let [pairs (for [kv (str/split query #"&")]
                    (let [[k v] (str/split kv #"=" 2)]
                      [(or k "") (or v "")]))]
        (->> pairs
             (sort-by (fn [[k v]] [k v]))
             (map (fn [[k v]] (str (url-encode-segment k) "=" (url-encode-segment v))))
             (str/join "&"))))
    (map? query)
    (->> (for [[k v] query]
           [(name k) (str v)])
         (sort-by (fn [[k v]] [k v]))
         (map (fn [[k v]] (str (url-encode-segment k) "=" (url-encode-segment v))))
         (str/join "&"))))

;; ---------------------------------------------------------------------------
;; Header canonicalization
;; ---------------------------------------------------------------------------

(defn- trim-collapse
  "Trim outer whitespace and collapse internal runs of whitespace per
   the SigV4 spec. Quoted strings are passed through unchanged."
  [^String v]
  (-> v str str/trim (str/replace #"\s+" " ")))

(defn- canonical-headers-map
  "Lower-case all header names, trim/collapse values."
  [headers]
  (reduce-kv (fn [m k v]
               (assoc m (-> k name str/lower-case) (trim-collapse (str v))))
             (sorted-map) headers))

(defn canonical-headers
  "Header block + signed-headers list. Returns [canonical-block signed-list]."
  [headers]
  (let [m (canonical-headers-map headers)
        block (str/join (map (fn [[k v]] (str k ":" v "\n")) m))
        signed (str/join ";" (keys m))]
    [block signed]))

;; ---------------------------------------------------------------------------
;; Canonical request
;; ---------------------------------------------------------------------------

(defn- url->parts
  "Parse a URL into {:host :path :query}."
  [^String url]
  (let [u (URI. url)]
    {:host (.getHost u)
     :path (or (.getRawPath u) "/")
     :query (.getRawQuery u)}))

(defn canonical-request
  "Build canonical request string per SigV4.
   Returns [canonical-request signed-headers payload-hash]."
  [method url headers body]
  (let [{:keys [path query]} (url->parts url)
        payload-hash (sha256-hex (or body ""))
        [hdr-block signed] (canonical-headers headers)
        method-str (str/upper-case (name method))
        cr (str method-str "\n"
                (canonical-uri path) "\n"
                (canonical-query-string query) "\n"
                hdr-block "\n"
                signed "\n"
                payload-hash)]
    [cr signed payload-hash]))

;; ---------------------------------------------------------------------------
;; String to sign + signing key
;; ---------------------------------------------------------------------------

(defn credential-scope
  "{short-date}/{region}/{service}/aws4_request"
  [short-date region service]
  (str short-date "/" region "/" service "/aws4_request"))

(defn string-to-sign
  [amz-date scope canonical-req]
  (str "AWS4-HMAC-SHA256" "\n"
       amz-date "\n"
       scope "\n"
       (sha256-hex canonical-req)))

(defn derive-signing-key
  ^bytes [^String secret short-date region service]
  (let [k-secret (.getBytes (str "AWS4" secret) StandardCharsets/UTF_8)
        k-date (hmac-sha256 k-secret short-date)
        k-region (hmac-sha256 k-date region)
        k-service (hmac-sha256 k-region service)
        k-signing (hmac-sha256 k-service "aws4_request")]
    k-signing))

;; ---------------------------------------------------------------------------
;; Public: sign-request
;; ---------------------------------------------------------------------------

(defn sign-request
  "Inject SigV4 auth headers into an unsigned request map.

   Required keys:
     :method  http verb keyword
     :url     full URL
     :headers map (will be augmented; existing :host added if missing)
     :body    string payload (may be nil)

   Required opts:
     :access-key-id      string
     :secret-access-key  string
     :region             e.g. \"us-east-1\"
     :service            e.g. \"bedrock\"
   Optional:
     :session-token      STS session token (adds x-amz-security-token)
     :now                java.util.Date (defaults to current time)
     :body-bytes         pre-serialized body if :body is non-string"
  [{:keys [method url headers body]}
   {:keys [access-key-id secret-access-key region service session-token now body-bytes]}]
  (let [{:keys [host]} (url->parts url)
        d (or now (Date.))
        [amz-date short-date] (format-times d)
        body-str (cond
                   body-bytes (String. ^bytes body-bytes StandardCharsets/UTF_8)
                   (nil? body) ""
                   :else (str body))
        payload-hash (sha256-hex body-str)
        ;; lowercased keys for hash so signed headers are deterministic
        base-headers (-> {}
                         (merge (or headers {}))
                         (assoc "host" host
                                "x-amz-date" amz-date
                                "x-amz-content-sha256" payload-hash))
        base-headers (if session-token
                       (assoc base-headers "x-amz-security-token" session-token)
                       base-headers)
        [cr signed _] (canonical-request method url base-headers body-str)
        scope (credential-scope short-date region service)
        sts (string-to-sign amz-date scope cr)
        key-bytes (derive-signing-key secret-access-key short-date region service)
        signature (bytes->hex (hmac-sha256 key-bytes sts))
        auth (str "AWS4-HMAC-SHA256 "
                  "Credential=" access-key-id "/" scope ", "
                  "SignedHeaders=" signed ", "
                  "Signature=" signature)]
    {:method method
     :url url
     :headers (-> base-headers
                  (assoc "Authorization" auth))
     :body body
     ::canonical-request cr
     ::string-to-sign sts
     ::signature signature}))

;; ---------------------------------------------------------------------------
;; Credentials discovery
;; ---------------------------------------------------------------------------

(defn discover-credentials
  "Pick up AWS credentials from the environment.
   Returns {:access-key-id :secret-access-key :session-token :region}.
   AWS_REGION wins over AWS_DEFAULT_REGION."
  []
  (let [env (fn [k] (System/getenv k))]
    {:access-key-id (env "AWS_ACCESS_KEY_ID")
     :secret-access-key (env "AWS_SECRET_ACCESS_KEY")
     :session-token (env "AWS_SESSION_TOKEN")
     :region (or (env "AWS_REGION") (env "AWS_DEFAULT_REGION"))}))

;; ---------------------------------------------------------------------------
;; Driver helper — call this from each modality driver right before
;; handing the built request to http/*. Keeps signing logic in one
;; place, but lets each driver decide when to invoke it.
;; ---------------------------------------------------------------------------

(defn maybe-sign
  "If the provider profile uses AWS SigV4, pre-serialize the body, sign,
   and return the signed request. Otherwise pass-through.
   Reads region/service hints off the request map at the bedrock-namespaced
   keys (:llm.sdk.providers.bedrock/aws-service / :aws-region) and the
   profile's :profile/aws-service fallback.

   Throws ex-info if SigV4 is required but AWS_ACCESS_KEY_ID is missing."
  [profile req]
  (case (:profile/auth-strategy profile)
    :aws-sigv4
    (let [body-str (when-let [b (:body req)]
                     (cond
                       (string? b) b
                       (bytes? b) (String. ^bytes b java.nio.charset.StandardCharsets/UTF_8)
                       :else (json/generate-string b)))
          {:keys [access-key-id secret-access-key
                  session-token region]}
          (discover-credentials)
          service (or (get req :llm.sdk.providers.bedrock/aws-service)
                      (:profile/aws-service profile)
                      "bedrock")
          region (or (get req :llm.sdk.providers.bedrock/aws-region)
                     region
                     "us-east-1")
          req (dissoc req
                      :llm.sdk.providers.bedrock/aws-service
                      :llm.sdk.providers.bedrock/aws-region)]
      (when-not access-key-id
        (throw (ex-info "AWS_ACCESS_KEY_ID not set for AWS SigV4 signing"
                        {:provider (:profile/id profile)})))
      (-> (sign-request
           (assoc req :body body-str)
           {:access-key-id access-key-id
            :secret-access-key secret-access-key
            :session-token session-token
            :region region
            :service service})
          (dissoc ::canonical-request ::string-to-sign ::signature)))
    req))
