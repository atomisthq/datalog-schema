(ns user
  (:require [atomist.datalog.core]
            [atomist.datalog.schema :as schema]
            [clojure.spec.alpha :as spec]
            [atomist.schema-report :as sr]
            [clojure.data :as data]
            [clojure.pprint :refer [pprint]]
            [babashka.process :as process]
            [babashka.fs :as fs]
            [clojure.edn :as clojure.edn]
            [clojure.string :as string]))

(atomist.datalog.core/report
 (spec/conform ::schema/query '[:find (pull ?a [:git.commit/message])
                                :in $ $b % ?ctx
                                :where
                                [?a :git.commit/sha "asdf"]]))

;; ===================================

(def entities (->> (sr/check-entities)
                   (mapcat (fn [[_ _ x]] (keys x)))
                   (sort-by #(str (namespace %) (name %)))
                   (into #{})))
;; extracted from subscriptions
(def checked-subscriptions (sr/check {}))
;; extracted from schema files
(def checked-attributes (->> (sr/check-schema)
                             (filter (complement (fn [[k _]] (sr/namespaces-to-remove (namespace k)))))
                             (filter (complement (fn [[k _]] (entities k))))
                             (filter (complement (fn [[k _]] (sr/private-attributes k))))
                             (into {})))

(def attribute->entities
  (->> checked-attributes
       (filter sr/checked-attribute-has-type?)
       (filter (complement sr/checked-attribute-is-ref?))
       (map (fn [[k _]] [k (sr/entity-for-attribute k)]))))
(pprint attribute->entities)
(spit "attribute->entities.edn" (pr-str (into {} attribute->entities)))
(def ref-attributes->relations
  (->> checked-attributes
       (filter sr/checked-attribute-has-type?)
       (filter sr/checked-attribute-is-ref?)
       (map (fn [[k _]] [k (sr/distinct-refs k)]))))
(pprint ref-attributes->relations)
(spit "ref-attributes->relations.edn" (pr-str (into {} ref-attributes->relations)))

(defn in-namespaces? [s [k _]] (some #(and
                                       (namespace k)
                                       (.startsWith (namespace k) %)) s))
;; tables by category
(doseq [[n s] [["docker" sr/docker-namespaces]
               ["sbom" sr/sbom-namespaces]
               ["git" sr/git-namespaces]
               ["advisory" sr/advisory-namespaces]
               ["deploy" sr/deployment-namespaces]
               ["atomist" sr/atomist-namespaces]]]
  (spit (format "%s.md" n)
        (str
         "### Attributes\n"
         (str "| attribute | type | doc | entities |\n| :---- | :---- | :---- | :----- |\n"
              (->> checked-attributes
                   (filter sr/checked-attribute-has-type?)
                   (filter (partial in-namespaces? s))
                   (filter (complement sr/checked-attribute-is-ref?))
                   (sort-by first)
                   (map (fn [[k [{:db/keys [doc valueType]} _]]]
                          (format "| %s | %s | %s | %s |"
                                  (str k)
                                  (cond 
                                    (and valueType (= valueType :db.type/ref))
                                    "enum"
                                    valueType (name valueType) 
                                    :else "")
                                  (or doc "")
                                  (or (->> (k (into {} attribute->entities)) (map str) (string/join "<br/>")) ""))))
                   (interpose "\n")
                   (apply str)))
         "\n\n### Relationships\n\n"
         (str "| attribute | doc | from | to |\n| :---- | :---- | :---- | :----- |\n"
              (->> checked-attributes
                   (filter sr/checked-attribute-has-type?)
                   (filter (partial in-namespaces? s))
                   (filter sr/checked-attribute-is-ref?)
                   (sort-by first)
                   (map (fn [[k [{:db/keys [doc]} _]]]
                          (let [[froms tos] (k (into {} ref-attributes->relations))]
                            (format "| %s | %s | %s | %s |"
                                    (str k)
                                    (or doc "")
                                    (or (->> froms (map str) (string/join "<br/>")) "")
                                    (or (->> tos (map str) (string/join "<br/>")) "")))))
                   (interpose "\n")
                   (apply str)))))
  (fs/copy (fs/file (format "%s.md" n)) (fs/file "/Users/slim/atomisthq/docs/docs/data" (format "%s.md" n)) {:replace-existing true}))

; attributes rules and function map
(def reverse-index
  (reduce (fn [agg [skill subscription {:keys [attributes rules functions]}]]
            (let [source (format "%s/%s" skill subscription)
                  add (partial sr/add-sources source)]
              (-> agg
                  (update :attributes (fnil add {}) (->> attributes (map sr/fix-back-ref-keyword)))
                  (update :rules (fnil add {}) rules)
                  (update :functions (fnil add {}) functions))))
          {}
          checked-subscriptions))

; compare schema attributes to the subscription and attributes
(let [[current-schema-only used-only both] (data/diff
                                            (into #{} (keys checked-attributes))
                                            (into #{} (keys (:attributes reverse-index))))]
  ; used-but-not-defined must all be from the attributes in our namespaces-to-remove set
  (println "attributes from schema files (some namespaces removed)" (count checked-attributes))
  (println "both used and defined" (count both))
  (println "only defined:  " (count current-schema-only))
  (println "only used: " (count used-only))
  (println "-------- only defined")
  (pprint current-schema-only)
  (println "-------- both used and defined")
  (pprint both)
  (println "-------- just used")
  (pprint used-only))

; to document - checked-attributes

(defn- part-of-namespaces [s k]
  (some #(and
          (namespace k)
          (.startsWith (namespace k) %)) s))

(defn- filter-attributes [all-attrs ns-coll] (filter (partial part-of-namespaces ns-coll) (keys all-attrs)))

(def docker-attributes (filter-attributes checked-attributes sr/docker-namespaces))
(def sbom-attributes (filter-attributes checked-attributes sr/sbom-namespaces))
(def git-attributes (filter-attributes checked-attributes sr/git-namespaces))
(def advisory-namespaces (filter-attributes checked-attributes sr/advisory-namespaces))
(def deployment-namespaces (filter-attributes checked-attributes sr/deployment-namespaces))
(def atomist-namespaces (filter-attributes checked-attributes sr/atomist-namespaces))

(let [[defined part-of both] (data/diff (into #{} (keys checked-attributes)) (into #{} (concat docker-attributes sbom-attributes git-attributes advisory-namespaces deployment-namespaces atomist-namespaces)))]
  (println (count defined) (count part-of) (count both))
  (println defined))

; print counts by type
(doseq [s [sr/docker-namespaces
           sr/sbom-namespaces
           sr/git-namespaces
           sr/advisory-namespaces
           sr/deployment-namespaces
           sr/atomist-namespaces]]
  (->> checked-attributes
       (filter sr/checked-attribute-has-type?)
       (map first)
       (filter (fn [k] (some #(and
                               (namespace k)
                               (.startsWith (namespace k) %)) s)))
       (count)
       (println)))

;; 501 attributes across these 6 categories
(+ 111 38 195 72 22 63)

(spit "attributes.md"
      (->> (merge
            (->> (keys checked-attributes)
                 (map (fn [k] [k []]))
                 (into {}))
            (:attributes reverse-index))
           (seq)
           (sort-by first)
           (filter #(-> % first namespace))
           (filter (complement #(sr/namespaces-to-remove (-> % first namespace))))
           (map (fn [[k coll]]
                  (format "| `%s` | %s | %s | %s |"
                          k
                          (-> checked-attributes (get k) first)
                          (-> checked-attributes (get k) second)
                          (->> coll (interpose "<br>") (apply str)))))
           (interpose "\n")
           (apply str)
           ((fn [s] (format "| attribute | documentation | defined-by | used-by |\n| :---- | :----- | :----- | :----- |\n%s" s)))))

(spit "rules.md"
      (->> (seq (:rules reverse-index))
           (sort-by first)
           (map (fn [[k coll]]
                  (format "| `%s` | %s |" k (->> coll (interpose "<br>") (apply str)))))
           (interpose "\n")
           (apply str)
           ((fn [s] (format "| rule | sources |\n| :---- | :----- |\n%s" s)))))

(spit "functions.md"
      (->> (seq (:functions reverse-index))
           (sort-by first)
           (map (fn [[k coll]]
                  (format "| `%s` | %s |" k (->> coll (interpose "<br>") (apply str)))))
           (interpose "\n")
           (apply str)
           ((fn [s] (format "| function | sources |\n| :---- | :----- |\n%s" s)))))

