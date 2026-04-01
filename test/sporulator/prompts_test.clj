(ns sporulator.prompts-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [sporulator.prompts :as prompts]))

;; =============================================================
;; Fix tier selection
;; Translated from Go graduated_fix_test.go
;; =============================================================

(deftest fix-tier-test
  (testing "attempt mapping"
    (is (= :standard (prompts/fix-tier 0)))
    (is (= :standard (prompts/fix-tier -1)))
    (is (= :standard (prompts/fix-tier 1)))
    (is (= :narrowed (prompts/fix-tier 2)))
    (is (= :fresh (prompts/fix-tier 3)))
    (is (= :fresh (prompts/fix-tier 4)))))

;; =============================================================
;; First failing test extraction
;; =============================================================

(deftest extract-first-failing-test-test
  (testing "single failure"
    (let [output "Testing example.test\n\nFAIL in (test-basic-case) (test.clj:10)\nexpected: (= 42 (:result result))\n  actual: (not (= 42 0))\n\nRan 3 tests containing 5 assertions.\n1 failures, 0 errors."
          result (prompts/extract-first-failing-test output)]
      (is (str/includes? result "FAIL in (test-basic-case)"))
      (is (str/includes? result "expected:"))))

  (testing "multiple failures extracts first"
    (let [output "Testing example.test\n\nFAIL in (test-first) (test.clj:10)\nexpected: 1\n  actual: 0\n\nFAIL in (test-second) (test.clj:20)\nexpected: 2\n  actual: 0\n\nRan 2 tests containing 2 assertions.\n2 failures, 0 errors."
          result (prompts/extract-first-failing-test output)]
      (is (str/includes? result "test-first"))
      (is (not (str/includes? result "test-second")))))

  (testing "error instead of failure"
    (let [output "Testing example.test\n\nERROR in (test-broken) (RT.java:1373)\nexpected: something\n  actual: java.lang.NullPointerException\n\nRan 1 tests containing 1 assertions.\n0 failures, 1 errors."
          result (prompts/extract-first-failing-test output)]
      (is (str/includes? result "ERROR in (test-broken)"))))

  (testing "all pass returns empty"
    (is (= "" (prompts/extract-first-failing-test
                "Testing example.test\n\nRan 3 tests containing 5 assertions.\n0 failures, 0 errors."))))

  (testing "empty output returns empty"
    (is (= "" (prompts/extract-first-failing-test "")))))

;; =============================================================
;; Graduated fix prompt building
;; =============================================================

(deftest graduated-fix-prompt-test
  (testing "tier 1 standard includes code, brief, test output"
    (let [prompt (prompts/build-graduated-fix-prompt
                   {:test-output "FAIL in (test-x)"
                    :test-code "(deftest test-x ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "Does Y" :schema "{:input {} :output {}}"}
                    :cell-id ":x/y"
                    :attempt 1
                    :max-attempts 5
                    :graph-context "## Workflow Position\n..."})]
      (is (str/includes? prompt "(fn [r d] {})"))
      (is (str/includes? prompt "Does Y"))
      (is (str/includes? prompt "FAIL in (test-x)"))))

  (testing "tier 1 with graph context"
    (let [graph-ctx "## Workflow Position\n\n**Receives data from:**\n- :order/validate"
          prompt (prompts/build-graduated-fix-prompt
                   {:test-output "FAIL in (test-x)"
                    :test-code "(deftest test-x ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "Does Y" :schema "{:input {} :output {}}"
                            :context graph-ctx}
                    :cell-id ":x/y"
                    :attempt 1
                    :max-attempts 3
                    :graph-context graph-ctx})]
      (is (str/includes? prompt ":order/validate"))
      (is (str/includes? prompt "(fn [r d] {})"))))

  (testing "tier 2 narrowed focuses on first failure"
    (let [test-output "Testing example.test\n\nFAIL in (test-first) (test.clj:10)\nexpected: 42\n  actual: 0\n\nFAIL in (test-second) (test.clj:20)\nexpected: 99\n  actual: 0\n\nRan 2 tests.\n2 failures."
          prompt (prompts/build-graduated-fix-prompt
                   {:test-output test-output
                    :test-code "(deftest test-first ...)\n(deftest test-second ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "Does Y"}
                    :cell-id ":x/y"
                    :attempt 2
                    :max-attempts 3})]
      (is (str/includes? prompt "test-first"))
      (is (str/includes? prompt "Focus on fixing this specific failure first"))))

  (testing "tier 3 fresh start"
    (let [prompt (prompts/build-graduated-fix-prompt
                   {:test-output "FAIL in (test-x)"
                    :test-code "(deftest test-x ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "Does Y" :schema "{:input {} :output {}}"}
                    :cell-id ":x/y"
                    :attempt 3
                    :max-attempts 3})]
      (is (str/includes? prompt "from scratch"))
      (is (str/includes? prompt "(deftest test-x ...)"))
      (is (str/includes? prompt "Does Y")))))

;; =============================================================
;; All tiers include essential elements
;; =============================================================

