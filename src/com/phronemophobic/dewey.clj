(ns com.phronemophobic.dewey
  (:require [com.phronemophobic.dewey.util
             :refer [copy read-edn with-auth ->edn]
             :as util]
            [datalevin.core :as d]
            [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import java.time.Instant
           java.time.Duration
           java.time.format.DateTimeFormatter
           java.io.PushbackReader))


(def api-base-url "https://api.github.com")

(def search-repos-url (str api-base-url "/search/repositories"))

(def base-request
  {:url search-repos-url
   :method :get
   :as :json
   :query-params {:per_page 100
                  :sort "stars"
                  :order "desc"}})

(defn release-dir [release-id]
  (let [dir (io/file "releases" release-id)]
    (.mkdirs dir)
    dir))

(defn search-repos-request [query]
  (assoc-in base-request [:query-params :q] query))

(defn list-releases []
  (let [list-release-req
        {:url (str api-base-url
                   "/repos/phronmophobic/dewey/releases")
         :method :get
         :content-type :json
         :as :json}]
    (http/request (with-auth list-release-req))))

(defn publish-release [release-id]
  (let [publish-release-req
        {:url (str api-base-url
                   "/repos/phronmophobic/dewey/releases/"
                   release-id)
         :method :patch
         :content-type :json
         :form-params {"draft" false}
         :as :json}]
    (http/request (with-auth publish-release-req))))

(defn make-github-release [release-id sha files]
  (let [make-tag-req
        {:url (str api-base-url
                   "/repos/phronmophobic/dewey/git/tags")
         :method :post
         :content-type :json
         :form-params
         {"tag" release-id
          "message" "Release",
          "object" sha
          "type" "commit",}
         :as :json}
        make-tag-response (http/request (with-auth make-tag-req))
        ;; _ (clojure.pprint/pprint make-tag-response)

        make-release-req
        {:url (str api-base-url
                   "/repos/phronmophobic/dewey/releases")
         :method :post
         :content-type :json
         :form-params {"tag_name" release-id
                       "target_commitish" "main"
                       "name" release-id
                       "draft" true
                       "prerelease" false}
         :as :json}
        make-release-response (http/request (with-auth make-release-req))
        github-release-id (-> make-release-response
                              :body
                              :id)]
    ;; (clojure.pprint/pprint make-release-response)
    (assert github-release-id)
    (doseq [file files]
      (let [upload-req
            {:url (str "https://uploads.github.com/repos/phronmophobic/dewey/releases/" github-release-id "/assets")
             :headers {"Content-Type" "application/octet-stream"}
             :method :post
             :query-params {:name (.getName file)}
             :body file
             :as :json}]
        (prn "uploading" (.getName file))
        (http/request (with-auth upload-req))))

    (publish-release github-release-id)))

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

(defn with-retries [step]
  (fn [m]
    (let [result
          (try+
           (step m)
           (catch [:status 500] e
             ::error)
           (catch [:status 502] e
             ::error)
           (catch [:status 503] e
             ::error)
           (catch [:status 504] e
             ::error))]
      (if (= result ::error)
        (let [error-count (get m ::error-count 0)]
          (if (< error-count 3)
            (do
              ;; sleep for a second and then retry
              (prn "received 50X error. retrying...")
              (Thread/sleep 1000)
              (recur (assoc m ::error-count (inc error-count))))
            (throw (ex-info "Failed after retries"
                            {:k m}))))
        result))))

(defn find-clojure-repos []
  (iteration
   (with-retries
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
                  ::request req)))))
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

(defn load-all-repos [release-id]
  (read-edn (io/file (release-dir release-id) "all-repos.edn")))

(defn fname-url [repo fname]
  (let [full-name (:full_name repo)
        ref (:git/sha repo)]
    (str "https://raw.githubusercontent.com/" full-name "/" ref "/" fname)))

