## Usage

Use `atomist.datalog.core/report` to report on a datalog form that conforms to `:atomist.datalog.schema/query`.

```clj
(ns user
  (:require [atomist.datalog.core]
            [atomist.datalog.schema :as schema]
            [clojure.spec.alpha :as spec]))

(atomist.datalog.core/report
  (spec/conform ::schema/query '[:find (pull ?a [:git.commit/message])
                                 :in $ $b % ?ctx
                                 :where
                                 [?a :git.commit/sha "asdf"]]))

```

If the datalog is conformed, then `atomist.datalog.core/report` will walk the conformed data and extract attributes, rules and functions.  In the above example, the return value would be the following.

```clj
{:attributes #{:git.commit/message :git.commit/sha},
 :rules #{},
 :functions #{}}
```

## Notes

* handles sub queries
* extract attributes from data rules, and pull expressions
* rejects functions that are not built-in or listed in the schema (`atomist/older?` `atomist/indexpackage`)
* accepts all rule names but I'd like to restrict to the set that I know we support - [current rules](https://docs.atomist.services/authoring/datalog/rules/)
* I have run this against every subscription across all of our skills, and the dso queries that I know about.  They all conform so this is a good starting point.
    * used this tool to check whether a particular rule or attribute was still being used anywhere

## Unit tests

```
clj -X:test
```

## Releasing

I've just been using this as a git dep in deps.edn so I haven't created a maven package.  Do we need one?

## Sforzando Release

```
clj -M:pack
clj -Spom
# update version in pom.xml
# requires $HOME/.m2/settings.xml sforzando
mvn deploy:deploy-file -Dfile=datalog_schema.jar -DrepositoryId=sforzando -DpomFile=pom.xml -Durl=https://sforzando.jfrog.io/sforzando/libs-release-local
```
