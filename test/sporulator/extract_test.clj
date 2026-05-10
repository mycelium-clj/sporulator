(ns sporulator.extract-test
  (:require [clojure.test :refer [deftest is testing]]
            [sporulator.extract :as extract]))

;; =============================================================
;; Code block extraction (markdown fence parsing)
;; Translated from Go extract_test.go
;; =============================================================

(deftest extract-code-blocks-test
  (testing "four backticks"
    (is (= ["(ns my.ns)"]
           (extract/extract-code-blocks "````clojure\n(ns my.ns)\n````\n"))))

  (testing "multiple blocks"
    (is (= ["(def a 1)" "{:key :val}"]
           (extract/extract-code-blocks
             "First:\n```clojure\n(def a 1)\n```\nSecond:\n```edn\n{:key :val}\n```\n"))))

  (testing "multiline content"
    (is (= ["(ns my.ns\n  (:require [foo.bar]))\n\n(defn hello []\n  \"world\")"]
           (extract/extract-code-blocks
             "```clojure\n(ns my.ns\n  (:require [foo.bar]))\n\n(defn hello []\n  \"world\")\n```")))))

(deftest strip-fence-markers-test
  (testing "leading fence"
    (is (= "(ns my.ns)\n(def x 1)"
           (extract/strip-fence-markers "```clojure\n(ns my.ns)\n(def x 1)"))))

  (testing "both fences"
    (is (= "(ns my.ns)"
           (extract/strip-fence-markers "```clojure\n(ns my.ns)\n```"))))

  (testing "four backticks"
    (is (= "(ns my.ns)"
           (extract/strip-fence-markers "````clojure\n(ns my.ns)\n````"))))

  (testing "no fences"
    (is (= "(ns my.ns)"
           (extract/strip-fence-markers "(ns my.ns)")))))

(deftest extract-first-code-block-test
  (testing "no code block returns nil"
    (is (nil? (extract/extract-first-code-block
                "We are given that there is an EOF while reading error.\nLet's count the parentheses..."))))

  (testing "fallback to ns form"
    (let [result (extract/extract-first-code-block
                   "Here is the fix:\n(ns my.ns\n  (:require [mycelium.cell :as cell]))\n\n(cell/defcell :my/cell {} (fn [_ d] d))")]
      (is (some? result))
      (is (clojure.string/starts-with? result "(ns ")))))

;; =============================================================
;; Paren balancing (recovery for truncated LLM output)
;; =============================================================

(deftest balance-parens-test
  (testing "balanced - no change"
    (is (= "(defn foo [] (+ 1 2))"
           (extract/balance-parens "(defn foo [] (+ 1 2))"))))

  (testing "missing one paren"
    (let [input "(defn foo [] (+ 1 2)"
          result (extract/balance-parens input)]
      (is (= 1 (- (count result) (count input))))))

  (testing "missing two parens"
    (let [input "(defn foo [] (+ 1 2"
          result (extract/balance-parens input)]
      (is (= 2 (- (count result) (count input))))))

  (testing "with strings - parens inside strings ignored"
    (is (= "(defn foo [] (str \"hello (world\"))"
           (extract/balance-parens "(defn foo [] (str \"hello (world\"))")))))

(deftest truncated-test
  (testing "balanced code is not truncated"
    (is (not (extract/truncated? "(defn foo [] (+ 1 2))"))))

  (testing "unbalanced code is truncated"
    (is (extract/truncated? "(defn foo [] (+ 1 2"))))

;; =============================================================
;; Heuristic checks
;; =============================================================

(deftest looks-like-clojure-test
  (testing "explanation text is not clojure"
    (is (not (extract/looks-like-clojure? "We are given that..."))))

  (testing "ns form"
    (is (extract/looks-like-clojure? "(ns my.ns)")))

  (testing "defn form"
    (is (extract/looks-like-clojure? "(defn foo [] 1)")))

  (testing "cell/defcell form"
    (is (extract/looks-like-clojure? "  (cell/defcell :my/cell {} (fn [_ d] d))"))))

;; =============================================================
;; Form extraction (uses Clojure reader - structured, not string-based)
;; =============================================================

(deftest read-all-forms-test
  (testing "reads multiple forms"
    (is (= ['(defn a [] 1) '(defn b [] 2)]
           (extract/read-all-forms "(defn a [] 1)\n(defn b [] 2)"))))

  (testing "returns nil for malformed code"
    (is (nil? (extract/read-all-forms "(defn a [] (+ 1"))))

  (testing "returns nil for blank input"
    (is (nil? (extract/read-all-forms "")))
    (is (nil? (extract/read-all-forms nil)))))

(deftest extract-fn-body-test
  (testing "simple fn"
    (is (= '(fn [resources data] {:result 1})
           (extract/extract-fn-body "(fn [resources data] {:result 1})"))))

  (testing "with helpers - picks fn, not defn"
    (is (= '(fn [resources data] (helper (:x data)))
           (extract/extract-fn-body "(defn helper [x] (* x 2))\n\n(fn [resources data] (helper (:x data)))"))))

  (testing "nested fn - picks last top-level fn"
    (is (= '(fn [resources data] data)
           (extract/extract-fn-body "(defn pred [x] (fn [y] (= x y)))\n\n(fn [resources data] data)"))))

  (testing "no fn returns nil"
    (is (nil? (extract/extract-fn-body "(defn foo [] 42)"))))

  (testing "fn inside string literal is ignored"
    (is (= '(fn [resources data] data)
           (extract/extract-fn-body "(defn helper [] (str \"(fn \")) (fn [resources data] data)"))))

  (testing "fn inside string only returns nil"
    (is (nil? (extract/extract-fn-body "(defn helper [] (str \"(fn [x] x\"))")))))

