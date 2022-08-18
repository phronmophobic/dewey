(ns com.phronemophobic.dewey
  (:require [com.phronemophobic.dewey.util
             :refer [copy read-edn]]
            [clj-http.client :as http]
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

(defn load-all-repos []
  (read-edn (io/file (release-dir) "all-repos.edn")))

(defn fname-url [repo fname]
  (let [full-name (:full_name repo)
        default-branch (:default_branch repo)]
    (str "https://raw.githubusercontent.com/" full-name "/" default-branch "/" fname)))

(defn sanitize [s]
  (str/replace s #"[^a-zA-Z0-9_.-]" "_"))



(defn fname-dir [dirname]
)

(defn repo->file [repo dir fname]
  (io/file dir
           (sanitize (-> repo :owner :login))
           (sanitize (:name repo))
           fname)
  )

(defn download-file
  ([opts]
   (let [repos (or (:repos opts)
                   (load-all-repos))
         fname (get opts :fname "deps.edn")
         dirname (get opts :dirname "deps")
         repo-count (count repos)
         ;; default rate limit is 5k/hour
         ;; aiming for 4.5k/hour since there's no good feedback mechanism
         chunks (partition-all 4500
                               (map-indexed vector repos))
         fname-dir (io/file (release-dir) dirname)]
     (.mkdirs fname-dir)
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
                                       {:url (fname-url repo fname)
                                        :method :get
                                        :as :stream}))
                output-file (repo->file repo fname-dir fname)]
            (.mkdirs (.getParentFile output-file))
            (println "found.")
            (copy (:body result)
                  output-file
                  ;; limit file sizes to 50kb

                  (* 50 1024))
            (.close (:body result)))
          (catch [:status 404] {:keys [body]}
            (println "not found" ))
          (catch [:type :max-bytes-limit-exceeded] _
            (println "file too big! skipping..."))))

       (when sleep?
         (println "sleeping for an hour")
         ;; sleep an hour
         (dotimes [i 60]
           (println (- 60 i) " minutes until next chunk.")
           (Thread/sleep (* 1000 60))))))))

(defn download-deps
  ([]
   (download-deps {}))
  ([opts]
   (download-file {:fname "deps.edn"
                   :dirname "deps"})))



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


(comment
  (def tags
    (http/request (with-auth
                    {:url tag-url
                     :as :json
                     :method :get}
                    )))
  ,)

(defn find-tags [repos]
  (iteration
   (fn [{:keys [repos last-response] :as k}]
     (let [repo (first repos)
           req {:url (:tags_url repo)
                :as :json
                :method :get}]
       (prn req)
       (rate-limit-sleep! last-response)
       (let [response (http/request (with-auth req))]
         (assoc response
                ::repo repo
                ::key k
                ::request req))))
   :vf (juxt ::repo :body)
   :kf
   (fn [response]
     (when-let [next-repos (-> response
                               ::key
                               :repos
                               next)]
      {:last-response response
       :repos next-repos}))
   :initk {:repos (seq repos)}))

(defn update-tag-index [& args]
  (let [all-repos (load-all-repos)
        deps-repos (->> all-repos
                        (filter (fn [repo]
                                  (.exists (repo->file repo
                                                       (io/file (release-dir) "deps")
                                                       "deps.edn")))))
        all-tags (vec (find-tags deps-repos))]
    (spit (io/file (release-dir) "deps-tags.edn")
          (->edn all-tags))))

(defn load-deps-tags []
  (read-edn (io/file (release-dir) "deps-tags.edn")))

(defn load-available-git-libs []
  (read-edn (io/file (release-dir)) "deps-libs.edn"))

(defn update-available-git-libs-index [& args]
  (let [deps-tags (load-deps-tags)
        deps-libs
        (into {}
              (map (fn [[repo tags]]
                     ;;io.github.yourname/time-lib {:git/tag "v0.0.1" :git/sha "4c4a34d"}
                     (let [login (-> repo :owner :login)
                           repo-name (:name repo)
                           repo-name (if (not (re-matches #"^[a-zA-Z].*" repo-name))
                                       (str "X-" repo-name)
                                       repo-name)
                           lib (symbol (str "io.github." login) repo-name)
                           versions
                           (vec
                            (for [tag tags]
                              {:git/tag (:name tag)
                               :git/sha (-> tag :commit :sha)}))]
                       [lib
                        {:description (:description repo)
                         :lib lib
                         :topics (:topics repo)
                         :stars (:stargazers_count repo)
                         :url (:html_url repo)
                         :versions versions}])))
              deps-tags)]
    (spit (io/file (release-dir) "deps-libs.edn")
          (->edn deps-libs))))

(defn make-release [& args]
  (update-clojure-repo-index)
  (download-deps {})
  (update-tag-index)
  (update-available-git-libs-index))
