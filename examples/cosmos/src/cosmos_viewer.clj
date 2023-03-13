(ns cosmos-viewer
  (:require [membrane.skia :as backend]
            [membrane.ui :as ui]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [membrane.component :as component
             :refer [defui defeffect make-app]]
                        [clojure.edn :as edn]
            [com.phronemophobic.clj-graphviz :as gv]
            [treemap-clj.rtree :as rtree]
            colors
            [com.phronemophobic.dewey.util
             :refer [analyses-iter]])
  (:import

   java.io.PushbackReader
   java.util.zip.GZIPInputStream

   com.github.davidmoten.rtree.RTree
   com.github.davidmoten.rtree.Entries
   com.github.davidmoten.rtree.geometry.Geometries))

(defn read-edn [fname]
  (with-open [is (io/input-stream fname)
              is (if (str/ends-with? fname ".gz")
                   (GZIPInputStream. is)
                   is)
              rdr (io/reader is)
              rdr (PushbackReader. rdr)]
    (edn/read rdr)))

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

(defn jtree [nodes]
  (RTree/create (sequence
                 (map (fn [{:keys [x y width height] :as node}]
                        (let [geom (Geometries/circle
                                    (double x)
                                    (double y)
                                    (double (* 72 (/ width 2))))]
                          (Entries/entry node geom))))
                 nodes)))

(defn jsearch [rt [x y]]
  (-> (.search ^RTree rt (Geometries/point (double x) (double y)))
      (.toBlocking)
      (.toIterable)
      (->> (map (fn [entry]
                  (.value ^com.github.davidmoten.rtree.Entry entry))))))


(defn cosmos-view [nodes colors-by-id]
  (into []
        (map (fn [{:keys [x y width height id]}]
               (let [width (* width 72)
                     height (* height 72)
                     radius (/ width 2)]
                 (ui/translate
                  (- x radius ) (- y radius)
                  (ui/with-color (get colors-by-id id)
                   (ui/rounded-rectangle width height radius))))))
        nodes))

(defui cosmos-viewer [{:keys [rtree hover edges-by-id]}]
  (let [{:keys [mx my]} extra]
    (ui/vertical-layout
     (ui/fixed-bounds [200
                       50]
                      (when (and mx my)
                        #_(ui/label (str (int mx) "," (int my) ": "hover))
                        (when hover
                         (ui/label hover))))
     [
      (ui/on
       :mouse-move
       (fn [[x y]]
         (let [x (/ x 0.0390449239785968 )
               y (/ y 0.0390449239785968 )
               hits (seq (jsearch rtree [x y]))]
           (if hits
             (let [biggest-hit
                   (->> hits
                        (map :id)
                        (apply max-key #(-> (get edges-by-id %)
                                            count)))]
               [[:set $hover biggest-hit]
                [:set $mx x]
                [:set $my y]])
             [[:set $hover nil]
              [:set $mx x]
              [:set $my y]]
             )))
       (ui/image "my-cosmos.jpeg"))
      (ui/no-events
       (when hover
         (ui/->Cached
          (edges-by-id hover))))])))


(comment
  

  (def cosmos-graph (read-edn "../stats/cosmos-graph.edn"))
  (def graph-layout
    #_(gv/layout cosmos-graph :sfdp)
    (read-edn "cosmos-layout.edn"))

  (with-open [w (io/writer "cosmos-layout.edn")]
    (write-edn w graph-layout))


  (def my-rtree (jtree (:nodes graph-layout)))

  (jsearch my-rtree [100 100])

  (def maxx
    (->> graph-layout
         :nodes
         (map :x)
         (apply max)))

  (def maxy
    (->> graph-layout
         p         (map :x)
         :nodes
         (apply max)))

  (def coords-by-id
    (into {}
          (map (fn [{:keys [x y id]}]
                 [id [x y]]))
          (:nodes graph-layout)))

  (def edges-by-id
    (into {}
          (map (fn [[id edges]]
                 [id
                  (ui/scale 0.0390449239785968
                            0.0390449239785968
                            (ui/with-style ::ui/style-stroke
                              (ui/with-color [1 0 0]
                                (into []
                                      (map (fn [[from to]]
                                             (ui/path (coords-by-id (:id from))
                                                      (coords-by-id (:id to)))))
                                      edges))))]))
          (group-by #(-> % second :id)
                    (:edges cosmos-graph))))

  (def colors-by-id
    (into {}
          (comp
           cat
           (map (fn [{id :id
                      fillcolor "fillcolor"
                      :as m}]
                  ;; (prn fillcolor (get m "fillcolor"))
                  [id (nth (:brbg9 colors/palettes) (dec (parse-long fillcolor)))])))
          (:edges cosmos-graph)))


  (backend/save-image "my-cosmos.jpeg"
                      (ui/scale
                       0.0390449239785968
                       0.0390449239785968
                       (ui/with-style ::ui/style-fill
                         (ui/with-color [0 0 0]
                           (cosmos-view (:nodes graph-layout)
                                        colors-by-id)))))
  

  (backend/run (make-app #'cosmos-viewer {:rtree my-rtree
                                          :edges-by-id edges-by-id
                                          :hover nil}))  

  ,)
