(ns cosmos
  "Utilities for exporting data for viewing namespaces on https://cosmograph.app/run/."
  (:require [clojure.java.io :as io]
            [stats :refer [analyses-iter]]))

(defn write-csv
  "Writes a csv file to `output` with two columns. Each row is a unique pair of an ns usage: from-ns,to-ns.

  `output` can be any type that can be coerced to a writer with `clojure.java.io/writer`."
  [rows header output]
  (with-open [writer (io/writer output)]
    (let [header (clojure.string/join ","
                                      header)]
      
      (.write writer header)
      (.newLine writer))
    (transduce
     (map #(clojure.string/join "," %))
     (completing
      (fn [_ s]
        (.write writer s)
        (.newLine writer)))
     nil
     rows)))

(defn distinct-by
  "Returns a lazy sequence of the elements of coll with duplicates removed.
  Returns a stateful transducer when no collection is provided."
  {:added "1.0"
   :static true}
  ([f]
   (fn [rf]
     (let [seen (volatile! #{})]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [by (f input)]
            (if (contains? @seen by)
              result
              (do (vswap! seen conj by)
                  (rf result input))))))))))


(defn -main [& args]

  (when (not (= 1
                (count args)))
    (println "Usage: clojure -M:cosmos <analysis-file>")
    (System/exit 1))
  
  (let [analysis-fname (first args)
        analysis (analyses-iter analysis-fname)
        ns-by-repo (transduce
                    (comp (map (juxt :repo :analysis))
                          (mapcat (fn [[repo analysis]]
                                    (eduction
                                     (map (fn [ns-def]
                                            [repo (:name ns-def)]))
                                     (:namespace-definitions analysis)))))
                    (completing
                     (fn [by-repo [repo name]]
                       (update by-repo repo 
                               (fnil conj #{}) name)))
                    {}
                    analysis)
        usages (into
                #{}
                (comp (map (juxt :repo :analysis))
                      (mapcat (fn [[repo analysis]]
                                (eduction
                                 (map (fn [usage]
                                        {:repo repo
                                         :from (:from usage)
                                         :to (:to usage)}))
                                 (:namespace-usages analysis))))
                      (distinct-by (juxt :from :to)))
                analysis)

        graph (transduce
               (map (fn [{:keys [from to]}]
                      [to (if (= (ns-by-repo from)
                                 (ns-by-repo to))
                            1
                            50)]))
               (completing
                (fn [m [to pts]]
                  (update m to (fnil + 0)
                          pts)))
               (into {}
                     (comp (mapcat (juxt :from :to))
                           (map (fn [ns]
                                  [ns 1])))
                     usages)
               usages)]
    (write-csv graph
               ["id" "points"]
               "graph-metadata.csv")
    (write-csv (eduction
                (map (juxt :from :to))
                usages)
               ["from" "to"]
               "graph.csv")))
