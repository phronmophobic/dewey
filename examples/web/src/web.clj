(ns web
  (:require [com.phronemophobic.dewey.util
             :refer [read-edn]]
            [hiccup.core :as hiccup]
            [clojure.data.json :as json]
            [clojure.java.io :as io]))


(def releases-url
  "https://api.github.com/repos/phronmophobic/dewey/releases/latest")
(def release-info )

(def release-info
  (delay
    (json/read-str (slurp (io/as-url releases-url)))))

(defn load-latest* [fname]
  (let [info @release-info
        release-url (-> info
                        (get "assets")
                        (->>
                         (filter
                          (fn [m]
                            (= fname (get m "name")))))
                        first
                        (get "browser_download_url"))]
    (read-edn release-url)))
(def load-latest (memoize load-latest*))

(def repos
  (delay (load-latest "all-repos.edn.gz")))

(def repos-short
  (delay
    (into []
          (comp (map (fn [m]

                       {:stars (:stargazers_count m)
                        :name (:name m)
                        :description (:description m)
                        :owner (-> m :owner :login)
                        :url (:html_url m)
                        :topics (:topics m)})))
          @repos)))


(def ++ (fnil + 0))
(def inc+ (fnil inc 0))


(defn write-topics []
  (let [topics
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
         @repos-short)
        
        table
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
            [:td stars]])]

        header
        [:div
         [:a {:href "search.html"}
          "Search"]
         " "
         [:a {:href "topics.html"}
          "Topics"]

         [:a {:href "https://github.com/phronmophobic/dewey/tree/main/examples/web"
              :style "float:right;"}
          "Source on Github"]]
        ]
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
           header
           [:hr]
           table]]
         ))))))

(defn write-repos []
  (with-open [writer (io/writer "public/data/repos.json")]
    (json/write @repos-short writer)))

(defn -main [& args]
  (.mkdirs (io/file "public"))
  (.mkdirs (io/file "public/data"))

  (with-open [out (io/writer "public/search.html")
              in (io/reader (io/resource "search.html"))]
    (io/copy in
             out))
  (write-repos)
  (write-topics))

(comment
  
  ,)

#_(def repos2  )

