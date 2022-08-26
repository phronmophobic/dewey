(ns com.phronemophobic.dewey.util
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [slingshot.slingshot :refer [try+ throw+]])
  (:import
   java.io.PushbackReader
   java.util.zip.GZIPInputStream
   java.util.zip.GZIPOutputStream))

(defn copy
  "Similar to clojure.java.io/copy, but throw exception if more than `max-bytes`
  are attempted to be written."
  [input output max-bytes]
  (with-open [is (io/input-stream input)
              os (io/output-stream output)]
    (let [buffer (make-array Byte/TYPE 1024)]
      (loop [bytes-remaining max-bytes]
        (let [size (.read is buffer)
              write-size (min bytes-remaining
                              size)]
          (when (pos? size)
            (.write os buffer 0 write-size)
            (when (> size bytes-remaining)
              (throw+
               {:type :max-bytes-limit-exceeded
                :limit max-bytes}))

            (recur (- bytes-remaining write-size))))))))

(defn read-edn [fname]
  (with-open [is (io/input-stream fname)
              is (if (str/ends-with? fname ".gz")
                   (GZIPInputStream. is)
                   is)
              rdr (io/reader is)
              rdr (PushbackReader. rdr)]
    (edn/read rdr)))


(defn squelch [f]
  (fn [& args]
    (try
      (apply f args)
      (catch Exception e))))


(defn ->edn [o]
  (binding [*print-namespace-maps* false
            *print-length* false]
    (pr-str o)))

(defn save-obj-edn-gz [fname obj]
  (with-open [os (io/output-stream fname)
              gs (GZIPOutputStream. os)
              writer (io/writer gs)]
    (binding [*print-namespace-maps* false
              *print-length* false
              *out* writer]
      (pr obj))))

(comment

  (def fs (->> (io/file "analysis")
               (.listFiles)
               (filter #(str/ends-with? (.getName %) ".edn.gz"))))



  (def cnt (atom 0))
  
  (prof/start)
  (def fut
    (future
      (time
       (try
         (def analysis (->> fs
                            (map (fn [o]
                                   (swap! cnt inc)
                                   o))
                            (pmap (squelch read-edn))
                            doall))
         (catch Exception e
           (println e))))))

  (while true
    (Thread/sleep 1000)
    (println @cnt))

  

  (def f (prof/stop))

  (time
   (save-obj-edn-gz "analysis.edn.gz" analysis))

  )
