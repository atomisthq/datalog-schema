(ns atomist.schema-report
  (:require
   [atomist.datalog.core :as core]
   [babashka.fs :as fs]
   [babashak.process :as process]
   [clojure.edn :as edn]
   [clojure.string]
   [clojure.spec.alpha :as s]))

(def sbom-namespaces #{"artifact" "package" "project" "sbom"})
(def atomist-namespaces #{"atomist"})
(def deployment-namespaces #{"check" "deployment" "image" "linking"})
(def docker-namespaces #{"docker.image" "docker.manifest-list" "docker.platform" "docker.repository" "docker.tag" "docker.registry" "ingestion" "oci"})
(def git-namespaces #{"docker.file" "git" "email" "github" "sarif" "team" "user"})
(def advisory-namespaces #{"vulnerability"})

(def namespaces-to-remove #{"schema"
                            "atomist"
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
                            "vulnerability.cve.baseline"
                            ;; I think this is okay because there's no private data here
                            "vulnerability.cve"})

(def current-enums
  #{:sbom/state
    :check/conclusion
    :image.recorded/status
    :vulnerability/state
    :vulnerability.report/state
    :git.ref/type
    :github.checkrun/conclusion
    :github.checkrun/status
    :github.checksuite/action
    :github.checksuite/conclusion
    :github.checksuite/status
    :sarif.result/kind
    :sarif.result/level
    :docker.image/link-state
    :docker.registry/type
    :docker.repository/type
    :vulnerability.advisory/state})

(def private-attributes
  #{:docker.file/path
    ;; add back when used`
    :project/effective-dependencies
    ;; add back when used`
    :artifact/package
    :package.dependency/dependencies
    :vulnerability.cwe/url})

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
                [(:db/ident entry) entry]
                [(first entry) (-> entry second)])))
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

(s/def ::check-schema (s/map-of keyword? (s/cat :schema any? :schema-file string?)))
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

(s/def ::check (s/coll-of (s/cat :skill-name string?
                                 :file-name string?
                                 :data any?)))
(defn check
  "check all known datalog subscriptions and queries
    returns ::check (attribute data used by subscriptions and queries) "
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
             (let [{:keys [status report data]} (core/check {} (edn/read-string (slurp (.toString f))))]
               (case status
                 :valid report
                 :invalid (pr-str data)))]
            (catch Throwable _ [f :exception]))))))


(defn fix-back-ref-keyword [k]
  (if (and (namespace k) (.startsWith (name k) "_"))
    (keyword (namespace k) (clojure.string/replace-first  (name k) #"^_" ""))
    k))

(defn add-sources [source m coll]
  (reduce #(update %1 %2 (fnil conj []) source) m coll))

(defn checked-attribute-has-type? [[_ [{:db/keys [valueType]}]]] valueType)
(defn checked-attribute-is-ref? [[k [{:db/keys [valueType]}]]] (and (= valueType :db.type/ref) (not (current-enums k))))

(defn entity-for-attribute [k]
  (println "check " k)
  (try
    (-> (clojure.edn/read-string
         (:out (deref (process/process ["bb" "cli" "q" "--team" "AQ1K5FIKA"
                                        "--query"
                                        (pr-str [:find
                                                 '(distinct ?from-type)
                                                 :in '$ '$b '% '?ctx
                                                 :where
                                                 ['?from k '_]
                                                 ['?from :schema/entity-type '?from-type]])
                                        "--token"
                                        (slurp "/Users/slim/.atomist/bb.jwt")]
                                       {:out :string :err :string :dir "/Users/slim/atmhq/bb_scripts"}))))
        first
        first)
    (catch Throwable t (println "failed " t))))

(defn distinct-refs [k]
  (println "check " k)
  (try
    (-> (clojure.edn/read-string
         (:out (deref (process/process ["bb" "cli" "q" "--team" "AQ1K5FIKA"
                                        "--query"
                                        (pr-str [:find
                                                 '(distinct ?from-type)
                                                 '(distinct ?to-type)
                                                 :in '$ '$b '% '?ctx
                                                 :where
                                                 ['?from k '?to]
                                                 ['?from :schema/entity-type '?from-type]
                                                 ['?to :schema/entity-type '?to-type]])
                                        "--token"
                                        (slurp "/Users/slim/.atomist/bb.jwt")]
                                       {:out :string :err :string :dir "/Users/slim/atmhq/bb_scripts"}))))
        first)
    (catch Throwable t (println "failed " t))))

