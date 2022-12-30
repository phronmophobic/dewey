(ns com.phronemophobic.dewey.web
  (:require-macros [reagent-mui.util :refer [react-component]])
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [cognitect.transit :as transit]
            [goog.net.XhrIo :as xhr]

)
  (:import (goog.i18n DateTimeSymbols_en_US))
  )


(defonce libs (r/atom nil))

(defn load-libs []
  (prn "loading libs")
  (xhr/send "data/deps-libs.json"
            (fn [e]
              (let [x (.-target e)
                    txt (.getResponseText ^js x)
                    r (transit/reader :json)]
                (reset! libs (transit/read r txt))))))



(set! *warn-on-infer* true)

(defn checkrow [lbl atm]
  [:label
   [:input.toggle {:type "checkbox" :value @atm
                   :on-change #(reset! atm (-> % .-target .-value))}]
   lbl])



;; Example

(defonce search-text* (r/atom ""))
(defonce search-description?* (r/atom nil))




(defn search-bar [value]
  [:input {:type "text"
           :value @value
           :on-change #(reset! value (-> % .-target .-value))}])

(defn lib-table []
  (let [search-text @search-text*
        search-description? @search-description?*]
    [:table
     [:tr
      (for [h ["stars" "name" "topics" "description"]]
        [:th h])]
     (for [lib (take 1 (vals @libs))]
       [:tr
        #_[:td
         (pr-str lib)]
        [:td
         (:stars lib)]
        [:td
         (name (:lib lib))]
        [:td
         (interpose
          " "
          (for [topic (:topics lib)]
            [:span topic]))
         ]
        [:td
         (:description lib)]]
       )]
    ))

(defn main []
  ;; fragment
  [:div
   [search-bar search-text*]
   [:div
    (pr-str)]
   [:div
    [checkrow "Search descriptions" search-description?*]]
   [:div (count @libs)]
   [:div @search-text*]
   [:div
    [lib-table]]])

(defn ^{:after-load true, :dev/after-load true}
  mount []
  (rdom/render [main] (js/document.getElementById "app")))

(defn ^:export init []
  (load-libs)
  (mount))
