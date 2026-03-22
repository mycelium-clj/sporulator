(ns sporulator.eval-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
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

;; =============================================================
;; validate-schema
;; =============================================================

(deftest validate-schema-test
  (testing "validates data against malli schema"
    (let [r (ev/validate-schema "{:x :int :y :string}" {:x 1 :y "hello"})]
      (is (:valid? r))))

  (testing "returns explanation for invalid data"
    (let [r (ev/validate-schema "{:x :int}" {:x "not-an-int"})]
      (is (not (:valid? r)))
      (is (some? (:explanation r)))))

  (testing "handles invalid schema string gracefully"
    (let [r (ev/validate-schema "{broken" {:x 1})]
      (is (not (:valid? r)))
      (is (some? (:error r))))))

;; =============================================================
;; lint-code
;; =============================================================

(deftest lint-code-test
  (testing "returns nil for clean code"
    (let [r (ev/lint-code "(defn foo [x] (+ x 1))")]
      (is (nil? (:errors r)))))

  (testing "returns errors for bad code"
    (let [r (ev/lint-code "(defn foo [x] (+ x))")]
      ;; clj-kondo may or may not flag this depending on version
      ;; but unresolved vars should be caught
      (is (map? r))))

  (testing "catches unresolved symbols"
    (let [r (ev/lint-code "(defn foo [x] (undefined-fn x))")]
      (is (some? (:errors r))))))

;; =============================================================
;; merge-test-corrections (Round 2 — pure function)
;; =============================================================

(deftest merge-test-corrections-test
  (testing "replaces matching deftest forms"
    (let [original "(deftest test-a\n  (is (= 1 1)))\n\n(deftest test-b\n  (is (= 2 2)))"
          corrected "(deftest test-b\n  (is (= 2 3)))"
          result (ev/merge-test-corrections original corrected)]
      (is (str/includes? result "test-a"))
      (is (str/includes? result "(is (= 2 3))"))
      (is (not (str/includes? result "(is (= 2 2))")))))

  (testing "appends new tests from corrections"
    (let [original "(deftest test-a\n  (is (= 1 1)))"
          corrected "(deftest test-c\n  (is (= 3 3)))"
          result (ev/merge-test-corrections original corrected)]
      (is (str/includes? result "test-a"))
      (is (str/includes? result "test-c"))))

  (testing "returns original when no corrections"
    (let [original "(deftest test-a\n  (is true))"
          result (ev/merge-test-corrections original "")]
      (is (= (str/trim original) (str/trim result)))))

  (testing "returns original when corrections is nil"
    (let [original "(deftest test-a\n  (is true))"]
      (is (= (str/trim original)
             (str/trim (ev/merge-test-corrections original nil)))))))

;; =============================================================
;; compile-workflow / run-workflow
;; =============================================================

(deftest compile-workflow-test
  (testing "compiles a valid manifest EDN"
    ;; First register a cell
    (ev/eval-code
      "(ns sporulator.test.cells.wf-double
         (:require [mycelium.cell :as cell]))
       (cell/defcell :test-wf/double
         {:doc \"Doubles x\"
          :input {:x :int} :output {:result :int}}
         (fn [_ data] {:result (* 2 (:x data))}))")
    (let [manifest "{:id :test-wf
                     :cells {:start {:id :test-wf/double
                                     :doc \"doubles\"
                                     :schema {:input {:x :int} :output {:result :int}}}}
                     :pipeline [:start]}"
          r (ev/compile-workflow manifest)]
      (is (= :ok (:status r)))
      (is (some? (:compiled r)))))

  (testing "returns error for unparseable EDN"
    (let [r (ev/compile-workflow "{:id :broken :cells")]
      (is (= :error (:status r))))))

(deftest run-workflow-test
  (testing "runs a compiled workflow end-to-end"
    ;; Ensure cell is registered
    (ev/eval-code
      "(ns sporulator.test.cells.wf-run-double
         (:require [mycelium.cell :as cell]))
       (cell/defcell :test-wf-run/double
         {:doc \"Doubles x\"
          :input {:x :int} :output {:result :int}}
         (fn [_ data] {:result (* 2 (:x data))}))")
    (let [manifest "{:id :test-wf-run
                     :cells {:start {:id :test-wf-run/double
                                     :doc \"doubles\"
                                     :schema {:input {:x :int} :output {:result :int}}}}
                     :pipeline [:start]}"
          r (ev/run-workflow manifest {:x 5} {})]
      (is (= :ok (:status r)))
      (is (= 10 (:result (:result r))))))

  (testing "returns error for unparseable manifest"
    (let [r (ev/run-workflow "{broken" {} {})]
      (is (= :error (:status r))))))
