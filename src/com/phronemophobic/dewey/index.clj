(ns com.phronemophobic.dewey.index
  (:require [com.phronemophobic.dewey.util
             :refer [copy read-edn with-auth ->edn]
             :as util]
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
           java.util.Date
           java.io.File
           java.util.zip.GZIPOutputStream
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
      (println parent-dir)
      (let [{:keys [out err exit]} (util/sh "lein" "pprint" ":source-paths")]
        (when-not (zero? exit)
          ;; (println out err)
          (throw+ {:type :lein-fail}))
        (let [paths (edn/read-string out)]
          (paths->files parent-dir paths))))))


(comment
  (deps-edn-classpath (io/file
                       "/Users/adrian/workspace/dewey/releases/2022-07-25/./deps/stopachka/llisp/deps.edn"))
  ,)



(defn default-linters-off []
  (let [default-cfg (clj-kondo/resolve-config (io/file "."))
        linters (into {}
                      (for [k (-> default-cfg :linters keys)]
                        [k {:level :off}]))]
    linters)
  )

(defn analyze-project [paths]
  (let [config {:cache false
                :lint paths
                :config {:analysis {:locals true
                                    :keywords true
                                    :arglists true
                                    :protocol-impls true}
                         :linters (default-linters-off)}}]
    (clj-kondo/run! config)))

(defn relative-path [root subpath]
  (subs (.getCanonicalPath subpath)
        (count (.getCanonicalPath root))))

(defn index-repo [rootf]
  ;; find all project.clj files and deps.edn
  ;; for each project
  ;;   generate classpath (filter to only files in project dir)
  ;; generate analysis
  (doall
   (->> (util/file-tree-seq rootf)
        (keep (fn [f]
                (when (.isFile f)
                  (let [nm (.getName f)]
                    (try+
                     (cond
                       (= nm "deps.edn")
                       (let [basis (relative-path rootf f)]
                         (println "analyzing subproject:" basis "...")
                         {:basis basis
                          :analysis (analyze-project (deps-edn-paths f))})

                       (= nm "project.clj")
                       (let [basis (relative-path rootf f)]
                         (println "analyzing subproject:" basis "...")
                         {:basis basis
                          :analysis (analyze-project (project-clj-paths f))})

                       :else nil)

                     (catch [:type :lein-fail] _
                       (println ":lein-fail")
                       nil)))))))))

(defn unzip [zip-file]
  (sh/with-sh-dir (.getCanonicalPath (.getParentFile zip-file))
    (let [{:keys [exit]} (sh/sh "unzip" (.getCanonicalPath zip-file))]
      (when-not (zero? exit)
        (throw+ {:type :unzip-fail}))
      nil)))


(defn download-repo
  "Downloads the zip for the default branch. Returns clj-kondo analysis."
  [repo]
  (let [owner (-> repo :owner :login)
        repo-name (:name repo)
        branch (:default_branch repo)
        zip-url (str "https://api.github.com" "/repos/" owner "/" repo-name "/zipball/")
        ;; zip-url (str "https://github.com/" owner "/" repo-name "/archive/refs/heads/" branch ".zip")

        output-dir (io/file "/var" "tmp" "dewey")
        output-file (io/file output-dir "project.zip")
        _ (prn "downloading " zip-url "...")
        response (http/request (with-auth
                                 {:url zip-url
                                  :method :get
                                  :as :stream}))]

    (sh/sh "rm" "-rf" (.getCanonicalPath output-dir))

    (.mkdirs output-dir)

    (copy (:body response)
          output-file
          ;; limit file sizes to 100Mb

          (* 100 1024 1024))
    (.close (:body response))

    (let [zip-size (.length output-file)]
      (println "unzipping")
      (unzip output-file)

      (.delete output-file)

      (let [repo-dir (->> output-file
                          (.getParentFile)
                          (.listFiles)
                          first)]
        {:dir repo-dir
         :zip-size zip-size}))))

(defn download-and-index [repo]
  (let [
        {:keys [dir zip-size]} (download-repo repo)
        _ (println "analyzing" (:name repo))
        analysis (index-repo dir)]
    {:zip-size zip-size
     :analysis analysis
     :repo (:full_name repo)
     :analyze-instant (Date.)})
  )


