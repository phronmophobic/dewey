(ns cosmos.data
  "Utilities for exporting data for viewing namespaces on https://cosmograph.app/run/."
  (:require [clojure.java.io :as io]
            [com.phronemophobic.clj-graphviz :refer [render-graph]
             :as gv]
            [com.phronemophobic.dewey.util
             :refer [analyses-iter
                     analyses-seq]]))

(defn write-edn [w obj]
  (binding [*print-length* nil
            *print-level* nil
            *print-dup* false
            *print-meta* false
            *print-readably* true

            ;; namespaced maps not part of edn spec
            *print-namespace-maps* false

            *out* w]
    (pr obj)))

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


(defn cosmos-graph [analysis]
  (let [ns-by-repo (transduce
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
               usages)

        cosmos-weights
        (into
         {}
         (map (fn [[k v]]
                [(str k) (/ (+ 0.5 (Math/log v))
                            4.0)]))
         graph)

        max-weight (apply max (vals cosmos-weights))

        cosmos-edges
        (into []
              (comp
               (map (juxt :from :to))
               (map (fn [edge]
                      (mapv str edge)))
               (map (fn [edge]
                      (mapv (fn [nid]
                              (let [weight (get cosmos-weights nid)
                                    ratio (/ weight
                                             max-weight)
                                    fillcolor (-> (* ratio 8)
                                                  (Math/round)
                                                  int
                                                  inc
                                                  str)
                                    ]
                                {:id nid
                                 "fillcolor" fillcolor
                                 "width" (format "%.2f" weight)
                                 "height" (format "%.2f" weight)}))
                            edge))))
              usages)]
    {:edges cosmos-edges
     :default-attributes
     {:node {"fixedsize" "true"
             "label" ""
             "width" "0.05"
             "style" "filled"
             "fillcolor" "black"
             "color" "#00000000"
             "colorscheme" "bupu9"
             "height" "0.05"}
      :edge {"color" "grey93"}
      :graph {"overlap" "false"
              "outputorder" "edgesfirst"}}
     :flags #{:directed}}))

(defn dump-data [analyses]
  (let [g (cosmos-graph analyses)]
    (with-open [w (io/writer "cosmos-graph.edn")]
      (write-edn w g))
    (let [graph-layout (gv/layout cosmos-graph :sfdp)]
      (with-open [w (io/writer "cosmos-layout.edn")]
        (write-edn w graph-layout)))))

(comment
  (def my-analysis (analyses-iter "../../releases/2023-03-06/analysis.edn.gz"))

  (dump-data my-analysis)
  ,)



