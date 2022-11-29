(ns atomist.datalog.core-test
  (:require
    [clojure.pprint :as pprint]
    [atomist.datalog.core :refer [check]]
    [clojure.edn :as clojure.edn]))

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

(comment
  (def conformed (second (check {} (clojure.edn/read-string (slurp "/Users/slim/atmhq/docker-capability/datalog/subscription/find-new-official-images-tags-schedule.edn"))))))
