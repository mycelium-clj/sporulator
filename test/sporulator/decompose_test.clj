(ns sporulator.decompose-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.decompose :as decompose]))

;; =============================================================
;; Decomposition response parsing
;; Translated from Go decompose_test.go
;; =============================================================

(deftest parse-decomposition-response-test
  (testing "basic three-step decomposition"
    (let [response (str "Here are the steps:\n```edn\n"
                        "[{:name \"validate-input\" :doc \"Validate the order data\" "
                        ":input-schema [:map] :output-schema [:map] :requires [] :simple? true}\n"
                        " {:name \"compute-tax\" :doc \"Calculate tax amounts\" "
                        ":input-schema [:map] :output-schema [:map] :requires [:tax-service] :simple? true}\n"
                        " {:name \"check-fraud\" :doc \"Run fraud detection\" "
                        ":input-schema [:map] :output-schema [:map] :requires [:db :fraud-api] :simple? false}]"
                        "\n```")
          nodes (decompose/parse-decomposition-response response "order")]
      (is (= 3 (count nodes)))
      ;; Check first node
      (is (= "validate-input" (:step-name (first nodes))))
      (is (= :order/validate-input (:cell-id (first nodes))))
      (is (= "Validate the order data" (:doc (first nodes))))
      (is (:leaf? (first nodes)))
      ;; Check requires on second node
      (is (= [:tax-service] (:requires (second nodes))))
      ;; Check complex (non-leaf) node
      (is (not (:leaf? (nth nodes 2))))
      (is (= [:db :fraud-api] (:requires (nth nodes 2))))))

  (testing "no code block returns nil"
    (is (nil? (decompose/parse-decomposition-response "no code blocks here" "ns"))))

  (testing "nested schemas preserved"
    (let [response (str "```edn\n"
                        "[{:name \"validate\" :doc \"Validate input\" "
                        ":input-schema [:map [:items [:vector :map]]] "
                        ":output-schema [:map [:valid? :boolean]] "
                        ":requires [:catalog] :simple? true}]"
                        "\n```")
          nodes (decompose/parse-decomposition-response response "order")]
      (is (= 1 (count nodes)))
      (is (= [:map [:items [:vector :map]]] (:input-schema (first nodes))))
      (is (= [:map [:valid? :boolean]] (:output-schema (first nodes)))))))

;; =============================================================
;; Tree operations
;; =============================================================

(deftest collect-leaves-test
  (testing "nested tree"
    (let [tree {:step-name "root" :leaf? false
                :children [{:step-name "a" :leaf? true}
                           {:step-name "b" :leaf? false
                            :children [{:step-name "b1" :leaf? true}
                                       {:step-name "b2" :leaf? true}]}
                           {:step-name "c" :leaf? true}]}
          leaves (decompose/collect-leaves tree)]
      (is (= ["a" "b1" "b2" "c"] (mapv :step-name leaves)))))

  (testing "nil returns empty"
    (is (= [] (decompose/collect-leaves nil))))

  (testing "single leaf"
    (is (= ["only"]
           (mapv :step-name (decompose/collect-leaves {:step-name "only" :leaf? true}))))))

(deftest collect-sub-workflows-test
  (testing "post-order traversal (deepest first)"
    (let [tree {:step-name "root" :leaf? false
                :children [{:step-name "a" :leaf? true}
                           {:step-name "b" :leaf? false
                            :children [{:step-name "b1" :leaf? true}
                                       {:step-name "b-inner" :leaf? false
                                        :children [{:step-name "b-inner-1" :leaf? true}]}]}
                           {:step-name "c" :leaf? true}]}
          subs (decompose/collect-sub-workflows tree)]
      (is (= 2 (count subs)))
      (is (= "b-inner" (:step-name (first subs))))
      (is (= "b" (:step-name (second subs))))))

  (testing "nil returns empty"
    (is (= [] (decompose/collect-sub-workflows nil)))))

;; =============================================================
;; Edge parsing (uses Clojure reader — no Go EDN lib needed)
;; =============================================================

