(ns re-frame.tracing
  "Tracing for re-frame."
  (:require [re-frame.interop :as interop]
            [clojure.string :as str]))

(def traces (interop/ratom []))
(def id (atom 0))
(def ^:dynamic *current-trace* nil)

(defn next-id [] (swap! id inc))

(defn start-trace [{:keys [operation op-type tags child-of]}]
  {:id        (next-id)
   :operation operation
   :type      op-type
   :tags      tags
   :child-of  (or child-of (:id *current-trace*))
   :start     (interop/now)})

(defn finish-trace [trace]
  (let [end      (interop/now)
        duration (- end (:start trace))
        add-trace? (not (str/includes? (or (get-in trace [:tags :component-path]) "") "todomvc.views.devtools"))]
    (when add-trace?
      (swap! traces conj (assoc trace
                           :duration duration
                           :end (interop/now))))))

#?(:clj (defmacro with-trace [{:keys [operation op-type tags child-of] :as trace-opts} & body]
          `(binding [*current-trace* (start-trace ~trace-opts)]
             (try ~@body                                    ;; TODO: should probably return val of body in finally?
                  (finally (finish-trace *current-trace*)))))) ;; TODO: should rethrow

(defn merge-trace! [m]
  ;; Overwrite keys in tags, and all top level keys.
  (let [new-trace (-> (update *current-trace* :tags merge (:tags m))
                      (merge (dissoc m :tags)))]
    (set! *current-trace* new-trace))
  nil)

(defn reset-tracing! []
  (println "Resetting tracing")
  (reset! id 0)
  (reset! traces []))
