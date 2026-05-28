(ns llm.sdk.cache.request
  "Request cache option readers.")

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
  "Read the cache TTL from a request, defaulting to '5m'."
  [request]
  (or (get-in request [:request/cache :ttl]) "5m"))

(defn breakpoints
  "Read the cache breakpoint count from a request, defaulting to 4."
  [request]
  (or (get-in request [:request/cache :breakpoints]) 4))

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
