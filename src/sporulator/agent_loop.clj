(ns sporulator.agent-loop
  "Interactive tool-use dispatch agent for cell implementation.
   The agent works in a small virtual filesystem of three named buffers
   (handler.clj, helpers.clj, test.clj) and drives DISPATCH → REVIEW
   transitions via native LLM tool_calls. The session itself carries the
   conversation history; agent state only tracks domain data."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as str]
            [sporulator.codegen :as codegen]
            [sporulator.code-graph :as cg]
            [sporulator.eval :as ev]
            [sporulator.llm :as llm]
            [sporulator.mcp-bridge :as mcp]
            [sporulator.source-gen :as source-gen]
            [sporulator.store :as store]
            [sporulator.tool-registry :as tools]))

(def system-prompt
  "You are implementing a Mycelium cell in Clojure.

## The contract (immutable)
The cell brief is the architect's contract. You must satisfy it:
- :doc      — what the cell does
- :schema   — input/output Malli schema
- :requires — resources injected at runtime

Use get_spec to read the contract. You cannot change it.

## Your workspace
You work in three editable files:
- handler.clj — must contain a single (fn [resources data] ...) form
- helpers.clj — top-level defn / def forms used by the handler
- test.clj    — tests (deftest forms); pre-populated with the test contract

test.clj starts with a generated test contract. You can refine it as you
learn more about the problem (add cases, fix wrong expectations) — but the
final implementation must still satisfy the architect's :schema and :doc.

## Calling convention — read carefully
The handler signature is `(fn [resources data] ...)`.
- `resources` is a map. The keys are the cell's `:requires` keywords.
  Their values are the **real runtime objects** (a JDBC DataSource for `:db`,
  an HTTP client for `:http`, etc.) — NOT mock maps that fake framework
  functions as keyed entries. To use a database resource, call its real API
  directly, e.g. `(next.jdbc/execute! db [...])` — do not write
  `((:execute! db) ...)` or similar.
- `data` is a flat Clojure map whose keys match the cell's `:input` schema
  *directly*. The schema `{:handle :string}` means the handler receives
  `{:handle \"alice\"}` — NOT `{:input {:handle \"alice\"}}`. Never destructure
  via `(:input data)`.

## Files
- read_file / write_file / edit_file / list_files — read and edit source
- write_file overwrites entirely
- edit_file replaces one exact substring (set replace_all=true for all)
- handler.clj contains ONLY the (fn [resources data] ...) form. Do NOT
  write `(ns ...)` or `(cell/defcell ...)` — the harness assembles those.

## Verification + introspection
- run_tests — assemble + evaluate + run tests in test.clj
- eval — try a Clojure expression in the REPL
- lint — clj-kondo on handler.clj
- get_callers / get_callees / graph_impact / graph_path — call-graph queries
  over YOUR functions in this cell
- list_functions / list_ns / inspect_ns — REPL introspection

## Finishing
- complete — declare done. The harness re-runs tests as a final check; if
  they pass, the cell is finalized. If they fail, you keep working.
- give_up — bail out with a reason if the task isn't achievable.

## You're working in a live REPL — use it
Every time you write_file, the harness reloads the cell into a real
running JVM. The functions you just wrote are immediately callable.
Use `eval` like you would a Clojure REPL session:

- Try a helper against a sample input: `(my-helper {:n 1})` — does it
  return what you expected? If not, you don't need run_tests to know
  the helper is wrong.
- Exercise the handler directly:
    `((:handler (mycelium.cell/get-cell! :your/cell-id))
      {:db ds} {:k \"v\"})`
  faster than running the whole test suite for a single case.
- For `:db` cells, set up a real `next.jdbc/get-datasource` against an
  in-memory sqlite, create the table the test uses, and try your INSERT
  / SELECT / UPDATE there. JDBC has subtle key-shape and return-shape
  defaults; experimenting beats guessing.
- `inspect_ns`, `list_functions`, `get_callers`, etc. are also there
  when you want to navigate.

Use whatever rhythm fits. write→test, eval→write→test, eval→eval→
write→test — all valid. The tools exist because reasoning is faster
when grounded in actual JVM feedback.

## When tests keep failing — step back, don't double down
If `run_tests` has failed several times in a row, the harness will
append a reframe hint to the failure output. Take it seriously: the
canonical stagnation pattern is refining the same approach instead of
considering whether the approach itself is wrong. Some prompts to use:
- What hidden assumption am I making?
- Can I decompose this into smaller helpers I can verify in isolation?
- Is the test contract itself sensible for the brief? You can edit
  test.clj if a test asserts something the brief doesn't promise.
- Try a completely different shape (different INSERT pattern, different
  data layout, different control flow) before fighting with the
  current attempt for one more cycle.

You're done when complete succeeds.")

;; =============================================================
;; Helpers — buffer parsing and file-shaped utilities
;; =============================================================

(defn- ->keyword
  [id]
  (cond
    (keyword? id) id
    (and (string? id) (str/starts-with? id ":")) (keyword (subs id 1))
    (string? id) (keyword id)
    :else (keyword (str id))))

(defn- parse-handler-buffer
  "Parses handler.clj content as a single Clojure form. Returns the form,
   or nil if the buffer is empty/blank/unparseable."
  [content]
  (when-not (str/blank? content)
    (try (binding [*read-eval* false] (read-string content))
         (catch Exception _ nil))))

(defn- parse-helpers-buffer
  "Parses helpers.clj content as a flat sequence of top-level forms.
   Returns a vector of forms, or nil if unparseable. Empty content → []."
  [content]
  (if (str/blank? content)
    []
    (let [src (str "[" content "]")]
      (try (binding [*read-eval* false] (vec (read-string src)))
           (catch Exception _ nil)))))

(defn- helpers-buffer-error
  "Returns an error string if `content` is invalid for helpers.clj — i.e.
   contains a top-level (ns ...) form that would clash with the assembled
   cell's own namespace. Returns nil when the buffer is well-formed."
  [content]
  (when-let [forms (parse-helpers-buffer content)]
    (when (some (fn [f] (and (seq? f) (= 'ns (first f)))) forms)
      (str "helpers.clj must be a flat list of (defn ...)/(def ...) forms — "
           "it must NOT contain a top-level (ns ...) declaration. The cell's "
           "ns and :require list are generated automatically from the brief; "
           "put any extra requires beside the helpers as a top-level "
           "(require '[...]) at use site, or hand the helper its dep "
           "explicitly."))))

(defn- format-with-line-numbers
  "Renders content with `   N\\tline` prefixes (cat -n style)."
  [content]
  (if (str/blank? content)
    "(empty)"
    (->> (str/split-lines content)
         (map-indexed (fn [i line] (format "%5d\t%s" (inc i) line)))
         (str/join "\n"))))

(defn- count-occurrences
  "Counts non-overlapping occurrences of sub in s."
  [^String s ^String sub]
  (if (str/blank? sub)
    0
    (loop [n 0 from 0]
      (let [idx (.indexOf s sub from)]
        (if (neg? idx)
          n
          (recur (inc n) (+ idx (count sub))))))))

(defn- rebuild-graph!
  "Re-parses handler.clj and helpers.clj from state's files and rebuilds the
   cell-graph. Tolerates parse failures (skips silently)."
  [state]
  (let [files   (:files state)
        handler (parse-handler-buffer (get files "handler.clj"))
        helpers (parse-helpers-buffer (get files "helpers.clj"))]
    (when (and handler (some? helpers))
      (cg/rebuild-cell-graph! (:cell-graph state) handler helpers))))

(defn- after-source-write
  "Marks tests stale and rebuilds the call-graph if a source file changed."
  [state path]
  (let [s (assoc state :last-tests-green? false)]
    (when (#{"handler.clj" "helpers.clj"} path)
      (rebuild-graph! s))
    s))

;; =============================================================
;; Session state
;; =============================================================

(def ^:private file-paths #{"handler.clj" "helpers.clj" "test.clj"})

(defn init-session
  "Creates the initial session state from orchestration context.

   Edit-mode opts (used when regenerating an existing cell):
     :initial-handler — pre-populate handler.clj with the prior green source
                        (the bare `(fn [resources data] ...)` form as a string)
     :initial-helpers — pre-populate helpers.clj with prior helper defns
     :change-summary  — string describing how the contract has changed since
                        the previous green implementation; surfaced in the
                        initial prompt so the agent knows what to revise"
  [{:keys [client cell-id cell-ns brief test-code schema-parsed
           turn-budget on-event on-chunk
           store run-id project-path base-ns task
           initial-handler initial-helpers change-summary]
    :or   {turn-budget 15
           on-event    (fn [_])
           on-chunk    (fn [_])}}]
  (let [cell-id-kw (->keyword (or cell-id (:id brief)))
        session    (llm/create-session (str "agent:" cell-id-kw) system-prompt)
        files      {"handler.clj" (or initial-handler "")
                    "helpers.clj" (or initial-helpers "")
                    "test.clj"    (or test-code "")}
        state      {:phase             :working
                    :turn              0
                    :turn-budget       turn-budget
                    :session           session
                    :cell-id           cell-id-kw
                    :cell-ns           (or cell-ns "")
                    :brief             brief
                    :test-code         test-code
                    :test-ns-name      (when test-code (second (re-find #"\(ns\s+(\S+)" test-code)))
                    :schema-parsed     schema-parsed
                    :cell-graph        (atom #{})
                    :files             files
                    :green-files       nil
                    :last-tests-green? false
                    :last-turn-shape   :initial
                    :change-summary    change-summary
                    :client            client
                    :on-event          on-event
                    :on-chunk          on-chunk
                    :store             store
                    :run-id            run-id
                    :project-path      project-path
                    :base-ns           base-ns
                    :task              task}]
    ;; If we pre-loaded source, prime the call graph from it so the
    ;; graph tools work on turn 1.
    (when (or (seq (or initial-handler "")) (seq (or initial-helpers "")))
      (rebuild-graph! state))
    state))

(defn- emit
  [state phase status & {:as extra}]
  (when-let [f (:on-event state)]
    (let [base {"phase" phase "status" status}
          converted (reduce-kv (fn [m k v]
                                 (assoc m (str/replace (name k) "-" "_") v))
                               base extra)]
      (f converted))))

(defn- mcp-ctx
  "Builds the context map mcp-bridge expects from the file-shaped agent state."
  [state]
  (let [files       (:files state)
        green-files (:green-files state)]
    {:brief           (:brief state)
     :task            (:task state)
     :test-code       (:test-code state)
     :current-handler (get files "handler.clj")
     :current-tests   (get files "test.clj")
     :green-handler   (get green-files "handler.clj")
     :green-tests     (get green-files "test.clj")
     :helpers         (or (parse-helpers-buffer (get files "helpers.clj")) [])}))

;; =============================================================
;; Prompts
;; =============================================================

(defn- dispatched-output?
  "True if `output` is a dispatched-output map (lite-form map values or
   Malli-form vector values), keyed by transition labels. Mirrors
   `sporulator.orchestrator/dispatched-output?` — duplicated here to
   avoid a require cycle (orchestrator already requires agent-loop)."
  [output]
  (and (map? output) (seq output)
       (every? (fn [v] (or (and (map? v) (seq v))
                            (vector? v)))
               (vals output))))

(defn- dispatched-handler-block
  "Implementor-side guidance for cells with a dispatched output schema."
  [output]
  (let [labels (mapv (comp name key) output)
        first-label (first labels)
        first-keys (let [v (val (first output))]
                     (cond
                       (map? v)    (vec (keys v))
                       (vector? v) []
                       :else       []))
        sample-key (or (first first-keys) :result)]
    (str "## Dispatched output — read carefully\n"
         "This cell's `:output` schema is dispatched; mycelium routes\n"
         "downstream based on which keys appear in your handler's flat\n"
         "return map. Possible transitions: "
         (str/join " | " (map #(str "`" % "`") labels))
         ".\n\n"
         "Your handler returns a flat map matching ONE of the per-transition\n"
         "sub-schemas in `:output`. Pick the shape based on what your work\n"
         "produced. **Never wrap the result under the transition label.**\n\n"
         "Right shape (assuming `:" first-label "` carries `" sample-key "`):\n"
         "  `{" sample-key " ...}`     ← flat\n"
         "Wrong shape:\n"
         "  `{:" first-label " {" sample-key " ...}}`   ← do NOT do this\n")))

(defn- requires-db-handler?
  "True if `requires` includes a :db / 'db'-named resource — gates the
   JDBC handler-shape hints in the initial prompt."
  [requires]
  (boolean
    (some (fn [r] (= "db" (name (keyword (name r))))) requires)))

(defn- jdbc-handler-shape-block
  "Implementor-side guidance for cells whose handler reads or writes via
   next.jdbc. Phase 4 validation 2026-04-26: persist-entry stagnated
   not on workflow discipline (Fix H raised its write count) but on
   uncertainty about how to extract the inserted id from JDBC's
   response. Empirical exploration via `eval` burned the budget. The
   patterns below let the agent pick a known-good shape directly."
  []
  (str "## JDBC handler patterns (`:db` resource is in scope)\n"
       "The runtime hands you a real `next.jdbc` DataSource as `(:db resources)`.\n"
       "Use `next.jdbc/...` (or any alias you `(:require ...)` in helpers.clj)\n"
       "directly — do not wrap or mock it.\n\n"
       "**Insert returning the new id:** SQLite supports a RETURNING clause,\n"
       "so a single `execute-one!` call gives you the row back. Default builder\n"
       "returns namespaced keyword keys (`{:guestbook/id 1}`); pass\n"
       "`{:builder-fn rs/as-unqualified-maps}` if you'd rather work with\n"
       "unqualified `:id` — pick one and stay consistent with however the\n"
       "test reads rows back:\n"
       "  ```\n"
       "  (let [row (next.jdbc/execute-one!\n"
       "              db [\"INSERT INTO t (a, b) VALUES (?, ?) RETURNING id\" a b]\n"
       "              {:builder-fn next.jdbc.result-set/as-unqualified-maps})]\n"
       "    {:id (:id row)})  ;; or wrap (try ... (catch Exception e {:error (.getMessage e)}))\n"
       "  ```\n\n"
       "**Failure path:** wrap the JDBC call in `try`/`catch Exception` and\n"
       "return `{:error (.getMessage e)}` on the failure transition. NOT NULL\n"
       "violations, UNIQUE conflicts, etc. throw `SQLException`s.\n\n"
       "**Don't** hand-roll `last_insert_rowid()` lookups, alter the table,\n"
       "or `(eval ...)` your way to the right call shape — pick one of the\n"
       "two patterns above, write it, and let `run_tests` confirm.\n"))

(defn- render-initial-prompt
  [state]
  (let [brief        (:brief state)
        cell-id      (:cell-id state)
        schema       (:schema brief)
        schema-parsed (:schema-parsed state)
        task         (:task state)
        requires     (:requires brief)
        edit-mode?   (or (seq (get-in state [:files "handler.clj"]))
                         (seq (get-in state [:files "helpers.clj"])))
        change-sum   (:change-summary state)
        output       (:output schema-parsed)
        dispatched-block (when (dispatched-output? output)
                           (dispatched-handler-block output))
        jdbc-block       (when (requires-db-handler? requires)
                           (jdbc-handler-shape-block))]
    (str "Implement cell `" cell-id "`.\n\n"
         "**Task:** " (or task "Implement the cell to pass all tests.") "\n\n"
         "**Schema:**\n" (or schema "{}") "\n\n"
         "**Resources:** "
         (if (seq requires) (str/join ", " (map name requires)) "none")
         "\n\n"
         (when dispatched-block
           (str dispatched-block "\n"))
         (when jdbc-block
           (str jdbc-block "\n"))
         (if edit-mode?
           (str "This cell already has an implementation that previously "
                "passed tests. handler.clj and helpers.clj are pre-loaded "
                "with that source. test.clj has the (possibly new) test "
                "contract.\n\n"
                (when change-sum
                  (str change-sum "\n\n"))
                "Read handler.clj and helpers.clj to see what's there. "
                "Decide what still applies under the new contract and what "
                "needs revising. Reuse what fits; replace what doesn't. "
                "Run the tests to verify, then call complete.")
           (str "test.clj is pre-populated with the test contract. Refine it "
                "if you spot issues, write helpers.clj and handler.clj, run "
                "tests, and call complete when you're satisfied.")))))

;; =============================================================
;; Test assembly + eval
;; =============================================================

(defn- wrap-test-code
  [test-code cell-ns cell-id]
  (if (or (nil? test-code) (str/blank? test-code))
    test-code
    (if (re-find #"^[\(\s]*\(ns\s" test-code)
      test-code
      (let [ns-name (str (or cell-ns (str/replace (str cell-id) #"^:" "")) "-test")]
        (str "(ns " ns-name "\n"
             "  (:require [clojure.test :refer [deftest is testing]]))\n\n"
             test-code)))))

(defn- assemble-and-eval
  "Reads handler.clj + helpers.clj + test.clj from state's files, parses,
   assembles, and runs tests. Returns {:status :ok :passed? bool :output str}
   or {:status :error :output reason}."
  [state]
  (let [cell-id       (:cell-id state)
        cell-ns       (:cell-ns state)
        brief         (:brief state)
        schema-parsed (:schema-parsed state)
        files         (:files state)
        handler-src   (get files "handler.clj")
        helpers-src   (get files "helpers.clj")
        test-code     (wrap-test-code (get files "test.clj") cell-ns cell-id)]
    (cond
      (str/blank? handler-src)
      {:status :error :output "handler.clj is empty. Use write_file to create the handler."}

      (str/blank? test-code)
      {:status :error :output "test.clj is empty — no tests to run."}

      :else
      (let [handler (parse-handler-buffer handler-src)
            helpers (parse-helpers-buffer helpers-src)]
        (cond
          (nil? handler)
          {:status :error :output "Could not parse handler.clj — check for syntax errors."}

          (nil? helpers)
          {:status :error :output "Could not parse helpers.clj — check for syntax errors."}

          (not (and (seq? handler) (= 'fn (first handler))))
          {:status :error :output "handler.clj must contain a single (fn [resources data] ...) form."}

          :else
          (let [source (codegen/assemble-cell-source
                         {:cell-ns        cell-ns
                          :cell-id        cell-id
                          :doc            (or (:doc brief) "")
                          :schema         schema-parsed
                          :requires       (mapv keyword (or (:requires brief) []))
                          :extra-requires []
                          :helpers        helpers
                          :fn-body        handler})
                test-src-fixed (if cell-ns
                                 (str/replace test-code
                                   (str "[" cell-ns "]")
                                   (str "[" cell-ns " :as _cell-ns]"))
                                 test-code)
                ;; Remove any pre-existing cell-ns / test-ns vars before
                ;; re-eval'ing the assembled source. Otherwise stale
                ;; deftest vars from PREVIOUS orchestration runs in this
                ;; JVM still trip during run-tests — the agent then sees
                ;; failures from tests no longer in test_body and can't
                ;; reconcile them with the file it wrote. This is the
                ;; bug that masqueraded as the agent failing on simple
                ;; cells (Phase 4 validation 2026-04-26).
                test-ns-name  (second (re-find #"\(ns\s+(\S+)" test-code))
                clear-source  (str
                                (when cell-ns
                                  (str "(when (find-ns '" cell-ns
                                       ") (remove-ns '" cell-ns "))\n"))
                                (when test-ns-name
                                  (str "(when (find-ns '" test-ns-name
                                       ") (remove-ns '" test-ns-name "))\n")))
                combined (str clear-source source "\n\n" test-src-fixed)
                eval-res (ev/eval-code combined)]
            (if (not= :ok (:status eval-res))
              {:status :error :output (str "Eval error: " (:error eval-res))}
              (let [test-ns-name (second (re-find #"\(ns\s+(\S+)" test-code))
                    test-ns      (when test-ns-name (find-ns (symbol test-ns-name)))
                    test-res     (if test-ns
                                   (ev/run-cell-tests test-code)
                                   {:status :error
                                    :output (str "Test namespace not found: " test-ns-name)})]
                (if (not= :ok (:status test-res))
                  {:status :error :output (:output test-res)}
                  {:status   :ok
                   :passed?  (:passed? test-res)
                   :output   (str (:output eval-res) "\n" (:output test-res))
                   :summary  (:summary test-res)
                   :failures (:failures test-res)})))))))))

;; =============================================================
;; Tool dispatch
;; =============================================================

(def ^:private progress-stall-threshold
  "How many consecutive run_tests calls can finish at the same passing
   count before the harness appends a 'you're stalled — rethink' hint.
   The hint is advisory — the agent still chooses what to do."
  3)

(defn- progress-stall-hint
  "Advisory appended to run_tests output when the passing test count has
   plateaued for `n` consecutive runs. Suggests rethinking, decomposing,
   or refining test.clj — does NOT block."
  [n pass total]
  (str "\n\n— Passing count has been stuck at " pass "/" total " for " n
       " consecutive run_tests calls. Tweaking the same approach is not "
       "moving the number. Try one of:\n"
       "- **Rethink the approach.** What hypothesis am I making that\n"
       "  could be wrong? Try a completely different shape — different\n"
       "  data flow, different control structure, different library\n"
       "  primitive — instead of refining the current attempt.\n"
       "- **Decompose.** Can the failing test be made simpler by\n"
       "  pulling the work into a helper you can verify in isolation?\n"
       "  Write the helper, eval it directly with a sample input, and\n"
       "  confirm it returns what you expect before wiring it into the\n"
       "  handler.\n"
       "- **Verify in the REPL.** The cell is loaded; try\n"
       "    ((:handler (mycelium.cell/get-cell! :your-id)) {:db ds} {...})\n"
       "  on the exact input the failing test uses. The actual return\n"
       "  value tells you the gap immediately.\n"
       "- **Refine test.clj.** If the failing test asserts something\n"
       "  the brief doesn't promise, edit it to align with the brief —\n"
       "  the brief is the contract, the tests are derived."))

(defn- format-failure
  "Renders one failure entry from run-cell-tests structured `:failures`
   into a focused 'fix this one' block."
  [{:keys [kind test message expected actual line]}]
  (str (if (= :error kind) "ERROR" "FAIL") " in " test
       (when line (str " (line " line ")"))
       "\n  expected: " (pr-str expected)
       "\n  actual:   " (pr-str actual)
       (when-not (str/blank? message)
         (str "\n  message:  " message))))

(defn- format-run-tests-failure
  "Renders the run_tests result when at least one test failed: shows the
   M/N summary, the first failing test in detail, and a brief reminder
   that one fix at a time is the rhythm. Includes any plateau hint
   based on `pass-count-history`."
  [result history]
  (let [summary (or (:summary result) {})
        pass    (:pass summary 0)
        fail    (:fail summary 0)
        errs    (:error summary 0)
        tests   (:test summary 0)
        first-failure (first (:failures result))
        stall-streak  (let [recent (take-last (inc progress-stall-threshold) history)]
                        (if (and (>= (count recent) (inc progress-stall-threshold))
                                 (apply = recent))
                          (dec (count recent))
                          0))]
    (str "TESTS: " pass "/" tests " passing"
         (when (pos? fail) (str ", " fail " failing"))
         (when (pos? errs) (str ", " errs " errored"))
         "\n\n"
         (if first-failure
           (str (format-failure first-failure)
                "\n\nFix this one, then run_tests again to see the next.")
           ;; Status was :ok-but-not-passed but no structured failure
           ;; (rare path) — fall back to raw output.
           (or (:output result) "Tests failed."))
         (when (pos? stall-streak)
           (progress-stall-hint stall-streak pass tests)))))

(defn- ok  [state msg] {:state state :result (str "ok — " msg)})
(defn- err [state msg] {:state state :result (str "ERROR — " msg)})

(defn- handle-file-read
  [state path]
  (cond
    (not path) (err state "Missing required arg: path")
    (not (file-paths path))
    (err state (str "Unknown file: '" path "'. Available: handler.clj, helpers.clj, test.clj"))
    :else
    (ok state (format-with-line-numbers (get-in state [:files path])))))

(defn- handle-file-write
  [state path content]
  (cond
    (not path)        (err state "Missing required arg: path")
    (nil? content)    (err state "Missing required arg: content")
    (not (file-paths path))
    (err state (str "Unknown file: '" path "'."))
    :else
    (if-let [hint (and (= "helpers.clj" path) (helpers-buffer-error content))]
      (err state hint)
      (let [new-state (-> state
                          (assoc-in [:files path] content)
                          (after-source-write path))]
        (ok new-state (str "wrote " path " (" (count content) " chars)"))))))

(defn- handle-file-edit
  [state {:keys [path old_string new_string replace_all]}]
  (cond
    (not path)            (err state "Missing required arg: path")
    (not old_string)      (err state "Missing required arg: old_string")
    (nil? new_string)     (err state "Missing required arg: new_string")
    (not (file-paths path))
    (err state (str "Unknown file: '" path "'."))
    :else
    (let [content (get-in state [:files path])
          n       (count-occurrences content old_string)]
      (cond
        (zero? n)
        (err state (str "old_string not found in " path "."))

        (and (not replace_all) (> n 1))
        (err state (str "old_string matches " n " times in " path
                        "; pass replace_all=true or include more context to make it unique."))

        :else
        (let [new-content (if replace_all
                            (str/replace content old_string new_string)
                            (str/replace-first content old_string new_string))]
          (if-let [hint (and (= "helpers.clj" path) (helpers-buffer-error new-content))]
            (err state hint)
            (let [new-state (-> state
                                (assoc-in [:files path] new-content)
                                (after-source-write path))]
              (ok new-state (str "edited " path
                                 (when replace_all (str " (" n " replacements)")))))))))))

(defn- handle-file-list
  [state]
  (let [rows (for [path (sort (keys (:files state)))
                   :let [content (get-in state [:files path])
                         lines   (if (str/blank? content)
                                   0
                                   (inc (count (re-seq #"\n" content))))
                         chars   (count content)]]
               (format "  %-12s  %d lines  %d chars" path lines chars))]
    (ok state (str "Files:\n" (str/join "\n" rows)))))

(defn- handle-dispatch-tool
  [state {:keys [name args]}]
  (let [g   @(:cell-graph state)
        ctx (mcp-ctx state)]
    (case name
      :get_spec        (ok state (mcp/get-spec ctx))
      :get_task        (ok state (mcp/get-task ctx))
      :list_functions  (ok state (mcp/list-functions ctx))
      :list_ns         (ok state (mcp/list-loaded-ns ctx))

      :inspect_ns
      (if-let [ns-name (:ns args)]
        (ok state (mcp/inspect-ns ctx ns-name))
        (err state "Missing required arg: ns"))

      :get_callers
      (let [target  (if-let [n (:name args)] (->keyword n) :handler)
            callers (cg/callers g target)]
        (ok state (if (seq callers)
                    (str "Callers of " target ": " (str/join ", " callers))
                    (str "No callers of " target " found within this cell."))))

      :get_callees
      (let [target  (if-let [n (:name args)] (->keyword n) :handler)
            callees (cg/callees g target)]
        (ok state (if (seq callees)
                    (str "Called by " target ": " (str/join ", " callees))
                    (str "No callees from " target " found within this cell."))))

      :graph_impact
      (if-let [target (some-> (:target args) ->keyword)]
        (let [impacted (cg/impact g target)]
          (ok state (if (seq impacted)
                      (str "Impact of changing " target ": " (str/join ", " impacted))
                      (str "No functions affected by " target " within this cell."))))
        (err state "Missing required arg: target"))

      :graph_path
      (let [from (some-> (:from args) ->keyword)
            to   (some-> (:to args) ->keyword)]
        (cond
          (or (nil? from) (nil? to))
          (err state "Missing required args: from and to.")
          :else
          (if-let [p (cg/path g from to)]
            (ok state (str "Path " from " -> " to ": " (str/join " -> " p)))
            (ok state (str "No call path from " from " to " to " within this cell.")))))

      :eval
      (if-let [code (:code args)]
        (let [;; Best-effort: if the cell's current handler.clj parses
              ;; cleanly, assemble + load the full cell source first so
              ;; helpers, the handler, and the cell registry entry are
              ;; all in scope. The agent's eval expression then runs in
              ;; the cell's own namespace, so it can call any helper by
              ;; name and exercise the handler via
              ;;   `((:handler (mycelium.cell/get-cell! :the/cell)) ... )`.
              ;;
              ;; Critical: clear cell-ns before re-evaluating. Otherwise
              ;; defs from a PREVIOUS helpers/handler still resolve here
              ;; and the agent gets false-positive signals from eval
              ;; (e.g. `(foo)` returns 42 even after foo was removed
              ;; from helpers.clj). Same root cause as the run_tests
              ;; bug Q — different surface.
              cell-ns       (:cell-ns state)
              files         (:files state)
              handler-src   (get files "handler.clj")
              helpers-src   (get files "helpers.clj")
              handler-form  (parse-handler-buffer handler-src)
              helpers-forms (parse-helpers-buffer helpers-src)
              eval-src      (if (and (seq cell-ns)
                                     handler-form
                                     (seq? handler-form)
                                     (= 'fn (first handler-form))
                                     (some? helpers-forms))
                              (str "(when (find-ns '" cell-ns
                                   ") (remove-ns '" cell-ns "))\n"
                                   (codegen/assemble-cell-source
                                     {:cell-ns        cell-ns
                                      :cell-id        (:cell-id state)
                                      :doc            (or (:doc (:brief state)) "")
                                      :schema         (:schema-parsed state)
                                      :requires       (mapv keyword
                                                            (or (:requires (:brief state)) []))
                                      :extra-requires []
                                      :helpers        helpers-forms
                                      :fn-body        handler-form})
                                   "\n\n(in-ns '" cell-ns ")\n"
                                   code)
                              code)
              res (ev/eval-code eval-src)]
          (if (= :ok (:status res))
            (ok state (str (pr-str (:result res))
                           (when (seq (:output res)) (str "\n" (:output res)))))
            (err state (:error res))))
        (err state "Missing required arg: code"))

      :read_file   (handle-file-read  state (:path args))
      :write_file  (handle-file-write state (:path args) (:content args))
      :edit_file   (handle-file-edit  state args)
      :list_files  (handle-file-list  state)

      :run_tests
      (let [result    (assemble-and-eval state)
            pass-now  (or (get-in result [:summary :pass]) 0)
            tests-now (or (get-in result [:summary :test]) 0)
            history   (-> (or (:pass-count-history state) [])
                          (conj pass-now)
                          ;; keep enough history to evaluate the stall threshold
                          (->> (take-last (inc progress-stall-threshold))
                               vec))]
        (cond
          (not= :ok (:status result))
          ;; Eval-time failure (cell didn't compile, ns missing, etc.).
          (err (assoc state :pass-count-history history)
               (or (:output result) "Run failed."))

          (:passed? result)
          (let [new-state (assoc state
                            :green-files        (:files state)
                            :last-tests-green?  true
                            :pass-count-history history)]
            (ok new-state
                (str "ALL TESTS PASSED (" pass-now "/" tests-now ").\n\n"
                     "When you're satisfied, call complete to finalize. "
                     "You can also keep iterating — refactor, add tests, etc.")))

          :else
          (err (assoc state
                 :last-tests-green?  false
                 :pass-count-history history)
               (format-run-tests-failure result history))))

      :lint
      (let [handler-src (get-in state [:files "handler.clj"])]
        (cond
          (str/blank? handler-src)
          (err state "handler.clj is empty.")
          :else
          (let [lint-res (ev/lint-code handler-src)]
            (if (seq (:errors lint-res))
              (err state (str "Lint errors:\n"
                              (str/join "\n"
                                (map #(str "- Line " (:line %) ": " (:message %))
                                     (:errors lint-res)))))
              (ok state "No lint errors.")))))

      :check_schema
      (ok state "Schema validation: no runtime schema checking configured.")

      :complete
      (let [result (if (:last-tests-green? state)
                     {:status :ok :passed? true :output "(using last green run)"}
                     (assemble-and-eval state))]
        (cond
          (not= :ok (:status result))
          (err state (str "complete blocked — " (:output result)
                          "\nFix the failure and run_tests again, or give_up."))

          (:passed? result)
          (ok (assoc state
                :green-files       (or (:green-files state) (:files state))
                :last-tests-green? true
                :phase             :done
                :status            :ok)
              "complete: tests green, finalizing.")

          :else
          (err (assoc state :last-tests-green? false)
               (str "complete blocked — tests not green:\n"
                    (or (:output result) "")
                    "\nFix the failure and run_tests again, or give_up."))))

      :give_up
      (ok (assoc state
            :phase :done
            :status :gave_up
            :give-up-reason (or (:reason args) "No reason given"))
          (str "Gave up: " (or (:reason args) "no reason"))))))

(defn- dispatch-tool-call
  "Runs one tool call against the agent state, appends the tool result to
   the LLM session, and returns the new state.

   The agent has free run of every tool: read, write, edit, eval, inspect,
   run_tests, complete, give_up. The harness adds NO blocks or warnings on
   tool selection — orchestration belongs to the model. Advisory feedback
   (e.g. the progress-stall hint inside `format-run-tests-failure`) is
   built into the per-tool handler's response so the model still gets the
   underlying tool output."
  [state {:keys [id name arguments]}]
  (let [tool-name (tools/normalize-tool-name name)
        session   (:session state)]
    (if-not (tools/known-tool? tool-name)
      (do (llm/session-append-tool-result! session id
            (str "ERROR — unknown tool '" (clojure.core/name tool-name) "'."))
          state)
      (let [{new-state :state result :result}
            (handle-dispatch-tool state {:name tool-name :args arguments})]
        (llm/session-append-tool-result! session id result)
        new-state))))

(defn- process-turn
  "One LLM round-trip:
     :initial → send the initial user prompt
     :tools   → continue (tool results were appended last turn)
     :no-tool → nudge with a user message"
  [state]
  (let [client   (:client state)
        on-chunk (:on-chunk state)
        session  (:session state)
        tools    (tools/working-tools)
        shape    (:last-turn-shape state)
        resp     (case shape
                   :initial
                   (llm/session-send-stream session client
                     (render-initial-prompt state) on-chunk
                     :tools tools :tool-choice "auto")

                   :tools
                   (llm/session-continue-stream session client on-chunk
                     :tools tools :tool-choice "auto")

                   :no-tool
                   (llm/session-send-stream session client
                     "Please emit a tool call to make progress."
                     on-chunk :tools tools :tool-choice "auto"))
        tool-calls (:tool-calls resp)]
    (if (seq tool-calls)
      (-> (reduce dispatch-tool-call state tool-calls)
          (assoc :last-turn-shape :tools)
          (update :turn inc))
      (-> state
          (assoc :last-turn-shape :no-tool)
          (update :turn inc)))))

;; =============================================================
;; Finalize
;; =============================================================

(defn- assemble-final-source
  "Builds the cell source string from green-files at finalize time."
  [state]
  (let [brief         (:brief state)
        cell-ns       (:cell-ns state)
        cell-id       (:cell-id state)
        schema-parsed (:schema-parsed state)
        green-files   (:green-files state)
        handler       (parse-handler-buffer (get green-files "handler.clj"))
        helpers       (or (parse-helpers-buffer (get green-files "helpers.clj")) [])]
    (codegen/assemble-cell-source
      {:cell-ns        cell-ns
       :cell-id        cell-id
       :doc            (or (:doc brief) "")
       :schema         schema-parsed
       :requires       (mapv keyword (or (:requires brief) []))
       :extra-requires []
       :helpers        helpers
       :fn-body        handler})))

(defn- finalize
  [state]
  (let [cell-id      (:cell-id state)
        brief        (:brief state)
        store        (:store state)
        project-path (:project-path state)
        base-ns      (:base-ns state)]
    (case (:status state)
      :ok
      (let [source (assemble-final-source state)
            handler-form (parse-handler-buffer
                           (get-in state [:green-files "handler.clj"]))
            ;; Strip the leading colon — the rest of the system stores IDs
            ;; as bare strings (e.g. "guestbook/collect", not ":guestbook/collect").
            store-id (let [s (str cell-id)]
                       (cond-> s (str/starts-with? s ":") (subs 1)))]
        (when store
          (store/save-cell! store
            {:id         store-id
             :handler    source
             :schema     (or (:schema brief) "")
             :doc        (or (:doc brief) "")
             :created-by "agent-loop-tdd"}))
        (when (and project-path base-ns)
          (source-gen/write-cell! project-path base-ns (str cell-id) source)
          (emit state "cell_implement" "file_written" :cell-id cell-id))
        (emit state "cell_implement" "done" :cell-id cell-id)
        {:status  :ok
         :cell-id cell-id
         :code    source
         :raw     (pr-str handler-form)
         :session (:session state)})

      :gave_up
      (do (emit state "cell_implement" "gave_up" :cell-id cell-id
                :reason (:give-up-reason state))
          {:status  :error
           :cell-id cell-id
           :error   (str "Agent gave up: " (:give-up-reason state))
           :session (:session state)})

      :stagnated
      (do (emit state "cell_implement" "stagnated" :cell-id cell-id)
          {:status  :error
           :cell-id cell-id
           :error   (str "Turn budget exhausted after " (:turn state)
                         " turns without complete or give_up.")
           :session (:session state)})

      {:status  :error
       :cell-id cell-id
       :error   (str "Agent ended with status: " (:status state) " after "
                     (:turn state) " turns")
       :session (:session state)})))

(defn run!
  "Main entry point: runs the agent loop for one cell.
   Returns {:status :ok :cell-id :code :session}
        or {:status :error :cell-id :error :session}."
  [opts]
  (let [state (atom (init-session opts))]
    (emit @state "cell_implement" "started" :cell-id (:cell-id @state))
    (loop []
      (let [s @state]
        (cond
          (= :done (:phase s))
          (finalize s)

          (>= (:turn s) (:turn-budget s))
          (do (swap! state assoc :phase :done :status :stagnated)
              (finalize @state))

          :else
          (do (swap! state process-turn)
              (recur)))))))

(defn reflect-on-stagnation
  "Sends a single-turn diagnostic prompt when stagnated.
   Returns the LLM's analysis of why the cell wasn't implemented."
  [client {:keys [cell-id brief session]}]
  (let [prompt (str "You attempted to implement cell `" cell-id "` but "
                    "exhausted the turn budget without getting tests to pass.\n\n"
                    "Cell spec:\n" (:doc brief) "\n"
                    "Schema: " (:schema brief) "\n\n"
                    "Diagnose why the cell wasn't implemented. Was it too "
                    "complex? Was the test contract wrong? Was there a missing "
                    "dependency? Be specific and actionable.")]
    (:content (llm/session-send-stream session client prompt (fn [_])))))
