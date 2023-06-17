(ns cosmos-viewer
  (:require [membrane.skia :as backend]
            [membrane.ui :as ui]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [membrane.component :as component
             :refer [defui defeffect make-app]]
            [membrane.basic-components :as basic]
            membrane.skia.paragraph
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

(defn stoggle [s x]
  (if (contains? s x)
    (disj s x)
    (conj s x)))

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
  (ui/with-style ::ui/style-fill
    (ui/with-color [0 0 0]
      (into []
            (map (fn [{:keys [x y width height id]}]
                   (let [width (* width 72)
                         height (* height 72)
                         radius (/ width 2)]
                     (ui/translate
                      (- x radius ) (- y radius)
                      (ui/with-color (get colors-by-id id)
                        (ui/rounded-rectangle width height radius))))))
            nodes))))

(defui cv [{:keys [nodes colors-by-id]}]
  (ui/no-events
   (ui/->Cached
    (ui/scale
     0.0390449239785968
     0.0390449239785968
     (cosmos-view nodes colors-by-id)))))

(defui topic-filter [{:keys [filter-str page selected topics]}])

(defn filtered-nodes [graph-layout filter-type selected-repos selected-topics neighbors
                      topic->repos]
  (cond
    (and
     (= filter-type :repo)
     (seq selected-repos))
    (eduction
     (let [repos
           (into selected-repos
                 (mapcat #(get neighbors %))
                 selected-repos)]
       (filter (fn [{:keys [id]}]
                 (contains? repos id))))
     (:nodes graph-layout))

    (and
     (= filter-type :topic)
     (seq selected-topics))
    (eduction
     (let [repos
           (into #{}
                 (mapcat #(get topic->repos %))
                 selected-topics)]
       (filter (fn [{:keys [id]}]
                 (contains? repos (symbol id)))))
     (:nodes graph-layout))

    :else (:nodes graph-layout)))

(def filtered-nodes-memo (memoize filtered-nodes))

(defui cosmos-viewer [{:keys [nodes-rtree hover edges-by-id graph-layout colors-by-id
                              topic->repos
                              topics
                              neighbors
                              selected-topics
                              selected-repos
                              filter-type]}]
  (let [{:keys [mx my]} extra]
    (ui/horizontal-layout
     (ui/fixed-bounds
      [200 500]
      (ui/vertical-layout
       (basic/dropdown {:options [[:topic "Topic"]
                                  [:repo "Repo"]]
                        :selected filter-type})
       (case filter-type
         :topic
         (topic-filter {:filter-str (get extra ::topic-filter-str "")
                        :page (get extra ::topic-page 0)
                        :selected selected-topics
                        :topics topics})
         :repo
         (topic-filter {:filter-str (get extra ::repo-filter-str "")
                        :page (get extra ::repo-page 0)
                        :selected selected-repos
                        :topics (sort (keys neighbors))})
         nil)))
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
                hits (seq (jsearch nodes-rtree [x y]))]
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
        (cv {:colors-by-id colors-by-id
             :nodes
             (filtered-nodes graph-layout filter-type selected-repos selected-topics neighbors topic->repos)
             })
        #_(ui/->Cached
           (ui/no-events
            ))
        #_(ui/image "clojure-namespace-graph.jpeg"))
       (ui/no-events
        (cond
          #_#_(and (= filter-type :repo)
               (seq selected-repos))
          (ui/->Cached
           (edges-by-id (first selected-repos)))

          hover
          (ui/->Cached
           (edges-by-id hover))))]))))

