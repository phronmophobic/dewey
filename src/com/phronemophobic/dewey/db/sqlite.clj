(ns com.phronemophobic.dewey.db.sqlite
  (:require [com.phronemophobic.dewey.util
             :refer [analyses-iter]]
            [com.phronemophobic.dewey :as dewey]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [honey.sql :as sql]
            [next.jdbc :as jdbc])
  (:import java.io.File
           java.util.zip.GZIPOutputStream))

(def analysis-tables
  {
   ;; JavaClassUsages
   :java-class-usages
   [[:end-row :int]
    [:name-end-col :int]
    [:name-end-row :int]
    [:user-meta [:varchar 256]]
    [:name-row :int]
    [:filename [:varchar 256]]
    [:skip-analysis :boolean]
    [:col :int]
    [:class [:varchar 256]]
    [:name-col :int]
    ;; [:uri ]
    [:end-col :int]
    [:tag [:varchar 256]]
    ;; [:import ]
    [:branch [:varchar 256]]
    [:row :int]
    ]
  
   ;; Namespace Usages
   :namespace-usages
   [[:name-end-col :int]
    [:name-end-row :int]
    [:name-row :int]
    [:alias-end-row :int]
    [:alias-row :int]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:alias [:varchar 256]]
    [:from [:varchar 256]]
    [:col :int]
    [:name-col :int]
    [:alias-col :int]
    [:alias-end-col :int]
    [:row :int]
    [:to [:varchar 256]]
    ]
   ;; Locals
   :locals
   [[:end-row :int]
    [:scope-end-row :int]
    [:name [:varchar 256]]
    [:scope-end-col :int]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:str [:varchar 256]]
    [:col :int]
    [:id :int]
    [:end-col :int]
    [:row :int]
    ]

   ;; LocalsUsages
   :local-usages
   [[:end-row :int]
    [:name-end-col :int]
    [:name-end-row :int]
    [:name-row :int]
    [:name [:varchar 256]]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:col :int]
    [:id :int]
    [:name-col :int]
    [:end-col :int]
    [:row :int]]

   ;; Instance Invocations
   :instance-invocations
   [[:method-name [:varchar 256]]
    [:filename [:varchar 256]]
    [:name-row :int]
    [:name-col :int]
    [:name-end-row :int]
    [:name-end-col :int]
    [:lang [:varchar 256]]]

   ;; NamespaceDefinitions
   :namespace-definitions
   [[:no-doc :boolean]
    [:end-row :int]
    [:meta [:varchar 256]]
    [:name-end-col :int]
    [:name-end-row :int]
    [:name-row :int]
    [:added [:varchar 256]]
    [:name [:varchar 256]]
    [:author [:varchar 256]]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:col :int]
    [:deprecated [:varchar 256]]
    [:name-col :int]
    [:end-col :int]
    [:doc [:varchar 256]]
    [:row :int]]

   ;; Keywords
   :keywords
   [[:end-row :int]
    ;; [:keys-destructuring-ns-modifier ]
    [:ns [:varchar 256]]
    [:name [:varchar 256]]
    [:auto-resolved :boolean]
    [:keys-destructuring :boolean]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:alias [:varchar 256]]
    [:from [:varchar 256]]
    [:col :int]
    [:from-var [:varchar 256]]
    [:reg [:varchar 256]]
    [:end-col :int]
    [:namespace-from-prefix :boolean]
    [:row :int]
    ]

   ;; VarDefinitions
   :var-definitions
   [[:fixed-arities [:varchar 256]]
    [:end-row :int]
    [:meta [:varchar 256]]
    [:name-end-col :int]
    [:protocol-ns [:varchar 256]]
    [:name-end-row :int]
    [:private :boolean]
    [:name-row :int]
    [:added [:varchar 256]]
    [:ns [:varchar 256]]
    [:name [:varchar 256]]
    [:defined-by [:varchar 256]]
    [:protocol-name [:varchar 256]]
    [:export :boolean]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:macro :boolean]
    [:col :int]
    [:deprecated [:varchar 256]]
    [:name-col :int]
    [:imported-ns [:varchar 256]]
    [:end-col :int]
    [:arglist-strs [:varchar 256]]
    [:varargs-min-arity :int]
    [:doc [:varchar 256]]
    [:test :boolean]
    [:row :int]
    ]
   ;; ProtocolImpls
   :protocol-impls
   [[:impl-ns [:varchar 256]]
    [:end-row :int]
    ;; [:derived-location [:varchar 256]]
    [:name-end-col :int]
    [:protocol-ns [:varchar 256]]
    [:name-end-row :int]
    [:method-name [:varchar 256]]
    [:name-row :int]
    [:defined-by [:varchar 256]]
    [:protocol-name [:varchar 256]]
    [:filename [:varchar 256]]
    [:col :int]
    [:name-col :int]
    [:end-col :int]
    [:row :int]
    ]
   ;; VarUsages

   :var-usages
   [[:fixed-arities [:varchar 256]]
    [:end-row :int]
    [:name-end-col :int]
    [:name-end-row :int]
    [:private :boolean]
    [:name-row :int]
    [:name [:varchar 256]]
    [:defmethod :boolean]
    [:dispatch-val-str [:varchar 256]]
    [:lang [:varchar 256]]
    [:filename [:varchar 256]]
    [:alias [:varchar 256]]
    [:from [:varchar 256]]
    [:macro :boolean]
    [:col :int]
    [:deprecated [:varchar 256]]
    [:name-col :int]
    [:from-var [:varchar 256]]
    [:end-col :int]
    [:arity :int]
    [:varargs-min-arity :int]
    [:refer :boolean]
    [:row :int]
    [:to [:varchar 256]]]
  

   })

