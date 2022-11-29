(ns atomist.datalog.schema-test
  (:require
   [clojure.test :as t]
   [clojure.spec.alpha :as s]
   [atomist.datalog.schema :as schema]))

(t/deftest function-tests
  (t/is
   (not (s/valid? ::schema/fn-call '(q ?blah $)))
   "q functions should not have variables as the first arg")
  (t/is
   (not (s/valid? ::schema/fn-call '(bad-symbol ?blah)))
   "test that unrecognized function symbols should be blocked")
  (t/is
   (=
    (s/conform ::schema/expression-clause '[(q (quote [:find ?a :in $ $b % ?ctx :where [?a]]) $) ?a])
    '[:fn-expr
      {:fn-call
       [:sub-query
        {:fn q,
         :sub-query [:atomist.datalog.schema/quoted-query
                     {:quote-symbol quote,
                      :sub-query {:find-spec {:find :find,
                                              :find-exp [:find-rel
                                                         [[:variable ?a]]]},
                                  :inputs? {:in :in,
                                            :inputs [:outer-input {:$ $,
                                                                   :$b $b,
                                                                   :% %,
                                                                   :?ctx ?ctx}]},
                                  :where-clauses? {:where :where,
                                                   :clauses [[:expression-clause
                                                              [:data-pattern
                                                               {:rule [[:variable ?a]]}]]]}}}],
         :args [[:src-var $]]}], :binding [:bind-scalar ?a]}])
   "test that queries take sub-queries with four inputs")
  (t/is
   (=
    (s/conform ::schema/expression-clause '[(= ?blah ?blah1)])
    '[:pred-expr {:fn-call [:simple-fn {:fn [:built-in =], :args [[:variable ?blah] [:variable ?blah1]]}]}])
   "test pred-expr conforms")
  (t/is
   (s/valid? ::schema/fn-call '(atomist/older? ?last-checked 1380))
   "test that atomist/older? is a valid fn-call")

  ;; TODO still not working for some entity-count.edn
  (s/conform ::schema/expression-clause '[(q (quote []) $) ?a])
  (s/conform ::schema/expression-clause '[(q (quote []) $) [[[[?type]]]]]))

(t/deftest binding-tests
  (t/is
   (s/valid? ::schema/bind-rel '[[?a]]))
  ;; this seems invalid but this can be used to bind an ?a relation that comes out of a sub query so it's actually useful!
  ;;(s/valid? ::bind-rel '[[[[?a]]]])
  )

(t/deftest schema-spec-tests
  (t/is (not (s/valid? ::schema/schema {:attributes
                                 {:git.commit/signature {:db/valueType :db.type/string
                                                         :db/cardinality :db.cardinality/wrong}}})))
  (t/is (s/valid? ::schema/schema {:attributes
                            {:git.commit/signature              {:db.entity/attrs [:git.commit.signature/commit]}
                             :git.commit.signature/commit       {:db/valueType   :db.type/ref
                                                                 :db/doc         "Reference to :git/commit"
                                                                 :db/cardinality :db.cardinality/one}
                             :git.commit.signature/NOT_VERIFIED {}}})))

(t/deftest specs
  (t/is (s/valid? ::schema/unqualified-symbol 'blah) "check unqualified symbol")
  (t/is (not (s/valid? ::schema/unqualified-symbol 'a/blah)) "check qualitfied symbol")
  (t/is (=
         (-> (:clojure.spec.alpha/problems (s/explain-data ::schema/query '[])) first (select-keys [:path :reason]))
         {:path [:find-spec :find],
          :reason "Insufficient input"}) "check empty array")
  (t/is
   (=
    (->> (s/explain-data ::schema/query '[:find]) :clojure.spec.alpha/problems (map #(select-keys % [:path :reason :val :via :in])))
    '({:path [:find-spec :find-exp],
       :reason "Insufficient input",
       :val (),
       :via [:atomist.datalog.schema/query :atomist.datalog.schema/find-exp],
       :in []})) "check empty find")

  (t/is
   (every?
    #(= :where (:val %))
    (->> (s/explain-data ::schema/query '[:find :where]) :clojure.spec.alpha/problems (map #(select-keys % [:val])))) "check empty :find :where")

  (t/is (s/valid? ::schema/query '[:find ?a (max ?b) :in $ $b % ?ctx :where [_]]) "check with empty where")
  (t/is (s/valid? ::schema/query '[:find ?a (max ?b) :in $ :where [_]]) "check db in with empty where")
  (t/is
   (s/valid? ::schema/query '[:find (pull ?a [*]) :in $ :where [? :a/b "45"]])
   "check pull expression with wildcard")
  (t/is
   (=
    (s/conform ::schema/query '[:find (pull ?a [*]) :in $ :where [? :a/b "45"]])
    '{:find-spec
      {:find :find,
       :find-exp
       [:find-rel
        [[:pull-expr
          {:pull pull,
           :variable ?a,
           :pattern [[:wildcard *]]}]]]},
      :inputs?
      {:in :in,
       :inputs [:outer-input {:$ $}]},
      :where-clauses?
      {:where :where,
       :clauses
       [[:expression-clause
         [:data-pattern
          {:rule
           [[:variable ?]
            [:constant [:keyword :a/b]]
            [:constant [:string "45"]]]}]]]}})
   "test working query"))
