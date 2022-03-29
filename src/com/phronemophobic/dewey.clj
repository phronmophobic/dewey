(ns com.phronemophobic.dewey
  (:require [clj-http.client :as http]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+]])
  (:import java.time.Instant
           java.time.Duration
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
  (str/replace s #"[^a-zA-Z0-9 -]" "_"))

(defn download-deps
  ([]
   (let [all-responses (with-open [rdr (io/reader (io/file "all-responses.edn"))
                                   rdr (PushbackReader. rdr)]
                         (edn/read rdr))
         repos (->> all-responses
                    (map :body)
                    (mapcat :items))]
     (download-deps repos)))
  ([repos]
   ;; default rate limit is 5k/hour
   ;; aiming for 4.5k/hour since there's no good feedback mechanism
   (doseq [chunk (partition-all 4500 repos)]
     (doseq [repo chunk]
       (try+
        (let [name (:name repo)
              owner (-> repo :owner :login)
              fname (str (sanitize name) "-" (sanitize owner) "-deps.edn" )
              _ (print "checking " name owner "...")
              result (http/request (with-auth
                                     {:url (deps-url repo)
                                      :method :get
                                      :as :stream}))]
          (println "found " fname)
          (io/copy (:body result)
                   (io/file "deps" fname))
          (.close (:body result)))
        (catch [:status 404] {:keys [body]}
          (println "not found" )))
       )
     (println "sleeping for an hour")
     ;; sleep an hour
     (dotimes [i 60]
       (println (- 60 i) " minutes until next chunk.")
       (Thread/sleep (* 1000 60))))))



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
                       (find-clojure-repos))]
    (spit "all-responses.edn"
          (->> all-responses
               (mapv #(dissoc % :http-client))
               ->edn))))


(comment
  (def all-responses (vec
                      (find-clojure-repos)))
  
  (download-deps
   (->> all-responses
        (map :body)
        (mapcat :items)))

  ,)
