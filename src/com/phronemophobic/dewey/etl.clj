(ns com.phronemophobic.dewey.etl
  (:require [amazonica.core :as amazonica]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3-transfer]
            [com.phronemophobic.dewey :as dewey]
            [com.phronemophobic.dewey.index :as index]
            [com.phronemophobic.taro :as taro]
            [com.phronemophobic.dewey.util :as util]
            [com.phronemophobic.dewey.db.sqlite :as sqlite]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [com.rpl.specter :as specter])
  (:import [java.util.zip
            GZIPOutputStream
            GZIPInputStream]))

;; generic ubuntu ami
(def ami "ami-0574da719dca65348")

(def s3-creds
  (merge {}
         (when-let [profile (System/getProperty "AWS_PROFILE")]
           {:profile profile
            :endpoint "us-east-1"})))

(amazonica/defcredential s3-creds)

(def steps
  [{:f #'dewey/update-clojure-repo-index
    :outputs [{:file "all-repos.edn"}]}
   {:f #'dewey/update-default-branches
    :outputs [{:file "default-branches.edn"}]}
   {:f #'dewey/download-deps
    :outputs [{:dir "deps"}]}
   {:f #'dewey/update-tag-index
    :outputs [{:file "deps-tags.edn"}]}
   {:f #'dewey/update-available-git-libs-index
    :outputs [{:file "deps-libs.edn"}]}])

(def bucket "com-phronemophobic-dewey")
(def key-prefix "releases")

(defn get-object [key]
  (try
    (s3/get-object bucket
                   key)
    (catch Exception e
      (clojure.pprint/pprint e)
      (if (= 404 (:status-code (amazonica/ex->map e)))
        nil
        ;; else
        (throw e)))))