(defn load-state []
  (let [
        cosmos-graph (read-edn "cosmos-graph.edn")
        graph-layout
        #_(gv/layout cosmos-graph :sfdp)
        (read-edn "cosmos-layout.edn")

        nodes-rtree (jtree (:nodes graph-layout))

        coords-by-id
        (into {}
              (map (fn [{:keys [x y id]}]
                     [id [x y]]))
              (:nodes graph-layout))

        edges-by-id
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
                        (:edges cosmos-graph)))

        palette (:paired9 colors/palettes)
        neighbors (transduce
                   (map (fn [[from to]]
                          [(:id to) (:id from)]))
                   (completing
                    (fn [neighbors [to from]]
                      (merge-with into neighbors {to #{from}})))
                   {}
                   (:edges cosmos-graph))
        neighbors (into {}
                        (filter (fn [[k v]]
                                  (> (count v) 20)))
                        neighbors)
        topic->repos (read-edn "topic->repos.edn")
        colors-by-id
        (into {}
              (comp
               cat
               (map (fn [{id :id
                          fillcolor "fillcolor"}]
                      [id (nth palette (dec (parse-long fillcolor)))])))
              (:edges cosmos-graph))]
    {:cosmos-graph cosmos-graph
     :neighbors neighbors
     :graph-layout graph-layout
     :nodes-rtree nodes-rtree
     :coords-by-id coords-by-id
     :edges-by-id edges-by-id
     :colors-by-id colors-by-id
     :topic->repos topic->repos
     :topics (sort (keys topic->repos))
     :selected-topics #{}
     :selected-repos #{}}))


(defonce app-state (atom nil))

(defn show! []

  (swap! app-state
         (fn [state]
           (if state
             state
             (load-state))))

  (backend/run (make-app #'cosmos-viewer app-state))  


  ,)

(comment

  (with-open [w (io/writer "cosmos-layout.edn")]
    (write-edn w (gv/layout
                  (read-edn "cosmos-graph.edn")
                  :sfdp)))

  

  (let [cosmos-graph (:cosmos-graph @app-state)
        coords-by-id (:coords-by-id @app-state)]
    (backend/save-image "clojure-edges.jpeg"
                        (ui/scale 0.0390449239785968
                                  0.0390449239785968
                                  (ui/with-style ::ui/style-stroke
                                    (ui/with-color
                                      [0.66 0.66 0.66 0.4]
                                      #_[0 0.66 0 0.25]
                                      (into []
                                            (map (fn [[from to]]
                                                   (ui/path (coords-by-id (:id from))
                                                            (coords-by-id (:id to)))))
                                            (:edges cosmos-graph)))))))


  (let [cosmos-graph (:cosmos-graph @app-state)
        graph-layout (:graph-layout @app-state)
        palette (:paired9 colors/palettes)
        colors-by-id (into {}
                           (comp
                            cat
                            (map (fn [{id :id
                                       fillcolor "fillcolor"}]
                                   [id (nth palette (dec (parse-long fillcolor)))])))
                           (:edges cosmos-graph))]
    (backend/save-image "clojure-namespace-graph.jpeg"
                        (ui/scale
                         0.0390449239785968
                         0.0390449239785968
                         (ui/with-style ::ui/style-fill
                           (ui/with-color [0 0 0]
                             (cosmos-view (:nodes graph-layout)
                                          colors-by-id))))))

  (def my-state (time
                 (load-state)))

  ,)

(comment

  (def all-repos (read-edn "../../releases/2023-03-06/all-repos.edn.gz"))

  (def my-analysis (analyses-iter "../../releases/2023-03-06/analysis.edn.gz"))

)

(defeffect ::dec-page [$page]
  (dispatch! :update $page #(max 0 (dec %))))

(defeffect ::inc-page [$page]
  (dispatch! :update $page inc))

(defeffect ::toggle-topic [& args]
  (prn args))



(comment
  (require '[com.phronemophobic.membrane.schematic3 :as s3])
  (alter-var-root #'s3/eval-ns (constantly *ns*))
  (s3/show!)

  ;; save ui
  #_(with-open [w (io/writer "ui.edn")]
    (write-edn w (:root @s3/app-state)))

  (def my-ui (read-edn "ui.edn"))
  (eval (s3/export my-ui))

  (clojure.pprint/pprint (s3/export (:root @s3/app-state)))
  (eval (s3/export (:root @s3/app-state)))

  (backend/run (component/make-app #'pager {:page 0}) )


  (def topic->repos (read-edn "topic->repos.edn"))
  (def test-state (atom {:filter-str ""
                         :page 0
                         :selected #{}
                         :topics (sort (keys topic->repos))}))
  (backend/run (component/make-app #'topic-filter test-state) )

  (reset! s3/app-state
          (->> @s3/app-history
               reverse
               (drop 2)
               first))

  (def old-neighbors (:neighbors @app-state))
  (def new-neighbors (into {}
                           (filter (fn [[k v]]
                                     (> (count v) 20)))
                           old-neighbors))
  (swap! app-state assoc :neighbors new-neighbors)
  

  ,)

;; auto generated
(membrane.component/defui
  topic-row
  [{:keys [topic selected]}]
  (clojure.core/when-let
      [elem__38486__auto__
       (membrane.ui/on
        :mouse-down
        (fn [_] [[:cosmos-viewer/toggle-topic topic selected]])
        [(clojure.core/when-let
             [elem__38486__auto__
              (clojure.core/apply
               membrane.ui/horizontal-layout
               [(clojure.core/when-let
                    [elem__38486__auto__
                     (membrane.basic-components/checkbox {:checked? selected})]
                  (clojure.core/with-meta
                    elem__38486__auto__
                    '#:com.phronemophobic.membrane.schematic3{:ast
                                                              #:element{:id
                                                                        #uuid "0a90f957-5f3c-487f-b2ff-3371ef271a49"}}))
                (clojure.core/when-let
                    [elem__38486__auto__
                     (membrane.skia.paragraph/paragraph topic nil nil)]
                  (clojure.core/with-meta
                    elem__38486__auto__
                    '#:com.phronemophobic.membrane.schematic3{:ast
                                                              #:element{:id
                                                                        #uuid "913be1d6-66e4-4c44-92bd-a4827be2c8e5"}}))])]
           (clojure.core/with-meta
             elem__38486__auto__
             '#:com.phronemophobic.membrane.schematic3{:ast
                                                       #:element{:id
                                                                 #uuid "2cedf414-c8ad-4f0e-a08e-9fa4e4b65eed"}}))])]
    (clojure.core/with-meta
      elem__38486__auto__
      '#:com.phronemophobic.membrane.schematic3{:ast
                                                #:element{:id
                                                          #uuid "dba86698-7d2a-49d1-81ad-bed97b41272d"}})))
(membrane.component/defui
  pager
  [{:keys [page]}]
  (clojure.core/when-let
      [elem__38486__auto__
       (clojure.core/apply
        membrane.ui/horizontal-layout
        [(clojure.core/when-let
             [elem__38486__auto__
              (membrane.basic-components/button
               {:text "<",
                :on-click (fn [] [[:cosmos-viewer/dec-page $page]])})]
           (clojure.core/with-meta
             elem__38486__auto__
             '#:com.phronemophobic.membrane.schematic3{:ast
                                                       #:element{:id
                                                                 #uuid "46ef5662-7352-4dcd-90ce-e40324c7044e"}}))
         (clojure.core/when-let
             [elem__38486__auto__
              (membrane.basic-components/button
               {:text ">",
                :on-click (fn [] [[:cosmos-viewer/inc-page $page]])})]
           (clojure.core/with-meta
             elem__38486__auto__
             '#:com.phronemophobic.membrane.schematic3{:ast
                                                       #:element{:id
                                                                 #uuid "aca63e49-c7dc-4ddb-8689-bd08d3c66331"}}))
         (clojure.core/when-let
             [elem__38486__auto__
              (membrane.ui/translate
               6.75
               4.84375
               (clojure.core/when-let
                   [elem__38486__auto__
                    (membrane.skia.paragraph/paragraph (str page) nil nil)]
                 (clojure.core/with-meta
                   elem__38486__auto__
                   '#:com.phronemophobic.membrane.schematic3{:ast
                                                             #:element{:id
                                                                       #uuid "7046f134-5fc3-4d52-aff0-d25963ae7acc"}})))]
           (clojure.core/with-meta
             elem__38486__auto__
             '#:com.phronemophobic.membrane.schematic3{:ast
                                                       #:element{:id
                                                                 #uuid "b3d11c62-d5f3-4d4f-a8fb-adb121d55fe2"}}))])]
    (clojure.core/with-meta
      elem__38486__auto__
      '#:com.phronemophobic.membrane.schematic3{:ast
                                                #:element{:id
                                                          #uuid "5aa4b43d-328b-400c-82a8-af7e568f4ae2"}})))
(membrane.component/defui
  topic-filter
  [{:keys [filter-str page topics selected]}]
  (clojure.core/when-let
      [elem__38486__auto__
       [(clojure.core/when-let
            [elem__38486__auto__
             (membrane.ui/translate
              -0.5390625
              68.2109375
              (clojure.core/when-let
                  [elem__38486__auto__
                   (membrane.basic-components/button
                    {:text "Clear Selection",
                     :on-click (fn [] [[:set $selected #{}]])})]
                (clojure.core/with-meta
                  elem__38486__auto__
                  '#:com.phronemophobic.membrane.schematic3{:ast
                                                            #:element{:id
                                                                      #uuid "01c8db48-0597-41d6-94d2-25404718fc8a"}})))]
          (clojure.core/with-meta
            elem__38486__auto__
            '#:com.phronemophobic.membrane.schematic3{:ast
                                                      #:element{:id
                                                                #uuid "c4e55164-6fbc-4710-b34c-8674e9481e22"}}))
        (clojure.core/when-let
            [elem__38486__auto__
             (membrane.ui/translate
              0
              0
              (clojure.core/when-let
                  [elem__38486__auto__
                   (membrane.basic-components/textarea {:text filter-str})]
                (clojure.core/with-meta
                  elem__38486__auto__
                  '#:com.phronemophobic.membrane.schematic3{:ast
                                                            #:element{:id
                                                                      #uuid "5dca33e9-c621-4838-b35b-df401951089e"}})))]
          (clojure.core/with-meta
            elem__38486__auto__
            '#:com.phronemophobic.membrane.schematic3{:ast
                                                      #:element{:id
                                                                #uuid "019bea13-fcaf-438c-b204-c5b16ce2f729"}}))
        (clojure.core/when-let
            [elem__38486__auto__
             (membrane.ui/translate
              0.3203125
              27.1640625
              (clojure.core/when-let
                  [elem__38486__auto__ (pager {:page page})]
                (clojure.core/with-meta
                  elem__38486__auto__
                  '#:com.phronemophobic.membrane.schematic3{:ast
                                                            #:element{:id
                                                                      #uuid "b44b3f02-80e7-4a14-8b55-bea4e6ee7b26"}})))]
          (clojure.core/with-meta
            elem__38486__auto__
            '#:com.phronemophobic.membrane.schematic3{:ast
                                                      #:element{:id
                                                                #uuid "bab0afa0-ceed-41ec-ba32-f8db5dcb9b4a"}}))
        (clojure.core/when-let
            [elem__38486__auto__
             (membrane.ui/translate
              3.7421875
              114.90625
              (clojure.core/when-let
                  [elem__38486__auto__
                   (membrane.ui/on
                    :cosmos-viewer/toggle-topic
                    (fn [topic _] [[:update $selected stoggle topic]])
                    [(clojure.core/when-let
                         [elem__38486__auto__
                          (clojure.core/apply
                           membrane.ui/vertical-layout
                           (clojure.core/for
                               [topic
                                (->>
                                 topics
                                 (filter
                                  (fn*
                                   [p1__55762#]
                                   (clojure.string/includes? p1__55762# filter-str)))
                                 (drop (* page 12))
                                 (take 12))]
                             (clojure.core/when-let
                                 [elem__38486__auto__
                                  (topic-row
                                   {:topic topic, :selected (get selected topic)})]
                               (clojure.core/with-meta
                                 elem__38486__auto__
                                 '#:com.phronemophobic.membrane.schematic3{:ast
                                                                           #:element{:id
                                                                                     #uuid "b26f6cac-758e-4535-b93e-71ad0fff4e4c"}}))))]
                       (clojure.core/with-meta
                         elem__38486__auto__
                         '#:com.phronemophobic.membrane.schematic3{:ast
                                                                   #:element{:id
                                                                             #uuid "d4bee5f2-405b-4031-a809-7e5e2f34a935"}}))])]
                (clojure.core/with-meta
                  elem__38486__auto__
                  '#:com.phronemophobic.membrane.schematic3{:ast
                                                            #:element{:id
                                                                      #uuid "bcf04006-d22d-41ee-bbf4-e2cba55724da"}})))]
          (clojure.core/with-meta
            elem__38486__auto__
            '#:com.phronemophobic.membrane.schematic3{:ast
                                                      #:element{:id
                                                                #uuid "cd367165-1be4-4831-af5e-61cbefc28511"}}))]]
    (clojure.core/with-meta
      elem__38486__auto__
      '#:com.phronemophobic.membrane.schematic3{:ast
                                                #:element{:id
                                                          #uuid "b9006550-bb0e-461e-96ee-af588593a385"}})))


