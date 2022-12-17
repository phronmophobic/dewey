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
            [clj-kondo.core :as clj-kondo]
            [edamame.core :as edamame] )
  (:import java.time.Instant
           java.time.Duration
           java.time.LocalDate
           java.util.Date
           java.nio.file.Files
           java.nio.file.attribute.FileAttribute
           java.nio.file.Path
           java.io.File
           java.util.zip.GZIPOutputStream
           java.time.format.DateTimeFormatter
           java.io.PushbackReader
           java.io.FilterInputStream))




(defn child-file? [^File parentf ^File childf]
  (let [pf (.getCanonicalFile parentf)
        f (.getCanonicalFile childf)]
    (loop [f f]
      (when f
        (if (= f pf)
          true
          (recur (.getParentFile f)))))))

(def path-separator (System/getProperty "path.separator"))
(def repos-dir (io/file "repos"))

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
  (let [project (with-open [rdr (io/reader projectf)
                            rdr (edamame/reader rdr)]
                  (edamame/parse-next
                   rdr
                   (edamame/normalize-opts {:all true})))

        {:keys [source-paths]} (when (seq? project)
                                 (let [kvs (drop 3 project)]
                                   (when (even? (count kvs))
                                     (apply hash-map kvs))))
        parent-dir (.getParentFile (io/file projectf))]
    (paths->files parent-dir
                  (or source-paths
                      ["src"]))))


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
                                    :protocol-impls true
                                    :var-definitions {:meta true}
                                    :namespace-definitions {:meta true}}
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
  "Downloads the zip. Returns clj-kondo analysis."
  [repo]
  (let [owner (-> repo :owner :login)
        repo-name (:name repo)
        ;; zip-url (str "https://api.github.com" "/repos/" owner "/" repo-name "/zipball/")
        zip-url (str "https://github.com/" owner "/" repo-name "/archive/" (:git/sha repo) ".zip")

        output-dir (io/file "/var" "tmp" "dewey")
        output-file (io/file output-dir "project.zip")
        _ (prn "downloading " zip-url "...")
        response (http/request (with-auth
                                 {:url zip-url
                                  :method :get
                                  :as :stream}))]

    (sh/sh "rm" "-rf" (.getCanonicalPath output-dir))

    (.mkdirs output-dir)
    (let [body (:body response)
          ;; wrap stream to call close on http client
          ;; before closing underlying inputstream
          body (proxy [FilterInputStream]
                   [^InputStream body]
                 (close []
                   (.close (:http-client response))))]
     (with-open [body body]
       (copy body
             output-file
             ;; limit file sizes to 100Mb
             (* 100 1024 1024))))

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

(defn local-checkout [repo]
  (let [
        repo-dir (io/file repos-dir
                          (-> repo :owner :login)
                          (:name repo))]

    (if (not (.exists repo-dir))
      (throw+ {:type :clone-fail
               :reason :missing-repo})
      (let [tmp (-> (Files/createTempDirectory (str (-> repo :owner :login)
                                                    "_"
                                                    (:name repo)
                                                    "_")
                                               (into-array FileAttribute nil))
                    (.toFile))
            {:keys [exit out err]} (util/sh "git" "clone" (.getCanonicalPath repo-dir) (.getCanonicalPath tmp))]
        (when-not (zero? exit)
          (println out)
          (println err)
          (throw+ {:type :clone-fail
                   :out out
                   :err err}))
        tmp))))

(defn download-and-index [repo]
  (let [
        {:keys [dir zip-size]} (download-repo repo)
        _ (println "analyzing" (:name repo))
        analysis (index-repo dir)]
    {:zip-size zip-size
     :analysis analysis
     :git/sha (:git/sha repo)
     :repo (:full_name repo)
     :analyze-instant (Date.)})
  )

(defn index-repo! [repo]
  (try
   (println "starting " (:name repo))
   (let [analysis (download-and-index repo)]
     analysis)
   #_(catch [:type :max-bytes-limit-exceeded] e
     (println "file too big! skipping...")
     {:error e})
   (catch OutOfMemoryError e
     (prn "got error " e)
     (if-let [data (ex-data e)]
       {:error data}
       {:error (str e)}))
   (catch Exception e
     (prn "got error " e)
     (if-let [data (ex-data e)]
       {:error data}
       {:error (str e)}))))

