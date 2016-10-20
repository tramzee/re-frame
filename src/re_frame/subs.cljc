(ns re-frame.subs
  (:require
    [reagent.core :as reagent]
    [reagent.ratom :as ratom]
    [re-frame.db :refer [app-db]]
    [re-frame.interop :refer [add-on-dispose! debug-enabled? make-reaction ratom? deref? dispose!]]
    [re-frame.loggers :refer [console]]
    [re-frame.utils :refer [first-in-vector reagent-id]]
    [re-frame.registrar :refer [get-handler clear-handlers register-handler]]
    [re-frame.tracing :as trace #?@(:cljs [:refer-macros [with-trace]] :clj [:refer [with-trace]])]))

(def sub-id (atom 0))
(defn next-id [] (swap! sub-id inc))


(def kind :sub)
(assert (re-frame.registrar/kinds kind))

;; -- cache -------------------------------------------------------------------
;;
;; De-duplicate subscriptions. If two or more equal subscriptions
;; are concurrently active, we want only one handler running.
;; Two subscriptions are "equal" if their query vectors test "=".
(def query->reaction (atom {}))

(defn clear-subscription-cache! []
  (doseq [[k rxn] @query->reaction]
    (dispose! rxn))
  (if (not-empty @query->reaction)
    #?(:cljs (js/console.warn "Subscription cache should be empty")
       :clj nil)))

(defn clear-all-handlers!
  "Unregisters all existing subscription handlers"
  []
  (clear-handlers kind)
  (clear-subscription-cache!))

(defn cache-and-return
  "cache the reaction r"
  [query-v dynv r]
  (let [cache-key [query-v dynv]]
    ;; when this reaction is no longer being used, remove it from the cache
    (add-on-dispose! r #(do (swap! query->reaction dissoc cache-key)
                            (trace/with-trace {:operation (first-in-vector query-v)
                                               :op-type   :sub/dispose
                                               :tags      {:query-v  query-v
                                                           :reaction (reagent-id r)}}
                              nil)
                            #_(console :log "Removing subscription:" cache-key)))
    ;; cache this reaction, so it can be used to deduplicate other, later "=" subscriptions
    (swap! query->reaction assoc cache-key r)
    (trace/merge-trace! {:tags {:reaction (reagent-id r)}})
    r))  ;; return the actual reaction

(defn cache-lookup
  ([query-v]
   (cache-lookup query-v []))
  ([query-v dyn-v]
   (get @query->reaction [query-v dyn-v])))


;; -- subscribe -----------------------------------------------------

(defn subscribe
  "Returns a Reagent/reaction which contains a computation"
  ([query-v]
   (with-trace {:operation (first-in-vector query-v)
                :op-type   :sub/create
                :tags      {:query-v query-v}}
     (if-let [cached (cache-lookup query-v)]
       (do
         (trace/merge-trace! {:tags {:cached? true
                                     :reaction (reagent-id cached)}})
         ;(console :log "Using cached subscription: " query-v)
         cached)
       (let [query-id   (first-in-vector query-v)
             handler-fn (get-handler kind query-id)
             sub-id     (next-id)]
         ;(console :log "Subscription created: " query-v)
         (trace/merge-trace! {:tags {:cached? false}})
         (when-not handler-fn
           (trace/merge-trace! {:error true})
           (console :error (str "re-frame: no subscription handler registered for: \"" query-id "\". Returning a nil subscription.")))
         (cache-and-return query-v [] (handler-fn app-db query-v))))))
  ([v dynv]
    ;; TODO: trace dynv
   (if-let [cached (cache-lookup v dynv)]
     (do ;(console :log "Using cached subscription: " v " and " dynv)
         cached)
     (let [query-id   (first-in-vector v)
           handler-fn (get-handler kind query-id)]
       (when debug-enabled?
         (when-let [not-reactive (not-empty (remove ratom? dynv))]
           (console :warn "re-frame: your subscription's dynamic parameters that don't implement IReactiveAtom:" not-reactive)))
       (if (nil? handler-fn)
         (console :error (str "re-frame: no subscription handler registered for: \"" query-id "\". Returning a nil subscription."))
         (let [dyn-vals (make-reaction (fn [] (mapv deref dynv)))
               sub (make-reaction (fn [] (handler-fn app-db v @dyn-vals)))]
           ;; handler-fn returns a reaction which is then wrapped in the sub reaction
           ;; need to double deref it to get to the actual value.
           ;(console :log "Subscription created: " v dynv)
           (cache-and-return v dynv (make-reaction (fn [] @@sub)))))))))

;; -- reg-sub -----------------------------------------------------------------

(defn- map-vals
  "Returns a new version of 'm' in which 'f' has been applied to each value.
  (map-vals inc {:a 4, :b 2}) => {:a 5, :b 3}"
  [f m]
  (into (empty m)
        (map (fn [[k v]] [k (f v)]))
        m))


(defn- deref-input-signals
  [signals query-id]
  (trace/merge-trace! {:tags {:input-signals (cond
                                               (sequential? signals) (map reagent-id signals)
                                               (map? signals) (map-vals reagent-id signals)
                                               (deref? signals) [(reagent-id signals)]
                                               :else (console :error "re-frame: in the reg-sub for " query-id ", the input-signals function returns: " signals))}})
  (cond
    (sequential? signals) (map deref signals)
    (map? signals)        (map-vals deref signals)
    (deref? signals)      @signals
    :else (console :error "re-frame: in the reg-sub for " query-id ", the input-signals function returns: " signals)))


(defn reg-sub
  "Associate the given `query id` with a handler function and an optional signal function.

  There's 3 ways this function can be called

  1. (reg-sub
       :test-sub
       (fn [db [_]] db))
  The value in app-db is passed to the computation function as the 1st argument.

  2. (reg-sub
       :a-b-sub
       (fn [q-vec d-vec]
         [(subs/subscribe [:a-sub])
          (subs/subscribe [:b-sub])])
       (fn [[a b] [_]] {:a a :b b}))

  Two functions provided. The 2nd is computation function, as before. The 1st
  is returns what `input signals` should be provided to the computation. The
  `input signals` function is called with two arguments: the query vector
  and the dynamic vector. The return value can be singleton reaction or
  a sequence of reactions.

  3. (reg-sub
       :a-b-sub
       :<- [:a-sub]
       :<- [:b-sub]
       (fn [[a b] [_]] {:a a :b b}))```
  This 3rd variation is just syntactic sugar for the 2nd. Pairs are supplied instead
  of an `input signals` functions. `:<-` is supplied followed by the subscription
  vector.
  "
  [query-id & args]
  (let [computation-fn (last args)
        input-args     (butlast args)    ;; may be empty, or one fn, or pairs of  :<- / vector
        err-header     (str "re-frame: reg-sub for " query-id ", ")
        inputs-fn      (case (count input-args)
                         ;; no `inputs` function provided - give the default
                         0  (fn
                              ([_] app-db)
                              ([_ _] app-db))

                         ;; a single `inputs` fn
                         1  (let [f (first input-args)]
                              (when-not (fn? f)
                                (console :error err-header "2nd argument expected to be an inputs function, got:" f))
                              f)

                         ;; TODO: make a PR for this and multiple pairs
                         ;; and write some tests to exercise it.
                         ;; also write tests to show reagent dereffing a disposed reaction

                         ;; one sugar pair
                         2 (fn inp-fn
                             ([_] (subscribe (second input-args)))
                             ([_ _] (subscribe (second input-args))))

                         ;; multiple sugar pairs
                         (let [pairs   (partition 2 input-args)
                               vecs    (map last pairs)]
                           (when-not (every?  vector? vecs)
                             (console :error err-header "expected pairs of :<- and vectors, got:" pairs))
                           (fn inp-fn
                             ([_] (map subscribe vecs))
                             ([_ _] (map subscribe vecs)))))]
    (register-handler
      kind
      query-id
      (fn subs-handler-fn
        ([db query-vec]
         (let [subscriptions (inputs-fn query-vec)
               reaction-id   (atom nil)
               reaction      (make-reaction
                               (fn [] (with-trace {:operation (first-in-vector query-vec)
                                                   :op-type   :sub/run
                                                   :tags      {:query-v  query-vec
                                                               :reaction @reaction-id}}
                                        (computation-fn (deref-input-signals subscriptions query-id) query-vec))))]
           (reset! reaction-id (reagent-id reaction))
           reaction))
        #_([db query-vec dyn-vec]
            ;; TODO: trace dynsub
            (let [subscriptions (inputs-fn query-vec dyn-vec)]
              (make-reaction
             (fn [] (computation-fn (deref-input-signals subscriptions query-id) query-vec dyn-vec)))))))))
