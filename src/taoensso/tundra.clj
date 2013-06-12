(ns taoensso.tundra
  "Alpha - subject to change (hide the kittens!).
  Semi-automatic Carmine-backed caching layer for Faraday: marries the best
  of Redis (simplicity, read+write performance, structured data types, low cost)
  with the best of DynamoDB (scalability, reliability, big data storage). All
  with a secure, dead-simple, high-performance API.

  It's like the magix.

  Requires Redis 2.6.0+, Carmine v1.12.0+.

  Redis keys:
    * carmine:tundra:<worker>:touched -> set, dirty keys."
  {:author "Peter Taoussanis"}
  (:refer-clojure :exclude [ensure])
  (:require [taoensso.faraday       :as far]
            [taoensso.faraday.utils :as futils]
            [taoensso.carmine       :as car]
            [taoensso.carmine.utils :as cutils]
            [taoensso.nippy         :as nippy ]
            [taoensso.nippy.crypto  :as crypto]
            [taoensso.timbre        :as timbre]))

;;;; TODO
;; * Roughest working prototype, ASAP.
;; * Lots 'o tests.
;; * Finish up README.

(def ttable :faraday.tundra.data-store)

(defn ensure-table
  "Creates the Faraday table iff it doesn't already exist."
  [creds & [{:keys [throughput block?]}]]
  (far/ensure-table creds
    {:name         ttable
     :throughput   throughput
     :hash-keydef  {:name :worker    :type :s}
     :range-keydef {:name :redis-key :type :s}
     :block?       block?}))

