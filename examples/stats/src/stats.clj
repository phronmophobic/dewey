(ns stats
  (:require [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.edn :as edn]
            [com.phronemophobic.dewey.util
             :refer [analyses-iter
                     analyses-seq]])
  )




(def frequencies-rf
  "Reducing function that returns the frequencies of items produced."
  (fn
    ([]
     (transient {}))
    ([counts]
     (persistent! counts))
    ([counts x]
     (assoc! counts x (inc (get counts x 0))))))


(def count-rf
  "Reducing function that returns the number of items produced."
  (fn
    ([]
     0)
    ([count]
     count)
    ([count _]
     (inc count))))


(def stats
  "Basic statistics for an analysis dump."
  {:num-analyses
   {:rf count-rf}

   :num-projects
   {:xform (comp (map :repo)
                 (distinct))
    :rf count-rf}

   :num-namespaces
   {:xform (comp (map :analysis)
                 (mapcat :namespace-definitions)
                 (map (constantly 1)))
    :rf count-rf}

   :defined-by-frequencies
   {:xform (comp (map :analysis)
                 (mapcat :var-definitions)
                 (map :defined-by))
    :rf frequencies-rf}

   :basis-frequencies
   {:xform (comp (map :basis)
                 (map #(clojure.string/split % #"/"))
                 (map last))
    :rf frequencies-rf}})

(def extra-stats
  {:ns-usage-frequencies
   {:xform (comp (map :analysis)
                 (mapcat :namespace-usages)
                 (map :to))
    :rf frequencies-rf}

   :java-class-frequencies
   {:xform (comp (map :analysis)
                 (mapcat :java-class-usages)
                 (map :class))
    :rf frequencies-rf}})

(defn run-stats
  "Returns a map of the statistics to their calculated value."
  [stats coll]
  (into {}
        (map (fn [[k stat]]
               (prn "starting " k)
               (let [rf (:rf stat)
                     xform (get stat :xform identity)
                     init (if-let [[_ init] (find stat :init)]
                            init
                            (rf))]
                 [k
                  (transduce xform
                             rf
                             init
                             coll)])))
        stats))

(defn -main [fname out]
  (if (not fname)
    (println "usage clojure -M:stats fname out.edn")
    (with-open [w (io/writer out)]
      (let [result (run-stats stats
                              (analyses-iter fname))]
        (pprint result w))
      (shutdown-agents))))

(comment

  (def full-analysis (analyses-iter "../cosmos/releases/2023-03-17/analysis.edn.gz"))
  (def analysis
    (doall (take 200 full-analysis)))

  ,)