(deftest extract-helpers-test
  (testing "with helpers"
    (is (= ['(defn round2 [x] (.doubleValue x))]
           (extract/extract-helpers "(defn round2 [x] (.doubleValue x))\n\n(fn [r d] d)"))))

  (testing "no helpers returns nil"
    (is (nil? (extract/extract-helpers "(fn [r d] d)"))))

  (testing "multiple helpers"
    (is (= ['(defn a [] 1) '(defn b [] 2)]
           (extract/extract-helpers "(defn a [] 1)\n(defn b [] 2)\n\n(fn [r d] d)"))))

  (testing "fn inside string not treated as boundary"
    (is (= ['(defn helper [] (str "(fn "))]
           (extract/extract-helpers "(defn helper [] (str \"(fn \"))\n\n(fn [r d] d)")))))

;; =============================================================
;; REQUIRE comment extraction
;; =============================================================

(deftest extract-extra-requires-test
  (testing "single require"
    (is (= ['[clojure.string :as str]]
           (extract/extract-extra-requires ";; REQUIRE: [clojure.string :as str]\n\n(fn [r d] d)"))))

  (testing "multiple requires"
    (is (= ['[clojure.string :as str] '[clojure.set :as set]]
           (extract/extract-extra-requires
             ";; REQUIRE: [clojure.string :as str]\n;; REQUIRE: [clojure.set :as set]\n\n(fn [r d] d)"))))

  (testing "no requires returns empty"
    (is (= [] (extract/extract-extra-requires "(fn [r d] d)")))))

;; =============================================================
;; Self-review corrections
;; =============================================================

(deftest extract-self-review-corrections-test
  (testing "all verified returns nil"
    (is (nil? (extract/extract-self-review-corrections
                "CORRECT: test-basic — values match spec\nCORRECT: test-edge — edge case is fine\n\nALL TESTS VERIFIED"))))

  (testing "corrections with deftest code block"
    (is (some? (extract/extract-self-review-corrections
                 "WRONG: test-basic — expected 82.67 but should be 85.08\n\nCorrected:\n```clojure\n(deftest test-basic\n  (is (= 85.08 result)))\n```"))))

  (testing "no deftest in code block returns nil"
    (is (nil? (extract/extract-self-review-corrections
                "Here's a helper:\n```clojure\n(defn round2 [x] x)\n```"))))

  (testing "no code block and no verified marker returns nil"
    (is (nil? (extract/extract-self-review-corrections
                "I think the tests look fine overall.")))))

;; =============================================================
;; Defcell extraction
;; =============================================================

(deftest extract-defcell-test
  (testing "defcell in code block"
    (let [response "Here's the cell:\n```clojure\n(cell/defcell :my/cell {:input {:x :int} :output {:y :int}} (fn [_ d] {:y (* 2 (:x d))}))\n```"
          result (extract/extract-defcell response)]
      (is (some? result))
      (is (= 'cell/defcell (first result)))
      (is (= :my/cell (second result)))))

  (testing "defcell in raw text"
    (let [result (extract/extract-defcell "(cell/defcell :my/cell {} (fn [_ d] d))")]
      (is (some? result))
      (is (= 'cell/defcell (first result)))))

  (testing "no defcell returns nil"
    (is (nil? (extract/extract-defcell "Just some explanation text")))))

(deftest extract-all-defcells-test
  (testing "multiple defcells"
    (let [response "```clojure\n(cell/defcell :a/one {} (fn [_ d] d))\n(cell/defcell :b/two {} (fn [_ d] d))\n```"
          result (extract/extract-all-defcells response)]
      (is (= 2 (count result)))
      (is (= :a/one (second (first result))))
      (is (= :b/two (second (second result)))))))

(deftest extract-cell-source-parts-test
  (testing "handler-only cell (no helpers)"
    (let [src "(ns app.cells.foo (:require [mycelium.cell :as cell]))\n\n(cell/defcell :foo/bar\n  {:doc \"test\" :input [:map] :output [:map]}\n  (fn [resources data] data))\n"
          parts (extract/extract-cell-source-parts src)]
      (is (some? (:handler parts)))
      (is (re-find #"\(fn \[resources data\]" (:handler parts)))
      (is (nil? (:helpers parts)))))

  (testing "cell with helpers"
    (let [src "(ns app.cells.foo (:require [mycelium.cell :as cell]))\n\n(defn doubled [x] (* x 2))\n(defn squared [x] (* x x))\n\n(cell/defcell :foo/bar\n  {:doc \"\" :input [:map] :output [:map]}\n  (fn [_ data] {:n (doubled (:n data))}))\n"
          parts (extract/extract-cell-source-parts src)]
      (is (some? (:handler parts)))
      (is (re-find #"doubled" (:handler parts)))
      (is (some? (:helpers parts)))
      (is (re-find #"defn doubled" (:helpers parts)))
      (is (re-find #"defn squared" (:helpers parts)))))

  (testing "blank or nil source"
    (is (nil? (extract/extract-cell-source-parts nil)))
    (is (nil? (extract/extract-cell-source-parts "")))
    (is (nil? (extract/extract-cell-source-parts "   "))))

  (testing "non-defcell source returns nil handler but no error"
    (let [parts (extract/extract-cell-source-parts "(defn foo [] 1)")]
      (is (nil? (:handler parts))))))
