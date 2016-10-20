(ns todomvc.core
  (:require-macros [secretary.core :refer [defroute]]
                   [re-frame.tracing :refer [with-trace]])
  (:require [goog.events :as events]
            [reagent.core :as reagent]
            [re-frame.core :as rf :refer [dispatch dispatch-sync]]
            [re-frame.tracing :as trace :include-macros true]
            [re-frisk.core :as re-frisk]
            [secretary.core :as secretary]
            [todomvc.events]
            [todomvc.subs]
            [todomvc.views]
            [reagent.impl.component :as component]
            [devtools.core :as devtools]
            [reagent.impl.util :as util]
            [clojure.string :as str]
            [reagent.impl.batching :as batch]
            [reagent.ratom :as ratom]
            [reagent.interop :refer-macros [$ $!]]
            [goog.object :as gob])
  (:import [goog History]
           [goog.history EventType]))


;; -- Debugging aids ----------------------------------------------------------
(devtools/install!)       ;; we love https://github.com/binaryage/cljs-devtools
(enable-console-print!)   ;; so println writes to console.log

;; -- Routes and History ------------------------------------------------------

(defn init-routes []
  (defroute "/" [] (dispatch [:set-showing :all]))
  (defroute "/:filter" [filter] (dispatch [:set-showing (keyword filter)]))
  )

(defonce history
         (doto (History.)
           (events/listen EventType.NAVIGATE
                          (fn [event] (secretary/dispatch! (.-token event))))
           (.setEnabled true)))


;; -- Entry Point -------------------------------------------------------------

(defn nothing []
  [:div])

(defn wrapper []
  (let [showing? (reagent/atom true)]
    (fn []
      [:div
       [:div {:on-click #(swap! showing? not)} "Click"]
       (if @showing?
         [todomvc.views/todo-app]
         [nothing])
       [todomvc.views/devtools]])))

(defn render []
  (rf/clear-subscription-cache!)
  (reagent/render [wrapper]
                  (.getElementById js/document "app")))

(defn ^:export main
  []
  (day8.re-frame.trace/init-tracing!)
  (init-routes)
  (dispatch-sync [:initialise-db])
  (render))

