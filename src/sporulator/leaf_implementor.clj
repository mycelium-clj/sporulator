(ns sporulator.leaf-implementor
  "Implements one tree node — a single (defn name [params] body) — by
   asking the LLM for the body and verifying it compiles + passes the
   `:test-body` deftests the decomposer planned for this node.

   Per the decomposer's contract, every node carries a runnable
   `:test-body` string. That deftest source uses real resources
   (in-memory sqlite, real sample data) — no placeholder symbols.
   The leaf-implementor eval-loads the leaf alongside its deps and
   the test-body, runs the tests, and reports.

   The harness here is intentionally narrower than `agent-loop/run!`:
   leaves are small (≤20 lines per the decomposer's contract) and
   shouldn't need a tool-using loop. We make at most one repair
   attempt; if the leaf still doesn't pass we hand it back unverified
   and let the cell-level run catch the gap."
  (:require [clojure.string :as str]
            [sporulator.eval :as ev]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]))

;; -----------------------------------------------------------------
;; Source assembly for the leaf eval/test step
;; -----------------------------------------------------------------

(defn- leaf-eval-source
  "Builds an eval-loadable source string:
     (when (find-ns 'leaf-ns) (remove-ns 'leaf-ns))
     (ns leaf-ns (:require [next.jdbc] [next.jdbc.result-set :as rs]))
     <accumulated helpers>
     <the leaf's defn>
     <optional deftests>

   The clear-ns ensures stale leaves from earlier batches don't
   resolve in this leaf's namespace (Q-shape staleness rule)."
  [{:keys [leaf-ns helpers-source defn-source tests-source]}]
  (str
    (when (seq leaf-ns)
      (str "(when (find-ns '" leaf-ns ") (remove-ns '" leaf-ns "))\n"))
    "(ns " leaf-ns "\n"
    "  (:require [clojure.test :refer [deftest is testing]]\n"
    "            [next.jdbc :as jdbc]\n"
    "            [next.jdbc.result-set :as rs]\n"
    "            [clojure.string :as str]))\n\n"
    (when (and helpers-source (seq helpers-source))
      (str helpers-source "\n\n"))
    defn-source
    "\n\n"
    (or tests-source "")))

(defn- compile-leaf
  "Eval-loads the leaf source. Returns
     {:status :ok}
   or
     {:status :error :error <message>}."
  [src]
  (let [r (ev/eval-code src)]
    (if (= :ok (:status r))
      {:status :ok}
      {:status :error :error (or (:error r) "(unknown eval error)")})))

(defn- maybe-run-tests
  "If `tests-source` is non-empty AND the source compiles, run the
   deftests. Returns
     {:tested? true :passed? bool :failures [...]}
   or
     {:tested? false}                                    (no tests)
     {:tested? true :passed? false :compile-error msg}"
  [src tests-source leaf-ns]
  (if (str/blank? tests-source)
    {:tested? false}
    (let [;; Need to construct a test-only source with the right (ns ...)
          ;; for run-cell-tests' ns-extraction regex.
          test-only-src (str "(ns " leaf-ns "\n"
                             "  (:require [clojure.test :refer [deftest is testing]]))\n\n"
                             tests-source)
          ;; First load the leaf source so the fns exist.
          load-r (ev/eval-code src)]
      (if (not= :ok (:status load-r))
        {:tested? true :passed? false :compile-error (:error load-r)}
        (let [test-r (ev/run-cell-tests test-only-src)]
          {:tested?  true
           :passed?  (boolean (:passed? test-r))
           :summary  (:summary test-r)
           :failures (:failures test-r)})))))

;; -----------------------------------------------------------------
;; The leaf agent — single LLM call + at most one repair
;; -----------------------------------------------------------------

