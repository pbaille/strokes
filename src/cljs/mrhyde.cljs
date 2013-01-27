; TODO
; recurse-from-hyde-cache should mark objects so as not to hit a cycle
; remove __define{GS}etter__ for Object.defineProperty()

; providing clojure data a split personality: don't be afraid to let it all out
(ns mrhyde
  (:require [clojure.string :refer [join re-matches]]
            [clojure.set :refer [difference]]
            [cljs.reader :refer [read-string]]))

; a replacement for the map call
(defn mapish [f]
  (this-as ct
    (doall (map 
      #(.call f js/undefined % %2 ct) (seq ct) (range)))))

; a replacement for the foreach call (which is map that returns null)
(defn eachish [f]
  ; call mapish with the same 'this'
  (this-as ct (.call mapish ct f))
  nil)

(def hyde-cache-key   "$cljs$mrhyde$cache")
(def hyde-access-key  "$cljs$mrhyde$acccess")
(def hyde-keylist-key "$cljs$mrhyde$keylist")
(def hyde-keyset-key  "$cljs$mrhyde$keyset")

(defn- strkey [x]
  (if (keyword? x)
    (name x)
    x))

(defn gen-map-getter [k]
  (fn []
    (this-as t 
        (get (aget t hyde-access-key) k))))

(defn gen-map-setter [k]
  (fn [v]
    (this-as t
      ; (.log js/console (str "setter: " t "," v "," n))
      ; ensure cache (transient) exists
      (if-not (goog.object.containsKey t hyde-cache-key)
        (let [c (transient t)]
          (aset t hyde-access-key c)
          (aset t hyde-cache-key c)))
      ; now use it
      (let [c (aget t hyde-cache-key)]
        (assoc! c k v)))))

; this can be called standalone or auto-run from cljs map initialzation
(defn patch-map [m]
  ; (.log js/console (str "keys: " (keys m)))
  (aset m hyde-access-key m)
  (doseq [k (keys m)]
    ; TODO: we need a better way to get prototype and could fallback to m
    (let [p (.-__proto__ m)]
      (if (and (keyword? k) (not (goog.object.containsKey m (name k)))) (do
        (.__defineGetter__ p (name k) (gen-map-getter k))
        (.__defineSetter__ p (name k) (gen-map-setter k))
  ))))
  (if (some keyword? (keys m)) (do
    (aset m hyde-keylist-key false)
    (aset m hyde-keyset-key false)
    (aset m hyde-keylist-key (.keys js/Object m))))
  m)

(def have-patched-js-with-key-lookup (atom false))

(defn patch-js-with-key-lookup []
  (if-not @have-patched-js-with-key-lookup (do
    (reset! have-patched-js-with-key-lookup true)
    (extend-type object
      ILookup
      (-lookup
        ([o k]
           (aget o (strkey k)))
        ([o k not-found]
          (let [s (strkey k)]
            (if (goog.object.containsKey o s)
              (aget o s) 
              not-found))))))))

(def MAXLEN 5000)

(defn patch-seq-object [o]
  ; this works but is now redundant
  ; (let [l (count (take MAXLEN o))]
  ;   (dotimes [n (- l 16)] 
  ;     (.__defineGetter__ o (str (+ n 16)) #(this-as t (nth t (+ n 16) js/undefined)))))
  nil)

(defn patch-map-object [o]
  ;(.log js/console (str (keys o)))
  (patch-map o)

  nil)

; of note -> http://stackoverflow.com/a/8843181/1010653
(defn patch-core-seq-type [s]
  ; (.log js/console (str "patching seq type " s))
  ; (-> js/gdebug (set! (aget js/cljs.core s)))
  (let [orig-fn (aget js/cljs.core s)
        orig-keys (.keys js/Object orig-fn)
        new-fn  (fn [& args]
                  (let [nargs (cons nil args)
                        binder (js/Function.prototype.bind.apply orig-fn nargs)
                        that  (new binder)]
                    (patch-seq-object that)
                    that))]

    ; set all properties of the new-fn based on the old one
    (doseq [k orig-keys]
      ;(.log js/console (str "remapping " k))
      ;(.__defineGetter__ new-fn k #(aget orig-fn k)))
      (aset new-fn k (aget orig-fn k)))
    (aset js/cljs.core s new-fn)))

(defn patch-core-map-type [s]
  ; (.log js/console (str "patching map type " s))
  (let [orig-fn (aget js/cljs.core s)
        orig-keys (.keys js/Object orig-fn)
        new-fn  (fn [& args]
                  (let [nargs (cons nil args)
                        binder (js/Function.prototype.bind.apply orig-fn nargs)
                        that  (new binder)]
                    (patch-map-object that)
                    that))]

    ; set all properties of the new-fn based on the old one
    (doseq [k orig-keys]
      ;(.log js/console (str "remapping " k))
      ;(.__defineGetter__ new-fn k #(aget orig-fn k)))
      (aset new-fn k (aget orig-fn k)))
    (aset js/cljs.core s new-fn)))

(defn gen-seq-getter [n]
  (fn []
    (this-as t 
      (let [src (if (goog.object.containsKey t hyde-cache-key) (aget t hyde-cache-key) t)]
        (nth src n js/undefined)))))

(defn gen-seq-setter [n]
  (fn [v]
    (this-as t
      ; (.log js/console (str "setter: " t "," v "," n))
      ; ensure cache (transient) exists
      (if-not (goog.object.containsKey t hyde-cache-key)
        (let [c (transient t)]
          (aset t hyde-cache-key c)))
      ; now use it
      (let [c (aget t hyde-cache-key)]
        (assoc! c n v)))))

;           ))
;       (let [src (if (goog.object.containsKey t hyde-cache-key) (t/hyde-cache-key) t)]
;         (nth src n js/undefined)))))

; Add functionality to cljs seq prototype to make it more like a js array
(defn patch-prototype-as-array [p o]
  ; array length call
  (.__defineGetter__ p "length" #(this-as t (count (take MAXLEN t))))
  ; access by index... we obviously need a smarter upper bound here
  (dotimes [n MAXLEN]
    (.__defineGetter__ p n (gen-seq-getter n))
    (.__defineSetter__ p n (gen-seq-setter n)))
  ; if we are acting like an array, we'll need in impl of forEach and map
  (-> p .-forEach (set! eachish))
  (-> p .-map (set! mapish))
  ; and we should print like a native string. (& squirrel the native one away)
  (-> p .-toCljString (set! (-> p .-toString)))
  (-> p .-toString (set! #(this-as t (clojure.string/join ", " t)))))

; silence warnings about protocol, what is all that gibber-jabber anyways?
(declare has-cache?)
(declare from-cache)

(defprotocol IHyde
  "Container types extended with js metaprogramming"
  (has-cache? [this] "is there cached information attached from js mutation?")
  (from-cache [this] "render this container with cached information"))

(defn add-hyde-protocol-to-seq [s]
  (extend-type s
  IHyde
  (has-cache? [this]
    (goog.object.containsKey this hyde-cache-key))
  (from-cache [this]
    (if-let [c (aget this hyde-cache-key)]
      ; attempt1: can we make a transient copy of a transient?
      (let [p (persistent! c)]
        (aset this hyde-cache-key (transient p))
        p)
      this))
  )
)

(defn filtered-keylist-set [l]
  (set (remove #(re-find #"cljs\$" %) l)))

(defn lazy-init-hyde-setset [m]
  (if (and (not (aget m hyde-keyset-key)) (aget m hyde-keylist-key))
    ; translate keylist into keymap
    (aset m hyde-keyset-key (filtered-keylist-set (aget m hyde-keylist-key)))))

(defn add-hyde-protocol-to-map [m]
  (extend-type m
    IHyde
    (has-cache? [this]
      (lazy-init-hyde-setset this)
      (or (goog.object.containsKey this hyde-cache-key)
        (not= (aget this hyde-keyset-key) (filtered-keylist-set (.keys js/Object this)))))
    (from-cache [this]
      (lazy-init-hyde-setset this)
      (let [; current keyset (minus a possible cache-key)
            cur-keyset (difference (filtered-keylist-set (.keys js/Object this)) #{hyde-cache-key})
            ; what new keys have appeared
            new-keys   (difference cur-keyset (aget this hyde-keyset-key))
            ; put all new key/value pairs into their own map
            new-map    (into {} (for [k new-keys] [(keyword k) (aget this k)]))
            ; pull out the cache too (might be js/undefined)
            cache      (aget this hyde-cache-key)]
        (if cache
          ; make a persistent copy, and then store right away again as new transient!
          (let [p (persistent! cache)]
            (aset this hyde-cache-key (transient p))
            ; persistent object mashed up with new keys
            (merge p new-map))
          ; else
          (merge this new-map))
      ))
  ))

; (this-as ct (aset ct "hascache" (fn [x] (has-cache? x))))
; (this-as ct (aset ct "fromcache" (fn [x] (from-cache x))))

(defn ^boolean hyde?
  "Returns true if coll satisfies IHyde"
  [x] (satisfies? IHyde x))

(defn from-cache-if-has-cache [x]
  (if (and (hyde? x) (has-cache? x))
      (from-cache x) 
      x))

(defn recurse-from-hyde-cache
  "Recursively transforms any cached hyde objects to new persistent versions."
  [x]
  (cond
    (goog.isArray x)
      (vec (map recurse-from-hyde-cache x))
    (map? x)
      (let [c (from-cache-if-has-cache x)]
        (into {} (for [[k v] c] 
          [(recurse-from-hyde-cache k)
            (if (= k :parent)
              nil
              (recurse-from-hyde-cache v))])))
    (coll? x) 
      (let [c (from-cache-if-has-cache x)]
        (into (empty c) (map recurse-from-hyde-cache c)))
    :else
      (from-cache-if-has-cache x)))

(defn repersist [x]
  (if (goog.isFunction x)
    (fn [& args] (repersist (this-as t (.apply x t args))))
    ;else
    (recurse-from-hyde-cache x)))

(def have-patched-arrayish-flag (atom false))
(def have-patched-mappish-flag (atom false))

; there must be a smarter way to do this, but for now i'll forge ahead
(defn patch-known-arrayish-types []
  (if-not @have-patched-arrayish-flag (do
    (reset! have-patched-arrayish-flag true)
    (doseq [p [cljs.core.PersistentVector
               cljs.core.List
               cljs.core.LazySeq
               cljs.core.IndexedSeq
               cljs.core.Cons
               cljs.core.Range
               cljs.core.ArrayNodeSeq
               cljs.core.ChunkedSeq]]
       (patch-prototype-as-array (aget p "prototype") p)
       (add-hyde-protocol-to-seq p))
    (patch-core-seq-type "PersistentVector"))))

(defn patch-known-mappish-types [] 
  (if-not @have-patched-mappish-flag (do
    (reset! have-patched-mappish-flag true)
    (doseq [p [cljs.core.ObjMap
               cljs.core.PersistentHashMap]]
       (add-hyde-protocol-to-map p))

    (patch-core-map-type "ObjMap")
    (patch-core-map-type "PersistentHashMap"))))

; The following helpers patch individual js functions
; TODO: some (all?) of these require patch-args-clj-to-js to have been run, why?

(defn get-store-cur-js-fn [o field-name]
  (let [cur-fn (aget o field-name)
        js-fn-name (str "_js_" field-name)
        root-fn (aget o js-fn-name)]
    ; first store original (if we have not already done so)
    (if (= js/undefined root-fn)
      ; (.log js/console "storing: " js-fn-name)
      (aset o js-fn-name cur-fn))
    ; return cur-fn
    cur-fn))

; patch a js function to return a clj-ish value
(defn patch-return-value-to-clj [o field-name]
  (let [orig-fn (get-store-cur-js-fn o field-name)]
    (aset o field-name (fn [& args] 
      (js->clj (this-as ct (.apply orig-fn ct args)))))))

; patch a js function to return a clj-ish value
(defn patch-return-value-recurse-from-cache [o field-name]
  (let [orig-fn (get-store-cur-js-fn o field-name)]
    (aset o field-name (fn [& args] 
      (recurse-from-hyde-cache (this-as ct (.apply orig-fn ct args)))))))

; patch a js fn returning a fn to return a fn returning a clj-ish value of the other fn
(defn patch-return-value-recurse-from-cache-as-function [o field-name]
  (let [orig-fn (get-store-cur-js-fn o field-name)]
    (aset o field-name (fn [& args]
      (let [dyn-fun (this-as ct (.apply orig-fn ct args))]
        ; dyn-fun)))))
        (fn [& nargs]
          (this-as ct (.apply dyn-fun ct nargs))))))))

(defn recurse-from-hyde-cache-maybe-fn [x]
  (if (goog.isFunction x)
    ; return funciton for execution-later
    (fn [& args]
      (recurse-from-hyde-cache (this-as ct (.apply x ct args))))
    ; else - execute here and now
    (recurse-from-hyde-cache x)))

; patch a js function convert specified keyword args to functions
(defn patch-args-recurse-from-cache [o field-name & fields]
  (let [orig-fn (get-store-cur-js-fn o field-name)
        arg-filter (if (empty? fields) #(identity true) (set fields))]
    (aset o field-name
      (fn [& args]
        ; (.log js/console (str "patching: " (count args)))
        (let [nargs (map #(if (arg-filter %1) (recurse-from-hyde-cache-maybe-fn %2) %2) (range) args)]
          ; (.log js/console (str "patched: " (type (nth nargs 0))))
          (this-as ct (.apply orig-fn ct nargs)))))))

; patch a js function convert specified keyword args to functions
(defn patch-args-keyword-to-fn [o field-name & fields]
  (let [orig-fn (get-store-cur-js-fn o field-name)
        arg-filter (if (empty? fields) #(identity true) (set fields))]
    (aset o field-name
      (fn [& args]
        ; (.log js/console (str "patching: " (count args)))
        (let [nargs (map (fn [c x] 
                           (if (and (arg-filter c) (keyword? x)) #(x %) x))
                         (range) args)]
          ; (.log js/console (str "patched: " (type (nth nargs 0))))
          (this-as ct (.apply orig-fn ct nargs)))))))

; patch a js function, converting specified seqs to js arrays
(defn patch-args-seq-to-array [o field-name & fields]
  (let [orig-fn (get-store-cur-js-fn o field-name)
        arg-filter (if (empty? fields) #(identity true) (set fields))]
    (aset o field-name
      (fn [& args]
        ; (.log js/console (str "patching: " (count args)))
        (let [nargs (map #(if (and (arg-filter %1) (sequential? %2))
                            (apply array %2) %2) (range) args)]
          ; (.log js/console (str "patched: " (type (nth nargs 0))))
          (this-as ct (.apply orig-fn ct nargs)))))))

; the big hammer: patch a js function, converting specified args from clj to js
(defn patch-args-clj-to-js [o field-name & fields]
  (let [orig-fn (get-store-cur-js-fn o field-name)
        arg-filter (if (empty? fields) #(identity true) (set fields))]
    (aset o field-name
      (fn [& args]
        ; (.log js/console (str "patching: " (count args)))
        (let [nargs (map #(if (arg-filter %1) (clj->js %2) %2) (range) args)]
          ; (.log js/console (str "patched: " (type (nth nargs 0))))
          (this-as ct (.apply orig-fn ct nargs)))))))

(defn toclj [x]
  (js->clj x :keywordize-keys true))

; (defn patch-args-clj-to-js [o field-name]
;   (let [orig-fn (aget o field-name)]
;     (aset o field-name
;       (fn [& args]
;         ; (.log js/console (str "patching: " (count args)))
;         (let [nargs (map clj->js args)]
;           ; (.log js/console (str "patched: " (type (nth nargs 0))))
;           (this-as ct (.apply orig-fn ct nargs)))))))

; poor man's unit test
; add this to your html
;
    ; M = {
    ;   testthis: function(a,b,c) {
    ;     console.log("here comes a b c");
    ;     console.log(a);
    ;     console.log(b);
    ;     console.log(c);
    ;   }
    ; }
;
; then
; (this-as ct (aset ct "toclj" #(js->clj % {:keywordize-keys true})))
; (patch-args-clj-to-js js/M "testthis" 0 2)
;
; and from the browser js console:
; M.testthis(toclj({}), toclj({}), toclj({}))
