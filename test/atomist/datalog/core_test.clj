(ns atomist.datalog.core-test
  (:require
   [clojure.pprint :as pprint]
   [atomist.datalog.core :refer [check report]]
   [atomist.datalog.schema :as schema]
   [clojure.spec.alpha :as spec]
   [clojure.test :as t]
   [clojure.edn :as clojure.edn]))

(t/deftest subscription-report
  (t/is
   (=
    (report
     (spec/conform
      ::schema/query
      (clojure.edn/read-string (slurp "/Users/slim/atmhq/docker-capability/datalog/subscription/find-new-official-images-tags-schedule.edn"))))
    '{:attributes
      #{:docker.tag/name
        :docker.repository/host
        :skill-id
        :atomist.skill/team-id
        :db/id
        :atomist.skill/id
        :docker.tag/repository
        :docker.repository/repository
        :docker.repository/last-checked-at},
      :rules #{map-value schedule-tx string-match},
      :functions #{atomist/older? missing?}})))

(comment
  (pprint/pprint (check {} ""))
; empty path -? predicate is clojure.core/vector?
;  via :atomist.datalog.schema/query
; in empty
  (pprint/pprint (check {} []))
; path :find-spec :find has a failed predicate
;  via :atomist.datalog.schema/query
; in empty
  (pprint/pprint (check {} [:find]))
; path :find-spec :find-exp
; via :atomist.datalog.schema/query :atomist.datalog.schema/find-exp
; in empty
  (pprint/pprint (check {} [:find '?a]))
; path :inputs? :in
; via :atomist.datalog.schema/query :atomist.datalog.schema/in-clause
; in empty
  (pprint/pprint (check {} [:find '?a :in]))
; path :inputs? :inputs
; via :atomist.datalog.schema/query
; in empty
  (pprint/pprint (check {} '[:find ?a :in $ $b % ?ctx :bl []]))
; path 
; via atomist.datalog.schema/query
; in 8
; which points me at the 8th symbol
  (pprint/pprint (check {} '[:find (pull ?a [*]) :in $ $b % ?ctx :where (tx-attribute-value ?a) [(q (quote [:find]) $) $results]]))
; longest in is 9 0 1 1
  (pprint/pprint (check {} '[:find ?a :in $ $b % ?ctx :where (tx-attribute-value ?a :a/b ?b)])))