(deftest parse-edge-targets-test
  (testing "simple unconditional"
    (is (= [:process] (decompose/parse-edge-targets ":process"))))

  (testing "conditional map"
    (is (= #{:check-inventory :end}
           (set (decompose/parse-edge-targets "{:valid :check-inventory, :invalid :end}")))))

  (testing "single outcome"
    (is (= [:next-step] (decompose/parse-edge-targets "{:done :next-step}"))))

  (testing "three outcomes"
    (is (= #{:pay :rollback :manual}
           (set (decompose/parse-edge-targets "{:approved :pay, :rejected :rollback, :review :manual}")))))

  (testing "empty returns nil"
    (is (nil? (decompose/parse-edge-targets ""))))

  (testing "namespaced keyword"
    (is (= [:ns/step-name] (decompose/parse-edge-targets ":ns/step-name"))))

  (testing "invalid EDN returns nil"
    (is (nil? (decompose/parse-edge-targets "not valid edn {{{")))))

;; =============================================================
;; Graph context
;; =============================================================

(deftest build-graph-context-test
  (testing "linear workflow A -> B -> C"
    (let [parent {:step-name "root"
                  :cell-id :test/workflow
                  :children [{:step-name "step-a" :cell-id :test/step-a :doc "First step"
                              :output-schema "{:x :int}"}
                             {:step-name "step-b" :cell-id :test/step-b :doc "Second step"
                              :input-schema "{:x :int}" :output-schema "{:y :int}"}
                             {:step-name "step-c" :cell-id :test/step-c :doc "Third step"
                              :input-schema "{:y :int}"}]
                  :walk-result {:edges {"step-a" "{:done :step-b}"
                                        "step-b" "{:done :step-c}"
                                        "step-c" "{:done :end}"}}}
          ctx (decompose/build-graph-context parent "step-b")]
      (is (= 1 (count (:predecessors ctx))))
      (is (= :test/step-a (:cell-id (first (:predecessors ctx)))))
      (is (= "First step" (:doc (first (:predecessors ctx)))))
      (is (= 1 (count (:successors ctx))))
      (is (= :test/step-c (:cell-id (first (:successors ctx)))))))

  (testing "first cell has no predecessors"
    (let [parent {:children [{:step-name "step-a" :cell-id :test/a :doc "First"}
                             {:step-name "step-b" :cell-id :test/b :doc "Second"}]
                  :walk-result {:edges {"step-a" "{:done :step-b}"
                                        "step-b" "{:done :end}"}}}
          ctx (decompose/build-graph-context parent "step-a")]
      (is (= 0 (count (:predecessors ctx))))
      (is (= 1 (count (:successors ctx))))))

  (testing "last cell has no successors (end is not a real cell)"
    (let [parent {:children [{:step-name "step-a" :cell-id :test/a :doc "First"}
                             {:step-name "step-b" :cell-id :test/b :doc "Last"}]
                  :walk-result {:edges {"step-a" "{:done :step-b}"
                                        "step-b" "{:done :end}"}}}
          ctx (decompose/build-graph-context parent "step-b")]
      (is (= 1 (count (:predecessors ctx))))
      (is (= 0 (count (:successors ctx))))))

  (testing "branching workflow"
    (let [parent {:children [{:step-name "validate" :cell-id :test/validate :doc "Validate input"
                              :output-schema "{:valid :boolean}"}
                             {:step-name "process" :cell-id :test/process :doc "Process order"
                              :input-schema "{:valid :boolean}"}
                             {:step-name "reject" :cell-id :test/reject :doc "Reject order"
                              :input-schema "{:valid :boolean}"}]
                  :walk-result {:edges {"validate" "{:valid :process, :invalid :reject}"
                                        "process" "{:done :end}"
                                        "reject" "{:done :end}"}}}
          ctx (decompose/build-graph-context parent "process")]
      (is (= 1 (count (:predecessors ctx))))
      (is (= :test/validate (:cell-id (first (:predecessors ctx)))))))

  (testing "nil parent returns empty context"
    (let [ctx (decompose/build-graph-context nil "step-a")]
      (is (= 0 (count (:predecessors ctx))))
      (is (= 0 (count (:successors ctx))))))

  (testing "nil walk-result returns empty context"
    (let [ctx (decompose/build-graph-context {:children [{:step-name "a" :cell-id :test/a}]
                                              :walk-result nil} "a")]
      (is (= 0 (count (:predecessors ctx))))
      (is (= 0 (count (:successors ctx))))))

  (testing "dangling edge target silently ignored"
    (let [ctx (decompose/build-graph-context
                {:children [{:step-name "step-a" :cell-id :test/a :doc "A"}]
                 :walk-result {:edges {"step-a" ":nonexistent"}}}
                "step-a")]
      (is (= 0 (count (:successors ctx)))))))

