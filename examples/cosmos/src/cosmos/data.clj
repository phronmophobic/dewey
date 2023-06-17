(ns cosmos.data
  "Utilities for exporting data for viewing namespaces on https://cosmograph.app/run/."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [com.phronemophobic.clj-graphviz :refer [render-graph]
             :as gv]
            [com.phronemophobic.dewey.util
             :refer [analyses-iter
                     analyses-seq]])
  (:import java.util.zip.GZIPInputStream
           java.io.PushbackReader))

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

(defn read-edn [fname]
  (with-open [is (io/input-stream fname)
              is (if (str/ends-with? fname ".gz")
                   (GZIPInputStream. is)
                   is)
              rdr (io/reader is)
              rdr (PushbackReader. rdr)]
    (edn/read rdr)))

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


;; should probably include topic->repos
(defn dump-data [analyses]
  (let [g (cosmos-graph analyses)]
    (with-open [w (io/writer "cosmos-graph.edn")]
      (write-edn w g))
    (let [graph-layout (gv/layout g :sfdp)]
      (with-open [w (io/writer "cosmos-layout.edn")]
        (write-edn w graph-layout)))))

(defn repos-by-topic [repo-topics analysis]
  (transduce
   (comp(mapcat (fn [{:keys [repo analysis]}]
                   (eduction
                    (mapcat
                     (fn [ns-def]
                       (let [ns (:name ns-def)]
                         (eduction
                          (map (fn [topic]
                                 [ns topic]))
                          (get repo-topics repo)))))
                    (:namespace-definitions analysis)))))
   (completing
    (fn [by-topic [ns topic]]
      (merge-with into by-topic {topic #{ns}})))
   {}
   analysis)
  
  )


(def releases-url "https://api.github.com/repos/phronmophobic/dewey/releases/latest")
(defn download-latest-release []
  (let [release-info (json/read-str (slurp (io/as-url releases-url)))
        release-name (get release-info "name")
        asset-urls (->
                    release-info
                    (get "assets")
                    (->> (map (juxt #(get % "name")
                                    #(get % "browser_download_url")))))
        release-dir (io/file "../../releases/" release-name)]
    (.mkdirs release-dir)
    (doseq [[fname url] asset-urls
            :let [f (io/file release-dir fname)]]
      (println "downloading" url "to" (str f))
      (with-open [is (io/input-stream (io/as-url url))
                  os (io/output-stream f)]
        (io/copy is
                 os)))))


(comment
  (def my-analysis (analyses-iter "../../releases/2023-06-12/analysis.edn.gz"))

  (dump-data my-analysis)

  (def github-repos (read-edn "../../releases/2023-06-12/all-repos.edn.gz"))
  (def repo-topics
    (transduce
     (mapcat (fn [repo]
               (let [full-name (:full_name repo)]
                 (eduction
                  (map (fn [topic]
                         [full-name topic]))
                  (:topics repo)))))
     (completing
      (fn [m [repo topic]]
        (merge-with into m {repo [topic]})))
     {}
     github-repos))


  ;; should probably be included in dump-data
  (def topic->repos
    (time
     (repos-by-topic repo-topics my-analysis)))

  (with-open [w (io/writer "topic->repos.edn")]
    (write-edn w topic->repos))

  (def cosmos-graph (read-edn "cosmos-graph.edn"))
  (def cosmos-layout (read-edn "cosmos-layout.edn"))

  (dev/write-edn "edges.edn.gz" (:edges cosmos-graph))
  
  ,)



