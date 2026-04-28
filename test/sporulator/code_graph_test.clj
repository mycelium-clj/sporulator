(ns sporulator.code-graph-test
  (:require [clojure.test :refer [deftest is testing]]
            [sporulator.code-graph :as cg]))

(def ^:private simple-graph-facts
  (hash-set [:calls :a :b]
            [:calls :b :c]
            [:calls :a :c]
            [:calls :d :e]
            [:calls :e :f]
            [:defines :a "ns/a.clj" :function 1]
            [:defines :b "ns/b.clj" :function 5]
            [:defines :c "ns/c.clj" :function 10]
            [:defines :d "ns/d.clj" :function 1]
            [:defines :e "ns/e.clj" :function 1]
            [:defines :f "ns/f.clj" :function 1]
            [:defines :g "ns/g.clj" :function 1]
            [:imports "ns/a.clj" :b]
            [:imports "ns/b.clj" :c]
            [:requires :a :db]
            [:produces :a :handle]
            [:consumes :a :user-id]))

(deftest test-callers
  (testing "direct callers"
    (is (= (hash-set :a) (cg/callers simple-graph-facts :b)))
    (is (= (hash-set :a :b) (cg/callers simple-graph-facts :c)))
    (is (= (hash-set) (cg/callers simple-graph-facts :a)))
    (is (= (hash-set :d) (cg/callers simple-graph-facts :e)))))

(deftest test-callees
  (testing "direct callees"
    (is (= (hash-set :b :c) (cg/callees simple-graph-facts :a)))
    (is (= (hash-set :c) (cg/callees simple-graph-facts :b)))
    (is (= (hash-set) (cg/callees simple-graph-facts :c)))
    (is (= (hash-set :f) (cg/callees simple-graph-facts :e)))))

(deftest test-reachable?
  (testing "transitive reachability"
    (is (cg/reachable? simple-graph-facts :a :b))
    (is (cg/reachable? simple-graph-facts :a :c))
    (is (cg/reachable? simple-graph-facts :b :c))
    (is (cg/reachable? simple-graph-facts :d :f))
    (is (not (cg/reachable? simple-graph-facts :b :a)))
    (is (not (cg/reachable? simple-graph-facts :c :a)))
    (is (not (cg/reachable? simple-graph-facts :a :d)))))

(deftest test-impact
  (testing "transitive callers affected by a change"
    (is (= (hash-set :a :b) (cg/impact simple-graph-facts :c)))
    (is (= (hash-set :a) (cg/impact simple-graph-facts :b)))
    (is (= (hash-set) (cg/impact simple-graph-facts :a)))
    (is (= (hash-set :d :e) (cg/impact simple-graph-facts :f)))))

(deftest test-dead-code
  (testing "defined functions not called by anyone"
    (is (= (hash-set :g) (cg/dead-code simple-graph-facts)))
    (is (not (contains? (cg/dead-code simple-graph-facts) :a)))))

(deftest test-cycles
  (let [cyclic-graph (conj simple-graph-facts [:calls :c :a])]
    (testing "cyclic call detection"
      (is (contains? (cg/cycles cyclic-graph) (hash-set :a :b :c)))
      (is (empty? (cg/cycles simple-graph-facts))))))

(deftest test-data-flow
  (testing "data keys produced/consumed"
    (let [facts simple-graph-facts]
      (is (= (hash-set :handle) (cg/produces-keys facts :a)))
      (is (= (hash-set :user-id) (cg/consumes-keys facts :a)))
      (is (= (hash-set) (cg/produces-keys facts :b)))
      (is (= (hash-set :db) (cg/resource-requires facts :a))))))

(deftest test-path
  (testing "path finding between functions"
    (is (= [:a :c] (cg/path simple-graph-facts :a :c)))
    (is (= [:a :b] (cg/path simple-graph-facts :a :b)))
    (is (= [:d :e :f] (cg/path simple-graph-facts :d :f)))
    (is (nil? (cg/path simple-graph-facts :c :a)))
    (is (nil? (cg/path simple-graph-facts :a :d)))))

(deftest test-add-edge!
  (let [graph (atom simple-graph-facts)]
    (testing "adding a new edge"
      (cg/add-edge! graph :calls :g :a)
      (is (contains? @graph [:calls :g :a]))
      (is (cg/reachable? @graph :g :a))
      (is (not (contains? (cg/dead-code @graph) :g))))))

(deftest test-add-def!
  (let [graph (atom (hash-set [:defines :h "ns/h.clj" :function 1]))]
    (testing "adding a new definition"
      (cg/add-def! graph :i "ns/i.clj" :function 5)
      (is (contains? @graph [:defines :i "ns/i.clj" :function 5]))
      (is (contains? (cg/dead-code @graph) :h))
      (is (contains? (cg/dead-code @graph) :i)))))

(deftest test-empty-graph
  (let [empty-graph (hash-set)]
    (testing "all operations work on empty graphs"
      (is (= (hash-set) (cg/callers empty-graph :a)))
      (is (= (hash-set) (cg/callees empty-graph :a)))
      (is (not (cg/reachable? empty-graph :a :b)))
      (is (= (hash-set) (cg/impact empty-graph :a)))
      (is (= (hash-set) (cg/dead-code empty-graph)))
      (is (empty? (cg/cycles empty-graph)))
      (is (nil? (cg/path empty-graph :a :b))))))

(deftest test-transitive-closure
  (let [deep-graph (hash-set [:calls :a :b] [:calls :b :c] [:calls :c :d]
                             [:calls :d :e] [:calls :e :f]
                             [:defines :a "" :function 0]
                             [:defines :b "" :function 0]
                             [:defines :c "" :function 0]
                             [:defines :d "" :function 0]
                             [:defines :e "" :function 0]
                             [:defines :f "" :function 0])]
    (testing "deep transitive closure"
      (is (cg/reachable? deep-graph :a :f))
      (is (= (hash-set :a :b :c :d :e) (cg/impact deep-graph :f))))))
