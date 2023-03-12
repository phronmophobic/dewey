(ns util
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import java.io.PushbackReader
           java.util.zip.GZIPInputStream))


(defn on-close
  "Transducer that calls f on completion."
  [f]
  (fn [rf]
    (fn
      ([] (rf))
      ([result]
       (f)
       (rf result))
      ([result input]
       (rf result input)))))

(defn analyses-seq
  "Reads f and returns a sequence of analyses."
  [f]
  (let [is (io/input-stream f)
        gz (GZIPInputStream. is)
        rdr (io/reader gz)]
   (sequence
    (comp (map edn/read-string)
          (on-close
           (fn []
             (prn "closing!")
             (.close rdr)
             (.close gz)
             (.close is))))
    (->> (line-seq rdr)
         (drop 1)
         (drop-last 1)))))


(defn analyses-iter
  "Returns an iterable of sequences by reading f.

  Will reopen and reread fname when reused as an iterable argument."
  [fname]
  (reify Iterable
    (iterator [_]
      (.iterator (analyses-seq fname)))))
