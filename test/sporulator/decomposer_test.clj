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
                                 [{:name "foo" :doc "" :params [] :examples []
                                   :depends-on []}]))
                       "handler")))

  (testing "good shape passes"
    (is (nil? (decomposer/validate-tree
                [{:name "valid?" :doc "..." :params ["s"]
                  :examples [["a" true]]
                  :depends-on []}
                 {:name "handler" :doc "..." :params ["resources" "data"]
                  :examples [[[{} {:k 1}] {:n 1}]]
                  :depends-on ["valid?"]}])))))

(deftest topo-sort-orders-leaves-first-test
  (let [tree [{:name "a" :params [] :examples [] :depends-on ["b" "c"]
               :doc "" }
              {:name "b" :params [] :examples [] :depends-on []
               :doc ""}
              {:name "c" :params [] :examples [] :depends-on ["b"]
               :doc ""}]
        ordered (decomposer/ordered-nodes tree)
        names (mapv :name ordered)]
    (is (< (.indexOf names "b") (.indexOf names "c"))
        "b (leaf) before c (depends on b)")
    (is (< (.indexOf names "c") (.indexOf names "a"))
        "c before a (a depends on c)")))

(deftest topo-sort-detects-cycles-test
  (let [tree [{:name "a" :params [] :examples [] :depends-on ["b"]
               :doc ""}
              {:name "b" :params [] :examples [] :depends-on ["a"]
               :doc ""}]]
    (is (thrown? Exception (decomposer/ordered-nodes tree)))))

(deftest examples-to-deftests-renders-deftest-form-test
  (testing "single-arg fn"
    (let [out (decomposer/examples->deftests
                {:name "doubled" :params ["n"]
                 :examples [[1 2] [10 20]]})]
      (is (str/includes? out "(deftest test-doubled"))
      (is (str/includes? out "(doubled 1)"))
      (is (str/includes? out "(is (= 2"))))

  (testing "multi-arg fn — input wrapped in vector"
    (let [out (decomposer/examples->deftests
                {:name "add" :params ["a" "b"]
                 :examples [[[1 2] 3] [[10 20] 30]]})]
      (is (str/includes? out "(add 1 2)"))
      (is (str/includes? out "(is (= 3"))
      (is (str/includes? out "(add 10 20)")))))