(deftest all-tiers-include-essentials-test
  (let [base {:test-output "FAIL in (test-x)\nexpected: 1\n  actual: 0\n\nRan 1 tests.\n1 failures."
              :test-code "(deftest test-x ...)"
              :impl-code "(fn [r d] {})"
              :brief {:id ":x/y" :doc "Does Y" :schema "{}"}
              :cell-id ":x/y"
              :max-attempts 5}]
    (doseq [attempt [1 2 3]]
      (let [prompt (prompts/build-graduated-fix-prompt (assoc base :attempt attempt))]
        (testing (str "attempt " attempt " includes REQUIRE instruction")
          (is (str/includes? prompt ";; REQUIRE:")))
        (testing (str "attempt " attempt " includes CRITICAL cell ID")
          (is (str/includes? prompt "CRITICAL: The cell ID is :x/y")))
        (testing (str "attempt " attempt " includes ns exclusion")
          (is (str/includes? prompt "Do NOT include (ns")))))))

(deftest fix-prompt-conditional-math-rules-test
  (testing "fix prompt omits math rules for string-only schema"
    (let [prompt (prompts/build-fix-prompt
                   {:test-output "FAIL"
                    :test-code "(deftest t ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "test" :schema "{:input [:map [:name :string]]}"}
                    :cell-id ":x/y"
                    :attempt 1
                    :max-attempts 3})]
      (is (not (str/includes? prompt "NUMERICAL PRECISION")))))
  (testing "fix prompt includes math rules for double schema"
    (let [prompt (prompts/build-fix-prompt
                   {:test-output "FAIL"
                    :test-code "(deftest t ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "test" :schema "{:input [:map [:price :double]]}"}
                    :cell-id ":x/y"
                    :attempt 1
                    :max-attempts 3})]
      (is (str/includes? prompt "NUMERICAL PRECISION")))))

(deftest narrowed-fallback-to-expanded-test
  (testing "no FAIL pattern falls back to standard"
    (let [prompt (prompts/build-graduated-fix-prompt
                   {:test-output "Compilation error: cannot find symbol"
                    :test-code "(deftest test-x ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "Does Y" :schema "{}"}
                    :cell-id ":x/y"
                    :attempt 2
                    :max-attempts 3
                    :graph-context "## Workflow Position\n..."})]
      (is (not (str/includes? prompt "Focus on fixing this specific failure first"))))))

(deftest fix-prompt-attempt-info-test
  (testing "includes attempt N/M format"
    (let [prompt (prompts/build-fix-prompt
                   {:test-output "FAIL"
                    :test-code "(deftest t ...)"
                    :impl-code "(fn [r d] {})"
                    :brief {:id ":x/y" :doc "test"}
                    :cell-id ":x/y"
                    :attempt 3
                    :max-attempts 5})]
      (is (str/includes? prompt "attempt 3/5")))))

;; =============================================================
;; Conditional math precision rules
;; =============================================================

(deftest needs-math-precision-test
  (testing "double schema needs math precision"
    (is (true? (prompts/needs-math-precision? "{:input [:map [:x :double]]}"))))
  (testing "int schema needs math precision"
    (is (true? (prompts/needs-math-precision? "{:input [:map [:count :int]]}"))))
  (testing "string-only schema does not need math precision"
    (is (false? (prompts/needs-math-precision? "{:input [:map [:handle :string]]}"))))
  (testing "generic map does not need math precision"
    (is (false? (prompts/needs-math-precision? "{:input [:map]}"))))
  (testing "empty schema does not need math precision"
    (is (false? (prompts/needs-math-precision? "{}"))))
  (testing "mixed schema with double needs math precision"
    (is (true? (prompts/needs-math-precision?
                 "{:input [:map [:name :string] [:price :double]]}"))))
  (testing ":integer schema needs math precision"
    (is (true? (prompts/needs-math-precision? "{:input [:map [:n :integer]]}"))))
  (testing "nil returns false"
    (is (false? (prompts/needs-math-precision? nil)))))

;; =============================================================
;; Model-aware fix tiers
;; =============================================================

;; =============================================================
;; Output format template in cell-prompt
;; =============================================================

(deftest cell-prompt-contains-template-test
  (testing "cell-prompt includes defcell template structure"
    (is (str/includes? prompts/cell-prompt "(ns <cell-namespace>"))
    (is (str/includes? prompts/cell-prompt "(cell/defcell <cell-id>"))
    (is (str/includes? prompts/cell-prompt ":doc"))
    (is (str/includes? prompts/cell-prompt "(fn [resources data]"))))

(deftest fix-tier-for-model-test
  (testing "deepseek starts narrowed on attempt 1 (case-insensitive)"
    (is (= :narrowed (prompts/fix-tier-for-model 1 "deepseek-chat")))
    (is (= :narrowed (prompts/fix-tier-for-model 1 "deepseek-coder")))
    (is (= :narrowed (prompts/fix-tier-for-model 1 "DeepSeek-V3")))
    (is (= :narrowed (prompts/fix-tier-for-model 1 "DEEPSEEK-REASONER"))))
  (testing "deepseek goes fresh on attempt 2"
    (is (= :fresh (prompts/fix-tier-for-model 2 "deepseek-chat"))))
  (testing "non-deepseek uses standard tiers"
    (is (= :standard (prompts/fix-tier-for-model 1 "claude-sonnet-4-20250514")))
    (is (= :narrowed (prompts/fix-tier-for-model 2 "gpt-4o"))))
  (testing "nil model uses standard tiers"
    (is (= :standard (prompts/fix-tier-for-model 1 nil)))))
