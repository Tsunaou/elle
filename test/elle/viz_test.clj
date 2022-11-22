(ns elle.viz-test
  (:require [clojure.pprint :refer [pprint]]
            [elle [core :as elle]
                  [graph :as g]
                  [viz :refer :all]
                  [txn :as t]
                  [list-append :as la]
                  [list-append-test :as lat :refer [pair]]
                  [rw-register :as r]
                  [rw-register-test :as rt]]
            [knossos.history :as h]
            [clojure.test :refer :all]))

(defn list-analysis
  []
  (let [[t1 t1'] (pair (lat/op 1 :ok "ax1ry1rz12"))
        [t2 t2'] (pair (lat/op 2 :ok "az1"))
        [t3 t3'] (pair (lat/op 3 :ok "rx12rz1"))
        [t4 t4'] (pair (lat/op 4 :ok "az2ay1"))
        [t5 t5'] (pair (lat/op 5 :ok "rzax2"))
        h (h/index  [t3 t3' t1 t1' t2 t2' t4 t4' t5 t5'])

        analyzer (elle/combine la/graph elle/realtime-graph)]
      (elle/check- analyzer h)))

(def gsib-his
  (let [[t1 t1'] (pair (lat/op 0 :ok "ax1ax2ay1"))
        [t2 t2'] (pair (lat/op 1 :ok "rxay2"))
        [t3 t3'] (pair (lat/op 0 :ok "ry12"))]
    (h/index [t1 t2 t1' t2' t3 t3'])))

(def gsib-his2
  (let [[t1 t1'] (pair (lat/op 0 :ok "ax1ax2ay1"))
        [t2 t2'] (pair (lat/op 1 :ok "ax3ay2"))
        [t3 t3'] (pair (lat/op 2 :ok "rx123"))
        [t4 t4'] (pair (lat/op 3 :ok "rx12"))]
    (h/index [t1 t2 t1' t2' t3 t3' t4 t4'])))

(defn gsib-list-analysis
  [h]
  (let [analyzer (elle/combine la/graph elle/realtime-graph)]
    (elle/check- analyzer h)))

(deftest ^:interactive view-scc-test
  (let [a (list-analysis)]
    (view-scc a (first (:sccs a)))))

(deftest plot-analysis!-test
  (plot-analysis! (list-analysis) "plots/list-append"))

;; (defn register-analysis
;;   []
;;   (let [[t1 t1'] (pair (rt/op 1 :ok "wx1ry1"))
;;         [t2 t2'] (pair (rt/op 1 :ok "wx2"))
;;         [t3 t3'] (pair (rt/op 2 :ok "rx2wy1"))
;;         [t4 t4'] (pair (rt/op 3 :ok "rx1"))
;;         h (h/index  [t1 t1' t2 t2' t3 t3' t4 t4'])

;;         analyzer (partial r/graph {:additional-graphs [elle/process-graph]
;;                                    :sequential-keys? true})]
;;       elle/check- analyzer h))

;; (deftest ^:interactive view-r-scc-test
;;   (let [a (register-analysis)]
;;     (view-scc a (first (:sccs a)))))

;; (deftest plot-r-analysis!-test
;;   (plot-analysis! (register-analysis) "plots/rw-register"))