(def ^:private leaf-system-prompt
  "You are implementing a single Clojure function as a leaf in a tree
of small composable helpers.

You will be given:
- The function's name, doc, and parameter list.
- The bodies of any helpers it depends on (already implemented).
- The deftest source the harness will run against your function. Make
  it pass.

Your reply MUST be exactly one (defn ...) form for the requested
function. No (ns ...), no extra requires, no commentary. The
function must:
- Use the exact name and parameter list given.
- Be implementable in 20 lines or less.
- Use only `next.jdbc`, `next.jdbc.result-set` (as `rs`),
  `clojure.string` (as `str`), and any helpers passed in. The harness
  has those required for you.

Wrap your reply in ```clojure ... ``` fences.")

(def ^:private jdbc-leaf-hints
  "## JDBC patterns (your function takes a `next.jdbc` DataSource)
**Insert returning the new id.** SQLite supports a RETURNING clause,
so a single `execute-one!` gives you the inserted row back. Default
builder returns namespaced keyword keys (e.g. `{:todos/id 1}`); pass
`{:builder-fn rs/as-unqualified-maps}` if you want unqualified `:id`.
Your test asserts on unqualified keys, so use the unqualified
builder. Canonical shape:

```
(let [row (next.jdbc/execute-one!
            db
            [\"INSERT INTO t (a, b) VALUES (?, ?) RETURNING id\" a b]
            {:builder-fn rs/as-unqualified-maps})]
  {:id (:id row)})
```

**Failure path.** Wrap the JDBC call in `try` / `catch Exception` and
return `{:error (.getMessage e)}`. NOT NULL violations, missing
tables, etc. throw `SQLException`s.

**Don't** roll your own `last_insert_rowid()` / `:return-keys true`
pattern — the test reads back `:id` as an unqualified int and those
shapes don't deliver one consistently. Use RETURNING + the builder-fn.")

(defn- needs-jdbc-hints?
  "Heuristic: does this leaf touch next.jdbc?
   - params include a 'db'-named arg, OR
   - test-body mentions next.jdbc/get-datasource."
  [{:keys [params test-body]}]
  (or (some #{"db" "ds" "datasource"} (map str (or params [])))
      (and test-body (str/includes? test-body "next.jdbc"))))

(defn- leaf-prompt
  [{:keys [name doc params test-body] :as node} helpers-source]
  (str "## Function to implement\n\n"
       "Name: `" name "`\n"
       "Doc:  " doc "\n"
       "Params: `[" (str/join " " params) "]`\n\n"
       (when (and helpers-source (seq helpers-source))
         (str "## Helpers in scope\n\n"
              "```clojure\n" helpers-source "\n```\n\n"))
       "## Tests your function must pass\n\n"
       "```clojure\n" test-body "\n```\n\n"
       (when (needs-jdbc-hints? node)
         (str jdbc-leaf-hints "\n\n"))
       "Return ONLY the (defn " name " [...] ...) form."))

(defn- extract-defn
  "Pulls a single (defn name ...) form from the LLM reply. Returns
   the source string or nil."
  [content target-name]
  (let [block (or (extract/extract-first-code-block content) content)]
    (when (str/includes? block (str "(defn " target-name))
      block)))

(defn implement-leaf
  "Drives the LLM to produce a (defn name ...) for one tree node.

   `node` is a decomposer node ({:name :doc :params :test-body
   :depends-on}); `helpers-source` is the accumulated source of
   already-implemented siblings/leaves.

   Returns:
     {:status :ok :defn-src \"...\" :tested? bool :passed? bool ...}
     {:status :gave-up :reason \"...\" :raw-content \"...\"}"
  [client node {:keys [helpers-source on-event]
                :or {helpers-source ""
                     on-event       (fn [_])}}]
  (let [{:keys [name test-body]} node
        leaf-ns      (str "tree-leaf." name)
        tests-source test-body
        session      (llm/create-session (str "leaf:" name) leaf-system-prompt)
        emit-attempt-result
        (fn [label result]
          ;; Surface the actual outcome of each attempt — caller (UI,
          ;; orchestrator, debugger) can inspect why a leaf failed
          ;; without having to re-run it. Includes the exact compile
          ;; error message or the first failing assertion's expected
          ;; vs. actual.
          (on-event
            (cond-> {"phase"   "leaf_implement"
                     "status"  (case (:status result)
                                 :ok            "ok"
                                 :compile-error "compile_error"
                                 :test-failed   "test_failed"
                                 "error")
                     "leaf"    name
                     "label"   label}
              (:error result)    (assoc "error" (subs (str (:error result))
                                                       0 (min 600 (count (str (:error result))))))
              (:defn-src result) (assoc "defn-src"
                                        (subs (:defn-src result)
                                              0 (min 600 (count (:defn-src result)))))
              (and (= :test-failed (:status result))
                   (first (:failures result)))
              (assoc "first-failure"
                     (let [f (first (:failures result))]
                       {"test"     (:test f)
                        "expected" (pr-str (:expected f))
                        "actual"   (pr-str (:actual f))
                        "message"  (:message f)})))))
        try-once     (fn [user-msg label]
                       (on-event {"phase"  "leaf_implement"
                                  "status" "attempt"
                                  "leaf"   name
                                  "label"  label})
                       (let [resp     (llm/session-send session client user-msg
                                        :temperature 0.2)
                             defn-src (extract-defn (:content resp) name)
                             result (if-not defn-src
                                      {:status :error :error "LLM did not return a (defn ...) form"
                                       :raw    (:content resp)}
                                      (let [src (leaf-eval-source
                                                  {:leaf-ns        leaf-ns
                                                   :helpers-source helpers-source
                                                   :defn-source    defn-src
                                                   :tests-source   tests-source})
                                            compile-r (compile-leaf src)]
                                        (cond
                                          (not= :ok (:status compile-r))
                                          {:status :compile-error
                                           :defn-src defn-src
                                           :error    (:error compile-r)}

                                          tests-source
                                          (let [t (maybe-run-tests src tests-source leaf-ns)]
                                            (if (:passed? t)
                                              {:status :ok :defn-src defn-src
                                               :tested? true :passed? true
                                               :summary (:summary t)}
                                              {:status :test-failed
                                               :defn-src defn-src
                                               :failures (:failures t)
                                               :compile-error (:compile-error t)}))

                                          :else
                                          {:status :ok :defn-src defn-src
                                           :tested? false})))]
                         (emit-attempt-result label result)
                         result))
        attempt1 (try-once (leaf-prompt node helpers-source) "first")]
    (cond
      (= :ok (:status attempt1))
      attempt1

      :else
      (let [feedback
            (cond
              (= :compile-error (:status attempt1))
              (str "Your function does not compile. Error:\n"
                   (:error attempt1) "\n\n"
                   "Return a corrected (defn " name " ...) form. "
                   "Same name, same params, no (ns ...).")

              (= :test-failed (:status attempt1))
              (str "Your function compiles but a test failed:\n"
                   (when-let [f (first (:failures attempt1))]
                     (str "  expected: " (pr-str (:expected f))
                          "\n  actual:   " (pr-str (:actual f)))) "\n\n"
                   "Return a corrected (defn " name " ...) form.")

              :else
              (str "Your reply didn't include a (defn " name " ...) form. "
                   "Reply with ONLY the (defn " name " ...) form, in a ```clojure``` fence."))
            attempt2 (try-once feedback "repair")]
        (if (= :ok (:status attempt2))
          attempt2
          ;; Both attempts failed — return diagnostic detail so the
          ;; caller can see exactly what the agent produced and why it
          ;; was rejected. (Earlier this path lost the test output and
          ;; the error message.)
          {:status   :gave-up
           :leaf     name
           :reason   (str "Two attempts didn't produce a passing leaf. "
                          "First: " (:status attempt1)
                          ", second: " (:status attempt2))
           :defn-src (or (:defn-src attempt2) (:defn-src attempt1))
           :first-attempt-error  (:error attempt1)
           :first-failure        (first (:failures attempt1))
           :second-attempt-error (:error attempt2)
           :second-failure       (first (:failures attempt2))})))))
