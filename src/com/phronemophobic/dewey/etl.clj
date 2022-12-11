(ns com.phronemophobic.dewey.etl
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [com.phronemophobic.dewey :as dewey]
            [com.phronemophobic.taro :as taro]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]])
  (:import [java.util.zip
            GZIPOutputStream]))

;; generic ubuntu ami
(def ami "ami-0574da719dca65348")

(def s3-client (aws/client {:api :s3
                            :region "us-west-1"
                            :credentials-provider
                            (credentials/profile-credentials-provider "dewey")}))

#_(def ec2-client (aws/client {:api :ec2
                             :region "us-west-1"
                             :credentials-provider
                             (credentials/profile-credentials-provider "dewey")}))


(def steps
  [{:f dewey/update-clojure-repo-index
    :files ["all-repos.edn"]}
   {:f dewey/download-deps
    :files ["deps"]}
   {:f dewey/update-tag-index
    :files ["deps-tags.edn"]}
   {:f dewey/update-available-git-libs-index
    :files ["deps-libs.edn"]}])

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


(defn gz! [file]
  (let [gz-file (io/file (.getParentFile file)
                         (str (.getName file) ".gz"))]
   (with-open [os (io/output-stream gz-file)
               gz  (GZIPOutputStream. os)]
     (io/copy file gz))
   gz-file))

(defn run []
  (let [release-id (str (random-uuid))
        opts {:release-id release-id}]
    (doseq [{:keys [f files]} steps]
      (f opts)
      ;; upload compressed files to s3
      (let [release-dir (dewey/release-dir release-id)]
        (doseq [fname files
                :let [file (io/file release-dir fname)]]
          (if (.isDirectory file)
            (let [tar-file (tar-gz! file)]
              (upload-file release-id release-dir tar-file))
            (let [gz-file (gz! file)]
              (upload-file release-id release-dir gz-file))))))))

