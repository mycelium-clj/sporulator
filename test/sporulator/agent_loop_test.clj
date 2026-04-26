(ns sporulator.agent-loop-test
  "End-to-end and behavior tests for the file-shaped agent loop.
   Drives sporulator.agent-loop/run! with mock LLM responses and asserts
   on the resulting cell source, the LLM session transcript, and the
   final agent state."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.data.json :as json]
            [sporulator.agent-loop :as agent-loop]
            [sporulator.codegen :as codegen]
            [sporulator.llm :as llm]))

;; =============================================================
;; Mock infrastructure
;; =============================================================

(defmacro ^:private with-llm-mock
  [mock-form & body]
  `(let [m# ~mock-form]
     (with-redefs [llm/session-send-stream     m#
                   llm/session-continue-stream m#]
       ~@body)))

(defn- assistant-msg [resp]
  (cond-> {:role "assistant" :content (:content resp)}
    (seq (:tool-calls resp))
    (assoc :tool_calls
           (mapv (fn [tc] {:id (:id tc) :type "function"
                           :function {:name (:name tc)
                                      :arguments (:arguments-json tc)}})
                 (:tool-calls resp)))))

(defn- tc
  "Builds a canned tool-call response. args is a Clojure map (encoded to JSON)."
  ([tool-name] (tc tool-name {}))
  ([tool-name args]
   (let [args-json (json/write-str args)]
     {:content       nil
      :finish-reason "tool_calls"
      :tool-calls    [{:id             (str "call_" (gensym))
                       :name           (name (keyword tool-name))
                       :arguments-json args-json
                       :arguments      args}]})))

(defn- mock-stream
  "Returns a fn bound to both session-send-stream and session-continue-stream.
   Walks `responses` in order, advancing one entry per call. After exhausting
   the list, returns the last response forever (so the loop fails gracefully
   instead of hanging on a real network call)."
  [responses]
  (let [counter (atom 0)]
    (fn [session _client & rest-args]
      (let [user-msg (when (string? (first rest-args)) (first rest-args))]
        (when user-msg
          (swap! (:messages session) conj {:role "user" :content user-msg}))
        (let [idx  @counter
              resp (if (< idx (count responses))
                     (nth responses idx)
                     (last responses))]
          (swap! counter inc)
          (swap! (:messages session) conj (assistant-msg resp))
          resp)))))

;; =============================================================
;; Brief + tests fixture
;; =============================================================

(def ^:private double-cell-brief
  {:id       :math/double
   :doc      "Doubles the :n field of input data."
   :schema   "{:input {:n :long} :output {:n :long}}"
   :requires []
   :context  ""})

(def ^:private double-test-code
  (codegen/assemble-test-source
    {:test-ns   "math.double-test"
     :cell-ns   "math.double"
     :cell-id   :math/double
     :test-body (str "(deftest doubles-n\n"
                     "  (is (= {:n 4} (handler {} {:n 2})))\n"
                     "  (is (= {:n 0} (handler {} {:n 0}))))\n")}))

(def ^:private base-opts
  {:cell-id       :math/double
   :cell-ns       "math.double"
   :brief         double-cell-brief
   :test-code     double-test-code
   :schema-parsed {:input  [:map [:n :long]]
                   :output [:map [:n :long]]}
   :on-event      (fn [_])
   :on-chunk      (fn [_])
   :turn-budget   20})

(defn- run-with [responses]
  (with-llm-mock (mock-stream responses)
    (agent-loop/run! base-opts)))

(defn- last-tool-result-for
  "Finds the most recent tool result message in the session whose preceding
   assistant turn called `tool-name`. Useful for asserting the body of a
   read_file / list_files response, which the agent never sees in the final
   :code but does see during the run."
  [session tool-name]
  (let [msgs (-> session :messages deref)]
    (loop [i (dec (count msgs))]
      (when (>= i 0)
        (let [m (nth msgs i)]
          (if (= "tool" (:role m))
            (let [call-id (:tool_call_id m)
                  tcs     (->> msgs
                               (mapcat (fn [m] (when (= "assistant" (:role m))
                                                 (:tool_calls m)))))
                  match   (some #(when (= call-id (:id %)) %) tcs)]
              (if (= (name tool-name) (get-in match [:function :name]))
                (:content m)
                (recur (dec i))))
            (recur (dec i))))))))

;; =============================================================
;; End-to-end: handler-only happy path
;; =============================================================

(deftest happy-path-handler-only-test
  (testing "agent writes handler.clj, run_tests goes green, approve"
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])]
      (is (= :ok (:status result)))
      (is (= :math/double (:cell-id result)))
      (is (string? (:code result)))
      ;; Generated source includes the cell macro and our handler shape
      (is (str/includes? (:code result) "(cell/defcell"))
      (is (str/includes? (:code result) "(fn [_ data]"))
      (is (str/includes? (:code result) "(* 2 (:n data))")))))

;; =============================================================
;; End-to-end: helpers.clj + handler.clj
;; =============================================================

(deftest jdbc-handler-block-rendered-for-db-cells-test
  (testing "the initial prompt for a :db-requiring cell includes JDBC handler patterns"
    ;; Phase 4 fix I: persist-entry stagnated trying to find the right
    ;; JDBC pattern for "insert returning id". The implementor prompt
    ;; now ships ready-to-use patterns so the agent doesn't burn its
    ;; budget exploring via eval.
    (let [cell-state {:cell-id   :guestbook/persist-entry
                      :brief     {:doc "Inserts a row." :requires [:db]}
                      :schema-parsed {:input  [:map [:k :string]]
                                      :output {:success [:map [:id :int]]
                                               :failure [:map [:error :string]]}}
                      :files     {"handler.clj" "" "helpers.clj" "" "test.clj" ""}
                      :task      nil
                      :change-summary nil}
          prompt     (#'agent-loop/render-initial-prompt cell-state)]
      (is (str/includes? prompt "JDBC handler patterns")
          "JDBC handler-shape block must render for :db cells")
      (is (or (str/includes? prompt "RETURNING")
              (str/includes? prompt "execute-one!"))
          "must show the canonical insert-returning-id shape")))

  (testing "non-db cells do NOT get the JDBC handler block"
    (let [cell-state {:cell-id   :x/y
                      :brief     {:doc "Pure cell" :requires []}
                      :schema-parsed {:input  [:map [:n :int]]
                                      :output [:map [:m :int]]}
                      :files     {"handler.clj" "" "helpers.clj" "" "test.clj" ""}
                      :task      nil
                      :change-summary nil}
          prompt     (#'agent-loop/render-initial-prompt cell-state)]
      (is (not (str/includes? prompt "JDBC handler patterns"))))))

(deftest no-block-or-warning-on-tool-choice-test
  (testing "the dispatcher does NOT warn or block on tool selection"
    ;; Phase 4 validation 2026-04-26: earlier guard variants (Fix H/J)
    ;; appended warnings or refused execution after consecutive non-
    ;; progress tool calls. That was an anti-pattern — the agent uses
    ;; eval / read / inspect to learn the environment. The dispatcher
    ;; now executes every known tool transparently and lets the model
    ;; orchestrate.
    (let [result (run-with
                   [(tc :read_file {:path "handler.clj"})
                    (tc :read_file {:path "test.clj"})
                    (tc :read_file {:path "helpers.clj"})
                    (tc :list_files)
                    (tc :inspect_ns {:ns "clojure.core"})
                    (tc :read_file {:path "handler.clj"})
                    (tc :write_file {:path "handler.clj" :content "(fn [_ d] {:n 1})"})
                    (tc :run_tests)
                    (tc :complete)])
          msgs   (-> result :session :messages deref)
          contents (mapv :content (filter #(= "tool" (:role %)) msgs))]
      (is (not-any? #(str/includes? % "STAGNATION GUARD") contents)
          "no STAGNATION GUARD warnings should appear")
      (is (not-any? #(str/includes? % "BLOCKED") contents)
          "no BLOCKED messages should appear"))))

(deftest reframe-hint-after-repeated-test-failures-test
  (testing "after 3 consecutive run_tests failures, the harness appends a reframe hint"
    ;; The hint is informational — the agent gets the actual test
    ;; output AND the suggestion to step back. It can ignore or
    ;; follow. No block, no censoring of the underlying error.
    (let [bad-handler "(fn [_ data] {:n (* 2 (:m data))})"
          result (run-with
                   [(tc :write_file {:path "handler.clj" :content bad-handler})
                    (tc :run_tests)                  ;; 1st failure
                    (tc :write_file {:path "handler.clj" :content bad-handler})
                    (tc :run_tests)                  ;; 2nd failure (still no hint)
                    (tc :write_file {:path "handler.clj" :content bad-handler})
                    (tc :run_tests)                  ;; 3rd failure → hint
                    (tc :give_up {:reason "demo"})])
          msgs   (-> result :session :messages deref)
          contents (mapv :content (filter #(= "tool" (:role %)) msgs))]
      ;; tool-result indexes align with the tc order above.
      (is (str/includes? (nth contents 5) "failed 3 times in a row")
          "3rd consecutive run_tests failure should mention the streak")
      (is (not (str/includes? (nth contents 1) "failed 3 times in a row"))
          "1st failure should NOT include the hint yet")
      (is (not (str/includes? (nth contents 3) "failed 3 times in a row"))
          "2nd failure should NOT include the hint yet"))))

(deftest system-prompt-encourages-repl-experimentation-test
  (testing "system-prompt frames eval as a first-class REPL affordance"
    ;; Earlier prompt told the agent to AVOID eval/inspect (anti-
    ;; pattern). New prompt explicitly encourages them, including
    ;; running DB calls and exercising the handler directly.
    (let [p agent-loop/system-prompt]
      (is (str/includes? p "live REPL")
          "must frame the workspace as a live REPL")
      (is (or (str/includes? p "experiment")
              (str/includes? p "Try a helper"))
          "must encourage trying things in eval")
      (is (str/includes? p "step back")
          "must tell the agent to reframe when stuck"))))

(deftest helpers-rejects-ns-form-test
  (testing "writing helpers.clj with a top-level (ns ...) returns an error"
    ;; helpers.clj is a flat sequence of (defn ...)/(def ...) forms;
    ;; codegen splices them into the assembled cell source above the
    ;; (cell/defcell ...). An (ns ...) declaration in helpers.clj
    ;; conflicts with the assembled file's own ns and confused agents
    ;; into multi-turn fixup spirals during Phase 4 validation. Reject
    ;; up front with a clear error so the agent recovers immediately.
    (let [bad-helpers (str "(ns app.cells.x.helpers\n"
                           "  (:require [next.jdbc :as jdbc]))\n"
                           "(defn doubled [x] (* x 2))")
          good-handler "(fn [_ data] {:n (doubled (:n data))})"
          result (run-with
                   [(tc :write_file {:path "helpers.clj" :content bad-helpers})
                    (tc :write_file {:path "helpers.clj" :content "(defn doubled [x] (* x 2))"})
                    (tc :write_file {:path "handler.clj" :content good-handler})
                    (tc :run_tests)
                    (tc :complete)])
          session (:session result)
          msgs    (-> session :messages deref)
          first-tool-result (->> msgs
                                 (filter #(= "tool" (:role %)))
                                 first
                                 :content)]
      (is (= :ok (:status result))
          "agent recovers after the rejection on attempt 1")
      (is (some #(re-find #"helpers\.clj" %) [first-tool-result])
          "the rejection mentions helpers.clj")
      (is (or (str/includes? (or first-tool-result "") "(ns")
              (str/includes? (or first-tool-result "") "ns"))
          "the rejection explains the (ns ...) issue"))))

(deftest helpers-then-handler-test
  (testing "agent writes a helper, then a handler that calls it, then runs"
    (let [result (run-with
                   [(tc :write_file {:path    "helpers.clj"
                                     :content "(defn doubled [x] (* x 2))"})
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (doubled (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          code   (:code result)]
      (is (= :ok (:status result)))
      ;; Final source includes the helper definition
      (is (str/includes? code "(defn doubled"))
      ;; Helper appears BEFORE the cell/defcell so it's in scope
      (is (< (.indexOf code "(defn doubled")
             (.indexOf code "(cell/defcell"))
          "helper must be defined before the cell"))))

;; =============================================================
;; edit_file behaviour
;; =============================================================

(deftest edit-file-replaces-unique-substring-test
  (testing "edit_file with a unique substring works in place"
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 1 (:n data))})"})
                    ;; Fix the multiplier from 1 to 2 via edit_file
                    (tc :edit_file  {:path        "handler.clj"
                                     :old_string  "* 1"
                                     :new_string  "* 2"})
                    (tc :run_tests)
                    (tc :complete)])]
      (is (= :ok (:status result)))
      (is (str/includes? (:code result) "(* 2 (:n data))")))))

(deftest edit-file-ambiguous-error-test
  (testing "edit_file fails when old_string matches multiple times without replace_all"
    (let [result (run-with
                   [(tc :write_file {:path    "helpers.clj"
                                     :content (str "(defn a [x] (+ x 1))\n"
                                                   "(defn b [x] (+ x 1))\n")})
                    ;; Try to change "+ x 1" — matches twice
                    (tc :edit_file  {:path       "helpers.clj"
                                     :old_string "+ x 1"
                                     :new_string "+ x 2"})
                    ;; Recover: write the handler and run
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          session (:session result)
          msgs    (-> session :messages deref)
          edit-result (some (fn [m]
                              (when (and (= "tool" (:role m))
                                         (str/includes? (:content m) "matches 2 times"))
                                (:content m)))
                            msgs)]
      (is (= :ok (:status result)) "agent should still finish after recovering")
      (is (some? edit-result) "ambiguous-edit error message must surface as tool result")
      (is (str/includes? edit-result "replace_all") "error must hint at replace_all"))))

(deftest edit-file-replace-all-test
  (testing "edit_file with replace_all=true replaces every occurrence"
    (let [result (run-with
                   [(tc :write_file {:path    "helpers.clj"
                                     :content (str "(defn a [x] (+ x 1))\n"
                                                   "(defn b [x] (+ x 1))\n")})
                    (tc :edit_file  {:path        "helpers.clj"
                                     :old_string  "+ x 1"
                                     :new_string  "+ x 1 0"
                                     :replace_all true})
                    ;; Now the handler that uses neither helper, just runs the
                    ;; tests against its own arithmetic
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          code   (:code result)]
      (is (= :ok (:status result)))
      (is (str/includes? code "(+ x 1 0)"))
      (is (= 2 (count (re-seq #"\+ x 1 0" code)))
          "both helpers should be edited"))))

(deftest edit-file-not-found-error-test
  (testing "edit_file fails with a clear message when old_string is absent"
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :edit_file  {:path       "handler.clj"
                                     :old_string "this-string-does-not-exist"
                                     :new_string "x"})
                    (tc :run_tests)
                    (tc :complete)])
          msgs   (-> result :session :messages deref)
          err-msg (some (fn [m]
                          (when (and (= "tool" (:role m))
                                     (str/includes? (:content m) "not found in handler.clj"))
                            (:content m)))
                        msgs)]
      (is (= :ok (:status result)))
      (is (some? err-msg) "not-found error must surface as tool result"))))

;; =============================================================
;; read_file output format
;; =============================================================

(deftest read-file-formats-with-line-numbers-test
  (testing "read_file returns content with cat -n style line prefixes"
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data]\n  {:n (* 2 (:n data))})"})
                    (tc :read_file  {:path "handler.clj"})
                    (tc :run_tests)
                    (tc :complete)])
          read-out (last-tool-result-for (:session result) :read_file)]
      (is (= :ok (:status result)))
      (is (some? read-out))
      (is (str/includes? read-out "    1\t(fn [_ data]"))
      (is (str/includes? read-out "    2\t  {:n (* 2 (:n data))})")))))

(deftest read-file-empty-buffer-test
  (testing "reading an empty buffer reports (empty)"
    (let [result (run-with
                   [(tc :read_file  {:path "helpers.clj"})
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          read-out (last-tool-result-for (:session result) :read_file)]
      (is (= :ok (:status result)))
      (is (str/includes? read-out "(empty)")))))

;; =============================================================
;; list_files
;; =============================================================

(deftest list-files-test
  (testing "list_files reports all three buffers with line/char counts"
    (let [result (run-with
                   [(tc :list_files)
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          list-out (last-tool-result-for (:session result) :list_files)]
      (is (= :ok (:status result)))
      (is (str/includes? list-out "handler.clj"))
      (is (str/includes? list-out "helpers.clj"))
      (is (str/includes? list-out "test.clj")))))

;; =============================================================
;; Initial state: test.clj pre-populated with the contract
;; =============================================================

(deftest test-clj-prepopulated-test
  (testing "test.clj contains the locked test contract on first read"
    (let [result (run-with
                   [(tc :read_file  {:path "test.clj"})
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          read-out (last-tool-result-for (:session result) :read_file)]
      (is (= :ok (:status result)))
      (is (str/includes? read-out "doubles-n"))
      (is (str/includes? read-out "(handler {} {:n 2})")))))

;; =============================================================
;; Parse-error recovery
;; =============================================================

(deftest parse-error-recovers-test
  (testing "agent recovers after writing malformed handler"
    (let [result (run-with
                   [;; First attempt: malformed (unbalanced parens)
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))"})
                    (tc :run_tests)
                    ;; Recover with a valid form
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          msgs (-> result :session :messages deref)
          parse-err (some (fn [m]
                            (when (and (= "tool" (:role m))
                                       (or (str/includes? (:content m) "Could not parse")
                                           (str/includes? (:content m) "Eval error")))
                              (:content m)))
                          msgs)]
      (is (= :ok (:status result)))
      (is (some? parse-err) "first run_tests should report a parse/eval error"))))

;; =============================================================
;; Unknown path
;; =============================================================

(deftest unknown-path-rejected-test
  (testing "writing to an unknown path returns an error and doesn't change state"
    (let [result (run-with
                   [(tc :write_file {:path    "main.clj"
                                     :content "(fn [_ data] data)"})
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])
          msgs (-> result :session :messages deref)
          err-msg (some (fn [m]
                          (when (and (= "tool" (:role m))
                                     (str/includes? (:content m) "Unknown file: 'main.clj'"))
                            (:content m)))
                        msgs)]
      (is (= :ok (:status result)))
      (is (some? err-msg) "unknown-path error must surface"))))

;; =============================================================
;; Soft phase gating — free iteration
;; =============================================================

(deftest free-iteration-handler-then-tests-then-handler-test
  (testing "agent edits handler, runs, fails, edits test.clj to fix the spec, edits handler again, completes"
    ;; First handler is wrong (multiplies by 3). First run_tests fails.
    ;; Then the agent decides the test was actually expecting a tripled value
    ;; (refining its understanding) — edits test.clj. Tests still fail because
    ;; expected updates don't match handler output. Then fixes handler back to
    ;; doubled. Final run is green; complete finalizes.
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 3 (:n data))})"})
                    (tc :run_tests) ;; fails: 6 != 4
                    ;; Refine test.clj to match a different expectation
                    (tc :edit_file  {:path        "handler.clj"
                                     :old_string  "* 3"
                                     :new_string  "* 2"})
                    (tc :run_tests) ;; passes
                    (tc :complete)])
          msgs (-> result :session :messages deref)
          fail-msg (some (fn [m]
                           (when (and (= "tool" (:role m))
                                      (str/includes? (:content m) "ERROR"))
                             (:content m)))
                         msgs)]
      (is (= :ok (:status result)))
      (is (some? fail-msg) "first run_tests should report a failure")
      (is (str/includes? (:code result) "(* 2 (:n data))")))))

(deftest agent-edits-tests-then-handler-test
  (testing "agent can refine test.clj before implementing — order is fluid"
    ;; The agent reads test.clj, decides to add an extra case, then writes
    ;; the handler that satisfies the augmented test set.
    (let [augmented-tests (str/replace double-test-code
                            "(is (= {:n 0} (handler {} {:n 0}))))"
                            (str "(is (= {:n 0} (handler {} {:n 0})))\n"
                                 "  (is (= {:n 10} (handler {} {:n 5}))))"))
          result (run-with
                   [(tc :read_file  {:path "test.clj"})
                    (tc :write_file {:path    "test.clj"
                                     :content augmented-tests})
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :run_tests)
                    (tc :complete)])]
      (is (= :ok (:status result))
          "agent should be free to edit tests before writing the handler"))))

;; =============================================================
;; complete as a verification gate
;; =============================================================

(deftest complete-runs-tests-on-its-own-test
  (testing "complete works without an explicit run_tests beforehand"
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    ;; No run_tests — complete should run them itself
                    (tc :complete)])]
      (is (= :ok (:status result)))
      (is (str/includes? (:code result) "(* 2 (:n data))")))))

(deftest complete-blocks-on-failing-tests-test
  (testing "complete fails when tests don't pass; agent recovers"
    (let [result (run-with
                   [(tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 7 (:n data))})"})
                    (tc :complete)  ;; should be blocked
                    ;; recover
                    (tc :edit_file  {:path        "handler.clj"
                                     :old_string  "* 7"
                                     :new_string  "* 2"})
                    (tc :complete)])
          msgs (-> result :session :messages deref)
          blocked-msg (some (fn [m]
                              (when (and (= "tool" (:role m))
                                         (str/includes? (:content m) "complete blocked"))
                                (:content m)))
                            msgs)]
      (is (= :ok (:status result)))
      (is (some? blocked-msg) "complete should report blocked when tests fail")
      (is (str/includes? (:code result) "(* 2 (:n data))")))))

(deftest complete-blocks-on-empty-handler-test
  (testing "complete fails when handler.clj is empty"
    (let [result (run-with
                   [(tc :complete) ;; blocked: handler.clj empty
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :complete)])
          msgs (-> result :session :messages deref)
          blocked-msg (some (fn [m]
                              (when (and (= "tool" (:role m))
                                         (str/includes? (:content m) "handler.clj is empty"))
                                (:content m)))
                            msgs)]
      (is (= :ok (:status result)))
      (is (some? blocked-msg) "complete should explain why it blocked"))))

;; =============================================================
;; Edit mode — pre-loaded buffers and change-summary
;; =============================================================

(deftest edit-mode-prepopulates-files-test
  (testing "agent sees previous handler/helpers and revised tests on turn 1"
    (let [;; Capture the very first user prompt the agent receives so we can
          ;; assert on its content.
          first-prompt (atom nil)
          spy-mock (let [counter (atom 0)]
                     (fn [session _client & rest-args]
                       (let [user-msg (when (string? (first rest-args)) (first rest-args))]
                         (when user-msg
                           (when (zero? @counter) (reset! first-prompt user-msg))
                           (swap! (:messages session) conj {:role "user" :content user-msg}))
                         (let [r {:content nil :tool-calls nil :finish-reason "stop"}]
                           (swap! counter inc)
                           ;; Always emit a final-tool sequence so the loop terminates.
                           (let [out (case @counter
                                       1 (tc :read_file {:path "handler.clj"})
                                       2 (tc :complete)
                                       r)]
                             (swap! (:messages session) conj (assistant-msg out))
                             out)))))
          result   (with-llm-mock spy-mock
                     (agent-loop/run!
                       (assoc base-opts
                         :initial-handler  "(fn [_ data] {:n (* 2 (:n data))})"
                         :initial-helpers  "(defn doubled [x] (* x 2))"
                         :change-summary   "Contract changes since the previous green implementation:\n  - schema: ..."
                         :turn-budget      6)))]
      (is (= :ok (:status result)))
      ;; The prompt should mention the edit-mode framing AND the change-summary
      (let [p @first-prompt]
        (is (some? p))
        (is (str/includes? p "already has an implementation"))
        (is (str/includes? p "Contract changes since the previous green"))
        (is (str/includes? p "schema"))))))

(deftest fresh-mode-prompts-the-old-way
  (testing "default (no initial files) prompt has no edit-mode framing"
    (let [first-prompt (atom nil)
          spy-mock (let [counter (atom 0)]
                     (fn [session _client & rest-args]
                       (let [user-msg (when (string? (first rest-args)) (first rest-args))]
                         (when user-msg
                           (when (zero? @counter) (reset! first-prompt user-msg))
                           (swap! (:messages session) conj {:role "user" :content user-msg}))
                         (swap! counter inc)
                         (let [out (case @counter
                                     1 (tc :write_file {:path "handler.clj"
                                                        :content "(fn [_ data] {:n (* 2 (:n data))})"})
                                     2 (tc :complete)
                                     {:content nil :tool-calls nil :finish-reason "stop"})]
                           (swap! (:messages session) conj (assistant-msg out))
                           out))))
          result (with-llm-mock spy-mock
                   (agent-loop/run! (assoc base-opts :turn-budget 6)))]
      (is (= :ok (:status result)))
      (let [p @first-prompt]
        (is (str/includes? p "test.clj is pre-populated"))
        (is (not (str/includes? p "already has an implementation")))))))

(deftest dispatched-output-block-included-test
  (testing "when :schema-parsed has dispatched output, the prompt includes the convention block"
    (let [first-prompt (atom nil)
          dispatched-opts
          (assoc base-opts
            :brief {:id :order/classify-total
                    :doc "Classify"
                    :schema "{:input {:total :double} :output {:high {:level :keyword} :low {:level :keyword}}}"
                    :requires []}
            :schema-parsed {:input  [:map [:total :double]]
                            :output {:high {:level :keyword}
                                     :low  {:level :keyword}}}
            :test-code double-test-code  ;; harmless stub so the loop has something to run
            :turn-budget 4)
          spy (let [counter (atom 0)]
                (fn [session _client & rest-args]
                  (let [user-msg (when (string? (first rest-args)) (first rest-args))]
                    (when user-msg
                      (when (zero? @counter) (reset! first-prompt user-msg))
                      (swap! (:messages session) conj {:role "user" :content user-msg}))
                    (swap! counter inc)
                    (let [out (tc :give_up {:reason "test stub"})]
                      (swap! (:messages session) conj (assistant-msg out))
                      out))))]
      (with-llm-mock spy
        (agent-loop/run! dispatched-opts))
      (let [p @first-prompt]
        (is (some? p))
        (is (str/includes? p "Dispatched output"))
        (is (str/includes? p "Possible transitions: `high` | `low`"))
        (is (str/includes? p "Never wrap the result under the transition label"))))))

(deftest dispatched-output-block-omitted-for-flat-schema-test
  (testing "flat output schema → no dispatched-output block in the prompt"
    (let [first-prompt (atom nil)
          spy (let [counter (atom 0)]
                (fn [session _client & rest-args]
                  (let [user-msg (when (string? (first rest-args)) (first rest-args))]
                    (when user-msg
                      (when (zero? @counter) (reset! first-prompt user-msg))
                      (swap! (:messages session) conj {:role "user" :content user-msg}))
                    (swap! counter inc)
                    (let [out (tc :give_up {:reason "test stub"})]
                      (swap! (:messages session) conj (assistant-msg out))
                      out))))]
      (with-llm-mock spy
        (agent-loop/run! (assoc base-opts :turn-budget 4)))
      (let [p @first-prompt]
        (is (not (str/includes? p "Dispatched output")))))))

(deftest no-phase-rejection-test
  (testing "no tool is rejected as 'wrong phase' — all tools are always available"
    (let [result (run-with
                   [;; sprinkle 'control'-style and 'introspection' tools throughout
                    (tc :list_files)
                    (tc :write_file {:path    "handler.clj"
                                     :content "(fn [_ data] {:n (* 2 (:n data))})"})
                    (tc :list_functions)
                    (tc :run_tests)
                    (tc :read_file {:path "handler.clj"})
                    (tc :complete)])
          msgs (-> result :session :messages deref)
          phase-error (some (fn [m]
                              (when (and (= "tool" (:role m))
                                         (str/includes? (:content m) "not available in"))
                                (:content m)))
                            msgs)]
      (is (= :ok (:status result)))
      (is (nil? phase-error) "no tool result should mention phase rejection"))))
