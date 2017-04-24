(ns libx.util
   (:require [clara.rules :as cr]))

(defn guid []
  #?(:clj (java.util.UUID/randomUUID)
     :cljs (random-uuid)))

(defn attr-ns [attr]
  (subs (first (clojure.string/split attr "/")) 1))

(defn map->tuples
  "Transforms entity map to vector of tuples
  {a1 v1 a2 v2 :db/id eid} -> [ [eid a1 v1] ... ]"
  [m]
  (mapv (fn [[k v]] (vector (:db/id m) k v))
    (dissoc m :db/id)))

(defrecord Tuple [e a v])

(defn third [xs]
  #?(:cljs (nth xs 2)
     :clj (try (nth xs 2)
           (catch java.lang.IndexOutOfBoundsException e
             (throw (ex-info "Received tuple without third slot" {}))))))

(defn record->vec [r]
  (let [v-pos (:v r)
        v (if (and (record? v-pos)
                   (not (record? (first v-pos))))
            (record->vec v-pos)
            v-pos)]
    (vector (:e r) (:a r) v)))

(defn vec->record [vec]
  (let [v-pos (third vec)
        v (if (and (vector? v-pos)
                   (not (record? (first v-pos))))
            (vec->record v-pos)
            v-pos)]
    (->Tuple (first vec)
             (second vec)
             v)))

(defn tuplize-into-vec
  "Returns [[]...].
  Arg may be {} [{}...] [] [[]...]"
  [x]
  (cond
    (map? x) (map->tuples x)
    (map? (first x)) (mapcat map->tuples x)
    (vector? (first x)) x
    :else (vector x)))

(defn insertable [x]
  "Arguments can be any mixture of vectors and records
  Ensures [], [[]...], Tuple, '(Tuple ...) conform to Tuple record instances."
  (cond
    (record? x) (vector x)
    (and (list? x) (record? (first x))) (into [] x)
    (and (vector? x) (vector? (first x))) (map vec->record x)
    (vector? x) (vector (vec->record x))))

(defn insert [session & facts]
  "Inserts Tuples. Accepts {} [{}...] [] [[]...]"
  (let [insertables (map vec->record (mapcat tuplize-into-vec facts))]
    (cr/insert-all session insertables)))

(defn insert! [facts]
  (let [insertables (map vec->record (mapcat tuplize-into-vec (list facts)))]
    (cr/insert-all! insertables)))

(defn insert-unconditional! [facts]
  (let [insertables (map vec->record (mapcat tuplize-into-vec (list facts)))]
    (cr/insert-all-unconditional! insertables)))

(defn retract! [facts]
  "Wrapper around Clara's `retract!`. To be used within RHS of rule only. "
  (let [insertables (insertable facts)]
    (doseq [to-retract insertables]
      (cr/retract! to-retract))))

(defn retract [session & facts]
  "Retracts either: Tuple, {} [{}...] [] [[]..]"
  (let [insertables (map vec->record (mapcat tuplize-into-vec facts))]
    (apply (partial cr/retract session) insertables)))

(defn replace! [session this that]
  (-> session
    (retract this)
    (insert that)))

;TODO. Does not support one-to-many. Attributes will collide
(defn clara-tups->maps
  "Takes seq of ms with keys :?e :?a :?v, joins on :?e and returns
  vec of ms (one m for each entity)"
  [tups]
  (->> (group-by :?e tups)
    (mapv (fn [[id ent]]
            (into {:db/id id}
              (reduce (fn [m tup] (assoc m (:?a tup) (:?v tup)))
                {} ent))))))

;TODO. Does not support one-to-many. Attributes will collide
(defn tuple-entity->hash-map-entity
  "Takes list of tuples for a *single* entity and returns single map"
  [tuples]
  (reduce
    (fn [acc [e a v]]
      (merge acc {:db/id e
                  a v}))
    {} tuples))

(defn get-index-of
  [coll x not-found-idx]
  (let [idx (.indexOf coll x)]
    (if (get coll idx) idx not-found-idx)))

(defn make-activation-group-fn [default-group]
  (fn [m] {:salience (or (:salience (:props m)) 0)
           :group (or (:group (:props m)) default-group)}))

(defn make-activation-group-sort-fn
  [groups default-group]
  (let [default-idx (.indexOf groups default-group)]
    (fn [a b]
      (let [group-a (get-index-of groups (:group a) default-idx)
            group-b (get-index-of groups (:group b) default-idx)]
        (cond
          (< group-a group-b) true
          (= group-a group-b) (> (:salience a) (:salience b))
          :else false)))))

;; From clojure.core.incubator
(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))