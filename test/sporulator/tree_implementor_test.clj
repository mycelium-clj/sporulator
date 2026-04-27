(ns sporulator.tree-implementor-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
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
