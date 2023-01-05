(ns atomist.datalog.schema
  (:require
   [clojure.spec.alpha :as s]))

(s/def ::in-clause (s/cat :in #(= :in %)
                          :inputs (s/alt
                                   :outer-input (s/cat :$ #(= '$ %)
                                                       :$b (s/? #(.startsWith (name %) "$"))
                                                       :% (s/? ::rules-var)
                                                       :?ctx (s/? #(= '?ctx %))
                                                       :args (s/? any? #_::bind-coll))
                                   :inner-input (s/cat :$ #(= '$ %)
                                                       :args (s/* (s/alt :variable ::variable :% ::rules-var))))))

(s/def ::query (s/and
                vector?
                (s/cat :find-spec (s/cat :find #(= :find %) :find-exp ::find-exp)
                       :return-map-spec? ::return-map-spec
                       :inputs? ::in-clause
                       :return-map-spec-bad-order? ::return-map-spec
                       :with-clause? (s/? (s/cat :with #(= :with %) :variable+ (s/+ ::variable)))
                       :where-clauses? (s/? (s/cat :where #(= :where %) :clauses (s/+ ::clause))))))

(s/def ::return-map-spec (s/? (s/alt :return-keys (s/cat :keys #(= :keys %) :syms (s/+ symbol?))
                                     :return-syms (s/cat :syms #(= :syms %) :syms (s/+ symbol?))
                                     ::return-strs (s/cat :strs #(= :strs %) :syms (s/+ symbol?)))))

(s/def ::find-exp (s/alt :find-rel (s/+ ::find-elem)
                         :find-coll (s/and vector? (s/cat :find-elem ::find-elem :ellipsis #(= '... %)))
                         :find-tuple (s/and vector? (s/coll-of (s/+ ::find-elem)))
                         :find-scalar (s/cat :find-elem ::find-elem :dot #(= '. %))))

(s/def ::find-spec (s/cat :find #(= :find %)
                          :find-exp ::find-exp))
(s/def ::find-elem (s/alt :variable ::variable :pull-expr ::pull-expr :aggregate ::aggregate :atomist-serialize (s/and list? #(= 'atomist/serialize-on (first %)))))
(s/def ::pull-expr (s/and
                    list?
                    (s/cat :pull #(= 'pull %) :variable ::variable :pattern ::pattern)))
(s/def ::variable #(and (symbol? %) (.startsWith (name %) "?")))
(s/def ::aggregate (s/and list? #(#{'min 'max 'count 'distinct 'count-distinct 'sum 'avg 'median 'variance 'stddev 'first} (first %))))
(s/def ::clause (s/or
                 :not-clause ::not-clause
                 :not-join-clause ::not-join-clause
                 :or-clause ::or-clause
                 :or-join-clause ::or-join-clause
                 :expression-clause ::expression-clause))
(s/def ::and-clause (s/and list? (s/cat :and #(= 'and %) :clauses (s/+ ::clause))))
(s/def ::not-clause (s/and list? (s/cat :src (s/? ::src-var)
                                        :not #(= 'not %)
                                        :clauses (s/+ ::clause))))
(s/def ::or-clause (s/and list? (s/cat :src (s/? ::src-var)
                                       :not #(= 'or %)
                                       :clauses (s/+ (s/alt :clause ::clause :and ::and-clause)))))
(s/def ::not-join-clause (s/and list? (s/cat :src (s/? ::src-var)
                                             :not #(= 'not-join %)
                                             :variables ::variables
                                             :clauses (s/+ ::clause))))
(s/def ::or-join-clause (s/and list? (s/cat :src (s/? ::src-var)
                                            :not #(= 'or-join %)
                                            :variables ::variables
                                            :clauses (s/+ (s/alt :and ::and-clause :clause ::clause)))))
(s/def ::expression-clause (s/or
                            :data-pattern ::data-pattern
                            :pred-expr ::pred-expr
                            :fn-expr ::fn-expr
                            :rule-expr ::rule-expr))



(s/def ::data-pattern (s/and
                       vector?
                       (s/cat :src (s/? ::src-var)
                              :rule (s/+ (s/alt :variable ::variable :_ #(= '_ %) :constant ::constant)))))

(s/def ::sub-query
  (s/or :query ::query
        ::quoted-query (s/and list?
                              (s/cat :quote-symbol #(= 'quote %)
                                     :sub-query ::query))))

(s/def ::fn-call (s/or
                  :simple-fn (s/and list? (s/cat :fn ::function :args (s/+ ::fn-arg)))
                  :sub-query (s/and list? (s/cat :fn ::sub-query-function :sub-query ::sub-query :args (s/+ ::fn-arg)))))
(s/def ::sub-query-function #(= 'q %))
(s/def ::pred-expr (s/cat :fn-call ::fn-call))
(s/def ::fn-expr (s/and
                  vector?
                  (s/cat :fn-call ::fn-call :binding ::binding)))
(s/def ::rule-expr (s/and
                    list?
                    (s/cat :src (s/? ::src-var)
                           :rule-name (s/and
                                       ::unqualified-symbol
                                       (complement #{'and 'and-join 'or 'or-join}))
                           :rule (s/+ (s/alt :variable ::variable :_ #(= '_ %)  :constant ::constant)))))

(s/def ::builtins #(#{'= '!= '< '<= '> '>= '+ '- '* '/
                                    ; TODO all of clojure.core
                                    ; TODO any JVM instance or static method calls?
                                    ; TODO all of fully-qualified symbols that atomist makes available
                      'not= 'ffirst 'empty? 'not-empty 'identity 'str 'clojure.string/starts-with? 'count 'flatten 'sort-by 'take 'vec
                      '.contains
                      'get-else 'get-some 'ground 'missing? 'tuple 'untuple
                      'adb/query 'get-skill-config-value 'decrypted-parameter
                      'atomist/older? 'atomist/index-package} %))

;; predicate or binding functions
(s/def ::function (s/or
                   :built-in ::builtins
                   :anonymous-function (s/and
                                        list?
                                        #(= 'fn (first %)))))

(s/def ::constant (s/or :string string?
                        :number number?
                        :keyword keyword?
                        :query? vector?
                        :boolean? boolean?
                        :nil #(= % 'nil)
                        :set? set?
                        :quoted (s/and
                                 list?
                                 (comp #(= 'quote %) first))))
(s/def ::fn-arg (s/alt :variable ::variable :src-var ::src-var :constant ::constant))

(s/def ::variables (s/and vector? (s/+ ::variable)))
(s/def ::src-var #(and (symbol? %) (.startsWith (name %) "$")))
(s/def ::rules-var (fn [x] (= '% x)))
; TODO unqualified symbols should be replaced by all of the rules we inject
(s/def ::unqualified-symbol #(and (symbol? %) (not (namespace %))))
(s/def ::binding (s/alt
                  :bind-scalar ::bind-scalar
                  :bind-tuple ::bind-tuple
                  :bind-coll ::bind-coll
                  :bind-rel ::bind-rel))
(s/def ::bind-scalar ::variable)
(s/def ::bind-tuple (s/and vector? (s/+ (s/alt :variable ::variable :_ #(= '_ %)))))
(s/def ::bind-coll (s/and vector? (s/cat :variable ::variable :ellipsis #(= '... %))))
(s/def ::bind-rel (s/and vector? (s/cat :tuple ::bind-tuple)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(s/def ::pattern (s/coll-of ::attr-spec))
(s/def ::attr-spec (s/or
                    :attr-name ::attr-name
                    :wildcard ::wildcard
                    :map-spec ::map-spec
                    :attr-expr ::attr-expr))
(s/def ::wildcard #(= '* %))
(s/def ::map-spec (s/map-of (s/or :attr-name ::attr-name :attr-expr ::attr-expr) (s/or :pattern ::pattern :recursion-limit ::recursion-limit)))
(s/def ::attr-expr (s/cat :attr-name ::attr-name :options (s/+ ::attr-option)))

;; TODO type this against a workspace
(s/def ::attr-name keyword?)
(s/def ::attr-option (s/alt :as ::as-expr :limit ::limit-expr :default ::default-expr))
(s/def ::as-expr (s/cat :as #(= :as %) :any-value any?))
(s/def ::limit-expr (s/cat :limit #(= :limit %) :number (s/alt :number ::positive-number :nil nil?)))
(s/def ::default-expr (s/cat :default #(= :default %) :any-value any?))
(s/def ::recursion-limit (s/or :number ::positive-number :ellipsis #(= '... %)))
(s/def ::positive-number (s/and int? #(> % 0)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; schema specs

;; TODO - keys in the attribute map do not always have to be namespaced - however, they kind of should be.
(s/def ::namespaced-keyword (s/and keyword? namespace))

(s/def ::schema (s/keys :req-un [::attributes]))
(s/def ::attributes (s/map-of ::attribute-name ::attribute-definition))
(s/def ::attribute-name keyword?)
(s/def ::attribute-definition (s/or :entity ::entity-attrs
                                    :definition ::schema-definition
                                    :enum (s/keys :opt [:db/doc])))
(s/def :db/doc string?)
(s/def ::entity-attrs (s/keys :req [:db.entity/attrs]))
(s/def :db.entity/attrs (s/and vector? (s/coll-of ::namespaced-keyword)))
(s/def ::schema-definition (s/keys :req [:db/valueType
                                         :db/cardinality]
                                   :opt [:db/doc]))
(s/def :db/valueType #{:db.type/ref
                       :db.type/string
                       :db.type/bigdec
                       :db.type/bigint
                       :db.type/boolean
                       :db.type/double
                       :db.type/float
                       :db.type/instant
                       :db.type/keyword
                       :db.type/long
                       :db.type/symbol
                       :db.type/tuple
                       :db.type/uuid
                       :db.type/uri
                       :db.type/bytes})
(s/def :db/cardinality #{:db.cardinality/one :db.cardinality/many})
(s/def :db/doc string?)

