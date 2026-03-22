(ns sporulator.eval-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sporulator.eval :as ev]
            [sporulator.codegen :as codegen]))

;; =============================================================
;; eval-code
;; =============================================================

(deftest eval-code-basic-test
  (testing "evaluates valid code and returns result"
    (let [r (ev/eval-code "(+ 1 2)")]
      (is (= :ok (:status r)))
      (is (= 3 (:result r)))))

  (testing "captures stdout"
    (let [r (ev/eval-code "(println \"hello\") 42")]
      (is (= :ok (:status r)))
      (is (= 42 (:result r)))
      (is (clojure.string/includes? (:output r) "hello"))))

  (testing "returns error for invalid code"
    (let [r (ev/eval-code "(throw (ex-info \"boom\" {:k 1}))")]
      (is (= :error (:status r)))
      (is (clojure.string/includes? (:error r) "boom"))))

  (testing "returns error for syntax errors"
    (let [r (ev/eval-code "(defn foo")]
      (is (= :error (:status r)))
      (is (some? (:error r))))))

(deftest eval-code-timeout-test
  (testing "times out on long-running code"
    (let [r (ev/eval-code "(Thread/sleep 10000)" :timeout-ms 100)]
      (is (= :timeout (:status r))))))

(deftest eval-code-multiple-forms-test
  (testing "evaluates multiple forms and returns last result"
    (let [r (ev/eval-code "(def ^:private test-x 10) (+ test-x 5)")]
      (is (= :ok (:status r)))
      (is (= 15 (:result r))))))

;; =============================================================
;; instantiate-cell
;; =============================================================

(deftest instantiate-cell-test
  (testing "loads a cell from assembled source and registers it"
    (let [source (codegen/assemble-cell-source
                   {:cell-ns    "sporulator.test.cells.doubler"
                    :cell-id    :test/doubler
                    :doc        "Doubles the input"
                    :schema     {:input {:x :int} :output {:result :int}}
                    :requires   []
                    :helpers    []
                    :fn-body    '(fn [_resources data] {:result (* 2 (:x data))})})
          r (ev/instantiate-cell source :test/doubler)]
      (is (= :ok (:status r)))
      (is (= :test/doubler (:cell-id r)))
      ;; Verify cell is actually callable
      (let [cell (requiring-resolve 'mycelium.cell/get-cell!)
            spec (cell :test/doubler)
            handler (:handler spec)
            result (handler {} {:x 5})]
        (is (= {:result 10} result)))))

  (testing "returns error for invalid source"
    (let [r (ev/instantiate-cell "(ns bad.cell)\n(this-is-broken" :bad/cell)]
      (is (= :error (:status r)))
      (is (some? (:error r))))))

;; =============================================================
;; run-cell-tests
;; =============================================================

(deftest run-cell-tests-test
  (testing "runs tests and returns structured results"
    ;; First instantiate the cell
    (let [cell-source (codegen/assemble-cell-source
                        {:cell-ns    "sporulator.test.cells.adder"
                         :cell-id    :test/adder
                         :doc        "Adds two numbers"
                         :schema     {:input {:a :int :b :int} :output {:sum :int}}
                         :requires   []
                         :helpers    []
                         :fn-body    '(fn [_ data] {:sum (+ (:a data) (:b data))})})
          _ (ev/instantiate-cell cell-source :test/adder)
          test-source (codegen/assemble-test-source
                        {:test-ns  "sporulator.test.cells.adder-test"
                         :cell-ns  "sporulator.test.cells.adder"
                         :cell-id  :test/adder
                         :test-body "(deftest adder-test
  (testing \"adds correctly\"
    (is (= {:sum 3} (handler {} {:a 1 :b 2})))))"})
          r (ev/run-cell-tests test-source)]
      (is (= :ok (:status r)))
      (is (:passed? r))
      (is (= 0 (get-in r [:summary :fail])))
      (is (pos? (get-in r [:summary :test])))))

  (testing "captures failing tests"
    ;; Reuse :test/adder but write a failing test
    (let [test-source (codegen/assemble-test-source
                        {:test-ns  "sporulator.test.cells.adder-fail-test"
                         :cell-ns  "sporulator.test.cells.adder"
                         :cell-id  :test/adder
                         :test-body "(deftest adder-fail-test
  (testing \"intentionally wrong\"
    (is (= {:sum 99} (handler {} {:a 1 :b 2})))))"})
          r (ev/run-cell-tests test-source)]
      (is (= :ok (:status r)))
      (is (not (:passed? r)))
      (is (pos? (get-in r [:summary :fail]))))))

;; =============================================================
;; verify-cell-contract
;; =============================================================

(deftest verify-cell-contract-test
  (testing "returns ok for registered cell"
    ;; Ensure cell is loaded (tests may run in any order)
    (ev/instantiate-cell
      (codegen/assemble-cell-source
        {:cell-ns  "sporulator.test.cells.contract_check"
         :cell-id  :test/contract-check
         :doc      "Test cell for contract verification"
         :schema   {:input {:x :int} :output {:y :int}}
         :requires []
         :helpers  []
         :fn-body  '(fn [_ data] {:y (:x data)})})
      :test/contract-check)
    (let [r (ev/verify-cell-contract :test/contract-check)]
      (is (= :ok (:status r)))
      (is (some? (:cell r)))))

  (testing "returns error for unregistered cell"
    (let [r (ev/verify-cell-contract :nonexistent/cell)]
      (is (= :error (:status r)))
      (is (some? (:error r))))))