(defn index-repos! [repos]
  (let [chunks (partition-all 4000 repos)]
    (doseq [[chunk sleep?] (map vector
                                chunks
                                (concat (map (constantly true) (butlast chunks))
                                        [false]))]
      (doseq [repo chunk]
        (let [owner (-> repo :owner :login)
              repo-name (:name repo)
              analysis-file (io/file "analysis" (str owner "-" repo-name ".edn.gz"))]
          (try+
           (println "starting " (:name repo))
           (let [analysis (download-and-index repo)]
             (.mkdirs (.getParentFile analysis-file))
             (with-open [fis (io/output-stream analysis-file)
                         gis (GZIPOutputStream. fis)]
               (io/copy (->edn analysis) gis))
             (println "wrote" (.getName analysis-file)))
           (catch [:type :max-bytes-limit-exceeded] e
             (with-open [fis (io/output-stream analysis-file)
                         gis (GZIPOutputStream. fis)]
               (io/copy (->edn {:error e}) gis))
             (println "file too big! skipping..."))
           (catch Object e
             (with-open [fis (io/output-stream analysis-file)
                         gis (GZIPOutputStream. fis)]
               (io/copy (->edn {:error (str e)}) gis))
             (println "failed ..." e)))))
      #_(when sleep?
          (println "sleeping for an hour...")
          (dotimes [i 60]
            (println (- 60 i) " minutes until next chunk.")
            (Thread/sleep (* 1000 60)))))))


(comment
  (require '[com.rpl.specter :as specter])
  (def odd-filenames (atom #{}))
  (defn data->analyses [data]
    (let [dbases (->> data :analysis)
          base->analysis (fn [data base]
                           (let [out {:repo (:repo data),
                                      :analyze-instant (:analyze-instant data),
                                      :basis (:basis base),
                                      :analysis
                                      (specter/transform
                                       [specter/MAP-VALS specter/ALL (specter/must :filename)]
                                       (fn [s]
                                         (if (clojure.string/starts-with? s "/var/tmp/dewey/")
                                           (->> (clojure.string/split s #"/")
                                                (drop 5)
                                                (clojure.string/join "/"))
                                           (do
                                             (swap! odd-filenames conj {:filename s
                                                                        :data data
                                                                        :base base})
                                             specter/NONE)))
                                       (-> base :analysis :analysis))}]
                             out))
          analyses (mapv (fn [base] (base->analysis data base)) dbases)]
      analyses))

  (defn zpath [zip]
    (loop [path []
           zip zip]
      (if zip
        (recur
         (let [o (z/node zip)]
           (if (map-entry? o)
             (conj path (key o))
             path))
         (z/up zip))
        path)))
  ,)





;; https://github.com/phronmophobic/dewey/archive/refs/heads/main.zip
(comment
  (def all-repos
    (read-edn "releases/2022-07-25/all-repos.edn.gz"))

  (index-repos! (->> all-repos
                     (filter #(= "originrose/peer" (:full_name %)))))

  (index-repos! (->> all-repos
                     (drop 50)
                     (take 50)))


  (def remaining
    (->> all-repos
         (remove (fn [repo]
                   (let [owner (-> repo :owner :login)
                         repo-name (:name repo)
                         analysis-file (io/file "analysis" (str owner "-" repo-name ".edn.gz"))]
                     (.exists analysis-file))))))

  (index-repos! remaining)

  (def analysis (time
                 (read-edn "analysis2.edn.gz")))

  (def memsis (time
               (read-edn "analysis/phronmophobic-membrane.edn.gz")))


  (def analyses-files (->> (io/file "analysis")
                           (.listFiles)
                           (filter #(clojure.string/ends-with? (.getName %) ".edn.gz"))))


  (with-open [os (io/output-stream "analysis2.edn.gz")
              gs (GZIPOutputStream. os)
              writer (io/writer gs)]
    (binding [*print-namespace-maps* false
              *print-length* false
              *out* writer]
      (println "[")
      (doseq [fname analyses-files
              :let [data (try
                           (read-edn fname)
                           (catch Exception e
                             nil))]
              :when data
              :let [analyses (data->analyses data)]]
        (doseq [analysis analyses]
          (pr analysis)
          (print "\n")))
      (println "]")))
  
  ,)
