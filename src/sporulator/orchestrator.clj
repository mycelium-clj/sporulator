(ns sporulator.orchestrator
  "TDD orchestrator: generate tests → review → implement → verify.
   Coordinates graph and cell agents through the full workflow."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [sporulator.cell-agent :as cell-agent]
            [sporulator.source-gen :as source-gen]
            [sporulator.codegen :as codegen]
            [sporulator.eval :as ev]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
            [sporulator.prompts :as prompts]
            [sporulator.store :as store]))

;; ── Review gates ───────────────────────────────────────────────

(defn create-review-gate
  "Creates a review gate (a promise that blocks until the user responds)."
  []
  (promise))

(defn deliver-review
  "Delivers user review responses to unblock a review gate."
  [gate responses]
  (deliver gate responses))

(defn await-review
  "Blocks until the review gate is delivered or timeout (ms) expires.
   Returns the responses or nil on timeout."
  [gate timeout-ms]
  (deref gate timeout-ms nil))

;; ── Events ─────────────────────────────────────────────────────

(defn- emit
  "Emits an orchestrator event via the callback.
   Converts keys to snake_case strings for consistent JSON serialization."
  [on-event phase status & {:as extra}]
  (when on-event
    (let [base {"phase" phase "status" status}
          converted (reduce-kv (fn [m k v]
                                 (assoc m (str/replace (name k) "-" "_") v))
                               base extra)]
      (on-event converted))))

;; ── Namespace helpers ──────────────────────────────────────────