(def ^:private tkey (memoize (partial car/kname "carmine" "tundra")))
(defn- opts [] {:keys ['worker 'cache-ttl-ms 'compress? 'password]
                :or   {'worker :default
                       'cache-ttl-ms (* 1000 60 60 24 28)
                       'compress? true}
                :as   'opts})

(defn- assert-args [keys #=(opts)]
  (assert (and (futils/coll?* keys) (every? string? keys) (<= (count keys) 100))
          (str "Malformed keys: " keys))
  ;; In particular, catch people from using 0 to mean nil!
  (assert (or (not cache-ttl-ms) (>= cache-ttl-ms (* 1000 60 60 60)))
          (str "Bad TTL (< 1 hour): " cache-ttl-ms)))

(defn- extend-exists
  "Returns 0/1 for each key that doesn't/exist. Extends any preexisting TTLs."
  [ttl-ms & keys]
  (apply car/eval*
    "local result = {}
     for i,k in pairs(KEYS) do
       if redis.call('ttl', k) > 0 then
         redis.call('pexpire', k, tonumber(ARGV[1]))
         result[i] = 1
       else
         -- TODO Waiting on -2/-1 return-value feature for TTL command
         result[i] = redis.call('exists', k)
       end
     end
     return result"
    (count keys)
    (conj (vec keys) (or ttl-ms "no-ttl"))))

(comment (wc (car/ping)
             (extend-exists nil "foo" "bar" "baz")
             (car/ping)))

(defn ensure
  "Alpha - subject to change.
  Blocks to ensure requested keys are available in Redis cache, fetching them
  from DynamoDB as necessary. Throws an exception if any keys couldn't be made
  available.

  Acts as a Redis command: call within a `with-conn` context."
  [creds ks & [#=(opts)]]
  (assert-args ks opts)
  (let [ks                (distinct ks)
        existance-replies (->> (apply extend-exists cache-ttl-ms ks)
                               (car/with-replies)) ; Immediately throw on errors
        missing-ks        (->> (mapv #(when (zero? %2) %1) ks existance-replies)
                               (filterv identity))]

    (when-not (empty? missing-ks)
      (timbre/trace "Fetching missing keys: " missing-ks)
      (let [fetch-data ; {<redis-key> <data> ...}
            (->> (far/batch-get-item creds
                   {ttable {:prim-kvs {:worker    (name worker)
                                       :redis-key missing-ks}
                            :attrs [:redis-key :data]}})
                 (ttable) ; [{:worker _ :redis-key _} ...]
                 (far/items-by-attrs :redis-key))

            ;; Restore what we can even if some fetches failed
            restore-replies ; {<redis-key> <restore-reply> ...}
            (->> (doseq [[k data] fetch-data]
                   (if-not (futils/bytes? data)
                     (car/return (Exception. "Malformed fetch data"))
                     (car/restore k (or cache-ttl-ms 0) (car/raw data))))
                 (car/with-replies :as-pipeline) ; ["OK" "OK" ...]
                 (zipmap (keys fetch-data)))

            errors ; {<redis-key> <error> ...}
            (reduce
             (fn [m k]
               (if-not (contains? fetch-data k)
                 (assoc m k "Fetch failed")
                 (let [^Exception rr (restore-replies k)]
                   (if (or (not (instance? Exception rr))
                           ;; Already restored:
                           (= (.getMessage rr) "ERR Target key name is busy."))
                     m (assoc m k (.getMessage rr))))))
             (sorted-map) ks)]

        (when-not (empty? errors)
          (let [ex (ex-info "Failed to ensure some key(s)" errors)]
            (timbre/error ex) (throw ex)))))))

(comment
  (wc (car/del "k1" "k2" "k3" "k4"))
  (far/put-item mc ttable {:worker "default" :redis-key "k3" :data "malformed-RDF"})
  (wc (ensure mc ["k1" "k4" "k3" "k2"])))

(defn touch
  "Alpha - subject to change.
  TODO

  *********************************************
  **WARNING**: KEYS WILL BE MARKED FOR EXPIRY!!
  *********************************************

  Acts as a Redis command: call within a `with-conn` context."
  [creds ks & [#=(opts)]]
  (assert-args keys opts)
  (let [ks (distinct ks)]

    ;; TODO Not too much to do here,
    ;; 1. Set pexpire on all the keys.
    ;; 2. Add all the keys to the appropriate 'dirty' set.
    ;; 3. Throw an exception if any of the `pexpires` failed (i.e. keys didn't
    ;;    exist)?

    ;; TODO What if we touch after expiry but before ensuring?

    ))

(comment (touch))

;; Do everything possible to keep this worker from dying. And scream bloody
;; murder if it does die.

;; (defprotocol ITundraWorker
;;   (stop  [this])
;;   (start [this]))

;; (cutils/defonce* ^:private workers (atom {}))

;; TODO Make sure to add some protection nil-local, nnil-foreign keys.
;; TODO What about locally (& intentionally) _deleted_ keys?
;;      We'll need a way to: a) Delete corresponding foreign key.
;;                           b) Backup (original) corresponding foreign key in
;;                              the local deletion was an error.
;; TODO And what about `ensure`? How shall it respond to the 'fetching' of an
;;      intentionally deleted key? Will we need/want a tombstone mechanism?

;; TODO STEP #1 will be to experiment with RDF deleted-key behavior:

(comment
  (wc (car/dump "non-existant")) ; nil

  )

;; (defn reset-worker ""
;;   [connection-pool connection-spec &
;;    [{:keys [worker throttle-ms backoff-ms]
;;      :or   {worker      :default
;;             throttle-ms 1000
;;             backoff-ms  #=(* 1000 60 60)}}]])

(comment (reset-worker))

(comment
  (def pool (car/make-conn-pool))
  (def spec (car/make-conn-spec))
  (defmacro wc [& body] `(car/with-conn pool spec ~@body))
  (def mc {:access-key "" :secret-key ""})

  (ensure-table mc)
  (far/describe-table mc ttable)

  (wc (car/set "fookey" "barval")
      (car/restore "fookey*3"
                   0 ; ms ttl
                   (car/raw (wc (car/parse-raw (car/dump "fookey")))))
      (car/get "fookey*3")))

(comment (ensure))

;;;; README

(comment)