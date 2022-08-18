(ns com.phronemophobic.dewey.index
  (:require [com.phronemophobic.dewey.util
             :refer [copy read-edn]]
            [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [slingshot.slingshot :refer [try+ throw+]]
            [clj-kondo.core :as clj-kondo])
  (:import java.time.Instant
           java.time.Duration
           java.time.LocalDate
           java.io.File
           java.time.format.DateTimeFormatter
           java.io.PushbackReader))


(defn child-file? [^File parentf ^File childf]
  (let [pf (.getCanonicalFile parentf)
        f (.getCanonicalFile childf)]
    (loop [f f]
      (when f
        (if (= f pf)
          true
          (recur (.getParentFile f)))))))

(def path-separator (System/getProperty "path.separator"))

(defn- paths->files [parent-dir paths]
  (->> paths
       (map (fn [fname]
              (if (.isAbsolute (io/file fname))
                (io/file fname)
                (io/file parent-dir fname))))))

(defn deps-edn-paths [ednf]
  (let [deps-edn (read-edn ednf)
        paths (get deps-edn :paths ["."])]
    (paths->files (.getParentFile ednf) paths)))


(defn project-clj-paths [projectf]
  (let [parent-dir (.getParentFile projectf)]
    (sh/with-sh-dir (.getCanonicalPath parent-dir)
      (let [{:keys [out exit]} (sh/sh "lein" "pprint" ":source-paths")]
        (assert (zero? exit))
        (let [paths (edn/read-string out)]
          (paths->files parent-dir paths))))))


(comment
  (deps-edn-classpath (io/file
                       "/Users/adrian/workspace/dewey/releases/2022-07-25/./deps/stopachka/llisp/deps.edn"))
  ,)

(defn analyze-project [paths]
  (let [config {:cache false
                :lint paths
                :config {:analysis {:locals true
                                    :keywords true
                                    :arglists true
                                    :protocol-impls true}}}]
    (clj-kondo/run! config)))


(defn index-repo [rootf]
  ;; find all project.clj files and deps.edn
  ;; for each project
  ;;   generate classpath (filter to only files in project dir)
  ;; generate analysis
  (->> (file-seq rootf)
       (keep (fn [f]
               (when (.isFile f)
                 (let [nm (.getName f)]
                   (cond
                     (= nm "deps.edn")
                     (analyze-project (eps-edn-paths f))

                     (= nm "project.clj")
                     (analyze-project (project-clj-paths f))

                     :else nil)))))))

;; for each repository
;; download zip
;; unzip somewhere
;; analyze
;; save



(defn create-index [repos]
  (doseq [repo repos
          :let [owner (-> repo :owner :login)
                repo-name (:name repo)
                branch (:default_branch repo)
                zip-url (str "https://github.com/" owner "/" repo-name "/archive/refs/heads/" branch ".zip")

                output-file (io/file output-dir "project.zip")
                response (http/request (with-auth
                                         {:url zip-url
                                          :method :get
                                          :as :stream}))]]
    (copy (:body result)
                output-file
                ;; limit file sizes to 100Mb

                (* 100 1024 1024))

    (unzip output-file)
    
    )
  )

;; https://github.com/phronmophobic/dewey/archive/refs/heads/main.zip
(comment
  (def all-repos
    (read-edn "releases/2022-07-25/all-repos.edn.gz"))
  ,)