(defn- cell-ns-name
  "Derives a namespace name from base-ns and cell-id.
   :order/compute-tax with base-ns 'myapp' → 'myapp.cells.compute-tax'"
  [base-ns cell-id]
  (let [id-str (if (str/starts-with? (str cell-id) ":")
                 (subs (str cell-id) 1)
                 (str cell-id))
        suffix (last (str/split id-str #"/"))]
    (str base-ns ".cells." (str/replace suffix "_" "-"))))

(defn- test-ns-name [base-ns cell-id]
  (str (cell-ns-name base-ns cell-id) "-test"))

;; ── Test contract generation ───────────────────────────────────

(defn- build-test-prompt
  "Builds the prompt to ask the LLM to write tests for a cell."
  [{:keys [id doc schema requires]}]
  (str "Write tests for the following Mycelium cell using clojure.test.\n\n"
       "**Cell ID:** `" id "`\n"
       "**Requirements:** " doc "\n"
       "**Schema:** `" schema "`\n"
       (when (seq requires)
         (str "**Resources:** " (str/join ", " requires) "\n"))
       "\n" prompts/math-precision-rules "\n"
       "\nReturn ONLY the `deftest` forms. Do NOT include the `(ns ...)` declaration "
       "or any requires — those are added automatically.\n"
       "The handler is available as `handler` and resources as `{}`.\n"
       "Use `(handler {} {:input-key value})` to call the cell.\n"))

(defn- self-review-prompt
  "Builds the self-review prompt for generated tests."
  [{:keys [doc schema]} test-body]
  (str "Review the following tests for correctness.\n\n"
       "**Requirements (source of truth):** " doc "\n"
       "**Schema:** `" schema "`\n\n"
       "**Tests:**\n```clojure\n" test-body "\n```\n\n"
       "Check that:\n"
       "1. All requirements are covered\n"
       "2. Input/output schemas are respected\n"
       "3. Edge cases are handled\n"
       "4. Assertions are mathematically correct\n\n"
       "If ALL tests are correct, respond with: ALL TESTS VERIFIED\n"
       "If corrections are needed, return the corrected test forms."))

(defn generate-test-contract
  "Generates a test contract for a single cell.
   Returns a contract map with :cell-id, :test-code, :test-body, :session.

   Options:
     :brief    — cell brief map
     :base-ns  — namespace prefix
     :store    — store instance
     :run-id   — orchestration run ID
     :on-event — event callback
     :on-chunk — streaming callback"
  [client {:keys [brief base-ns store run-id on-event on-chunk]}]
  (let [cell-id  (:id brief)
        cell-ns  (cell-ns-name base-ns cell-id)
        test-ns  (test-ns-name base-ns cell-id)
        session  (llm/create-session (str "test:" cell-id) prompts/cell-prompt)]
    (emit on-event "cell_test" "started" :cell-id cell-id)

    ;; Generate test body
    (let [test-prompt (build-test-prompt brief)
          test-body   (llm/session-send-stream session client test-prompt on-chunk)]

      (emit on-event "cell_test" "written" :cell-id cell-id)

      ;; Self-review
      (let [review-prompt (self-review-prompt brief test-body)
            review-resp   (llm/session-send-stream session client review-prompt on-chunk)
            ;; If corrections returned, use those instead
            final-body    (if (str/includes? review-resp "ALL TESTS VERIFIED")
                            test-body
                            (or (extract/extract-first-code-block review-resp)
                                test-body))
            ;; Assemble full test source
            test-code     (codegen/assemble-test-source
                            {:test-ns  test-ns
                             :cell-ns  cell-ns
                             :cell-id  (keyword (subs (str cell-id) 1))
                             :test-body final-body})
            contract      {:cell-id      cell-id
                           :brief        brief
                           :test-code    test-code
                           :test-body    final-body
                           :review-notes review-resp
                           :test-ns      test-ns
                           :cell-ns      cell-ns
                           :session      session
                           :revision     0}]

        ;; Persist to store
        (when store
          (store/save-test-contract! store
            {:run-id       run-id
             :cell-id      cell-id
             :test-code    test-code
             :test-body    final-body
             :review-notes review-resp
             :status       "pending"
             :revision     0}))

        contract))))

;; ── Implementation from contract ───────────────────────────────

;; ── Lint fix loop ──────────────────────────────────────────────

(defn lint-fix-loop
  "Runs clj-kondo on code. If lint errors found, asks LLM to fix syntax
   only (no logic changes). Retries up to max-attempts.
   Returns {:status :ok :code fixed-code} or {:status :error :error msg}."
  [client session code cell-id
   & {:keys [max-attempts on-chunk on-event]
      :or   {max-attempts 3}}]
  (loop [current-code code
         attempt      0]
    (let [lint (ev/lint-code current-code)]
      (if-not (seq (:errors lint))
        {:status :ok :code current-code}
        ;; Lint errors found
        (do
          (when on-event
            (on-event {:phase "cell_implement" :status "lint_fix"
                       :cell-id cell-id :attempt (inc attempt)
                       :message (str (count (:errors lint)) " lint errors")}))
          (if (>= attempt max-attempts)
            {:status :error
             :error  (str "Lint errors persist after " max-attempts " attempts: "
                          (str/join "; " (map :message (:errors lint))))}
            ;; Ask LLM to fix syntax only
            (let [fix-prompt (str "Fix ONLY the syntax errors in this code. "
                                  "Do NOT change any logic.\n\n"
                                  "**Errors:**\n"
                                  (str/join "\n" (map #(str "- Line " (:line %) ": " (:message %))
                                                      (:errors lint)))
                                  "\n\n**Code:**\n```clojure\n" current-code "\n```")
                  fixed (if session
                          (llm/session-send-stream session client fix-prompt
                            (or on-chunk (fn [_])))
                          current-code)
                  extracted (or (extract/extract-first-code-block fixed) fixed)]
              (recur extracted (inc attempt)))))))))

;; ── Implementation ─────────────────────────────────────────────

(defn- build-impl-prompt
  "Builds the prompt to implement a cell against its test contract."
  [{:keys [brief test-body]}]
  (str "Implement the following Mycelium cell. The tests are already written "
       "and locked — your code must pass them.\n\n"
       (cell-agent/build-cell-prompt brief)
       "\n\n**Tests your implementation must pass:**\n```clojure\n"
       test-body "\n```\n\n"
       prompts/math-precision-rules "\n"
       "\nReturn the complete source including `(ns ...)` and `(cell/defcell ...)`."))

(defn implement-from-contract
  "Implements a cell from a test contract using the LLM + eval feedback loop.

   Options:
     :contract     — test contract map
     :store        — store instance
     :run-id       — orchestration run ID
     :on-event     — event callback
     :on-chunk     — streaming callback
     :max-attempts — max fix attempts (default 3)
     :project-path — project root directory for writing source files
     :base-ns      — base namespace for source files"
  [client {:keys [contract store run-id on-event on-chunk max-attempts
                  project-path base-ns]
           :or   {max-attempts 3}}]
  (let [{:keys [cell-id brief test-code cell-ns]} contract
        session (or (:session contract)
                    (llm/create-session (str "impl:" cell-id) prompts/cell-prompt))]
    (emit on-event "cell_implement" "started" :cell-id cell-id)

    (loop [msg     (build-impl-prompt contract)
           attempt 0]
      (let [content  (llm/session-send-stream session client msg on-chunk)
            source   (or (extract/extract-first-code-block content) content)]

        (emit on-event "cell_implement" "written" :cell-id cell-id
              :attempt (inc attempt))

        ;; Eval the implementation
        (let [eval-res (ev/eval-code source)]
          (if (not= :ok (:status eval-res))
            ;; Eval failed
            (do
              (emit on-event "cell_implement" "error" :cell-id cell-id
                    :message (:error eval-res) :attempt (inc attempt))
              (when store
                (store/save-cell-attempt! store
                  {:run-id run-id :cell-id cell-id :attempt-type "implement"
                   :attempt-number (inc attempt) :code source
                   :output (:error eval-res) :passed? false}))
              (if (< attempt max-attempts)
                (recur (str "The code produced this error:\n```\n"
                            (:error eval-res)
                            "\n```\nPlease fix and return the corrected source.")
                       (inc attempt))
                {:status :error :error (:error eval-res) :cell-id cell-id}))

            ;; Eval succeeded — now load tests and run them
            (let [test-res (ev/run-cell-tests test-code)]
              (when store
                (store/save-cell-attempt! store
                  {:run-id run-id :cell-id cell-id :attempt-type "test"
                   :attempt-number (inc attempt) :code source
                   :test-code test-code
                   :output (:output test-res)
                   :passed? (and (= :ok (:status test-res)) (:passed? test-res))}))

              (cond
                ;; Tests not runnable
                (not= :ok (:status test-res))
                (do
                  (emit on-event "cell_test" "error" :cell-id cell-id
                        :message (:error test-res))
                  (if (< attempt max-attempts)
                    (recur (str "Tests failed to load:\n```\n"
                                (:error test-res)
                                "\n```\nPlease fix the implementation.")
                           (inc attempt))
                    {:status :error :error (:error test-res) :cell-id cell-id}))

                ;; Tests passed
                (:passed? test-res)
                (do
                  (emit on-event "cell_test" "passed" :cell-id cell-id
                        :attempt (inc attempt))
                  ;; Save cell to store
                  (when store
                    (let [defcell-code (extract/extract-defcell content)]
                      (store/save-cell! store
                        {:id         cell-id
                         :handler    (or defcell-code source)
                         :schema     (or (:schema brief) "")
                         :doc        (or (:doc brief) "")
                         :created-by "cell-agent-tdd"})))
                  ;; Write source file to disk
                  (when (and project-path base-ns)
                    (source-gen/write-cell! project-path base-ns cell-id source)
                    (emit on-event "cell_implement" "file_written"
                          :cell-id cell-id))
                  {:status :ok :cell-id cell-id
                   :output (:output test-res)
                   :summary (:summary test-res)})

                ;; Tests failed — retry
                :else
                (do
                  (emit on-event "cell_test" "failed" :cell-id cell-id
                        :attempt (inc attempt))
                  (if (< attempt max-attempts)
                    (let [fix-prompt (prompts/build-graduated-fix-prompt
                                      {:test-output (:output test-res)
                                       :test-code   test-code
                                       :impl-code   source
                                       :brief       brief
                                       :cell-id     cell-id
                                       :attempt     (inc attempt)
                                       :max-attempts max-attempts})]
                      (recur fix-prompt (inc attempt)))
                    {:status :error
                     :error  (str "Tests failed after " (inc attempt) " attempts")
                     :cell-id cell-id
                     :output (:output test-res)}))))))))))
;; ── Main orchestration ─────────────────────────────────────────

(defn orchestrate!
  "Runs the full TDD orchestration for a set of leaf cells.

   Options:
     :leaves        — vector of leaf cell maps (cell-id, doc, schemas, etc.)
     :base-ns       — namespace prefix
     :store         — store instance
     :on-event      — event callback
     :on-chunk      — streaming callback
     :on-test-review — review callback: (fn [contracts] -> responses)
     :auto-approve? — skip review gates (default false)
     :max-attempts  — max fix attempts per cell (default 3)
     :manifest-id   — manifest ID for run tracking"
  [client {:keys [leaves base-ns store on-event on-chunk
                  on-test-review auto-approve? max-attempts manifest-id
                  spec-hash project-path]
           :or   {auto-approve? false max-attempts 3
                  manifest-id "" spec-hash ""}
           :as   opts}]
  (let [run-id  (str "run-" (System/nanoTime))
        on-event (or on-event (fn [_]))
        on-chunk (or on-chunk (fn [_]))]

    ;; Create run in store
    (when store
      (store/create-run! store
        {:id          run-id
         :spec-hash   spec-hash
         :manifest-id manifest-id
         :status      "running"}))

    (emit on-event "manifest" "started" :run-id run-id)

    ;; Build briefs from leaves (accept both kebab and underscore keys from JSON)
    (let [briefs (mapv (fn [leaf]
                         (let [cell-id (or (:cell-id leaf) (:cell_id leaf))
                               doc (or (:doc leaf) "")
                               input-schema (or (:input-schema leaf) (:input_schema leaf) "{}")
                               output-schema (or (:output-schema leaf) (:output_schema leaf) "{}")
                               requires (or (:requires leaf) [])]
                           {:id       cell-id
                            :doc      doc
                            :schema   (str "{:input " input-schema " :output " output-schema "}")
                            :requires requires}))
                       leaves)]

      ;; Phase 1: Generate test contracts
      (emit on-event "cell_test" "started" :message "Generating test contracts")
      (let [contracts (mapv (fn [brief]
                              (try
                                (generate-test-contract client
                                  {:brief    brief
                                   :base-ns  base-ns
                                   :store    store
                                   :run-id   run-id
                                   :on-event on-event
                                   :on-chunk on-chunk})
                                (catch Exception e
                                  {:cell-id (:id brief)
                                   :error   (.getMessage e)})))
                            briefs)
            ;; Split into good and failed
            good-contracts (filterv :test-code contracts)
            failed         (filterv :error contracts)]

        ;; Report failed contract generation
        (doseq [f failed]
          (emit on-event "cell_test" "error"
                :cell-id (:cell-id f) :message (:error f)))

        ;; Phase 2: Review gate
        (let [approved (if auto-approve?
                         (do
                           (doseq [c good-contracts]
                             (when store
                               (store/update-test-contract-status!
                                 store run-id (:cell-id c) "approved"))
                             (emit on-event "test_review" "approved"
                                   :cell-id (:cell-id c)))
                           good-contracts)
                         ;; Block for user review
                         (if on-test-review
                           (let [responses (on-test-review good-contracts)]
                             (filterv
                               (fn [c]
                                 (let [resp (first (filter #(= (:cell-id %)
                                                               (:cell-id c))
                                                          responses))]
                                   (case (some-> resp :decision)
                                     "approve"
                                     (do (when store
                                           (store/update-test-contract-status!
                                             store run-id (:cell-id c) "approved"))
                                         (emit on-event "test_review" "approved"
                                               :cell-id (:cell-id c))
                                         true)
                                     "skip"
                                     (do (when store
                                           (store/update-test-contract-status!
                                             store run-id (:cell-id c) "skipped"))
                                         (emit on-event "test_review" "skipped"
                                               :cell-id (:cell-id c))
                                         false)
                                     ;; default (nil or unknown): approve
                                     (do (when store
                                           (store/update-test-contract-status!
                                             store run-id (:cell-id c) "approved"))
                                         true))))
                               good-contracts))
                           ;; No review callback — auto-approve
                           good-contracts))]

          ;; Phase 3: Implement approved contracts in parallel
          (emit on-event "cell_implement" "started"
                :message (str "Implementing " (count approved) " cells"))
          (let [impl-futures
                (mapv (fn [contract]
                        (future
                          (try
                            (implement-from-contract client
                              {:contract     contract
                               :store        store
                               :run-id       run-id
                               :on-event     on-event
                               :on-chunk     on-chunk
                               :max-attempts max-attempts
                               :project-path project-path
                               :base-ns      base-ns})
                            (catch Exception e
                              {:status  :error
                               :cell-id (:cell-id contract)
                               :error   (.getMessage e)}))))
                      approved)
                impl-results (mapv deref impl-futures)
                passed  (filterv #(= :ok (:status %)) impl-results)
                failed-impl (filterv #(not= :ok (:status %)) impl-results)]

            ;; Update run status and tree
            (let [final-status (if (and (empty? failed) (empty? failed-impl))
                                 "completed" "partial")
                  tree-json    (json/write-str
                                 {:passed (mapv :cell-id passed)
                                  :failed (into (mapv :cell-id failed)
                                                (mapv :cell-id failed-impl))})]
              (when store
                (store/update-run-tree! store run-id final-status tree-json))
              (emit on-event "complete" "done"
                    :passed (count passed)
                    :failed (+ (count failed) (count failed-impl)))

              {"status"      (if (= "completed" final-status) "ok" "partial")
               "run_id"      run-id
               "passed"      (mapv :cell-id passed)
               "failed"      (into (mapv :cell-id failed)
                                   (mapv :cell-id failed-impl))
               :results      impl-results})))))))

;; ── Resume ─────────────────────────────────────────────────────

(defn resume!
  "Resumes a previous orchestration run. Checks store for a previous run
   matching manifest-id. Reloads passed cells, re-implements failed ones.
   Falls back to a fresh orchestrate! if no previous run found.

   Options: same as orchestrate! plus :manifest-id (required)"
  [client {:keys [manifest-id store on-event on-chunk] :as opts}]
  (let [on-event (or on-event (fn [_]))]
    (if-not store
      (orchestrate! client opts)
      ;; Check for previous run
      (let [prev-run (store/get-latest-run-for-manifest store manifest-id)]
        (if-not prev-run
          (do (emit on-event "resume" "fresh"
                    :message "No previous run found, starting fresh")
              (orchestrate! client opts))

          ;; Previous run found — check what passed/failed
          (let [summary  (store/get-run-summary store (:id prev-run))
                tree     (try (json/read-str (or (:tree-json prev-run) "{}")
                                :key-fn keyword)
                              (catch Exception _ {}))
                passed   (or (:passed tree) [])
                failed   (or (:failed tree) [])]

            (emit on-event "resume" "found"
                  :run-id (:id prev-run)
                  :passed (count passed)
                  :failed (count failed))

            ;; Reload passed cells from store
            (doseq [cell-id passed]
              (when-let [cell (store/get-latest-cell store cell-id)]
                (let [r (ev/eval-code (:handler cell))]
                  (if (= :ok (:status r))
                    (emit on-event "resume" "reloaded" :cell-id cell-id)
                    (do
                      (emit on-event "resume" "reload_failed" :cell-id cell-id
                            :message (:error r)))))))

            ;; If nothing failed, we're done
            (if (empty? failed)
              (do (emit on-event "resume" "all_passed"
                        :message "All cells from previous run passed")
                  {"status" "ok" "run_id" (:id prev-run)
                   "passed" passed "failed" []})

              ;; Re-implement failed cells
              (do (emit on-event "resume" "implementing"
                        :message (str "Re-implementing " (count failed) " failed cells"))
                  (orchestrate! client
                    (assoc opts
                      :leaves (mapv (fn [cell-id]
                                      {:cell-id cell-id
                                       :doc ""
                                       :input-schema "{}"
                                       :output-schema "{}"})
                                    failed)))))))))))
