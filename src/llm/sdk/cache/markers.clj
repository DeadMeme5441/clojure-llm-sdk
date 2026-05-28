(ns llm.sdk.cache.markers
  "Provider-native context cache marker transforms.")

(defn marker
  "Build a cache_control marker for the given TTL ('5m' or '1h')."
  ([] (marker "5m"))
  ([ttl]
   (cond-> {:type "ephemeral"}
     (= ttl "1h") (assoc :ttl "1h"))))

(defn- apply-marker-to-blocks
  "Add cache_control to the last content block of a list."
  [blocks ttl-marker]
  (if (and (sequential? blocks) (seq blocks))
    (let [last-idx (dec (count blocks))
          last-block (nth blocks last-idx)]
      (if (map? last-block)
        (assoc (vec blocks) last-idx (assoc last-block :cache_control ttl-marker))
        blocks))
    blocks))

(defn apply-marker-to-message
  "Place a cache_control marker on a provider-native message."
  [message ttl-marker layout]
  (let [content (:content message)
        role (:role message)]
    (cond
      (= layout :envelope)
      (assoc message :cache_control ttl-marker)

      (= role "tool")
      (assoc message :cache_control ttl-marker)

      (or (nil? content) (= content ""))
      (assoc message :cache_control ttl-marker)

      (string? content)
      (assoc message :content
             [{:type "text" :text content :cache_control ttl-marker}])

      (sequential? content)
      (assoc message :content (apply-marker-to-blocks content ttl-marker))

      :else
      (assoc message :cache_control ttl-marker))))

(defn apply-system-and-3
  "Apply system_and_3 caching to a vector of provider-native messages."
  ([messages] (apply-system-and-3 messages {}))
  ([messages {:keys [ttl layout breakpoints]
              :or {ttl "5m" layout :native breakpoints 4}}]
   (if (or (empty? messages) (zero? breakpoints))
     (vec messages)
     (let [mk (marker ttl)
           messages (vec messages)
           sys? (= (:role (first messages)) "system")
           used (if sys? 1 0)
           remaining (- breakpoints used)
           non-sys-indices (vec (keep-indexed
                                 (fn [i m] (when (not= (:role m) "system") i))
                                 messages))
           pick (when (pos? remaining)
                  (set (take-last remaining non-sys-indices)))
           with-sys (if sys?
                      (assoc messages 0 (apply-marker-to-message
                                          (first messages) mk layout))
                      messages)]
       (reduce (fn [acc i]
                 (assoc acc i (apply-marker-to-message (nth acc i) mk layout)))
               with-sys
               pick)))))

(defn apply-system-blocks-cache
  "Place cache_control on the last Anthropic system content block."
  [system-blocks {:keys [ttl] :or {ttl "5m"}}]
  (if (and (sequential? system-blocks) (seq system-blocks))
    (apply-marker-to-blocks (vec system-blocks) (marker ttl))
    system-blocks))

(defn apply-tools-cache
  "Mark the last Anthropic tools[] item as cacheable."
  [tools {:keys [ttl] :or {ttl "5m"}}]
  (if (and (sequential? tools) (seq tools))
    (let [tools (vec tools)
          last-idx (dec (count tools))]
      (assoc tools last-idx (assoc (nth tools last-idx)
                                   :cache_control (marker ttl))))
    tools))
