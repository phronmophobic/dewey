(ns web
  (:require [com.phronemophobic.dewey.util
             :refer [read-edn]]
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

(comment
  (with-open [writer (io/writer "public/data/repos.json")]
    (json/write repos-short writer))
  ,)

#_(def repos2  )

