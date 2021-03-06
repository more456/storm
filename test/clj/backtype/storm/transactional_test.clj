(ns backtype.storm.transactional-test
  (:use [clojure test])
  (:import [backtype.storm.topology TopologyBuilder])
  (:import [backtype.storm.transactional TransactionalSpoutCoordinator ITransactionalSpout ITransactionalSpout$Coordinator TransactionAttempt
            TransactionalTopologyBuilder])
  (:import [backtype.storm.transactional.state TransactionalState RotatingTransactionalState RotatingTransactionalState$StateInitializer])
  (:import [backtype.storm.testing CountingBatchBolt MemoryTransactionalSpout
            KeyedCountingBatchBolt KeyedCountingCommitterBolt KeyedSummingBatchBolt
            IdentityBolt CountingCommitBolt])
  (:use [backtype.storm bootstrap testing])
  (:use [backtype.storm.daemon common])  
  )

(bootstrap)

;; Testing TODO:
;; * Test that it repeats the meta for a partitioned state (test partitioned emitter on its own)
;; * Test that partitioned state emits nothing for the partition if it has seen a future transaction for that partition (test partitioned emitter on its own)

(defn mk-coordinator-state-changer [atom]
  (TransactionalSpoutCoordinator.
   (reify ITransactionalSpout
     (getComponentConfiguration [this]
       nil)
     (getCoordinator [this conf context]
       (reify ITransactionalSpout$Coordinator
         (initializeTransaction [this txid prevMetadata]
           @atom )
         (close [this]
           )))
     )))

(def BATCH-STREAM TransactionalSpoutCoordinator/TRANSACTION_BATCH_STREAM_ID)
(def COMMIT-STREAM TransactionalSpoutCoordinator/TRANSACTION_COMMIT_STREAM_ID)

(defn mk-spout-capture [capturer]
  (SpoutOutputCollector.
    (reify ISpoutOutputCollector
      (emit [this stream-id tuple message-id]
        (swap! capturer update-in [stream-id]
          (fn [oldval] (concat oldval [{:tuple tuple :id message-id}])))
        []
        ))))