(defn sanitize [s]
  (str/replace s #"[^a-zA-Z0-9_.-]" "_"))

(defn repo->file [repo dir fname]
  (io/file dir
           (sanitize (-> repo :owner :login))
           (sanitize (:name repo))
           fname))


(defn ^:private do-download
  ([url]
   (do-download url 3))
  ([url retries]
   (loop [retries retries]
     (let [result
           (try+
            (let [result (http/request (with-auth
                                        {:url url
                                         :method :get
                                         :as :stream}))
                  bao (java.io.ByteArrayOutputStream.)]
              (copy (:body result)
                    bao
                    ;; limit file sizes to 50kb
                    (* 50 1024))
              (.close (:body result))
              (.toByteArray bao))
            (catch [:status 500] e
              ::error)
            (catch [:status 502] e
              ::error)
            (catch [:status 503] e
              ::error)
            (catch [:status 504] e
              ::error)
            (catch [:status 429] e
              (dotimes [i 60]
                (println (- 60 i) " minutes until next chunk.")
                (Thread/sleep (* 1000 60)))
              ::error)
            (catch [:status 404] {:keys [body]}
              ::missing)
            (catch [:type :max-bytes-limit-exceeded] _
              ::too-big))]
       (if (= result ::error)
         (if (pos? retries)
           (recur (dec retries))
           (throw (Exception. "too many retries. quitting")))
         result)))))

(def ^:private file-cache-table "file-cache-table")
(defn ^:private get-file [db k]
  (d/get-value db file-cache-table k))

(defn ^:private put-file [db k bs]
  (d/transact-kv
   db
   [[:put file-cache-table k bs]]))

(defn ^:private lookup-file [db k compute]
  (if-let [bs (get-file db k)]
    bs
    ;; else
    (let [val (compute)]
      (put-file db k val)
      val)))

(defmacro ^:private with-file-cache-db [[db release-id] & body ]
  `(let [~db (doto (d/open-kv (-> (io/file (release-dir ~release-id)
                                           "file-cache.db")
                                  (.getCanonicalPath)))
               (d/open-dbi file-cache-table))]
     (try
       ~@body
       (finally
         (d/close-kv ~db)))))

(defn download-file
  ([{:keys [repos
            fname
            release-id
            dirname]}]
   (assert (and repos
                fname
                dirname))
   (let [repo-count (count repos)
         fname-dir (io/file (release-dir release-id) dirname)]
     (.mkdirs fname-dir)
     
     (with-file-cache-db
         [db release-id]
       (doseq [[i repo] (map-indexed vector repos)]
         (let [name (:name repo)
               owner (-> repo :owner :login)
               _ (print i "/" repo-count  " checking " name owner "...")
               
               url (fname-url repo fname)
               k {:git/sha (:git/sha repo)
                  :repo name
                  :owner owner
                  :file fname}
               bs (db/lookup-file db k (fn [] (do-download url)))
               
               output-file (repo->file repo fname-dir fname)]
           
           (case bs
             
             ::too-big (println "file too big! skipping...")
             ::missing (println "not found" )
             
             ;; else
             (do
               (when-not (bytes? bs)
                 (throw (Exception. "expected byte array")))
               (println "found.")
               (.mkdirs (.getParentFile output-file))
               (io/copy bs output-file)))))))))

(defn download-deps
  ([opts]
   (let [release-id (:release-id opts)
         repos (->> (util/read-edn (io/file (release-dir release-id)
                                            "default-branches.edn"))
                    (into
                     []
                     (comp (map (fn [[repo branch-info]]
                                  (assoc repo
                                         :git/sha (-> branch-info
                                                      :commit
                                                      :sha))))
                           (filter :git/sha))))]
     (assert release-id)
     (download-file {:fname "deps.edn"
                     :dirname "deps"
                     :release-id release-id
                     :repos repos}))))



;; (repos/repos {:auth auth :per-page 1})




(defn parse-edn [f]
  (try
    (with-open [rdr (io/reader f)
                rdr (PushbackReader. rdr)]
      (edn/read rdr))
    (catch Exception e
      (prn f e)
      nil)))

(defn update-clojure-repo-index [{:keys [release-id]}]
  (assert release-id)
  (let [all-responses (vec
                       (find-clojure-repos))
        all-repos (->> all-responses
                       (map :body)
                       (mapcat :items))]
    (spit (io/file (release-dir release-id) "all-repos.edn")
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
   (with-retries
     (fn [{:keys [repos last-response] :as k}]
       (let [repo (first repos)
             req {:url (:tags_url repo)
                  :as :json
                  :method :get}]
         (prn req)
         (rate-limit-sleep! last-response)
         (let [response (try+
                         (http/request (with-auth req))
                         (catch [:status 404] e
                           {:body []}))]
           (assoc response
                  ::repo repo
                  ::key k
                  ::request req)))))
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



(defn update-tag-index [{:keys [release-id]}]
  (assert release-id)
  (let [all-repos (load-all-repos release-id)
        deps-repos (->> all-repos
                        (filter (fn [repo]
                                  (.exists (repo->file repo
                                                       (io/file (release-dir release-id) "deps")
                                                       "deps.edn")))))
        all-tags (vec (find-tags deps-repos))]
    (spit (io/file (release-dir release-id) "deps-tags.edn")
          (->edn all-tags))))

(defn load-deps-tags [release-id]
  (read-edn (io/file (release-dir release-id) "deps-tags.edn")))

(defn branch-test [repo]
  (str/replace (:branches_url repo)
               #"\{/branch}"
               (str "/" (:default_branch repo))))

(defn find-default-branches [repos]
  (iteration
   (with-retries
     (fn [{:keys [repos last-response] :as k}]
       (let [repo (first repos)
             req {:url (str/replace (:branches_url repo)
                                    #"\{/branch}"
                                    (str "/" (:default_branch repo)))
                  :unexceptional-status #(or (http/unexceptional-status? %)
                                             (= % 404))
                  :as :json
                  :method :get}]
         (rate-limit-sleep! last-response)
         (let [response (http/request (with-auth req))]
           (assoc response
                  ::repo repo
                  ::key k
                  ::request req)))))
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

(defn update-default-branches [{:keys [release-id]}]
  (assert release-id)
  (let [all-repos (load-all-repos release-id)
        all-default-branches (vec (find-default-branches all-repos))]
    (util/save-obj-edn (io/file (release-dir release-id) "default-branches.edn")
                       all-default-branches)))

(defn load-available-git-libs [release-id]
  (read-edn (io/file (release-dir release-id)) "deps-libs.edn"))

(defn update-available-git-libs-index [{:keys [release-id]}]
  (assert release-id)
  (let [deps-tags (load-deps-tags release-id)
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
    (spit (io/file (release-dir release-id) "deps-libs.edn")
          (->edn deps-libs))))

(defn make-release [{:keys [release-id]
                     :as opts}]
  (assert release-id)
  (update-clojure-repo-index opts)
  (download-deps opts)
  (update-tag-index opts)
  (update-available-git-libs-index opts))
