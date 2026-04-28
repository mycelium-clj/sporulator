(ns sporulator.decomposer-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.decomposer :as decomposer]))

(deftest validate-tree-rejects-bad-shapes-test
  (testing "non-vector"
    (is (some? (decomposer/validate-tree "not a tree"))))

  (testing "empty vector"
    (is (some? (decomposer/validate-tree []))))

  (testing "missing handler root"
    (is (str/includes? (:error (decomposer/validate-tree
                                 [{:name "foo" :doc "" :params [] :test-body ""
                                   :depends-on []}]))
                       "handler")))

  (testing "good shape passes"
    (is (nil? (decomposer/validate-tree
                [{:name "valid?" :doc "..." :params ["s"]
                  :test-body "(deftest t (is (true? (valid? \"a\"))))"
                  :depends-on []}
                 {:name "handler" :doc "..." :params ["resources" "data"]
                  :test-body "(deftest t2 (is (= {:n 1} (handler {} {:k 1}))))"
                  :depends-on ["valid?"]}]))))

  (testing "test-body must be a string, not a vector"
    (is (some? (decomposer/validate-tree
                 [{:name "handler" :doc "" :params [] :test-body []
                   :depends-on []}])))))

(deftest topo-sort-orders-leaves-first-test
  (let [tree [{:name "a" :params [] :test-body "" :depends-on ["b" "c"]
               :doc "" }
              {:name "b" :params [] :test-body "" :depends-on []
               :doc ""}
              {:name "c" :params [] :test-body "" :depends-on ["b"]
               :doc ""}]
        ordered (decomposer/ordered-nodes tree)
        names (mapv :name ordered)]
    (is (< (.indexOf names "b") (.indexOf names "c"))
        "b (leaf) before c (depends on b)")
    (is (< (.indexOf names "c") (.indexOf names "a"))
        "c before a (a depends on c)")))

(deftest topo-sort-detects-cycles-test
  (let [tree [{:name "a" :params [] :test-body "" :depends-on ["b"]
               :doc ""}
              {:name "b" :params [] :test-body "" :depends-on ["a"]
               :doc ""}]]
    (is (thrown? Exception (decomposer/ordered-nodes tree)))))
