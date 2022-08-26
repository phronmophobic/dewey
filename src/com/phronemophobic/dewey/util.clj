(ns com.phronemophobic.dewey.util
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.shell :as sh]
   [clojure.java.io :as io]
   [slingshot.slingshot :refer [try+ throw+]])
  (:import
   java.nio.file.Files
   java.io.PushbackReader
   java.util.zip.GZIPInputStream))

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

(def auth (-> (slurp "secrets.edn")
              (edn/read-string)
              :github
              ((fn [{:keys [user token]}]
                 (clojure.string/join ":" [user token])))))

(defn with-auth [req]
  (assoc req :basic-auth auth))


(defn ->edn [o]
  (binding [*print-namespace-maps* false
            *print-length* false]
    (pr-str o)))

(defn sh
  "Similar to clojure.java.shell/sh, but use a 30 second timeout.

Passes the given strings to Runtime.exec() to launch a sub-process.

  Options are

  :in      may be given followed by any legal input source for
           clojure.java.io/copy, e.g. InputStream, Reader, File, byte[],
           or String, to be fed to the sub-process's stdin.
  :in-enc  option may be given followed by a String, used as a character
           encoding name (for example \"UTF-8\" or \"ISO-8859-1\") to
           convert the input string specified by the :in option to the
           sub-process's stdin.  Defaults to UTF-8.
           If the :in option provides a byte array, then the bytes are passed
           unencoded, and this option is ignored.
  :out-enc option may be given followed by :bytes or a String. If a
           String is given, it will be used as a character encoding
           name (for example \"UTF-8\" or \"ISO-8859-1\") to convert
           the sub-process's stdout to a String which is returned.
           If :bytes is given, the sub-process's stdout will be stored
           in a byte array and returned.  Defaults to UTF-8.
  :env     override the process env with a map (or the underlying Java
           String[] if you are a masochist).
  :dir     override the process dir with a String or java.io.File.

  You can bind :env or :dir for multiple operations using with-sh-env
  and with-sh-dir.

  sh returns a map of
    :exit => sub-process's exit code
    :out  => sub-process's stdout (as byte[] or String)
    :err  => sub-process's stderr (String via platform default encoding)"
  {:added "1.2"}
  [& args]
  (let [[cmd opts] (#'sh/parse-args args)
        proc (.exec (Runtime/getRuntime) 
               ^"[Ljava.lang.String;" (into-array cmd)
               (#'sh/as-env-strings (:env opts))
               (io/as-file (:dir opts)))
        {:keys [in in-enc out-enc]} opts]
    (if in
      (future
        (with-open [os (.getOutputStream proc)]
          (copy in os :encoding in-enc)))
      (.close (.getOutputStream proc)))
    (with-open [stdout (.getInputStream proc)
                stderr (.getErrorStream proc)]
      (let [out (future (#'sh/stream-to-enc stdout out-enc))
            err (future (#'sh/stream-to-string stderr))
            exited? (.waitFor proc 30 java.util.concurrent.TimeUnit/SECONDS)
            exit-code (if exited?
                        (.exitValue proc)
                        (do
                          (.destroyForcibly proc)
                          -1))
             ]
        {:exit exit-code :out @out :err @err}))))

(defn file-tree-seq
  "like file-seq, but ignore symbolic links"
  {:added "1.0"
   :static true}
  [dir]
    (tree-seq
     (fn [^java.io.File f] (. f (isDirectory)))
     (fn [^java.io.File d]
       (->> d
            (.listFiles)
            (remove (fn [f]
                      (Files/isSymbolicLink (.toPath f))))))
     dir))


