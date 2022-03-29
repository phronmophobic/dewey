(ns com.phronemophobic.dewey
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import java.time.Instant
           java.time.Duration
           java.time.LocalDate
           java.time.format.DateTimeFormatter
           java.io.PushbackReader))


(def api-base-url "https://api.github.com")

(def search-repos-url (str api-base-url "/search/repositories"))
(def auth (-> (slurp "secrets.edn")
              (edn/read-string)
              :github
              ((fn [{:keys [user token]}]
                 (clojure.string/join ":" [user token])))))

(defn with-auth [req]
  (assoc req :basic-auth auth))

(def base-request
  {:url search-repos-url
   :method :get
   :as :json
   :query-params {:per_page 100
                  :sort "stars"
                  :order "desc"}})

(def formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))
(defn release-dir []
  (let [date-str (.format (LocalDate/now)
                          formatter)
        dir (io/file "releases" date-str)]
    (.mkdirs dir)
    dir))

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

(defn search-repos-request [query]
  (assoc-in base-request [:query-params :q] query))

(defn rate-limit-sleep! [response]
  (when response
    (let [headers (:headers response)
          rate-limit-remaining (Long/parseLong (get headers "X-RateLimit-Remaining"))
          rate-limit-reset (-> headers
                               (get "X-RateLimit-Reset")
                               (Long/parseLong)
                               (Instant/ofEpochSecond))]
      (println "limit remaining " rate-limit-remaining)
      (when (<= rate-limit-remaining 0)
        (let [duration (Duration/between (Instant/now)
                                         rate-limit-reset)
              duration-ms (+ 1000 (.toMillis duration))]
          (when (pos? duration-ms)
            (prn "sleeping " duration-ms)
            (Thread/sleep duration-ms)))))))

(defn find-clojure-repos []
  (iteration
   (fn [{:keys [url num-stars last-response] :as k}]
     (prn (select-keys k [:url :num-stars]))
     (let [req
           (cond
             ;; initial request
             (nil? k) (search-repos-request "language:clojure")

             ;; received next-url
             url (assoc base-request
                        :url url)

             ;; received star number
             num-stars (search-repos-request (str "language:clojure " "stars:" num-stars))

             :else (throw (Exception. (str "Unexpected key type: " (pr-str k)))))]
       (rate-limit-sleep! last-response)
       (let [response (http/request (with-auth req))]
         (assoc response
                ::key k
                ::request req))))
   :kf
   (fn [response]
     (let [num-stars (-> response
                         ::key
                         :num-stars)
           url (-> response :links :next :href)]
       (if url
         {:last-response response
          :url url
          :num-stars num-stars}
         (if num-stars
           (let [next-num-stars (dec num-stars)]
             (when (>= next-num-stars 1)
               {:num-stars next-num-stars
                :last-response response}))
           (let [num-stars (-> response
                               :body
                               :items
                               last
                               ;; want to continue from where we left off
                               :stargazers_count)]
             {:num-stars num-stars
              :last-response response})))))
;   :initk {:num-stars 50}
   ))

(defn deps-url [repo]
  (let [full-name (:full_name repo)
        default-branch (:default_branch repo)
        fname "deps.edn"]
    (str "https://raw.githubusercontent.com/" full-name "/" default-branch "/" fname)))

(defn sanitize [s]
  (str/replace s #"[^a-zA-Z0-9_.-]" "_"))

(defn load-all-repos []
  (with-open [rdr (io/reader (io/file (release-dir) "all-repos.edn"))
                                rdr (PushbackReader. rdr)]
                      (edn/read rdr)))

(defn download-deps
  ([opts]
   (let [repos (or (:repos opts)
                   (load-all-repos))
         repo-count (count repos)
         deps-dir (io/file (release-dir) "deps")
         ;; default rate limit is 5k/hour
         ;; aiming for 4.5k/hour since there's no good feedback mechanism
         chunks (partition-all 4500
                               (map-indexed vector repos))]
     (.mkdirs deps-dir)
     (doseq [[chunk sleep?] (map vector
                                 chunks
                                 (concat (map (constantly true) (butlast chunks))
                                         [false]))]
       (doseq [[i repo] chunk]
         (try+
          (let [name (:name repo)
                owner (-> repo :owner :login)
                _ (print i "/" repo-count  " checking " name owner "...")
                result (http/request (with-auth
                                       {:url (deps-url repo)
                                        :method :get
                                        :as :stream}))
                output-dir (io/file deps-dir
                                    (sanitize owner)
                                    (sanitize name))
                output-file (io/file output-dir "deps.edn")]
            (.mkdirs output-dir)
            (println "found " owner "/" name "/deps.edn")
            (copy (:body result)
                  output-file
                  ;; limit file sizes to 50kb

                  (* 50 1024))
            (.close (:body result)))
          (catch [:status 404] {:keys [body]}
            (println "not found" ))
          (catch [:type :max-bytes-limit-exceeded] _
            (println "deps file too big! skipping..."))))

       (when sleep?
         (println "sleeping for an hour")
         ;; sleep an hour
         (dotimes [i 60]
           (println (- 60 i) " minutes until next chunk.")
           (Thread/sleep (* 1000 60))))))))



;; (repos/repos {:auth auth :per-page 1})

(defn ->edn [o]
  (binding [*print-namespace-maps* false
            *print-length* false]
    (pr-str o)))


(defn parse-edn [f]
  (try
    (with-open [rdr (io/reader f)
                rdr (PushbackReader. rdr)]
      (edn/read rdr))
    (catch Exception e
      (prn f e)
      nil)))

(defn update-clojure-repo-index [& args]
  (let [all-responses (vec
                       (find-clojure-repos))
        all-repos (->> all-responses
                       (map :body)
                       (mapcat :items))]
    (spit (io/file (release-dir) "all-repos.edn")
          (->edn all-repos))))


(defn archive-zip-url
  ([owner repo]
   (archive-zip-url owner repo nil))
  ([owner repo ref]
   (str api-base-url "/repos/"owner "/"repo "/zipball/" ref)))


