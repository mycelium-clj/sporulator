(ns sporulator.tree-implementor-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.decomposer :as decomposer]
            [sporulator.tree-implementor :as ti]))

(def ^:private rlh-tree
  [{:name "one-minute-ago" :doc "Returns Unix timestamp 60s ago."
    :params [] :examples [[[] 1700000000]] :depends-on []}
   {:name "count-submissions-since"
    :doc "Counts submissions for handle after since."
    :params ["db" "handle" "since"]
    :examples [[["DB" "alice" 1000] 2]]
    :depends-on []}
   {:name "rate-limit-result"
    :doc "Success map if count <= 3, else error map."
    :params ["handle" "count"]
    :examples [[["alice" 2] {:validated-handle "alice"}]
               [["bob" 4] {:error "Rate limit exceeded"}]]
    :depends-on []}
   {:name "handler"
    :doc "Validates rate limit; passes through or returns error."
    :params ["resources" "data"]
    :examples [[[{} {:validated-handle "alice"}] {:validated-handle "alice"}]]
    :depends-on ["one-minute-ago" "count-submissions-since" "rate-limit-result"]}])

(deftest skeleton-helpers-renders-stubs-for-non-handler-nodes-test
  (let [src (ti/skeleton-helpers-source rlh-tree)]
    (is (str/includes? src "(defn one-minute-ago []"))
    (is (str/includes? src "(defn count-submissions-since [db handle since]"))
    (is (str/includes? src "(defn rate-limit-result [handle count]"))
    (is (not (str/includes? src "(defn handler"))
        "handler stays in handler.clj, not helpers.clj")
    (is (str/includes? src "TODO:")
        "stubs include the doc as a TODO comment")
    (is (str/includes? src "UnsupportedOperationException")
        "stubs throw so tests can't accidentally pass on the stub")))

(deftest handler-skeleton-renders-fn-form-test
  (let [src (ti/handler-skeleton-source rlh-tree)]
    (is (str/includes? src "(fn [resources data]"))
    (is (str/includes? src "TODO:"))
    (is (or (str/includes? src "one-minute-ago")
            (str/includes? src "helpers available"))
        "skeleton mentions which helpers it can call")
    (is (str/includes? src "UnsupportedOperationException"))))

(deftest build-skeleton-returns-both-buffers-test
  (let [{:keys [initial-handler initial-helpers]} (ti/build-skeleton rlh-tree)]
    (is (string? initial-handler))
    (is (string? initial-helpers))
    (is (str/includes? initial-handler "(fn ["))
    (is (str/includes? initial-helpers "(defn one-minute-ago"))))

(deftest batch-by-deps-groups-independent-leaves-test
  (testing "batches order leaves before nodes that depend on them, and group independents together"
    ;; chain-shaped tree: a depends on b, b depends on c.
    (let [chain [{:name "c" :params [] :examples [] :doc "" :depends-on []}
                 {:name "b" :params [] :examples [] :doc "" :depends-on ["c"]}
                 {:name "a" :params [] :examples [] :doc "" :depends-on ["b"]}
                 {:name "handler" :params [] :examples [] :doc "" :depends-on ["a"]}]
          ordered (decomposer/ordered-nodes chain)
          batches (#'ti/batch-by-deps ordered)]
      (is (= [["c"] ["b"] ["a"] ["handler"]]
             (mapv (fn [b] (mapv :name b)) batches))
          "chain → 4 batches, one node each"))

    ;; flat tree: 3 independents + handler.
    (let [flat [{:name "x" :params [] :examples [] :doc "" :depends-on []}
                {:name "y" :params [] :examples [] :doc "" :depends-on []}
                {:name "z" :params [] :examples [] :doc "" :depends-on []}
                {:name "handler" :params [] :examples [] :doc ""
                 :depends-on ["x" "y" "z"]}]
          ordered (decomposer/ordered-nodes flat)
          batches (#'ti/batch-by-deps ordered)]
      (is (= 2 (count batches))
          "flat → 2 batches: one with the 3 independents, one with handler")
      (is (= #{"x" "y" "z"} (set (map :name (first batches))))
          "first batch holds all 3 leaves (concurrent-implementable)")
      (is (= ["handler"] (mapv :name (second batches)))))))
