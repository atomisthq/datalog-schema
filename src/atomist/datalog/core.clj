(ns atomist.datalog.core
  (:require
   [atomist.datalog.schema :as schema]
   [babashka.fs]
   [clojure.edn]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [rewrite-clj.zip :as z]))

(s/def ::attributes (s/coll-of keyword?))
(s/def ::rules (s/coll-of string?))
(s/def ::functions (s/coll-of string?))
(s/def ::report (s/keys :req-un [::attributes ::rules ::functions]))
(defn ^{:doc " returns ::report"} report
  ([stack form]
   (let [merge-report (fn [agg report]
                        (-> agg
                            (update :attributes (fnil #(into #{} (concat %1 %2)) []) (:attributes report))
                            (update :rules (fnil #(into #{} (concat %1 %2)) []) (:rules report))
                            (update :functions (fnil #(into #{} (concat %1 %2)) []) (:functions report))))]
     (if (coll? form)
       (loop [d {} items (seq form)]
         (if-let [item (first items)]
           (recur (merge-report
                   d
                   (report
                    (if (and (vector? item) (-> item first keyword?))
                      (conj stack (first item))
                      stack)
                    (first items))) (rest items))
           d))
       (cond
         (and
          (= (take-last 4 stack) '(:data-pattern :rule :constant :keyword))
          (keyword? form)
          (not (= :keyword form))) {:attributes [form]}
         (and
          (= :map-spec (first (take-last 2 stack)))
          (keyword? (last stack))) {:attributes [(last stack)]}
         (and
          (= (take-last 2 stack) '(:pattern :attr-name))
          (keyword? form)
          (not (= :attr-name form))) {:attributes [form]}
           ;; rules
         (and
          (= (take-last 3 stack) '(:expression-clause :rule-expr :rule-name))
          (symbol? form)) {:rules [form]}
         (and
          (= (take-last 4 stack) '(:rule-expr :rule :constant :keyword))
          (keyword? form)
          (not (= :keyword form))) {:attributes [form]}
           ;; functions
         (and
          (= (take-last 5 stack) '(:fn-call :simple-fn :args :constant :keyword))
          (keyword? form)
          (not (= :keyword form))) {:attributes [form]}
         (and
          (= (take-last 4 stack) '(:fn-call :simple-fn :fn :built-in))
          (symbol? form)) {:functions [form]}
         (and
             ;; TODO if we have more than one sub-query function
          (= (take-last 3 stack) '(:fn-call :sub-query :fn))
          (symbol? form)) {:functions [form]}
         :else
         {}))))
  ([form]
   (report [] form)))

(defn current-schema
  "read current-schema.edn from .atomist cache"
  [cli-options]
  (try
    (let [schema (-> (babashka.fs/file
                      (str (or
                            (-> cli-options :options :basedir)
                            (System/getenv "HOME")))
                      ".atomist"
                      "current-schema.edn")
                     (slurp)
                     (clojure.edn/read-string))]
      (->> schema
           (map first)
           (map (fn [{:db/keys [_ ident doc]}] [ident doc]))
           (into {})))
    (catch Throwable _ {})))

(s/def ::attribute-report (s/map-of keyword? (s/or :doc string? :status #{:invalid :undocumented})))
(defn attribute-report
  "returns ::atribute-report"
  [cli-options attributes local-schema]
  (let [attribute->doc (merge
                        (current-schema cli-options)
                        (->> (seq local-schema)
                             (map (fn [[k v]] [k (:db/doc v)]))
                             into {}))
        valid-attributes (into #{} (concat (keys attribute->doc) (keys local-schema)))]
    (->> attributes
         (filter (complement #(and
                               (.startsWith (name %) "_")
                               (valid-attributes (keyword (namespace %) (subs (name %) 1))))))
         (map #(cond
                 (get attribute->doc %) [% (get attribute->doc %)]
                 (valid-attributes %) [% :undocumented]
                 :else [% :invalid]))
         (into {}))))

(defn validate-local-schema
  "check all of the local schema files
    return a merged copy of the attribute maps if valid - throw ex-info if invalid
    return an empty map if there are no local schema"
  [cli-options]
  (let [schema-dir (babashka.fs/file
                    (-> cli-options :options :basedir)
                    "datalog"
                    "schema")]
    (if (babashka.fs/exists? schema-dir)
      (let [schemas (for [f (babashka.fs/list-dir schema-dir)]
                      (let [edn (try (clojure.edn/read-string (slurp (str f))) (catch Throwable _ :invalid))
                            schema-valid? (if (not (= :invalid edn))
                                            (s/valid? ::schema/schema edn)
                                            false)]
                        [(str f) schema-valid? (if schema-valid? edn (s/explain-data ::schema/schema edn))]))]
        (if (some (fn [[_ v _]] (not v)) schemas)
          (throw (ex-info "invalid schema files" {:schemas (into [] schemas)}))
          (->> schemas
               (map #(nth % 2))
               (map :attributes)
               (apply merge))))
      {})))

(s/def ::valid #{:valid :invalid :invalid-schema})
(s/def ::message string?)
(s/def ::check  (s/keys :req-un [::valid ::message]
                        :opt-un [::conformed ::report ::atribute-report ::data]))

(defn check
  "check datalog edn
    returns ::check"
  [cli-options edn]
  (try
    ; the local-schema has to be valid before we can validate subscriptions
    (let [local-schema (validate-local-schema cli-options)]
      ; schema is valid! - now verify the subscription
      (if (s/valid? ::schema/query edn)
        (let [conformed (s/conform ::schema/query edn)
              r (report conformed)
              r1 (attribute-report cli-options (:attributes r) local-schema)]
          (merge
           {:conformed conformed
            :report r
            :attribute-report r1}
           (if (some #{:invalid} (vals r1))
             {:status :invalid
              :message (format
                        "some attributes are not valid - %s"
                        (->> r1
                             (filter #(-> % second (= :invalid)))
                             (into {})
                             (keys)))}
             {:status :valid
              :message "schema is valid"})))
        (let [data (s/explain-data ::schema/query edn)]
          {:status :invalid
           :message "not sure"
           :data data})))
    (catch Exception e {:status :invalid-schema
                        :message (.getMessage e)
                        :data (ex-data e)})))

(defn- range-all [s]
  {:start {:line 0 :character 0}
   :end (let [lines (string/split-lines s)] {:line (dec (count lines)) :character (count (last lines))})})

(defn- location-of [s k]
  (if-let [[n line] (->> (interleave (range) (string/split-lines s))
                         (partition 2)
                         (filter (fn [[_ line]] (string/includes? line (str k))))
                         first)]
    {:start {:line n :character 0}
     :end {:line n :character (count line)}}
    (range-all s)))

(defn shift-zipper-right [zloc n]
  (last (take (+ n 1) (iterate z/right (z/down zloc)))))

(defn- edn-location [s in]
  (let [zloc (z/of-string s {:track-position? true})
        [row column] (z/position (reduce shift-zipper-right zloc in))
        r (- row 1) ]
    {:start {:line r :character column}
     :end {:line r :character (count (nth (string/split-lines s) r))}}))

#_:clj-kondo/ignore
(defn diagnostics [s]
  (try
    (let [edn (clojure.edn/read-string s)
          {:keys [status message] :as checked} (check {} edn)]
      (case status
        :invalid-schema {:message message
                         :range (range-all s)}
        :valid []
        :invalid (cond
                   ; valid schema but some invalid attributes
                   (:conformed checked)
                   (->> (:attribute-report checked)
                        (filter (fn [[_ v]] (= :invalid v)))
                        (map (fn [[k _]] {:message (format "%s is not valid" k)
                                          :range (location-of s k)}))
                        (into []))
                   ; invalid schema
                   (:data checked)
                   (->> (-> checked :data :clojure.spec.alpha/problems)
                        (map (fn [{:keys [path pred val via in]}]
                               ; path pred val are always set - via and in may be nil
                               ; in is vector of ints jumping to the right part of the edn data structure
                               ; via is a vector
                               {:message (format "tried predicate %s on value %s at %s"  pred val (last path))
                                :range (edn-location s in)}))
                        (into [])))
        [{:message "unrecognized"
          :range (range-all s)}]))
    (catch Throwable ex
      (println ex)
      [{:message "invalid edn"
        :range (range-all s)}])))