(defn normalize-tx-tuple [values]
  (-> values vec (update 0 #(-> % .getTransactionId .intValue))))

(defn verify-and-reset! [expected-map emitted-map-atom]
  (let [results @emitted-map-atom]
    (dorun
     (map-val
      (fn [tuples]
        (doseq [t tuples]
          (is (= (-> t :tuple first) (:id t)))
          ))
      results))
    (is (= expected-map
           (map-val
            (fn [tuples]
              (map (comp normalize-tx-tuple
                         #(take 2 %)
                         :tuple)
                   tuples))
            results
            )))
    (reset! emitted-map-atom {})
    ))

(defn get-attempts [capture-atom stream]
  (map :id (get @capture-atom stream)))

(defn get-commit [capture-atom]
  (-> @capture-atom (get COMMIT-STREAM) first :id))

(deftest test-coordinator
  (let [zk-port (available-port 2181)
        coordinator-state (atom nil)
        emit-capture (atom nil)]
    (with-inprocess-zookeeper zk-port
      (letlocals
        (bind coordinator
              (mk-coordinator-state-changer coordinator-state))
        (.open coordinator
               (merge (read-default-config)
                       {TOPOLOGY-MAX-SPOUT-PENDING 4
                       TOPOLOGY-TRANSACTIONAL-ID "abc"
                       STORM-ZOOKEEPER-PORT zk-port
                       STORM-ZOOKEEPER-SERVERS ["localhost"]
                       })
               nil
               (mk-spout-capture emit-capture))
        (reset! coordinator-state 10)
        (.nextTuple coordinator)
        (bind attempts (get-attempts emit-capture BATCH-STREAM))
        (bind first-attempt (first attempts))
        (verify-and-reset! {BATCH-STREAM [[1 10] [2 10] [3 10] [4 10]]}
                           emit-capture)

        (.nextTuple coordinator)
        (verify-and-reset! {} emit-capture)
        
        (.fail coordinator (second attempts))
        (bind attempts (get-attempts emit-capture BATCH-STREAM))
        (bind new-second-attempt (first attempts))
        (verify-and-reset! {BATCH-STREAM [[2 10] [3 10] [4 10]]} emit-capture)
        (is (not= new-second-attempt (second attempts)))
        (.ack coordinator new-second-attempt)
        (verify-and-reset! {} emit-capture)
        (.ack coordinator first-attempt)
        (bind commit-id (get-commit emit-capture))
        (verify-and-reset! {COMMIT-STREAM [[1]]} emit-capture)

        (reset! coordinator-state 12)
        (.ack coordinator commit-id)
        (bind commit-id (get-commit emit-capture))
        (verify-and-reset! {COMMIT-STREAM [[2]] BATCH-STREAM [[5 12]]} emit-capture)
        (.ack coordinator commit-id)
        (verify-and-reset! {BATCH-STREAM [[6 12]]} emit-capture)

        (.fail coordinator (nth attempts 1))
        (bind attempts (get-attempts emit-capture BATCH-STREAM))
        (verify-and-reset! {BATCH-STREAM [[3 10] [4 10] [5 12] [6 12]]} emit-capture)

        (.ack coordinator (first attempts))
        (bind commit-id (get-commit emit-capture))
        (verify-and-reset! {COMMIT-STREAM [[3]]} emit-capture)

        (.ack coordinator (nth attempts 1))
        (verify-and-reset! {} emit-capture)

        (.fail coordinator commit-id)
        (bind attempts (get-attempts emit-capture BATCH-STREAM))
        (verify-and-reset! {BATCH-STREAM [[3 10] [4 10] [5 12] [6 12]]} emit-capture)

        (.ack coordinator (first attempts))
        (bind commit-id (get-commit emit-capture))
        (verify-and-reset! {COMMIT-STREAM [[3]]} emit-capture)

        (.ack coordinator (second attempts))
        (.nextTuple coordinator)
        (verify-and-reset! {} emit-capture)
        
        (.ack coordinator commit-id)
        (verify-and-reset! {COMMIT-STREAM [[4]] BATCH-STREAM [[7 12]]} emit-capture)

        (.close coordinator)
        ))))

(defn verify-bolt-and-reset! [expected-map emitted-atom]
  (is (= expected-map @emitted-atom))
  (reset! emitted-atom {}))

(defn mk-bolt-capture [capturer]
  (let [adder (fn [amap key newvalue]
                (update-in
                 amap
                 [key]
                 (fn [ov]
                   (concat ov [newvalue])
                   )))]
    (OutputCollector.
     (reify IOutputCollector
       (emit [this stream-id anchors values]
         (swap! capturer adder stream-id values)
         []
         )
       (ack [this tuple]
         (swap! capturer adder :ack (.getValues tuple))
         )
       (fail [this tuple]
         (swap! capturer adder :fail (.getValues tuple)))
       ))))

(defn mk-attempt [txid attempt-id]
  (TransactionAttempt. (BigInteger. (str txid)) attempt-id))


(defn finish! [bolt id]
  (.finishedId bolt id))

(deftest test-batch-bolt
  (let [bolt (BatchBoltExecutor. (CountingBatchBolt.))
        capture-atom (atom {})
        attempt1-1 (mk-attempt 1 1)
        attempt1-2 (mk-attempt 1 2)
        attempt2-1 (mk-attempt 2 1)
        attempt3 (mk-attempt 3 1)
        attempt4 (mk-attempt 4 1)
        attempt5 (mk-attempt 5 1)
        attempt6 (mk-attempt 6 1)]
    (.prepare bolt {} nil (mk-bolt-capture capture-atom))


    ;; test that transactions are independent
    
    (.execute bolt (test-tuple [attempt1-1]))
    (.execute bolt (test-tuple [attempt1-1]))
    (.execute bolt (test-tuple [attempt1-2]))
    (.execute bolt (test-tuple [attempt2-1]))
    (.execute bolt (test-tuple [attempt1-1]))
    
    (finish! bolt attempt1-1)

    (verify-bolt-and-reset! {:ack [[attempt1-1] [attempt1-1] [attempt1-2]
                                   [attempt2-1] [attempt1-1]]
                             "default" [[attempt1-1 3]]}
                            capture-atom)

    (.execute bolt (test-tuple [attempt1-2]))
    (finish! bolt attempt2-1)
    (verify-bolt-and-reset! {:ack [[attempt1-2]]
                             "default" [[attempt2-1 1]]}
                            capture-atom)

    (finish! bolt attempt1-2)
    (verify-bolt-and-reset! {"default" [[attempt1-2 2]]}
                            capture-atom)  
    ))

(defn mk-state-initializer [atom]
  (reify RotatingTransactionalState$StateInitializer
    (init [this txid last-state]
      @atom
      )))


(defn- to-txid [txid]
  (BigInteger. (str txid)))

(defn- get-state [state txid initializer]
  (.getState state (to-txid txid) initializer))

(defn- get-state-or-create [state txid initializer]
  (.getStateOrCreate state (to-txid txid) initializer))

(defn- cleanup-before [state txid]
  (.cleanupBefore state (to-txid txid)))

(deftest test-rotating-transactional-state
  ;; test strict ordered vs not strict ordered
  (let [zk-port (available-port 2181)]
    (with-inprocess-zookeeper zk-port
      (let [conf (merge (read-default-config)
                        {STORM-ZOOKEEPER-PORT zk-port
                         STORM-ZOOKEEPER-SERVERS ["localhost"]
                         })
            state (TransactionalState/newUserState conf "id1" {})
            strict-rotating (RotatingTransactionalState. state "strict" true)
            unstrict-rotating (RotatingTransactionalState. state "unstrict" false)
            init (atom 10)
            initializer (mk-state-initializer init)]
        (is (= 10 (get-state strict-rotating 1 initializer)))
        (is (= 10 (get-state strict-rotating 2 initializer)))
        (reset! init 20)
        (is (= 20 (get-state strict-rotating 3 initializer)))
        (is (= 10 (get-state strict-rotating 1 initializer)))

        (is (thrown? Exception (get-state strict-rotating 5 initializer)))
        (is (= 20 (get-state strict-rotating 4 initializer)))
        (is (= 4 (count (.list state "strict"))))
        (cleanup-before strict-rotating 3)
        (is (= 2 (count (.list state "strict"))))

        (is (nil? (get-state-or-create strict-rotating 5 initializer)))
        (is (= 20 (get-state-or-create strict-rotating 5 initializer)))
        (is (nil? (get-state-or-create strict-rotating 6 initializer)))        
        (cleanup-before strict-rotating 6)
        (is (= 1 (count (.list state "strict"))))

        (is (= 20 (get-state unstrict-rotating 10 initializer)))
        (is (= 20 (get-state unstrict-rotating 20 initializer)))
        (is (nil? (get-state unstrict-rotating 12 initializer)))
        (is (nil? (get-state unstrict-rotating 19 initializer)))
        (is (nil? (get-state unstrict-rotating 12 initializer)))
        (is (= 20 (get-state unstrict-rotating 21 initializer)))

        (.close state)
        ))))

(defn mk-transactional-source []
  (HashMap.))

(defn add-transactional-data [source partition-map]
  (doseq [[p data] partition-map]
    (if-not (contains? source p)
      (.put source p (Collections/synchronizedList (ArrayList.))))
    (-> source (.get p) (.addAll data))
    ))

(defn tracked-captured-topology [cluster topology]
  (let [{captured :capturer topology :topology} (capture-topology topology)
        tracked (mk-tracked-topology cluster topology)]
    (assoc tracked :capturer captured)
    ))

;; puts its collector and tuples into the global state to be used externally
(defbolt controller-bolt {} {:prepare true :params [state-id]}
  [conf context collector]
  (let [{tuples :tuples
         collector-atom :collector} (RegisteredGlobalState/getState state-id)]
    (reset! collector-atom collector)
    (reset! tuples [])
    (bolt
     (execute [tuple]
              (swap! tuples conj tuple))
     )))

(defmacro with-controller-bolt [[bolt collector-atom tuples-atom] & body]
  `(let [~collector-atom (atom nil)
         ~tuples-atom (atom [])
         id# (RegisteredGlobalState/registerState {:collector ~collector-atom
                                                   :tuples ~tuples-atom})
         ~bolt (controller-bolt id#)]
     ~@body
     (RegisteredGlobalState/clearState id#)
    ))

(deftest test-transactional-topology
  (with-tracked-cluster [cluster]
    (with-controller-bolt [controller collector tuples]
      (letlocals
       (bind data (mk-transactional-source))
       (bind builder (TransactionalTopologyBuilder.
                      "id"
                      "spout"
                      (MemoryTransactionalSpout. data
                                                 (Fields. ["word" "amt"])
                                                 2)
                      2))

       (-> builder
           (.setBolt "id1" (IdentityBolt. (Fields. ["tx" "word" "amt"])) 3)
           (.shuffleGrouping "spout"))

       (-> builder
           (.setBolt "id2" (IdentityBolt. (Fields. ["tx" "word" "amt"])) 3)
           (.shuffleGrouping "spout"))

       (-> builder
           (.setBolt "global" (CountingBatchBolt.) 1)
           (.globalGrouping "spout"))

       (-> builder
           (.setBolt "gcommit" (CountingCommitBolt.) 1)
           (.globalGrouping "spout"))
       
       (-> builder
           (.setBolt "sum" (KeyedSummingBatchBolt.) 2)
           (.fieldsGrouping "id1" (Fields. ["word"])))

       (-> builder
           (.setCommitterBolt "count" (KeyedCountingBatchBolt.) 2)
           (.fieldsGrouping "id2" (Fields. ["word"])))

       (-> builder
           (.setBolt "count2" (KeyedCountingCommitterBolt.) 3)
           (.fieldsGrouping "sum" (Fields. ["key"]))
           (.fieldsGrouping "count" (Fields. ["key"])))

       (bind builder (.buildTopologyBuilder builder))
       
       (-> builder
           (.setBolt "controller" controller 1)
           (.directGrouping "count2" Constants/COORDINATED_STREAM_ID)
           (.directGrouping "sum" Constants/COORDINATED_STREAM_ID))

       (add-transactional-data data
                               {0 [["dog" 3]
                                   ["cat" 4]
                                   ["apple" 1]
                                   ["dog" 3]]
                                1 [["cat" 1]
                                   ["mango" 4]]
                                2 [["happy" 11]
                                   ["mango" 2]
                                   ["zebra" 1]]})
       
       (bind topo-info (tracked-captured-topology
                        cluster
                        (.createTopology builder)))
       (submit-local-topology (:nimbus cluster)
                              "transactional-test"
                              {TOPOLOGY-MAX-SPOUT-PENDING 2}
                              (:topology topo-info))

       (bind ack-tx! (fn [txid]
                       (let [[to-ack not-to-ack] (separate
                                                  #(-> %
                                                       (.getValue 0)
                                                       .getTransactionId
                                                       (= txid))
                                                  @tuples)]
                         (reset! tuples not-to-ack)
                         (doseq [t to-ack]
                           (ack! @collector t)))))

       (bind fail-tx! (fn [txid]
                        (let [[to-fail not-to-fail] (separate
                                                     #(-> %
                                                          (.getValue 0)
                                                          .getTransactionId
                                                          (= txid))
                                                     @tuples)]
                          (reset! tuples not-to-fail)
                          (doseq [t to-fail]
                            (fail! @collector t)))))

       ;; only check default streams
       (bind verify! (fn [expected]
                       (let [results (-> topo-info :capturer .getResults)]
                         (doseq [[component tuples] expected
                                 :let [emitted (->> (read-tuples results
                                                                 component
                                                                 "default")
                                                    (map normalize-tx-tuple))]]
                           (is (ms= tuples emitted)))
                         (.clear results)
                         )))

       (tracked-wait topo-info 2)
       (verify! {"sum" [[1 "dog" 3]
                        [1 "cat" 5]
                        [1 "mango" 6]
                        [1 "happy" 11]
                        [2 "apple" 1]
                        [2 "dog" 3]
                        [2 "zebra" 1]]
                 "count" []
                 "count2" []
                 "global" [[1 6]
                           [2 3]]
                 "gcommit" []})
       (ack-tx! 1)
       (tracked-wait topo-info 1)
       (verify! {"sum" []
                 "count" [[1 "dog" 1]
                          [1 "cat" 2]
                          [1 "mango" 2]
                          [1 "happy" 1]]
                 "count2" [[1 "dog" 2]
                           [1 "cat" 2]
                           [1 "mango" 2]
                           [1 "happy" 2]]
                 "global" []
                 "gcommit" [[1 6]]})

       (add-transactional-data data
                               {0 [["a" 1]
                                   ["b" 2]
                                   ["c" 3]]
                                1 [["d" 4]
                                   ["c" 1]]
                                2 [["a" 2]
                                   ["e" 7]
                                   ["c" 11]]
                                3 [["a" 2]]})
       
       (ack-tx! 1)
       (tracked-wait topo-info 1)
       (verify! {"sum" [[3 "a" 5]
                        [3 "b" 2]
                        [3 "d" 4]
                        [3 "c" 1]
                        [3 "e" 7]]
                 "count" []
                 "count2" []
                 "global" [[3 7]]
                 "gcommit" []})
       (ack-tx! 3)
       (ack-tx! 2)
       (tracked-wait topo-info 1)
       (verify! {"sum" []
                 "count" [[2 "apple" 1]
                          [2 "dog" 1]
                          [2 "zebra" 1]]
                 "count2" [[2 "apple" 2]
                           [2 "dog" 2]
                           [2 "zebra" 2]]
                 "global" []
                 "gcommit" [[2 3]]})

       (fail-tx! 2)
       (tracked-wait topo-info 2)

       (verify! {"sum" [[2 "apple" 1]
                        [2 "dog" 3]
                        [2 "zebra" 1]
                        [3 "a" 5]
                        [3 "b" 2]
                        [3 "d" 4]
                        [3 "c" 1]
                        [3 "e" 7]]
                 "count" []
                 "count2" []
                 "global" [[2 3]
                           [3 7]]
                 "gcommit" []})
       (ack-tx! 2)
       (tracked-wait topo-info 1)
       
       (verify! {"sum" []
                 "count" [[2 "apple" 1]
                          [2 "dog" 1]
                          [2 "zebra" 1]]
                 "count2" [[2 "apple" 2]
                           [2 "dog" 2]
                           [2 "zebra" 2]]
                 "global" []
                 "gcommit" [[2 3]]})
       
       (ack-tx! 2)
       (ack-tx! 3)
       
       (tracked-wait topo-info 2)
       (verify! {"sum" [[4 "c" 14]]
                 "count" [[3 "a" 3]
                          [3 "b" 1]
                          [3 "d" 1]
                          [3 "c" 1]
                          [3 "e" 1]]
                 "count2" [[3 "a" 2]
                           [3 "b" 2]
                           [3 "d" 2]
                           [3 "c" 2]
                           [3 "e" 2]]
                 "global" [[4 2]]
                 "gcommit" [[3 7]]})
       
       (ack-tx! 4)
       (ack-tx! 3)
       (tracked-wait topo-info 2)
       (verify! {"sum" []
                 "count" [[4 "c" 2]]
                 "count2" [[4 "c" 2]]
                 "global" [[5 0]]
                 "gcommit" [[4 2]]})
       
       (ack-tx! 5)
       (ack-tx! 4)
       (tracked-wait topo-info 2)
       (verify! {"sum" []
                 "count" []
                 "count2" []
                 "global" [[6 0]]
                 "gcommit" [[5 0]]})
       
       (-> topo-info :capturer .getAndClearResults)
       ))))

(deftest test-transactional-topology-restart
  (with-simulated-time-local-cluster [cluster]
    (letlocals
     (bind data (mk-transactional-source))
     (bind builder (TransactionalTopologyBuilder.
                    "id"
                    "spout"
                    (MemoryTransactionalSpout. data
                                               (Fields. ["word"])
                                               3)
                    2))

     (-> builder
         (.setBolt "count" (CountingCommitBolt.) 2)
         (.globalGrouping "spout"))

     (add-transactional-data data
                             {0 [["a"]
                                 ["b"]
                                 ["c"]
                                 ["d"]]
                              1 [["d"]
                                 ["c"]]
                              })
     
     (bind results (complete-topology cluster
                                      (.buildTopology builder)
                                      :cleanup-state false))

     (is (ms= [[5] [0] [1] [0]] (->> (read-tuples results "count")
                                     (take 4)
                                     (map (partial drop 1))
                                     )))

     (add-transactional-data data
                             {0 [["a"]
                                 ["b"]]
                              })
     
     (bind results (complete-topology cluster (.buildTopology builder)))

     ;; need to do it this way (check for nothing transaction) because there is one transaction already saved up before that emits nothing (because of how memorytransctionalspout detects partition completion)
     (is (ms= [[0] [0] [2] [0]] (->> (read-tuples results "count")
                             (take 4)
                             (map (partial drop 1))
                             )))
     )))

