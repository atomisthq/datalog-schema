(ns atomist.schema-report
  (:require
   [atomist.datalog.schema :as schema]
   [atomist.datalog.core :as core]
   [babashka.fs :as fs]
   [clojure.data :as data]
   [clojure.edn :as edn]
   [clojure.pprint :refer [pprint]]
   [clojure.string]
   [clojure.spec.alpha :as s]))

(def sbom-namespaces #{"artifact" "package" "project" "sbom"})
(def atomist-namespaces #{"atomist"})
(def deployment-namespaces #{"check" "deployment" "image" "linking"})
(def docker-namespaces #{"docker" "ingestion" "oci"})
(def git-namespaces #{"git" "email" "github" "sarif" "team" "user"})
(def advisory-namespaces #{"vulnerability"})

(def namespaces-to-remove #{"atomist"
                            "analysis.discovery"
                            "analysis"
                            "analysis.discovery.source"
                            "analysis.discovery.status"
                            "db.part"
                            "db.type"
                            "db"
                            "db.alter"
                            "db.attr"
                            "db.bootstrap"
                            "db.cardinality"
                            "db.entity"
                            "db.excise"
                            "db.install"
                            "db.unique"
                            "vonwig.testing.observation"
                            "vonwig.testing"
                            "os"
                            "os.distro"
                            "os.package-manager"
                            "docker.analysis"
                            "docker.analysis.diff"
                            "docker.analysis.report"
                            "github.convergence.comment"
                            "github.convergence.comment.ref"
                            "github.convergence.comment.tag"
                            "chat"
                            "chat.channel"
                            "chat.team"
                            "chat.user"
                            "falco"
                            "falco.alert"
                            "vulnerability.cpe"
                            "vulnerability.cve.baseline"})

(def atomisthq-skills #{"docker-capability"
                        "dockerhub-internal-integration"
                        "gcr-integration"
                        "gar-integration"
                        "ecr-integration"
                        "ghcr-integration"
                        "docker-file-policy"
                        "deploy-integration"
                        "file-indexer-skill"
                        "common-clj"
                        "neo4j-ingester"})
(def atomist-skills-skills #{"docker-vulnerability-policy"
                             "docker-vulnerability-scanner-skill"
                             "docker-base-image-policy"
                             "github-secret-scanner-skill"
                             "skill-registration-skill"
                             "pusher-skill"})
(def dso-web-files ["atomisthq/dso-web/src/atomist/webapp/datalog_queries"])

(defn datalog-dir
  " return the file for the datalog/subscription dir"
  [f]
  (condp (fn [dir basedir]
           (let [d (fs/file basedir dir)]
             (when (fs/exists? d) d))) f
    "datalog/subscription" :>> identity
    f))

(defn schema-dir
  " return the file for dir that might contain datalog schema files"
  [f]
  (condp (fn [dir f]
           (let [d (fs/file f dir)]
             (when (fs/exists? d) d))) f
    "datalog/schema" :>> identity
    "resources/datomic/schema" :>> identity
    "resources/ingestion-schemas" :>> identity
    "resources" :>> identity
    f))

(defn skill-name
  "extract skill-name using basedir"
  [f]
  (let [skill-path->name
        (fn [coll _]
          (first (filter #(.contains (str f) %) coll)))]
    (condp skill-path->name f
      dso-web-files :>> (constantly "dso-web")
      atomist-skills-skills :>> identity
      atomisthq-skills :>> identity
      :unrecognized)))

(defn is-schema-edn?
  "check whether this is an edn file containing a map with an :edn key"
  [f]
  (let [content (slurp (str f))
        schema (edn/read-string content)]
    (and (map? schema) (:attributes schema))))

(defn attribute->doc
  "build a map of the :db/ident value to the :db/doc entry"
  [schema-edn]
  (->> (:attributes schema-edn)
       (map (fn [entry]
              (if (:db/ident entry)
                [(:db/ident entry) (:db/doc entry)]
                [(first entry) (-> entry second :db/doc)])))
       (into {})))

(defn filter-entities
  "filter out the entity attrs
     return map of :db/ident to schema"
  [schema-edn]
  (->> (:attributes schema-edn)
       (map (fn [entry]
              (if (:db/ident entry)
                [(:db/ident entry) entry]
                [(first entry) (-> entry second)])))
       (filter (fn [entry]
                 (-> entry second :db.entity/attrs)))
       (into {})))

(defn all-schema-files
  "return coll of all schema files"
  []
  (->>
   (for [s (concat
            (map #(str "atomisthq/" %) atomisthq-skills)
            (map #(str "atomist-skills/" %) atomist-skills-skills))]
     (fs/file "/Users/slim" s))
   (map schema-dir)
   (mapcat #(fs/list-dir % "*.edn"))
   (filter is-schema-edn?)))

(s/def ::entities (s/coll-of (s/cat :skill-name string?
                                    :file-name string?
                                    :map (s/map-of keyword? (s/map-of keyword? (s/coll-of keyword?))))))

(defn check-entities
  " return ::entities"
  []
  (->>
   (all-schema-files)
   (map (fn [f]
          (try
            [(skill-name f)
             (str (.getFileName f))
             (filter-entities (edn/read-string (slurp (str f))))]
            (catch Throwable _ [f :exception]))))))

(comment
  ;; returns set of all current entities
  (->> (check-entities)
       (mapcat (fn [[_ _ x]] (keys x)))
       (sort-by #(str (namespace %) (name %)))
       (into [])))

(s/def ::check-schema (s/map-of keyword? (s/cat :doc any? :schema-file string?)))
(defn check-schema
  " returns ::check-schema"
  []
  (->>
   (all-schema-files)
   (map (fn [f]
          (try
            [(skill-name f)
             (str (.getFileName f))
             (attribute->doc (edn/read-string (slurp (str f))))]
            (catch Throwable _ [f :exception]))))
   (map (fn [[skill-name file-name m]]
          (->> m
               (map (fn [[k v]] [k [v (format "%s/%s" skill-name file-name)]]))
               (into {}))))
   (apply merge)))

(comment
  (s/valid? ::check-schema (check-schema)))

(defn check
  "check all known datalog subscriptions and queries"
  [& _]
  (->>
   (for [s (concat
            (map #(str "atomisthq/" %) atomisthq-skills)
            (map #(str "atomist-skills/" %) atomist-skills-skills)
            dso-web-files)]
     (fs/file "/Users/slim" s))
   (map datalog-dir)
   (mapcat #(fs/list-dir % "*.edn"))
   (map (fn [f]
          (try
            [(skill-name f)
             (str (.getFileName f))
             (let [[status s] (core/check (edn/read-string (slurp (.toString f))))]
               (case status
                 :conformed (core/report s)
                 :invalid (pr-str s)))]
            (catch Throwable _ [f :exception]))))))

(defn fix-back-ref-keyword [k]
  (if (and (namespace k) (.startsWith (name k) "_"))
    (keyword (namespace k) (clojure.string/replace-first  (name k) #"^_" ""))
    k))

(defn add-sources [source m coll]
  (reduce #(update %1 %2 (fnil conj []) source) m coll))

;; ===================================
(comment
  (def checked-subscriptions (check {}))
  (def checked-attributes (->> (check-schema)
                               (filter (complement (fn [[k _]] (namespaces-to-remove (namespace k)))))
                               (into {})))

  ; attributes rules and function map
  (def reverse-index
    (->> checked-subscriptions
         (reduce
          (fn [agg [skill subscription {:keys [attributes rules functions]}]]
            (let [source (format "%s/%s" skill subscription)
                  add (partial add-sources source)]
              (-> agg
                  (update :attributes (fnil add {}) (->> attributes (map fix-back-ref-keyword)))
                  (update :rules (fnil add {}) rules)
                  (update :functions (fnil add {}) functions))))
          {})))

  ; compare schema attributes to the subscription and attributes
  (let [[current-schema-only used-only both] (data/diff
                                              (into #{} (keys checked-attributes))
                                              (into #{} (keys (:attributes reverse-index))))]
    (println "all attributes" (count checked-attributes))
    (println "both: " (count both))
    (println "not-used:  " (count current-schema-only))
    (println "only-found: " (count used-only))
    (pprint current-schema-only)
    (pprint both)
    (pprint used-only))

  ; print counts by type
  (doseq [s [docker-namespaces sbom-namespaces git-namespaces advisory-namespaces deployment-namespaces atomist-namespaces]]
    (->> (keys checked-attributes)
         (filter (fn [k] (some #(and
                                 (namespace k)
                                 (.startsWith (namespace k) %)) s)))
         (count)
         (println)))

  (spit "attributes.md"
        (->> (merge
              (->> (keys checked-attributes)
                   (map (fn [k] [k []]))
                   (into {}))
              (:attributes reverse-index))
             (seq)
             (sort-by first)
             (filter #(-> % first namespace))
             (filter (complement #(namespaces-to-remove (-> % first namespace))))
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
             ((fn [s] (format "| function | sources |\n| :---- | :----- |\n%s" s))))))