(def table-column->type
  (into {}
        (mapcat (fn [[table columns]]
                  (eduction
                   (map (fn [[col type]]
                          [[table col]  type]))
                   columns)))
        analysis-tables))

(defn sformat [hsql]
  (sql/format hsql :pretty true :dialect :ansi))

(defn create-tables-sql []
  ;; basis
  (into [{:create-table :basis
          :with-columns [[:id :int [:not nil]]
                         [:repo [:varchar 256] [:not nil]]
                         [:sha [:varchar 256] [:not nil]]
                         [:basis [:varchar 256] [:not nil]]]}]
        (map (fn [[k columns]]
               {:create-table k
                :with-columns
                (conj columns
                      [:basis-id :int [:not nil]])}))
        analysis-tables))

(defn create-tables! [db]
  (with-open [conn (jdbc/get-connection db)]
    (doseq [hsql (create-tables-sql)
            :let [stmt (sformat hsql)
                  _ (prn (:create-table hsql))
                  ;; conn (get connections (:create-table hsql))
                  ]]
      (println (first stmt))
      (jdbc/execute! conn stmt)))
  )

(defn truncate [s n]
  (if (<= (count s) n)
    s
    (subs s 0 n)))

(def pr-str-cols
  #{[:java-class-usages :user-meta]
    [:namespace-definitions :meta]
    [:var-definitions :fixed-arities]
    [:var-definitions :meta]
    [:var-usages :fixed-arities]})

(defn coerce [table col type val]
  (condp = type
    [:varchar 256] (let [->str (if (contains? pr-str-cols [table col])
                                 pr-str
                                 str)]
                     (when val (truncate (->str val) 256)))
    :int (when val (long val))
    :boolean (when val (if val 1 0))))


(defn load-db [db all-analyses]
  (with-open [conn (jdbc/get-connection db)]
    (doseq [[basis-id ana] (map-indexed vector all-analyses)]
      ;; insert basis
      (let [hsql {:insert-into [:basis]
                  :values
                  [(into {:id basis-id}
                         (map (fn [[column-name k]]
                                [column-name (coerce :basis k [:varchar 256] (get ana k))]))
                         {:sha :git/sha
                          :basis :basis
                          :repo :repo})]}]
        (jdbc/execute! conn (sformat  hsql)))

      ;; insert each table
      (doseq [[table rows] (:analysis ana)]
        (let [chunks (into []
                           (comp
                            (map (fn [m]
                                   (into {:basis-id basis-id}
                                         (keep (fn [[col val]]
                                                 (when-let [type (table-column->type [table col])]
                                                   [col (coerce table col type val)])))
                                         m)))
                            (partition-all 500))
                           rows)]
          (when (seq chunks)
            (doseq [chunk chunks]
              (let [hsql {:insert-into [table]
                          :values chunk}]
                (try 
                  (jdbc/execute! conn (sformat hsql))
                  (catch Exception e
                    (def errinfo {:ana ana
                                  :table table
                                  :chunk chunk
                                  :hsql hsql})
                    (throw (ex-info "ERror!"
                                    {:ana ana
                                     :table table
                                     :chunk chunk
                                     :hsql hsql}
                                    e))))))))))))

 
(defn dump-db! [db-path out-path-gz]
  (let [pb (doto (ProcessBuilder.
                  ["/usr/bin/sqlite3"
                   (.getCanonicalPath (io/file db-path))
                   ".dump"])
             (.redirectError (io/file "/dev" "null")))
        p (.start pb)
        _ (future
            (with-open [os (io/output-stream out-path-gz)
                        gs (GZIPOutputStream. os)
                        is (.getInputStream p)]
              (io/copy is gs)))


        result (.waitFor p)]
    (when (not (zero? result))
      (throw (ex-info "Db dump failed."
                      {:db-path db-path
                       :out-path-gz out-path-gz})))
    nil))

(defn index->db [analysis-edn-gz]
  (let [release-dir (.getParentFile analysis-edn-gz)
        full-analysis (analyses-iter analysis-edn-gz)
        db {:dbtype "sqlite"
            :dbname (io/file "/var/"
                             "tmp"
                             "dewey.sqlite")}]
    (create-tables! db)
    (load-db db full-analysis)
    (let [output-file (io/file release-dir
                          "dewey.sqlite3.sql.gz")]
     (dump-db! (:dbname db) output-file)
     output-file)))



(comment
  (time (index->db (io/file
                    "releases"
                    "2024-09-30"
                    "analysis.edn.gz")))

  (require '[com.phronemophobic.easel :as easel ])
  (easel/run)
  
  
  
  (def full-analysis (analyses-iter "analysis.edn.gz"))
  (def analysis
    (time
     (doall (take 200 full-analysis))))

  ;; usage
  (create-tables!)
  (time (load-db full-analysis))
  

  ,)