;; =============================================================
;; Find parent in tree
;; =============================================================

(deftest find-parent-test
  (testing "nil tree returns nil"
    (is (nil? (decompose/find-parent nil "step-a"))))

  (testing "root level"
    (let [tree {:step-name "root"
                :children [{:step-name "step-a" :leaf? true}
                           {:step-name "step-b" :leaf? true}]}]
      (is (= tree (decompose/find-parent tree "step-a")))))

  (testing "deeply nested"
    (let [inner {:step-name "inner"
                 :children [{:step-name "deep-leaf" :leaf? true}]}
          tree {:step-name "root"
                :children [{:step-name "outer" :leaf? false :children [inner]}]}]
      (is (= inner (decompose/find-parent tree "deep-leaf")))))

  (testing "not found returns nil"
    (let [tree {:step-name "root"
                :children [{:step-name "step-a" :leaf? true}]}]
      (is (nil? (decompose/find-parent tree "nonexistent"))))))

;; =============================================================
;; Format graph context for LLM prompts
;; =============================================================

(deftest format-graph-context-test
  (testing "full context"
    (let [ctx {:predecessors [{:cell-id :order/validate :doc "Validates input"
                               :schema "{:items [:vector :map]}"}]
               :successors [{:cell-id :order/process :doc "Processes payment"
                             :schema "{:total :double}"}]}
          formatted (decompose/format-graph-context ctx)]
      (is (str/includes? formatted ":order/validate"))
      (is (str/includes? formatted "Validates input"))
      (is (str/includes? formatted ":order/process"))
      (is (str/includes? formatted "Processes payment"))))

  (testing "empty context returns empty string"
    (is (= "" (decompose/format-graph-context {:predecessors [] :successors []}))))

  (testing "predecessors only"
    (let [formatted (decompose/format-graph-context
                      {:predecessors [{:cell-id :test/a :doc "Step A"}]
                       :successors []})]
      (is (str/includes? formatted "Receives data from"))
      (is (not (str/includes? formatted "Feeds data to")))))

  (testing "no schema line when schema empty"
    (let [formatted (decompose/format-graph-context
                      {:predecessors [{:cell-id :test/a :doc "Step A" :schema ""}]
                       :successors []})]
      (is (not (str/includes? formatted "Output schema"))))))

;; =============================================================
;; Serialize/deserialize tree
;; =============================================================

(deftest serialize-deserialize-test
  (testing "round-trip"
    (let [tree {:step-name "root" :cell-id :ns/root :doc "Root node"
                :input-schema [:map] :output-schema [:map]
                :leaf? false :depth 0
                :children [{:step-name "a" :cell-id :ns/a :doc "Step A" :leaf? true :depth 1}
                           {:step-name "b" :cell-id :ns/b :doc "Step B" :leaf? true :depth 1
                            :requires [:db]}]}
          json-str (decompose/serialize-tree tree)
          restored (decompose/deserialize-tree json-str)]
      (is (= "root" (:step-name restored)))
      (is (= 2 (count (:children restored))))
      (is (= "a" (:step-name (first (:children restored)))))
      (is (= [:db] (:requires (second (:children restored))))))))