(comment
  (def my-analysis
    (index-repo! {:name "membrane"
                  :owner {:login "phronmophobic"}
                  :git/sha "9e5a2a53e3909e86d119153693c2d0a4d0dbd24c"}))
  ,)

#_(defn index-repos! [repos]
  (let [chunks (partition-all 4000 repos)]
    (doseq [[chunk sleep?] (map vector
                                chunks
                                (concat (map (constantly true) (butlast chunks))
                                        [false]))]
      (doseq [repo chunk]
        )
      #_(when sleep?
          (println "sleeping for an hour...")
          (dotimes [i 60]
            (println (- 60 i) " minutes until next chunk.")
            (Thread/sleep (* 1000 60)))))))


#_(defn index-repos! [repos]
  (.mkdirs (io/file "analysis"))
  (dorun
   (pmap (fn [repo]
           (let [owner (-> repo :owner :login)
                 repo-name (:name repo)
                 full-repo-name (str owner "/" repo-name)
                 analysis-file (io/file "analysis" (str owner "-" repo-name ".edn.gz"))]
             (when-not (.exists analysis-file)
              (println full-repo-name "creating local checkout ")
              (try+
               (let [
                     repo-dir (local-checkout repo)]
                 (try+
                  (println full-repo-name "analyzing" )
                  (let [analysis (index-repo repo-dir)]
                    (.mkdirs (.getParentFile analysis-file))
                    (with-open [fis (io/output-stream analysis-file)
                                gis (GZIPOutputStream. fis)]
                      (io/copy (->edn analysis) gis))
                    (println full-repo-name "done" ))
                  (catch [:type :max-bytes-limit-exceeded] e
                    (with-open [fis (io/output-stream analysis-file)
                                gis (GZIPOutputStream. fis)]
                      (io/copy (->edn {:error e}) gis))
                    (println full-repo-name "file too big! skipping..."))
                  (catch Object e
                    (with-open [fis (io/output-stream analysis-file)
                                gis (GZIPOutputStream. fis)]
                      (io/copy (->edn {:error (str e)}) gis))
                    (println full-repo-name "failed ..." e))
                  (finally
                    (println "deleting" (.getCanonicalPath repo-dir) (util/delete-tree repo-dir true))
                    )))
               (catch [:type :clone-fail
                       :reason :missing-repo] _
                 (println full-repo-name  "missing repo. skipping..."))))))
         repos))
  )

(defn clone-repo [repo]
  (let [owner (-> repo :owner :login)
        output-dir (io/file repos-dir owner)
        clone-url (:clone_url repo)]
    (.mkdirs output-dir)
    (sh/with-sh-dir (.getCanonicalPath output-dir)
      (let [{:keys [out err exit]} (util/sh "git" "clone" "--bare" "--depth=1" clone-url)]
        (when-not (zero? exit)
          (println out)
          (println err)
          (throw+ {:type :clone-fail}))
        nil)))
  )

(comment
  (require '[com.rpl.specter :as specter])
  (def odd-filenames (atom #{}))
  (defn data->analyses [data]
    (let [dbases (->> data :analysis)
          base->analysis (fn [data base]
                           (let [out {:repo (:repo data),
                                      :analyze-instant (:analyze-instant data),
                                      :git/sha (:git/sha data)
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

  ;; check out all repos
  (def to-checkout (->> all-repos
                        (filter #(< (:size %) 400000))))

  (doseq [repo to-checkout
          :let [owner (-> repo :owner :login)
                output-dir (io/file repos-dir owner)
                repo-dir (io/file output-dir (str (:name repo) ".git"))]
          :when (not (.exists repo-dir))]
    (println "checking out" (-> repo :owner :login) (:name repo))
    (try+
      (clone-repo repo)
      (catch [:type :clone-fail] _
        (println "clone failed."))))

  

  (def analysis (time
                 (read-edn "releases/2022-07-25/analysis.edn.gz")))

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


