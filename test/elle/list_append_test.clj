(ns elle.list-append-test
  (:refer-clojure :exclude [test])
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [elle [core :as elle]
                  [core-test :refer [read-history]]
                  [list-append :refer :all]
                  [graph :as g]
                  [util :refer [map-vals]]]
            [jepsen.txn :as txn]
            [knossos [history :as history]
                     [op :as op]]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn just-graph
  "Takes a history, indexes it, uses the given analyzer function to construct a
  graph+explainer, extracts just the graph, converts it to Clojure, and removes
  indices from the ops."
  [analyzer history]
  (->> history
       history/index
       analyzer
       :graph
       g/->clj
       (map (fn [[k vs]]
              [(dissoc k :index)
               (map #(dissoc % :index) vs)]))
       (into {})))

(defn op
  "Generates an operation from a string language like so:

  ax1       append 1 to x
  ry12      read y = [1 2]
  ax1ax2    append 1 to x, append 2 to x"
  ([string]
  (let [[txn mop] (reduce (fn [[txn [f k v :as mop]] c]
                            (case c
                              \a [(conj txn mop) [:append]]
                              \r [(conj txn mop) [:r]]
                              \x [txn (conj mop :x)]
                              \y [txn (conj mop :y)]
                              \z [txn (conj mop :z)]
                              (let [e (Long/parseLong (str c))]
                                [txn [f k (case f
                                            :append e
                                            :r      (conj (or v []) e))]])))
                          [[] nil]
                          string)
        txn (-> txn
                (subvec 1)
                (conj mop))]
    {:type :ok, :value txn}))
  ([process type string]
   (assoc (op string) :process process :type type)))

(defn pair
  "Takes a completed op and returns an [invocation, completion] pair."
  [completion]
  [(-> completion
       (assoc :type :invoke)
       (update :value (partial map (fn [[f k v :as mop]]
                                        (if (= :r f)
                                          [f k nil]
                                          mop)))))
   completion])

(deftest op-test
  (is (= {:type :ok, :value [[:append :x 1] [:append :x 2]]}
         (op "ax1ax2"))))

(deftest ww-graph-test
  (let [g   (comp (partial just-graph ww-graph) vector)
        t1  (op "ax1")
        t2  (op "ax2")
        t3  (op "rx12")]
    (testing "write-write"
      ; Notice that T3 doesn't depend on T2, because we don't detect wr edges!
      (is (= {t1 [t2] t2 []}
             (g t1 t2 t3))))))

(deftest wr-graph-test
  (let [g (comp (partial just-graph wr-graph) vector)]
    (testing "basic read"
      (let [t1 (op "ax1")
            t2 (op "rx1")
            t3 (op "ax2")
            t4 (op "rx12")]
        ; Note that t2 doesn't precede t3, because the wr graph doesn't encode
        ; anti-dependency edges, and t1 doesn't precede t3, because there are
        ; no ww edges here.
        (is (= {t1 [t2], t2 [], t3 [t4], t4 []}
               (g t1 t2 t3 t4)))))))

(deftest graph-test
  (let [g (comp (partial just-graph graph) vector)
        ax1       {:type :ok, :value [[:append :x 1]]}
        ax2       {:type :ok, :value [[:append :x 2]]}
        ax1ay1    {:type :ok, :value [[:append :x 1] [:append :y 1]]}
        ax1ry1    {:type :ok, :value [[:append :x 1] [:r :y [1]]]}
        ax2ay1    {:type :ok, :value [[:append :x 2] [:append :y 1]]}
        ax2ay2    {:type :ok, :value [[:append :x 2] [:append :y 2]]}
        az1ax1ay1 {:type :ok, :value [[:append :z 1]
                                      [:append :x 1]
                                      [:append :y 1]]}
        rxay1     {:type :ok, :value [[:r :x nil] [:append :y 1]]}
        ryax1     {:type :ok, :value [[:r :y nil] [:append :x 1]]}
        rx121     {:type :ok, :value [[:r :x [1 2 1]]]}
        rx1ry1    {:type :ok, :value [[:r :x [1]] [:r :y [1]]]}
        rx1ay2    {:type :ok, :value [[:r :x [1]] [:append :y 2]]}
        ry12az3   {:type :ok, :value [[:r :y [1 2]] [:append :z 3]]}
        rz13      {:type :ok, :value [[:r :z [1 3]]]}
        rx        {:type :ok, :value [[:r :x nil]]}
        rx1       {:type :ok, :value [[:r :x [1]]]}
        rx12      {:type :ok, :value [[:r :x [1 2]]]}
        rx12ry1   {:type :ok, :value [[:r :x [1 2]] [:r :y [1]]]}
        rx12ry21  {:type :ok, :value [[:r :x [1 2]] [:r :y [2 1]]]}
        ]
    (testing "empty history"
      (is (= {} (g))))

    (testing "one append"
      (is (= {} (g ax1))))

    (testing "empty read"
      (is (= {} (g rx))))

    (testing "one append one read"
      (is (= {ax1 [rx1], rx1 []}
             (g ax1 rx1))))

    (testing "read empty, append, read"
      ; This verifies anti-dependencies.
      ; We need the third read in order to establish ax1's ordering
      (is (= {rx [ax1] ax1 [rx1] rx1 []}
             (g rx ax1 rx1))))

    (testing "append, append, read"
      ; This verifies write dependencies
      (is (= {ax1 [ax2], ax2 [rx12], rx12 []}
             (g ax2 ax1 rx12))))

    (testing "serializable figure 3 from Adya, Liskov, O'Neil"
      (is (= {az1ax1ay1 [rx1ay2 ry12az3]
              rx1ay2    [ry12az3]
              ry12az3   [rz13]
              rz13      []}
             (g az1ax1ay1 rx1ay2 ry12az3 rz13))))

    (testing "G0: write cycle"
      (let [t1 ax1ay1
            t2 ax2ay2
            ; Establishes that the updates from t1 and t2 were applied in
            ; different orders
            t3 rx12ry21]
        (is (= {t1 [t2 t3], t2 [t1 t3], t3 []}
               (g t1 t2 t3)))))

    ; TODO: we should do internal consistency checks here as well--see G1a and
    ; G1b.


    (testing "G1c: circular information flow"
      ; G0 is a special case of G1c, so for G1c we'll construct a cycle with a
      ; ww dependency on x and a wr dependency on y. The second transaction
      ; overwrites the first on x, but the second's write of y is visible to
      ; the first's read.
      (let [t1 ax1ry1
            t2 ax2ay1
            t3 rx12]
        (is (= {t1 [t2], t2 [t3 t1], t3 []}
               (g t1 t2 t3)))))

    (testing "G2: anti-dependency cycle"
      ; Here, two transactions observe the empty state of a key that the other
      ; transaction will append to.
      (is (= {rxay1 [ryax1 rx1ry1], ryax1 [rxay1 rx1ry1], rx1ry1 []}
             (g rxay1 ryax1 rx1ry1)))
      (is (= {:valid? false
              :scc-count 1
              :cycles ["Let:\n  T1 = {:type :ok, :value [[:r :x nil] [:append :y 1]]}\n  T2 = {:type :ok, :value [[:r :y nil] [:append :x 1]]}\n\nThen:\n  - T1 < T2, because T1 observed the initial (nil) state of :x, which T2 created by appending 1.\n  - However, T2 < T1, because T2 observed the initial (nil) state of :y, which T1 created by appending 1: a contradiction!"]}
             (elle/check {:analyzer graph} [rxay1 ryax1 rx1ry1]))))

    ; We can't infer anything about an info's nil reads: an :ok read of nil
    ; means we saw the initial state, but an :info read of nil means we don't
    ; know what was observed.
    (testing "info reads"
      ; T1 appends 2 to x after T2, so we can infer T2 < T1.
      ; However, T1's crashed read of y = nil does NOT mean T1 < T2.
      (let [t1 {:type :info, :value [[:append :x 2] [:r :y nil]]}
            t2 {:type :ok,   :value [[:append :x 1] [:append :y 1]]}
            t3 {:type :ok,   :value [[:r :x [1 2]] [:r :y [1]]]}]
        (is (= {t1 [t3], t2 [t3 t1], t3 []}
               (g t1 t2 t3)))))

    ; Special case: when there's only one append for a key, we can trivially
    ; infer the version order for that key, even if we never observe it in a
    ; read: it has to go from nil -> [x].
    (testing "single appends without reads"
      (is (= {rx [ax1] ax1 []} (g rx ax1))))

    (testing "multiple appends without reads"
      ; With two appends, we can't infer a precise order, but we still know ax1
      ; and ax2 both had to come after rx!
      (is (= {rx [ax1 ax2] ax1 [] ax2 []} (g rx ax1 ax2))))

    (testing "duplicate inserts attempts"
      (let [ax1ry  {:index 0, :type :invoke, :value [[:append :x 1] [:r :y nil]]}
            ay2ax1 {:index 1, :type :invoke, :value [[:append :y 2] [:append :x 1]]}
            e (try+ (g ax1ry ay2ax1)
                    false
                    (catch [:type :duplicate-appends] e e))]
        (is (= ay2ax1 (:op e)))
        (is (= :x (:key e)))
        (is (= 1 (:value e)))))))

(deftest g1a-cases-test
  (testing "empty"
    (is (= [] (g1a-cases []))))
  (testing "valid and invalid reads"
    (let [t1 {:type :fail, :value [[:append :x 1]]}
          t2 (op "rx1ax2")
          t3 (op "rx12ry3")]
      (is (= [{:op        t2
               :mop       [:r :x [1]]
               :writer    t1
               :element   1}
              {:op        t3
               :mop       [:r :x [1 2]]
               :writer    t1
               :element   1}]
             (g1a-cases [t2 t3 t1]))))))

(deftest g1b-cases-test
  (testing "empty"
    (is (= [] (g1b-cases []))))

  (testing "valid and invalid reads"
    ; t1 has an intermediate append of 1 which should never be visible alone.
    (let [t1 (op "ax1ax2")
          t2 (op "rx1")
          t3 (op "rx12ry3")
          t4 (op "rx123")]
      (is (= [{:op        t2
               :mop       [:r :x [1]]
               :writer    t1
               :element   1}]
             (g1b-cases [t2 t3 t1 t4])))))

  (testing "internal reads"
    (let [t1 (op "ax1rx1ax2")]
      (is (= [] (g1b-cases [t1]))))))

(deftest internal-cases-test
  (testing "empty"
    (is (= nil (internal-cases []))))

  (testing "good"
    (is (= nil (internal-cases [{:type :ok, :value [[:r :y [5 6]]
                                                   [:append :x 3]
                                                   [:r :x [1 2 3]]
                                                   [:append :x 4]
                                                   [:r :x [1 2 3 4]]]}]))))

  (testing "read-append-read"
    (let [stale      {:type :ok, :value [[:r :x [1 2]]
                                         [:append :x 3]
                                         [:r :x [1 2]]]}
          bad-prefix {:type :ok, :value [[:r :x [1 2]]
                                         [:append :x 3]
                                         [:r :x [0 2 3]]]}
          extension  {:type :ok, :value [[:r :x [1 2]]
                                         [:append :x 3]
                                         [:r :x [1 2 3 4]]]}
          short-read {:type :ok, :value [[:r :x [1 2]]
                                         [:append :x 3]
                                         [:r :x [1]]]}]
    (is (= [{:op stale
             :mop [:r :x [1 2]]
             :expected [1 2 3]}
            {:op bad-prefix
             :mop [:r :x [0 2 3]]
             :expected [1 2 3]}
            {:op extension
             :mop [:r :x [1 2 3 4]]
             :expected [1 2 3]}
            {:op short-read
             :mop [:r :x [1]]
             :expected [1 2 3]}]
           (internal-cases [stale bad-prefix extension short-read])))))

  (testing "append-read"
    (let [disagreement {:type :ok, :value [[:append :x 3]
                                         [:r :x [1 2 3 4]]]}
          short-read {:type :ok, :value [[:append :x 3]
                                         [:r :x []]]}]
    (is (= [{:op disagreement
             :mop [:r :x [1 2 3 4]]
             :expected ['... 3]}
            {:op short-read
             :mop [:r :x []]
             :expected ['... 3]}]
           (internal-cases [disagreement short-read])))))

  (testing "FaunaDB example"
    (let [h [{:type :invoke, :f :txn, :value [[:append 0 6] [:r 0 nil]]
              :process 1, :index 20}
             {:type :ok, :f :txn, :value [[:append 0 6] [:r 0 nil]]
              :process 1, :index 21}]]
      (is (= [{:expected '[... 6],
               :mop [:r 0 nil],
               :op {:f :txn,
                    :index 21,
                    :process 1,
                    :type :ok,
                    :value [[:append 0 6] [:r 0 nil]]}}]
              (internal-cases h))))))

(defn c
  "Check a history."
  [opts history]
  (-> (check opts history)
      ; We don't care about these; it's kinda redundant.
      (dissoc :also-not)))

(defn cf
  "Checks a file"
  [opts filename]
  (c opts (read-history filename)))

(deftest checker-test
  (testing "G0"
    (let [; A pure write cycle: x => t1, t2; but y => t2, t1
          t1 (op "ax1ay1")
          t2 (op "ax2ay2")
          t3 (op "rx12ry21")
          h [t1 t2 t3]
          msg {:cycle
               [{:type :ok, :value [[:append :x 2] [:append :y 2]]}
                {:type :ok, :value [[:append :x 1] [:append :y 1]]}
                {:type :ok, :value [[:append :x 2] [:append :y 2]]}],
               :steps
               [{:type :ww,
                 :key :y,
                 :value 2,
                 :value' 1,
                 :a-mop-index 1,
                 :b-mop-index 1}
                {:type :ww,
                 :key :x,
                 :value 1,
                 :value' 2,
                 :a-mop-index 0,
                 :b-mop-index 0}],
               :type :G0}]
      ; G1 and G0 both catch this, because technically G0 *is* G1.
      (is (= {:valid? false
              :anomaly-types  [:G0]
              :not            #{:read-uncommitted}
              :anomalies {:G0 [msg]}}
             (c {:consistency-models nil, :anomalies [:G0]} h)))
      (is (= {:valid? false
              :anomaly-types  [:G0]
              :not            #{:read-uncommitted}
              :anomalies {:G0 [msg]}}
             (c {:consistency-models nil, :anomalies [:G1]} h)))
      ; G2 doesn't actually include G0, so it won't.
      (is (= {:valid? true}
             (c {:consistency-models nil, :anomalies [:G2]} h)))))

  (testing "G1a"
    (let [; T2 sees T1's failed write
          t1 {:type :fail, :value [[:append :x 1]]}
          t2 (op "rx1")
          h  [t1 t2]]
      ; Read-uncommitted won't catch this
      (is (= {:valid?         :unknown
              :anomaly-types  [:empty-transaction-graph]
              :anomalies      {:empty-transaction-graph true}
              ; Right now, it'll see that it's not RC/RA, though it won't tell
              ; you WHY.
              :not #{:read-atomic :read-committed}}
             (c {:consistency-models [:read-uncommitted]} h)))
      ; Read-committed will, of course
      (is (= {:valid? false
              :anomaly-types  [:G1a :empty-transaction-graph]
              :not            #{:read-committed :read-atomic}
              :anomalies {:empty-transaction-graph true
                          :G1a [{:op      t2
                                 :writer  t1
                                 :mop     [:r :x [1]]
                                 :element 1}]}}
             (c {:consistency-models [:read-committed]} h)))
      ; Just asking for G2 won't complain about this, though it *will* complain
      ; about the empty transaction graph.
      (is (= {:valid? :unknown
              :anomaly-types [:empty-transaction-graph]
              :anomalies {:empty-transaction-graph true}
              ; Right now, it'll see that it's not RC/RA, though it won't tell
              ; you WHY.
              :not #{:read-atomic :read-committed}}
             (c {:consistency-models nil, :anomalies [:G2]} h)))
      ; But a repeatable-read checker will see it, because they prohibit both
      ; G1 and G2-item.
      (is (= {:valid? false
              :anomaly-types  [:G1a :empty-transaction-graph]
              :not            #{:read-committed :read-atomic}
              :anomalies {:empty-transaction-graph true
                          :G1a [{:op      t2
                                 :writer  t1
                                 :mop     [:r :x [1]]
                                 :element 1}]}}
             (c {:consistency-models [:repeatable-read]} h)))))

  (testing "G1b"
    (let [; T2 sees T1's intermediate write
          [t1 t1'] (pair (op "ax1ax2"))
          [t2 t2'] (pair (op "rx1"))
          [t1 t1' t2 t2' :as h] (history/index [t1 t1' t2 t2'])]
      ; This is not only G1b, since it has an intermediate read, but also
      ; G-single, since rx1 observes ax1 but does not observe ax2!

      ; G0 checker won't catch this
      (is (= {:valid? true} (c {:consistency-models nil, :anomalies [:G0]} h)))
      ; G1 will
      (is (= {:valid? false
              :anomaly-types  [:G1b]
              :not            #{:read-committed}
              :anomalies {:G1b [{:op      t2'
                                 :writer  t1'
                                 :mop     [:r :x [1]]
                                 :element 1}]}}
             (c {:consistency-models nil, :anomalies [:G1]} h)))
      ; G2 catches G-single but won't actually report G1b: even though the
      ; graph covers G1c, we filter out the G1b anomaly since we weren't asked
      ; for it. Still report that it's not read-committed, which is... maybe
      ; questionable. Might change this later?
      (is (= {:valid? false
              :not #{:read-committed}
              :anomaly-types [:G-single]}
             (-> (c {:consistency-models nil, :anomalies [:G2]} h)
                 (select-keys [:valid? :not :anomaly-types]))))
      ; But, say, strict-1SR will
      (is (= {:valid? false
              :anomaly-types  [:G-single :G1b]
              :not            #{:read-committed}
              :anomalies {:G1b [{:op      t2'
                                 :writer  t1'
                                 :mop     [:r :x [1]]
                                 :element 1}]
                          :G-single
                          [{:cycle
                            [{:type :ok, :value [[:r :x [1]]], :index 3}
                             {:type :ok,
                              :value [[:append :x 1] [:append :x 2]],
                              :index 1}
                             {:type :ok, :value [[:r :x [1]]], :index 3}],
                            :steps
                            [{:type :rw,
                              :key :x,
                              :value 1,
                              :value' 2,
                              :a-mop-index 0,
                              :b-mop-index 1}
                             {:type :wr,
                              :key :x,
                              :value 1,
                              :a-mop-index 0,
                              :b-mop-index 0}],
                            :type :G-single}]}}
             (c {:consistency-models [:strict-serializable]} h)))))

  (testing "G1c"
    (let [; T2 writes x after T1, but T1 observes T2's write on y.
          t1 (op "ax1ry1")
          t2 (op "ax2ay1")
          t3 (op "rx12ry1")
          h  [t1 t2 t3]
          msg {:cycle
               [{:type :ok, :value [[:append :x 2] [:append :y 1]]}
                {:type :ok, :value [[:append :x 1] [:r :y [1]]]}
                {:type :ok, :value [[:append :x 2] [:append :y 1]]}],
               :steps
               [{:type :wr,
                 :key :y,
                 :value 1,
                 :a-mop-index 1,
                 :b-mop-index 1}
                {:type :ww,
                 :key :x,
                 :value 1,
                 :value' 2,
                 :a-mop-index 0,
                 :b-mop-index 0}],
               :type :G1c}]
      ; G0 won't see this
      (is (= {:valid? true} (c {:consistency-models nil, :anomalies [:G0]} h)))
      ; But G1 will!
      (is (= {:valid? false
              :anomaly-types  [:G1c]
              :not            #{:read-committed}
              :anomalies {:G1c [msg]}}
             (c {:consistency-models nil, :anomalies [:G1]} h)))
      ; G2 won't
      (is (= {:valid? true}
             (c {:consistency-models nil, :anomalies [:G2]} h)))))

  (testing "G-single"
    (let [t1  (op "ax1ay1")  ; T1 writes y after T1's read
          t2  (op "ax2ry")   ; T2 writes x after T1
          t3  (op "rx12")
          h   [t1 t2 t3]
          msg {:cycle [{:type :ok, :value [[:append :x 2] [:r :y]]}
                       {:type :ok, :value [[:append :x 1] [:append :y 1]]}
                       {:type :ok, :value [[:append :x 2] [:r :y]]}],
               :steps
               [{:type :rw,
                 :key :y,
                 :value :elle.list-append/init,
                 :value' 1,
                 :a-mop-index 1,
                 :b-mop-index 1}
                {:type :ww,
                 :key :x,
                 :value 1,
                 :value' 2,
                 :a-mop-index 0,
                 :b-mop-index 0}],
               :type :G-single}]
      ; G0 and G1 won't catch this
      (is (= {:valid? true} (c {:consistency-models nil, :anomalies [:G0]} h)))
      (is (= {:valid? true} (c {:consistency-models nil, :anomalies [:G1]} h)))
      ; But G-single and G2 will!
      (is (= {:valid? false
              :anomaly-types [:G-single]
              :not           #{:consistent-view}
              :anomalies {:G-single [msg]}}
             (c {:consistency-models nil, :anomalies [:G-single]} h)))
      (is (= {:valid? false
              :anomaly-types [:G-single]
              :not           #{:consistent-view}
              :anomalies {:G-single [msg]}}
             (c {:consistency-models nil, :anomalies [:G2]} h)))))

  (testing "G2"
    (let [; A pure anti-dependency cycle
          t1 (op "ax1ry")
          t2 (op "ay1rx")
          h  [t1 t2]]
      ; G0 and G1 won't catch this
      (is (= {:valid? true} (c {:consistency-models nil, :anomalies [:G0]} h)))
      (is (= {:valid? true} (c {:consistency-models nil, :anomalies [:G1]} h)))
      ; Nor will checkers for, say, read committed.
      (is (= {:valid? true} (c {:consistency-models [:read-committed]} h)))
      ; But G2 will
      (let [err {:valid? false
                 :anomaly-types  [:G2-item]
                 :not            #{:repeatable-read}
                 :anomalies
                 {:G2-item [{:cycle
                             [{:type :ok, :value [[:append :x 1] [:r :y]]}
                              {:type :ok, :value [[:append :y 1] [:r :x]]}
                              {:type :ok, :value [[:append :x 1] [:r :y]]}],
                             :steps
                             [{:type :rw,
                               :key :y,
                               :value :elle.list-append/init,
                               :value' 1,
                               :a-mop-index 1,
                               :b-mop-index 0}
                              {:type :rw,
                               :key :x,
                               :value :elle.list-append/init,
                               :value' 1,
                               :a-mop-index 1,
                               :b-mop-index 0}],
                             :type :G2-item}]}}]
      (is (= err (c {:consistency-models nil, :anomalies [:G2]} h)))
      ; As will a serializable checker.
      (is (= err (c {:consistency-models [:serializable]} h)))
      ; And repeatable-read
      (is (= err (c {:consistency-models [:repeatable-read]} h)))
      )))

  (testing "Strong SI violation"
    (let [; T1 anti-depends on T2, but T1 happens first in wall-clock order.
          t0  {:index 0, :type :invoke, :value [[:append :x 1]]}
          t0' {:index 1, :type :ok,     :value [[:append :x 1]]}
          t1  {:index 2, :type :invoke, :value [[:append :x 2]]}
          t1' {:index 3, :type :ok,     :value [[:append :x 2]]}
          t2  {:index 4, :type :invoke, :value [[:r :x nil]]}
          t2' {:index 5, :type :ok,     :value [[:r :x [1]]]}
          t3  {:index 6, :type :invoke, :value [[:r :x nil]]}
          t3' {:index 7, :type :ok,     :value [[:r :x [1 2]]]}
          h [t0 t0' t1 t1' t2 t2' t3 t3']]
      ; G2 won't catch this by itself
      (is (= {:valid? true}
             (c {:consistency-models nil, :anomalies [:G2]} h)))
      ; Nor will a serializable checker
      (is (= {:valid? true}
             (c {:consistency-models [:serializable]} h)))
      ; But it will if we ask for strict-serializable.
      (is (= {:valid?         false
              :anomaly-types  [:G-single-realtime]
              :not            #{:strong-snapshot-isolation}
              :anomalies
              {:G-single-realtime
               [{:cycle [t2' t1' t2']
                 :steps
                 [{:type :rw,
                   :key :x,
                   :value 1,
                   :value' 2,
                   :a-mop-index 0,
                   :b-mop-index 0}
                  {:type :realtime,
                   :a' t1'
                   :b  t2}],
                 :type :G-single-realtime}]}}
             (c {:consistency-models [:strict-serializable]}
                h)))))

  (testing "contradictory read orders"
    (let [t1 (op "ax1ry1")  ; read t3's ay1
          t2 (op "ax2")
          t3 (op "ax3ay1")  ; append of x happens later
          t4 (op "rx13")
          t5 (op "rx123")
          h [t1 t2 t3 t4 t5]]
      (is (= {:valid? false
              :anomaly-types [:G1c :incompatible-order]
              :not           #{:read-committed :read-atomic}
              :anomalies
              {:incompatible-order [{:key :x, :values [[1 3] [1 2 3]]}]
               :G1c [{:cycle
                      [{:type :ok, :value [[:append :x 3] [:append :y 1]]}
                       {:type :ok, :value [[:append :x 1] [:r :y [1]]]}
                       {:type :ok, :value [[:append :x 3] [:append :y 1]]}],
                      :steps
                      [{:type :wr,
                        :key :y,
                        :value 1,
                        :a-mop-index 1,
                        :b-mop-index 1}
                       {:type :ww,
                        :key :x,
                        :value 1,
                        :value' 3,
                        :a-mop-index 0,
                        :b-mop-index 0}]
                      :type :G1c}]}}
             (c {:consistency-models nil
                 :anomalies [:G1]
                 :directory "test-output"}
                h)))))

  (testing "dirty update"
    (testing "none"
      (let [t1 (op 0 :fail "ax1")
            h [t1]]
        (is (= {:valid?         :unknown
                :anomaly-types  [:empty-transaction-graph]
                :not            #{}
                :anomalies      {:empty-transaction-graph true}}
               (c {:consistency-models nil, :anomalies [:dirty-update]} h)))))

    (testing "direct"
      (let [t1 (op 0 :fail "ax1")
            t2 (op 1 :ok   "ax2")
            t3 (op 2 :ok   "rx12")
            h [t1 t2 t3]]
        (is (= {:valid? false
                :anomaly-types [:dirty-update]
                :not           #{:read-committed :read-atomic}
                :anomalies {:dirty-update [{:key        :x
                                            :values     [1 2]
                                            :txns       [t1 '... t2]}]}}
               (c {:consistency-models nil, :anomalies [:dirty-update]} h)))))

    (testing "indirect"
      (let [t1 (op 0 :fail "ax1")
            t2 (op 1 :info "ax2")
            t3 (op 2 :ok   "ax3")
            t4 (op 3 :ok   "rx123")
            h [t1 t2 t3 t4]]
        (is (= {:valid? false
                :anomaly-types [:dirty-update]
                :not           #{:read-committed :read-atomic}
                :anomalies {:dirty-update [{:key        :x
                                            :values     [1 2 3]
                                            :txns       [t1 '... t3]}]}}
               (c {:consistency-models nil, :anomalies [:dirty-update]} h))))))

  (testing "duplicated elements"
    ; This is also an instance of G1c.
    (let [t1 (op "ax1ry1") ; read t2's write of y
          t2 (op "ax2ay1")
          t3 (op "rx121")
          h  [t1 t2 t3]]
      (is (= {:valid? false
              :anomaly-types [:G1c :duplicate-elements]
              :not           #{:read-uncommitted}
              :anomalies
              {:duplicate-elements [{:op t3
                                     :mop [:r :x [1 2 1]]
                                     :duplicates {1 2}}]
               :G1c [{:cycle
                      [{:type :ok, :value [[:append :x 2] [:append :y 1]]}
                       {:type :ok, :value [[:append :x 1] [:r :y [1]]]}
                       {:type :ok, :value [[:append :x 2] [:append :y 1]]}],
                      :steps
                      [{:type :wr,
                        :key :y,
                        :value 1,
                        :a-mop-index 1,
                        :b-mop-index 1}
                       {:type :ww,
                        :key :x,
                        :value 1,
                        :value' 2,
                        :a-mop-index 0,
                        :b-mop-index 0}],
                      :type :G1c}]}}
             ; We flag this as an instance of G-single, because the x = [1 2 1]
             ; read looks like 1 was appended most recently. Whether you want
             ; consider this "real" seems up for debate, cuz duplicate elements
             ; break everything. Let's do read-committed for now to avoid
             ; seeing it.
             (c {:consistency-models [:read-committed]} h)))))

  (testing "internal consistency violation"
    (let [t1 (op "ax1ax2ax4")
          t2 (op "ax3rx1234")
          h  [t1 t2]]
      (is (= {:valid?         false
              :anomaly-types  [:internal]
              ; Read-atomic ruled out by internal, read-uncommitted by the G0.
              :not            #{:read-atomic :read-uncommitted}
              :anomalies      {:internal [{:op t2
                                           :mop [:r :x [1 2 3 4]]
                                           :expected '[... 3]}]}}
             ; There's a G0 here too, but we don't care.
             (c {:consistency-models nil, :anomalies [:internal]} h))))))

(deftest unobserved-write-test
  ; When we see [:r :x [1 2]], and 1 was written by t1, 2 written by t2, and 3
  ; written by t3, we can infer a dependency not only from the transaction 1 ->
  ; 2 but *also* from 2 -> 3: transactions which are not observed in the
  ; longest read must fall after the transaction which generated that value!
  ;
  ; To test this, we perform writes of 1, 2, and 3 to both x and y, but let y
  ; fail to observe 1.
  (let [[w1 w1'] (pair (op "ax1ay1"))
        [w2 w2'] (pair (op "ax2ay2"))
        [w3 w3'] (pair (op "ax3ay3"))
        [rx rx'] (pair (op "rx12"))
        [ry ry'] (pair (op "ry2"))
        h        (history/index [w1 w2 w3 rx ry w1' w2' w3' rx' ry'])]
    ; w1 -ww-> w2, by rx12
    ; w2 -ww-> w1, by ry2
    ; ry -rw-> w1, since y fails to observe w1
    ; w3 is a red herring; just there to create multiple final edges
    (is (= {:valid?         false
            :anomaly-types  [:G-single :G0]
            :anomalies
            ; We know this is G-single because ry -rw-> w1 -ww-> w2 -wr-> ry
            {:G-single
             [{:cycle
               [{:type :ok, :value [[:r :y [2]]], :index 9}
                {:type :ok,
                 :value [[:append :x 1] [:append :y 1]],
                 :index 5}
                {:type :ok,
                 :value [[:append :x 2] [:append :y 2]],
                 :index 6}
                {:type :ok, :value [[:r :y [2]]], :index 9}],
               :steps
               [{:type :rw,
                 :key :y,
                 :value 2,
                 :value' 1,
                 :a-mop-index 0,
                 :b-mop-index 1}
                {:type :ww,
                 :key :x,
                 :value 1,
                 :value' 2,
                 :a-mop-index 0,
                 :b-mop-index 0}
                {:type :wr,
                 :key :y,
                 :value 2,
                 :a-mop-index 1,
                 :b-mop-index 0}],
               :type :G-single}],
             ; But worse, it's G0 because w2 -ww-> w1 -ww->w2
             :G0
             [{:cycle
               [{:type :ok,
                 :value [[:append :x 2] [:append :y 2]],
                 :index 6}
                {:type :ok,
                 :value [[:append :x 1] [:append :y 1]],
                 :index 5}
                {:type :ok,
                 :value [[:append :x 2] [:append :y 2]],
                 :index 6}],
               :steps
               [{:type :ww,
                 :key :y,
                 :value 2,
                 :value' 1,
                 :a-mop-index 1,
                 :b-mop-index 1}
                {:type :ww,
                 :key :x,
                 :value 1,
                 :value' 2,
                 :a-mop-index 0,
                 :b-mop-index 0}],
               :type :G0}]},
            :not #{:read-uncommitted}}
           (-> (c {:consistency-models [:serializable]} h)
               (dissoc :also-not))))))

(deftest repeatable-read-test
  ; This is a long fork, which is also G2-item, by virtue of its only cycle
  ; being two anti-dependency edges. We shouldn't be able to detect this with
  ; read-committed, but repeatable-read should fail.
  (let [t1 (op "rxay1")
        t2 (op "ryax1")
        h  [t1 t2]]
    (is (= {:valid? true}
           (c {:consistency-models [:read-committed]} h)))
    (is (= {:valid?         false
            :not            #{:repeatable-read}
            :anomaly-types  [:G2-item]
            :anomalies {:G2-item [{:cycle
                                   [t2 t1 t2]
                                   :steps
                                   [{:type :rw,
                                     :key :y,
                                     :value :elle.list-append/init,
                                     :value' 1,
                                     :a-mop-index 0,
                                     :b-mop-index 1}
                                    {:type :rw,
                                     :key :x,
                                     :value :elle.list-append/init,
                                     :value' 1,
                                     :a-mop-index 0,
                                     :b-mop-index 1}],
                                   :type :G2-item}]}}
           (c {:consistency-models [:repeatable-read]} h)))))

(deftest ^:perf huge-scc-test
  (let [r (cf {} "histories/huge-scc.edn")]
    ; There's a full explanation here but... it's long, and all we care about
    ; is that we can fall back to saying SOMETHING about this enormous SCC.
    ;
    ; TODO: might be worth modifying graph/fallback-cycle so it tries to follow
    ; minimal edges first. Might help generate worse anomalies.
    (is (not (:valid? r)))
    (is (= #{:strong-serializable} (:not r)))
    (is (= [:G2-item-realtime :cycle-search-timeout]
           (:anomaly-types r)))
    (let [cst (-> r :anomalies :cycle-search-timeout first)]
      ; This might change if we get faster or adjust timeouts
      (is (= []     (:does-not-contain cst)))
      (is (= :G0    (:anomaly-spec-type cst)))
      (is (number?  (:scc-size cst))))))

(deftest G-nonadjacent-test
  ; For G-nonadjacent, we need two rw edges (just one would be G-single), and
  ; they can't be adjacent, so that's four edges altogether.
  (let [[t1 t1'] (pair (op "ax1"))
        [t2 t2'] (pair (op "rx1ry"))
        [t3 t3'] (pair (op "ay1"))
        [t4 t4'] (pair (op "ry1rx"))
        h (history/index [t1 t1' t2 t2' t3 t3' t4 t4'])]
    (is (= {:valid?         false
            :not            #{:snapshot-isolation}
            :anomaly-types  [:G-nonadjacent]
            :anomalies      {:G-nonadjacent
                             [{:cycle [(h 1) (h 3) (h 5) (h 7) (h 1)]
                               :steps [{:type :wr,
                                        :key :x,
                                        :value 1,
                                        :a-mop-index 0,
                                        :b-mop-index 0}
                                       {:type :rw,
                                        :key :y,
                                        :value :elle.list-append/init,
                                        :value' 1,
                                        :a-mop-index 1,
                                        :b-mop-index 0}
                                       {:type :wr,
                                        :key :y,
                                        :value 1,
                                        :a-mop-index 0,
                                        :b-mop-index 0}
                                       {:type :rw,
                                        :key :x,
                                        :value :elle.list-append/init,
                                        :value' 1,
                                        :a-mop-index 1,
                                        :b-mop-index 0}],
                               :type :G-nonadjacent}]}}
                             (c {} h)))))

(deftest lost-update-test
  ; For a lost update, we need two transactions which read the same value (e.g.
  ; 0) of some key (e.g. x) and both append to x.
  (let [[t0 t0'] (pair (op "ax0"))
        [t1 t1'] (pair (op "rx0ax1"))
        [t2 t2'] (pair (op "rx0ax2"))
        [t0 t0' t1 t1' t2 t2' :as h] (history/index [t0 t0' t1 t1' t2 t2'])]
    (is (= {:valid?         false
            :not            #{:ROLA :cursor-stability}
            :anomaly-types  [:G2-item :lost-update]
            :anomalies      {:lost-update
                             [{:key   :x
                               :value [0]
                               :txns  [t1' t2']}]
                             ; We're also clever enough to infer a rw-rw cycle
                             ; here because neither t1 nor t2 saw each other's
                             ; effects, making this G2-item
                             :G2-item
                             [{:cycle
                               [{:type :ok,
                                 :value [[:r :x [0]] [:append :x 1]],
                                 :index 3}
                                {:type :ok,
                                 :value [[:r :x [0]] [:append :x 2]],
                                 :index 5}
                                {:type :ok,
                                 :value [[:r :x [0]] [:append :x 1]],
                                 :index 3}],
                               :steps [{:type :rw,
                                        :key :x,
                                        :value 0,
                                        :value' 2,
                                        :a-mop-index 0,
                                        :b-mop-index 1}
                                       {:type :rw,
                                        :key :x,
                                        :value 0,
                                        :value' 1,
                                        :a-mop-index 0,
                                        :b-mop-index 1}],
                               :type :G2-item}]}}
           (c {} h))))

  ; It's not illegal for a single txn to write over a read twice though
  (testing "one txn, double write"
    (let [[t0 t0'] (pair (op "rxax1ax2"))
          ; Just to avoid empty txn graphs
          [t1 t1'] (pair (op "rx12"))
          h        (history/index [t0 t0' t1 t1'])]
      (is (= {:valid? true}
             (c {} h))))))

; Example of checking a file, for later
;(deftest dirty-update-1-test
;  (cf {} "histories/dirty-update-1.edn")))

(deftest merge-order-test
  (is (= [] (merge-orders [] [])))
  (is (= [1 2 3] (merge-orders [1 2 3] [])))
  (is (= [2 3 4] (merge-orders [] [2 3 4])))
  (is (= [1 2 3] (merge-orders [1 2 3] [1 2 3])))
  (is (= [1 4 9] (merge-orders [1 4] [1 4 9])))
  (is (= [1 4 5] (merge-orders [1 4 5] [1])))
  (is (= [1 5 6] (merge-orders [1 2 5 6] [1 3 5 6])))
  (is (= [1 3]   (merge-orders [1 2] [1 3])))
  (testing "dups"
    (is (= [1 2 3] (merge-orders [1 2 2 3] [])))
    (is (= [1 2 3 5] (merge-orders [1 2 3 2]
                                   [1 2 3 2 5])))))

(deftest ^:perf scc-search-perf-test
  ; A case where even small SCCs caused the cycle search to time out
  (cf {:consistency-models [:strong-snapshot-isolation]}
      "histories/small-slow-scc.edn"))

(deftest ^:perf elle-compare-test
  (cf {:consistency-models [:strong-snapshot-isolation]}
      "to-check/disthotspot/history0.edn"))

(def params
  ["to-check/sess5/",
   "to-check/ops-per-txn10/",
   "to-check/ops-per-txn25/",
   "to-check/read-ratio0.2/",
   "to-check/distuniform/",
   "to-check/read-ratio0.4/",
   "to-check/keys2000/",
   "to-check/ops-per-txn20/",
   "to-check/txns-per-sess50/",
   "to-check/sess25/",
   "to-check/ops-per-txn15/",
   "to-check/sess20/",
   "to-check/read-ratio0.6/",
   "to-check/keys10000/",
   "to-check/disthotspot/",
   "to-check/txns-per-sess150/",
   "to-check/txns-per-sess200/",
   "to-check/txns-per-sess100/",
   "to-check/read-ratio0.8/",
   "to-check/distzipf/",
   "to-check/sess15/",
   "to-check/txns-per-sess250/",
   "to-check/read-ratio1.0/",
   "to-check/ops-per-txn5/",
   "to-check/keys8000/",
   "to-check/keys6000/",
   "to-check/sess30/",
   "to-check/keys4000/",
   "to-check/sess10/",
   "to-check/ops-per-txn30/"])

(defn get-time
  [filename]
  (let [his (read-history filename)]
    (time
     (c {:consistency-models [:strong-snapshot-isolation]} his))))

(for [param params]
  (let [check-results (for [i (range 10)]
                        (let [filename (str param "history" i ".edn")]
                          (get-time filename)))]
    {:param param
     :res   check-results}))