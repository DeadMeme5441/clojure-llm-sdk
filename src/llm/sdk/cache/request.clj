(ns llm.sdk.cache.request
  "Request cache option readers.")

(def ^:private supported-ttls #{"5m" "1h"})

(defn validate-ttl!
  "Return ttl when supported, otherwise throw a request validation error."
  [ttl]
  (when-not (supported-ttls ttl)
    (throw (ex-info "Cache TTL must be one of: 5m, 1h"
                    {:error/type :request/unsupported-cache-ttl
                     :ttl ttl
                     :supported-ttls ["5m" "1h"]})))
  ttl)

(defn validate-breakpoints!
  "Return count when it is a non-negative integer, otherwise throw."
  [breakpoints]
  (when-not (and (int? breakpoints) (not (neg? breakpoints)))
    (throw (ex-info "Cache breakpoints must be a non-negative integer"
                    {:error/type :request/invalid-cache-breakpoints
                     :breakpoints breakpoints})))
  breakpoints)

(defn cache-enabled?
  "Return true when the request opted in to caching."
  [request]
  (let [c (:request/cache request)]
    (cond
      (nil? c) false
      (= (:enabled? c) false) false
      (= (:strategy c) :none) false
      :else true)))

(defn ttl
  "Read and validate the cache TTL from a request, defaulting to '5m'."
  [request]
  (validate-ttl! (or (get-in request [:request/cache :ttl]) "5m")))

(defn breakpoints
  "Read and validate the cache breakpoint count from a request, defaulting to 4."
  [request]
  (validate-breakpoints! (or (get-in request [:request/cache :breakpoints]) 4)))

(defn scope-id
  "Read the caller-supplied scope id."
  [request]
  (get-in request [:request/cache :scope-id]))

(defn cached-content-id
  "Read the caller-supplied explicit cached content id."
  [request]
  (get-in request [:request/cache :cached-content-id]))

(defn tools-cache?
  "Should tools[] schema be cached in addition to messages?"
  [request]
  (boolean (get-in request [:request/cache :tools-cache?])))
