(ns com.phronemophobic.dewey.db
  (:require [datalevin.core :as d]
            [com.phronemophobic.dewey.util
             :refer [analyses-iter]])
  )




;; Define an optional schema.
;; Note that pre-defined schema is optional, as Datalevin does schema-on-write.
;; However, attributes requiring special handling need to be defined in schema,
;; e.g. many cardinality, uniqueness constraint, reference type, and so on.

(def schema
  {
   ;; analysis
   :com.phronemophobic.dewey.db/repo {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/analyze-instant {:db/valueType :db.type/instant}
   :git/sha {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/basis {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/release {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/type {:db/valueType :db.type/keyword}
   :com.phronemophobic.dewey.db/analysis {:db/valueType :db.type/ref}
   :com.phronemophobic.dewey.db/analysis-id {:db/valueType :db.type/string
                                             :db/unique :db.unique/identity}
   
   ;; finding
   :com.phronemophobic.dewey.db/finding-id {:db/valueType :db.type/string
                                            :db/unique :db.unique/identity}

   ;; generic
   :com.phronemophobic.dewey.db/arglist-strs {:db/valueType :db.type/string :db/cardinality :db.cardinality/many}
   :com.phronemophobic.dewey.db/fixed-arities {:db/valueType :db.type/long :db/cardinality :db.cardinality/many} 

   
   :com.phronemophobic.dewey.db/alias {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/alias-col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/alias-end-col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/alias-end-row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/alias-row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/arity {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/auto-resolved {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/branch {:db/valueType :db.type/keyword}
   :com.phronemophobic.dewey.db/class {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/defined-by {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/defmethod {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/dispatch-val-str {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/doc {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/end-col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/end-row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/export {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/filename {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/from {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/from-var {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/id {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/impl-ns {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/import {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/keys-destructuring {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/keys-destructuring-ns-modifier {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/lang {:db/valueType :db.type/keyword}
   :com.phronemophobic.dewey.db/macro {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/name-col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/name-end-col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/name-end-row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/name-row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/no-doc {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/ns {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/private {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/protocol-name {:db/valueType :db.type/symbol}
   :com.phronemophobic.dewey.db/refer {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/scope-end-col {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/scope-end-row {:db/valueType :db.type/long}
   :com.phronemophobic.dewey.db/skip-analysis {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/str {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/test {:db/valueType :db.type/boolean}
   :com.phronemophobic.dewey.db/varargs-min-arity {:db/valueType :db.type/long}


   ;; need coercion. they return some combination of strings, symbols, and keywords
   :com.phronemophobic.dewey.db/method-name {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/name {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/protocol-ns {:db/valueType :db.type/string}
   :com.phronemophobic.dewey.db/to {:db/valueType :db.type/string}

   ;; :com.phronemophobic.dewey.db/derived-location 
   ;; :com.phronemophobic.dewey.db/meta map

   ;; remove
   ;; :com.phronemophobic.dewey.db/uri
   })

(def stringish-keys
  #{:com.phronemophobic.dewey.db/method-name
    :com.phronemophobic.dewey.db/name
    :com.phronemophobic.dewey.db/protocol-ns
    :com.phronemophobic.dewey.db/to})



(defn coerce-attribute [entity k v]
  (let [nk (keyword "com.phronemophobic.dewey.db" (name k))]
    (cond

      (stringish-keys nk) [nk (str v)]

      (and (= nk ::doc)
           (not (string? v))) nil

      (and (= nk ::ns)
           (= v :clj-kondo/unknown-namespace)) nil

      (and (= nk ::private)
           (not (boolean? v))) nil

      (and (= nk ::alias)
           (not (symbol? v))) nil

      (= nk ::arglist-strs)
      [nk (into [] (remove nil?) v)]

      :else
      [nk v])))

(defn analysis->tx [release-id analysis]
  (let [


        analysis-id (clojure.string/join
                       "-"
                       [release-id
                        (:repo analysis)
                        (:basis analysis)])
        analysis-entity
        {::repo (:repo analysis)
         ::analyze-instant (:analyze-instant analysis)
         ::analysis-id analysis-id
         :git/sha (:git/sha analysis)
         ::basis (:basis analysis)
         ::release release-id}
        analysis-ref [::analysis-id analysis-id]

        findings
        (into []
              (comp
               (mapcat
                (fn [[type findings]]
                  (into
                   []
                   (map (fn [finding]
                          (into
                           {::analysis analysis-ref
                            ::type type}
                           (comp
                            (remove (fn [[k v]]
                                      (nil? v)))
                            (keep (fn [[k v]]
                                    (coerce-attribute finding k v))))
                           finding)))
                   findings)))
               (map-indexed
                (fn [i finding]
                  (assoc finding
                         ::finding-id (clojure.string/join "-"
                                                           [analysis-id
                                                            (str i)]))
                  )))
              (:analysis analysis))]
    (into [analysis-entity]
          findings)))


(comment

  (def full-analysis (analyses-iter "../../releases/2023-06-12/analysis.edn.gz"))
  (def analysis
    (doall (take 200 full-analysis)))
  ,

;; Create DB on disk and connect to it, assume write permission to create given dir
  (def conn (d/get-conn "/tmp/datalevin/mydb17" schema))

  (d/transact!
     conn
     (analysis->tx "2023-06-12"
                  (first analysis))
     )

  ;; load full analysis
  (doseq [[i a] (map-indexed vector full-analysis)
          :when (> i 8638)]
    (let [tx (analysis->tx "2023-06-12"
                           a)]
      (prn i (:repo a))
      (def my-tx tx)
      (def my-analysis a)
      (d/transact! conn tx)))

  ;; break apart and redo last transaction to find minimal problematic transaction
  (doseq [part my-tx]
    (let [part (dissoc part ::analysis)]
      (prn part)
      (def my-tx2 part)
      (d/transact! conn [part])))

  ;; retry transacting last analysis after a fix
  (d/transact! conn (analysis->tx "2023-06-12"
                                  my-analysis))


(d/q '[:find (pull ?e [*])
       :where
       [?e ::type :java-class-usages]]
     (d/db conn))

  (d/q '[:find ?e
         :where
         [?e ::type :java-class-usages]]
       (d/db conn))

  (d/q '[:find (pull ?analysis [*])
         :in $ ?eid
         :where
         [?eid ::analysis ?analysis]]
       
       (d/db conn)
       4)

  ,)


(comment
  (d/q
   '[:find (pull ?e [*])
     :where
     [?e ::from membrane.ui]
     
     :timeout 1000]
   (d/db conn))



  (d/q
   '[:find ?from
     :where
     [?e ::to "clojure.core"]
     [?e ::name "commute"]
     [?e ::from ?from]
     :timeout 5000]
   (d/db conn))


  (d/seek-datoms (d/db conn)
                 :aevt
                 )

  (d/datoms
   (d/db conn)
   :ave
   ::to)

  (->> (d/seek-datoms
        (d/db conn)
        :ave
        ::basis
        "/fo")
       (take 10))



  ,)
