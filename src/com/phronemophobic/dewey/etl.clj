(ns com.phronemophobic.dewey.etl
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [com.phronemophobic.dewey :as dewey]
            [com.phronemophobic.dewey.index :as index]
            [com.phronemophobic.taro :as taro]
            [com.phronemophobic.dewey.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :refer [pprint]])
  (:import [java.util.zip
            GZIPOutputStream
            GZIPInputStream]))

;; generic ubuntu ami
(def ami "ami-0574da719dca65348")

(def s3-client (aws/client {:api :s3
                            :region "us-west-1"
                            #_#_:credentials-provider
                            (credentials/profile-credentials-provider "dewey")}))

#_(def ec2-client (aws/client {:api :ec2
                             :region "us-west-1"
                             :credentials-provider
                             (credentials/profile-credentials-provider "dewey")}))


(def steps
  [{:f #'dewey/update-clojure-repo-index
    :outputs [{:file "all-repos.edn"}]}
   {:f #'dewey/find-default-branches
    :outputs [{:file "default-branches.edn"}]}
   {:f #'dewey/download-deps
    :outputs [{:dir "deps"}]}
   {:f #'dewey/update-tag-index
    :outputs [{:file "deps-tags.edn"}]}
   {:f #'dewey/update-available-git-libs-index
    :outputs [{:file "deps-libs.edn"}]}])

(def bucket "com-phronemophobic-dewey")
(def key-prefix "releases")

(defn upload-file [release-id base file]
  (if (.isDirectory file)
    (run! #(upload-file release-id base %) (.listFiles file))
    
    (with-open [is (io/input-stream file)]
      (let [parts (loop [;; since we're walking up the tree
                         ;; use a list to reverse
                         parts ()
                         file file]
                    (if (= file base)
                      parts
                      (recur (conj parts (.getName file))
                             (.getParentFile file))))
            key-parts (into [key-prefix release-id] parts)
            request {:Bucket bucket
                     :Key (clojure.string/join "/" key-parts)
                     :Body is
                     :ACL "private"}]
        (prn request)
        (aws/invoke s3-client {:op :PutObject
                             :request request})))))

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
            response
            (aws/invoke s3-client
                        {:op :GetObject
                         :request
                         {:Bucket bucket
                          :Key key}})
            out-path (io/file release-dir path)
            found? (not= (:cognitect.anomalies/category response)
                         :cognitect.anomalies/not-found)]
        (if found?
          (do
            (with-open [os (io/output-stream out-path)]
             (io/copy (:Body response)
                      os))
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
                       (aws/invoke s3-client
                                   {:op :ListObjectsV2
                                    :request
                                    (merge
                                     {:Bucket bucket
                                      :Prefix prefix}
                                     (when token
                                       {:ContinuationToken token}))})))
                   :kf :NextContinuationToken
                   )]
    (into #{}
          (comp (mapcat :Contents)
                (map :Key)
                (map (fn [k]
                       (->> (clojure.string/split k #"/")
                            (drop 3)
                            (drop-last 1)
                            (into [])))))
          responses)))

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
    (doseq [repo repos]
      (let [
            owner (-> repo :owner :login)
            repo-name (:name repo)
            analysis-dir (io/file release-dir
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
            (util/delete-tree analysis-dir true)))))))

(defn run
  ([]
   (run (str (random-uuid))))
  ([release-id]
   (let [opts {:release-id release-id}]
     (prn release-id)
     (doseq [{:keys [f files] :as step} steps]
       (prn "starting step: " step)
       (f opts)
       ;; upload compressed files to s3
       (let [release-dir (dewey/release-dir release-id)]
         (doseq [fname files
                 :let [file (io/file release-dir fname)]]
           (if (.isDirectory file)
             (let [tar-file (tar-gz! file)]
               (upload-file release-id release-dir tar-file))
             (let [gz-file (gz! file)]
               (upload-file release-id release-dir gz-file)))))))))

