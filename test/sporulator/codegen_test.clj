(ns sporulator.codegen-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.codegen :as codegen]))

;; =============================================================
;; Cell source assembly
;; Translated from Go scaffold_test.go
;; =============================================================

(deftest assemble-cell-source-test
  (testing "full assembly with all options"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :doc "Validate input"
                    :schema {:input [:map] :output [:map]}
                    :requires [:db]
                    :extra-requires ['[clojure.string :as str]]
                    :helpers ['(defn helper [x] x)]
                    :fn-body '(fn [resources data] data)})]
      (is (str/includes? result "(ns example.cells.order"))
      (is (str/includes? result "[mycelium.cell :as cell]"))
      (is (str/includes? result "[clojure.string :as str]"))
      (is (str/includes? result "(defn helper [x] x)"))
      (is (str/includes? result "(cell/defcell :order/validate"))
      (is (str/includes? result "\"Validate input\""))
      (is (str/includes? result "(fn [resources data] data)"))))

  (testing "no helpers - no triple newlines"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :doc "Validate"
                    :schema {:input [:map] :output [:map]}
                    :fn-body '(fn [resources data] data)})]
      (is (not (str/includes? result "\n\n\n")))
      (is (str/includes? result "(cell/defcell :order/validate"))))

  (testing "escapes doc quotes"
    (let [result (codegen/assemble-cell-source
                   {:cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :doc "Validate \"special\" input"
                    :schema {:input [:map] :output [:map]}
                    :fn-body '(fn [resources data] data)})]
      ;; pr-str handles quoting automatically
      (is (str/includes? result (pr-str "Validate \"special\" input"))))))

;; =============================================================
;; Test source assembly
;; =============================================================

(deftest assemble-test-source-test
  (testing "full test assembly"
    (let [result (codegen/assemble-test-source
                   {:test-ns "example.cells.order-test"
                    :cell-ns "example.cells.order"
                    :cell-id :order/validate
                    :test-body "(deftest test-basic\n  (is (= 1 1)))"})]
      (is (str/includes? result "(ns example.cells.order-test"))
      (is (str/includes? result "[clojure.test :refer [deftest is testing]]"))
      (is (str/includes? result "[example.cells.order]"))
      (is (str/includes? result "(def handler (:handler cell-spec))"))
      (is (str/includes? result "(def cell-spec (cell/get-cell! :order/validate))"))
      (is (str/includes? result "(defn approx="))
      (is (str/includes? result "(deftest test-basic")))))

;; =============================================================
;; Manifest assembly
;; =============================================================

(deftest assemble-manifest-test
  (testing "simple linear manifest"
    (let [result (codegen/assemble-manifest
                   {:id :order/placement
                    :ns-prefix "order"
                    :steps [{:name "parse-request"
                             :doc "Parse incoming request"
                             :input-schema {:raw-request :map}
                             :output-schema {:order-id :string}}
                            {:name "validate"
                             :doc "Validate order"
                             :input-schema {:order-id :string}
                             :output-schema {:valid? :boolean}}]
                    :edges {"parse-request" ":validate"
                            "validate" ":end"}})]
      (is (str/includes? result ":order/placement"))
      (is (str/includes? result ":start"))
      (is (str/includes? result ":order/parse-request"))
      (is (str/includes? result ":order/validate")))))