(defn upload-file
  ([release-id file]
   (upload-file release-id (.getParentFile file) file))
  ([release-id base file]
   (if (.isDirectory file)
     (run! #(upload-file release-id base %) (.listFiles file))
     
     (let [parts (loop [ ;; since we're walking up the tree
                        ;; use a list to reverse
                        parts ()
                        file file]
                   (if (= file base)
                     parts
                     (recur (conj parts (.getName file))
                            (.getParentFile file))))
           key-parts (into [key-prefix release-id] parts)
           key (clojure.string/join "/" key-parts)]
       (s3/put-object bucket
                      key
                      file)))))

(defn tar-gz!
  "Converts a directory to a tar.gz and returns the tar.gz file."
  [file]
  (assert (.isDirectory file))
  (let [tar-gz-file (io/file (.getParentFile file)
                             (str (.getName file) ".tar.gz"))]
    (with-open [os (io/output-stream tar-gz-file)]
      (taro/write-tar-gz! os file))
    tar-gz-file))

(defn untar-gz! [tar-gz-file]
  (let [out-dir (.getParentFile tar-gz-file)
        ret-dir (io/file (.getParentFile tar-gz-file)
                         (str/replace (.getName tar-gz-file) #".tar.gz$" ""))]
    (with-open [is (io/input-stream tar-gz-file)
               gz  (GZIPInputStream. is)]
     (taro/untar gz
                 out-dir))
    ret-dir))

(defn gz! [file]
  (let [gz-file (io/file (.getParentFile file)
                         (str (.getName file) ".gz"))]
   (with-open [os (io/output-stream gz-file)
               gz  (GZIPOutputStream. os)]
     (io/copy file gz))
   gz-file))

(defn ungz! [gz-file]
  (let [file (io/file (.getParentFile gz-file)
                      (str/replace (.getName gz-file) #".gz$" ""))]
    (with-open [is (io/input-stream gz-file)
                gz  (GZIPInputStream. is)]
      (io/copy gz file))
    file))


(defn download-release [release-id]
  (let [release-dir (dewey/release-dir release-id)]
    (doseq [{:keys [outputs]} steps
            output outputs]
      (let [path (if-let [dir (:dir output)]
                   (str dir ".tar.gz")
                   (str (:file output) ".gz"))
            key (clojure.string/join "/"
                                     [key-prefix release-id path])
            _ (print "downloading " key " ... ")
            response (get-object key)
            out-path (io/file release-dir path)
            ]
        (if response
          (do
            (with-open [os (io/output-stream out-path)
                        is (:input-stream response)]
              (io/copy is os))
            (if (:dir output)
              (untar-gz! out-path)
              (ungz! out-path))
            (println "done."))
          (println "not found."))))))

(defn analyzed-repos
  "Checks s3 for already uploaded analysis for this release."
  [release-id]
  (let [responses (iteration
                   (fn [token]
                     (let [prefix (clojure.string/join "/"
                                                       [key-prefix release-id "analysis"])]
                       (s3/list-objects-v2
                        (merge
                         {:bucket-name bucket
                          :prefix prefix}
                         (when token
                           {:continuation-token token})))))
                   :kf :next-continuation-token
                   )]
    (into #{}
          (comp (mapcat :object-summaries)
                (map :key)
                (map (fn [k]
                       (->> (clojure.string/split k #"/")
                            (drop 3)
                            (drop-last 1)
                            (into [])))))
          responses)))

(def problematic-index-repos
  #{
    ["penpot" "penpot"] ;; Runs out of Java Heap Memory
    })

(defn index-release [release-id]
  (let [release-dir (dewey/release-dir release-id)
        repos (let [default-branches
                    (util/read-edn (io/file release-dir "default-branches.edn"))]
                (into
                 []
                 (comp (map (fn [[repo branch-info]]
                         (assoc repo
                                :git/sha (-> branch-info
                                             :commit
                                             :sha))))
                       (filter :git/sha))
                 default-branches))

        already-analyzed (atom (analyzed-repos release-id))]
    (doseq [repo repos
            :let [owner (-> repo :owner :login)
                  repo-name (:name repo)]
            :when (not (contains? problematic-index-repos
                                  [owner repo-name]))]
      (let [analysis-dir (io/file release-dir
                                  "analysis"
                                  owner
                                  repo-name)
            analysis-file (io/file analysis-dir "analysis.edn.gz")]
        (if (or (.exists analysis-file)
                (contains? @already-analyzed [owner repo-name]))
          (do
            (println (str owner "/" repo-name) "-" "analysis already exists. skipping..."))
          (let [analysis (index/index-repo! repo)]
            (.mkdirs analysis-dir)
            (util/save-obj-edn-gz analysis-file
                                  analysis)
            (upload-file release-id release-dir analysis-file)
            (swap! already-analyzed conj [owner repo-name])
            #_(util/delete-tree analysis-dir true)))))))

(defn run
  ([]
   (run (str (random-uuid))))
  ([release-id]
   (let [opts {:release-id release-id}]
     (prn release-id)
     (doseq [{:keys [f outputs] :as step} steps]
       (prn "starting step: " step)
       (f opts)
       ;; upload compressed files to s3
       (let [release-dir (dewey/release-dir release-id)]
         (doseq [output outputs]
           (if-let [dir (:dir output)]
             (let [tar-file (tar-gz! (io/file release-dir dir))]
               (upload-file release-id release-dir tar-file))
             (let [gz-file (gz! (io/file release-dir (:file output)))]
               (upload-file release-id release-dir gz-file)))))))))

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
                                         specter/NONE))
                                     (-> base :analysis :analysis))}]
                           out))
        analyses (mapv (fn [base] (base->analysis data base)) dbases)]
    analyses))

(defn combine-analyses [release-id]
  (let [release-dir (dewey/release-dir release-id)
        analyses-files
        (->> (io/file release-dir
                      "analysis")
             (tree-seq #(.isDirectory %)
                       #(.listFiles %))
             (filter #(clojure.string/ends-with? (.getName %) ".edn.gz")))
        stdout *out*]
    (println "combining analyses")
    (with-open [os (io/output-stream
                    (io/file release-dir
                             "analysis.edn.gz"))
                gs (GZIPOutputStream. os)
                writer (io/writer gs)]
      (binding [*print-namespace-maps* false
                *print-length* false
                *out* writer]
        (println "[")
        (doseq [fname analyses-files
                :let [data (try
                             (binding [*out* stdout]
                               (println (str "including " fname)))
                             (util/read-edn fname)
                             (catch Throwable e
                               (binding [*out* stdout]
                                 (prn e))
                               nil))]
                :when data
                :let [analyses (data->analyses data)]]
          (doseq [analysis analyses]
            (pr analysis)
            (print "\n")))
        (println "]")))))

(defn run-index
  ([release-id]
   (let [release-dir (dewey/release-dir release-id)]
     (download-release release-id)
     (index-release release-id)
     (combine-analyses release-id)
     (upload-file release-id (io/file release-dir "analysis.edn.gz"))

     (let [sql-file (sqlite/index->db (io/file release-dir "analysis.edn.gz"))]
       (upload-file release-id sql-file)))))



(defn make-release [release-id sha]
  (let [release-dir (dewey/release-dir release-id)]

    (download-release release-id)
    ;; download analysis and sql
    (doseq [fname ["analysis.edn.gz"
                   "dewey.sqlite3.sql.gz"]]
      (let [key (clojure.string/join "/"
                                     [key-prefix release-id fname])
            response (get-object key)
            out-path (io/file release-dir fname)]
        (assert response "Missing key.")
        (clojure.pprint/pprint response)
        (with-open [os (io/output-stream out-path)
                    is (:input-stream response)]
          (io/copy is os))))

    (dewey/make-github-release
     release-id
     sha
     (for [fname ["all-repos.edn.gz"
                  "analysis.edn.gz"
                  "dewey.sqlite3.sql.gz"
                  "default-branches.edn.gz"
                  "deps-libs.edn.gz"
                  "deps-tags.edn.gz"
                  "deps.tar.gz"]]
       (io/file release-dir fname)))))

