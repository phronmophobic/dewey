(ns web
  (:require [com.phronemophobic.dewey.util
             :refer [read-edn]]
            [hiccup.core :as hiccup]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))


(def libs (read-edn "../../releases/f33b5afb-9dc6-48c5-a250-7496b7edcbd3/deps-libs.edn.gz"))



(def repos (read-edn "../../releases/f33b5afb-9dc6-48c5-a250-7496b7edcbd3/all-repos.edn.gz"))

(def repos-short
  (into []
        (comp (map (fn [m]

                     {:stars (:stargazers_count m)
                      :name (:name m)
                      :description (:description m)
                      :owner (-> m :owner :login)
                      :url (:html_url m)
                      :topics (:topics m)})))
        repos))

(def ++ (fnil + 0))
(def inc+ (fnil inc 0))
(def topics
  (reduce
   (fn [m repo]
     (let [stars (:stars repo)]
       (reduce (fn [m topic]
                 (-> m
                     (update-in [topic :count] inc+)
                     (update-in [topic :stars] ++ stars)))
               m
               (:topics repo))))
   {}
   repos-short))

(defn write-topics []
  (let [table
        [:table#example
         [:thead
          [:th "topic"]
          [:th "repo count"]
          [:th "star count"]]
         (for [[topic {:keys [count stars]}] (->> topics
                                                  (sort-by first))]
           [:tr
            [:td
             [:a {:href (str "search.html?topic=" topic)}
              topic]]
            [:td count]
            [:td stars]])]]
   (with-open [w (io/writer "public/topics.html")]
     (binding [*out* w]
       (print
        (hiccup/html
         [:html
          [:head
           [:title "Topics"]
           [:link {:rel "stylesheet"
                   :type "text/css"
                   :href "https://cdn.datatables.net/1.13.1/css/jquery.dataTables.css"}]
           [:script {:type "text/javascript"
                     :src "https://code.jquery.com/jquery-3.6.3.min.js"}]
           [:script {:type "text/javascript"
                     :charset "utf8"
                     :src "https://cdn.datatables.net/1.13.1/js/jquery.dataTables.js"}]
           [:script {:type "text/javascript"}
            (slurp (io/resource "topic-table.js"))]
           ]
          [:body
           table]]
         ))))))

(comment
  (with-open [writer (io/writer "public/data/repos.json")]
    (json/write repos-short writer))
  ,)

#_(def repos2  )

