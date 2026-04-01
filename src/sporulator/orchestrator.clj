(ns sporulator.orchestrator
  "TDD orchestrator: generate tests → review → implement → verify.
   Coordinates graph and cell agents through the full workflow."
  (:require [cljfmt.core :as cljfmt]
            [clojure.data.json :as json]
            [zprint.core :as zp]
            [clojure.string :as str]
            [sporulator.cell-agent :as cell-agent]
            [sporulator.source-gen :as source-gen]
            [sporulator.codegen :as codegen]
            [sporulator.eval :as ev]
            [sporulator.extract :as extract]
            [sporulator.llm :as llm]
            [sporulator.hashline :as hashline]
            [sporulator.manifest-validate :as mv]
            [sporulator.prompts :as prompts]
            [sporulator.store :as store]))

;; ── System EDN reading ────────────────────────────────────────

(def ^:private aero-readers
  "Data readers for Aero/Integrant tags so we can parse system.edn
   without the Aero runtime. Maps tags to tolerant handler functions."
  {'or       second        ;; #or [a b] → b (default)
   'env      identity      ;; #env FOO → "FOO"
   'long     identity      ;; #long "3000" → "3000"
   'profile  identity      ;; #profile {...} → {...}
   'ig/ref   identity      ;; #ig/ref :key → :key
   'ig/refset identity     ;; #ig/refset :key → :key
   'app-path identity})    ;; #app-path → identity

(defn read-system-edn
  "Reads a system.edn file tolerantly, handling Aero/Integrant reader tags.
   Returns the parsed map, or nil on failure."
  [path]
  (try
    (let [f (java.io.File. (str path))]
      (when (.exists f)
        (clojure.edn/read-string {:readers aero-readers} (slurp f))))
    (catch Exception _ nil)))

(defn extract-resource-docs
  "Extracts :mycelium/doc metadata from a parsed system.edn map.
   Cross-references with :reitit.routes/pages to find the key name
   each resource is injected under (e.g. :db, not :sqlite).
   Returns {resource-keyword doc-string}."
  [sys-edn]
  (when sys-edn
    (let [;; Build mapping: integrant-key → :mycelium/doc
          docs-by-ig-key (->> sys-edn
                              (keep (fn [[k v]]
                                      (when (and (map? v) (:mycelium/doc v))
                                        [k (:mycelium/doc v)])))
                              (into {}))
          ;; Get the routes config to find injection key names
          ;; :reitit.routes/pages {:db #ig/ref :db/sqlite} → :db/sqlite is injected as :db
          routes-cfg (get sys-edn :reitit.routes/pages)
          ;; Build reverse: integrant-key → injection-key
          ig->injection (when (map? routes-cfg)
                          (into {} (map (fn [[inject-key ig-ref]]
                                         (when (keyword? ig-ref)
                                           [ig-ref inject-key]))
                                       routes-cfg)))]
      (->> docs-by-ig-key
           (map (fn [[ig-key doc]]
                  (let [inject-key (or (get ig->injection ig-key)
                                      (keyword (name ig-key)))]
                    [inject-key doc])))
           (into {})))))

;; ── Formatting ────────────────────────────────────────────────

(defn- format-source
  "Formats Clojure source code with zprint. Returns original on failure."
  [source]
  (try
    (zp/zprint-str source {:parse-string? true :parse-string-all? true :width 80})
    (catch Exception _
      ;; Fallback to cljfmt for files with multiple top-level forms
      (try (cljfmt/reformat-string source)
           (catch Exception _ source)))))

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
  [{:keys [id doc schema requires context]}]
  (str "Write tests for the following Mycelium cell using clojure.test.\n\n"
       "**Cell ID:** `" id "`\n"
       "**Requirements:** " doc "\n"
       "**Schema:** `" schema "`\n"
       (when (seq requires)
         (str "**Resources:** " (str/join ", " requires) "\n"))
       (when context
         (str "\n" context "\n"))
       (when (prompts/needs-math-precision? schema)
         (str "\n" prompts/math-precision-rules "\n"))
       "\nReturn ONLY the `deftest` forms. Do NOT include the `(ns ...)` declaration "
       "or any requires — those are added automatically.\n"
       "The handler is available as `handler` and resources as `{}`.\n"
       "Use `(handler {} {:input-key value})` to call the cell.\n\n"
       "Example test structure:\n"
       "```clojure\n"
       "(deftest test-basic-case\n"
       "  (let [result (handler {} {:key \"value\"})]\n"
       "    (is (contains? result :expected-key))\n"
       "    (is (= expected-value (:expected-key result)))))\n"
       "```\n"))

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
            cell-id-kw    (let [s (str cell-id)]
                            (keyword (cond-> s (str/starts-with? s ":") (subs 1))))
            test-code     (codegen/assemble-test-source
                            {:test-ns  test-ns
                             :cell-ns  cell-ns
                             :cell-id  cell-id-kw
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

;; ── Structural auto-fix + validation ──────────────────────────

(defn- auto-fix-cell-source
  "Mechanically fixes known deterministic issues in cell source code.
   Corrects namespace name and cell ID since these are known values."
  [source cell-id cell-ns]
  (let [expected-id (cond-> (str cell-id)
                      (str/starts-with? (str cell-id) ":") (subs 1))
        ;; Fix namespace: replace wrong ns name with correct one
        source (if-let [[_ found-ns] (re-find #"\(ns\s+(\S+)" source)]
                 (if (and cell-ns (not= found-ns cell-ns))
                   (str/replace-first source (re-pattern (java.util.regex.Pattern/quote found-ns)) cell-ns)
                   source)
                 source)
        ;; Fix cell ID: replace wrong cell ID with correct one
        source (if-let [[_ found-id] (re-find #"defcell\s+:(\S+)" source)]
                 (if (not= found-id expected-id)
                   (str/replace-first source (str ":" found-id) (str ":" expected-id))
                   source)
                 source)
        ;; Add (ns ...) if missing entirely
        source (if (re-find #"(?m)^\s*\(ns\s" source)
                 source
                 (str "(ns " cell-ns "\n  (:require [mycelium.cell :as cell]))\n\n" source))
        ;; Add [mycelium.cell :as cell] if ns exists but require is missing
        source (if (and (re-find #"\(ns\s" source)
                        (not (re-find #"mycelium\.cell" source)))
                 (str/replace-first source #"\(ns\s+(\S+)\s*\)" (str "(ns $1\n  (:require [mycelium.cell :as cell]))"))
                 source)]
    source))

(defn- validate-cell-source
  "Validates cell source code structurally before eval.
   Returns nil if valid, or a string describing the problem with fix instructions."
  [source cell-id cell-ns]
  (let [issues (transient [])]
    ;; Check for (ns ...) form
    (when-not (re-find #"(?m)^\s*\(ns\s" source)
      (conj! issues (str "Missing (ns ...) declaration. The source MUST start with:\n"
                         "(ns " cell-ns "\n  (:require [mycelium.cell :as cell]))")))
    ;; Check for correct namespace name
    (when-let [[_ found-ns] (re-find #"\(ns\s+(\S+)" source)]
      (when (and cell-ns (not= found-ns cell-ns))
        (conj! issues (str "Wrong namespace: found `" found-ns "` but expected `" cell-ns "`"))))
    ;; Check for (cell/defcell ...) or (mycelium.cell/defcell ...)
    (when-not (re-find #"(?:cell/defcell|mycelium\.cell/defcell)" source)
      (conj! issues "Missing (cell/defcell ...) form. Must use cell/defcell to register the cell."))
    ;; Check cell ID matches
    (when-let [[_ found-id] (re-find #"defcell\s+:(\S+)" source)]
      (let [expected (cond-> (str cell-id)
                       (str/starts-with? (str cell-id) ":") (subs 1))]
        (when (not= found-id expected)
          (conj! issues (str "Wrong cell ID: found `:" found-id "` but expected `:" expected "`")))))
    ;; Check for :doc in defcell opts
    (when (and (re-find #"defcell" source)
               (not (re-find #":doc\s" source)))
      (conj! issues "Missing :doc key in defcell opts map. The opts MUST include :doc."))
    ;; Check for [mycelium.cell :as cell] require
    (when (and (re-find #"\(ns\s" source)
               (not (re-find #"mycelium\.cell" source)))
      (conj! issues "Missing [mycelium.cell :as cell] in :require. Add it to the ns form."))
    ;; Return combined issues or nil
    (let [result (persistent! issues)]
      (when (seq result)
        (str/join "\n\n" result)))))

(defn- build-structural-fix-prompt
  "Builds a prompt asking the LLM to fix structural issues in the source."
  [source issues cell-id cell-ns]
  (str "The implementation has structural problems that must be fixed:\n\n"
       issues "\n\n"
       "**Expected structure:**\n```clojure\n"
       "(ns " cell-ns "\n"
       "  (:require [mycelium.cell :as cell]))\n\n"
       "(cell/defcell :" cell-id "\n"
       "  {:doc \"...\" :input [...] :output [...]}\n"
       "  (fn [resources data] ...))\n```\n\n"
       "**Current code:**\n```clojure\n" source "\n```\n\n"
       "Fix ALL the structural issues above and return the corrected source."))

;; ── Implementation ─────────────────────────────────────────────

(defn- build-impl-prompt
  "Builds the prompt to implement a cell against its test contract.
   Asks the LLM to return ONLY the handler function (and optional helpers).
   The ns, defcell, doc, schema, requires are injected by codegen."
  [{:keys [brief test-body]}]
  (str "Implement the handler function for the following Mycelium cell.\n\n"
       "**Cell ID:** `" (:id brief) "`\n"
       (when (:doc brief)
         (str "**Purpose:** " (:doc brief) "\n"))
       (when (:schema brief)
         (str "**Schema:** `" (:schema brief) "`\n"))
       (when (seq (:requires brief))
         (str "**Resources available in handler:**\n"
              (str/join "\n"
                (map (fn [r]
                       (let [doc (get (:resource-docs brief) (keyword r)
                                     (get (:resource-docs brief) r))]
                         (if doc
                           (str "- `" r "` — " doc)
                           (str "- `" r "`"))))
                     (:requires brief)))
              "\n\nAccess via: `(let [{:keys ["
              (str/join " " (map name (:requires brief)))
              "]} resources] ...)`\n"))
       (when (:context brief)
         (str "\n" (:context brief) "\n"))
       "\n**Tests your implementation must pass:**\n```clojure\n"
       test-body "\n```\n"
       (when (prompts/needs-math-precision? (:schema brief))
         (str "\n" prompts/math-precision-rules "\n"))
       "\nReturn ONLY:\n"
       "1. (OPTIONAL) Helper functions — define any helper `defn` forms you need\n"
       "2. (REQUIRED) `(fn [resources data] ...)` — MUST be the LAST form\n\n"
       "If you need extra requires beyond `[mycelium.cell :as cell]`, list each as a comment:\n"
       ";; REQUIRE: [clojure.string :as str]\n\n"
       "Do NOT include `(ns ...)` or `(cell/defcell ...)` — those are generated for you.\n"))

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
            raw-code (or (extract/extract-first-code-block content) content)
            ;; Extract fn body + helpers from LLM response, assemble with codegen
            fn-body       (extract/extract-fn-body raw-code)
            helpers       (extract/extract-helpers raw-code)
            extra-requires (extract/extract-extra-requires raw-code)
            cell-id-kw    (let [s (str cell-id)]
                            (keyword (cond-> s (str/starts-with? s ":") (subs 1))))
            ;; Parse schema from brief string and normalize to Malli
            schema-parsed (try
                            (let [raw (binding [*read-eval* false]
                                        (clojure.edn/read-string (:schema brief)))
                                  ;; Convert string-key maps to keyword-key maps (lite Malli)
                                  fix-keys (fn fix-keys [v]
                                             (cond
                                               (and (map? v) (every? string? (keys v)))
                                               (into {} (map (fn [[k vv]] [(keyword k) (fix-keys vv)])) v)
                                               (and (vector? v) (= "map" (first v)))
                                               (into [:map] (map (fn [entry]
                                                                    (if (vector? entry)
                                                                      [(keyword (first entry)) (keyword (second entry))]
                                                                      (keyword entry)))
                                                                  (rest v)))
                                               (string? v) (keyword v)
                                               :else v))]
                              {:input  (fix-keys (:input raw))
                               :output (fix-keys (:output raw))})
                            (catch Exception _ {:input [:map] :output [:map]}))
            ;; If LLM returned a full (ns ...) + (cell/defcell ...) form, use it directly
            ;; Otherwise assemble from extracted parts
            source (if fn-body
                     (codegen/assemble-cell-source
                       {:cell-ns        cell-ns
                        :cell-id        cell-id-kw
                        :doc            (or (:doc brief) "")
                        :schema         schema-parsed
                        :requires       (mapv keyword (or (:requires brief) []))
                        :extra-requires (or extra-requires [])
                        :helpers        (or helpers [])
                        :fn-body        fn-body})
                     ;; Fallback: LLM returned full source, auto-fix it
                     (auto-fix-cell-source raw-code cell-id cell-ns))]

        (emit on-event "cell_implement" "written" :cell-id cell-id
              :attempt (inc attempt))

        ;; Eval cell + tests together (avoids require-can't-find-namespace issue)
        ;; Replace the cell require in test source so it doesn't try to load from disk
        (let [test-src-fixed (if cell-ns
                               (str/replace test-code
                                 (str "[" cell-ns "]")
                                 (str "[" cell-ns " :as _cell-ns]"))
                               test-code)
              combined (str source "\n\n" test-src-fixed)
              eval-res (ev/eval-code combined)]
          (if (not= :ok (:status eval-res))
            ;; Eval failed (cell or test loading)
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
                            "\n```\nPlease fix and return the corrected "
                            "(fn [resources data] ...) handler.")
                       (inc attempt))
                {:status :error :error (:error eval-res) :cell-id cell-id}))

            ;; Eval succeeded — run the tests from the loaded test namespace
            (let [test-ns-name (second (re-find #"\(ns\s+(\S+)" test-code))
                  test-ns      (when test-ns-name (find-ns (symbol test-ns-name)))
                  test-res     (if test-ns
                                 ;; Run tests via clojure.test
                                 (let [out       (java.io.StringWriter.)
                                       counters  (atom {:test 0 :pass 0 :fail 0 :error 0})
                                       reporter  (fn [m]
                                                   (case (:type m)
                                                     :begin-test-ns nil
                                                     :end-test-ns nil
                                                     :begin-test-var (swap! counters update :test inc)
                                                     :pass (swap! counters update :pass inc)
                                                     :fail (do (swap! counters update :fail inc)
                                                               (.write out
                                                                 (str "\nFAIL in " (clojure.test/testing-vars-str m) "\n"
                                                                      (:message m "")
                                                                      "\nexpected: " (pr-str (:expected m))
                                                                      "\n  actual: " (pr-str (:actual m)) "\n")))
                                                     :error (do (swap! counters update :error inc)
                                                                (.write out
                                                                  (str "\nERROR in " (clojure.test/testing-vars-str m) "\n"
                                                                       (:message m "")
                                                                       "\n  actual: " (pr-str (:actual m)) "\n")))
                                                     :summary nil
                                                     nil))
                                       _         (binding [*out* out
                                                           clojure.test/report reporter]
                                                   (clojure.test/run-tests test-ns))
                                       summary   @counters]
                                   {:status  :ok
                                    :passed? (and (zero? (:fail summary))
                                                  (zero? (:error summary)))
                                    :summary summary
                                    :output  (str (:output eval-res) (str out))})
                                 ;; Test namespace not found
                                 {:status :error
                                  :error  (str "Test namespace not found: " test-ns-name)
                                  :output (:output eval-res)})]
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
                (let [formatted-source (format-source source)]
                  (emit on-event "cell_test" "passed" :cell-id cell-id
                        :attempt (inc attempt))
                  ;; Save cell to store
                  (when store
                    (let [defcell-code (extract/extract-defcell content)]
                      (store/save-cell! store
                        {:id         cell-id
                         :handler    (format-source (or defcell-code source))
                         :schema     (or (:schema brief) "")
                         :doc        (or (:doc brief) "")
                         :created-by "cell-agent-tdd"})))
                  ;; Write formatted source file to disk
                  (when (and project-path base-ns)
                    (source-gen/write-cell! project-path base-ns cell-id formatted-source)
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
     :manifest      — parsed manifest map (used for graph context + validation)
     :base-ns       — namespace prefix
     :store         — store instance
     :on-event      — event callback
     :on-chunk      — streaming callback
     :on-test-review — review callback: (fn [contracts] -> responses)
     :auto-approve? — skip review gates (default false)
     :max-attempts  — max fix attempts per cell (default 3)
     :manifest-id   — manifest ID for run tracking"
  [client {:keys [leaves manifest base-ns store on-event on-chunk
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

    ;; Build reverse mapping: cell-id → cell-name (for graph context lookup)
    (let [id->cell-name (when manifest
                          (into {} (map (fn [[cell-name cell-def]]
                                         [(:id cell-def) cell-name]))
                                (:cells manifest)))

          ;; Load resource docs from system.edn
          resource-docs (when project-path
                          (extract-resource-docs
                            (read-system-edn (str project-path "/resources/system.edn"))))

          ;; Build briefs from leaves (accept both kebab and underscore keys from JSON)
          briefs (mapv (fn [leaf]
                         (let [cell-id (or (:cell-id leaf) (:cell_id leaf))
                               doc (or (:doc leaf) "")
                               input-schema (or (:input-schema leaf) (:input_schema leaf) "{}")
                               output-schema (or (:output-schema leaf) (:output_schema leaf) "{}")
                               requires (or (:requires leaf) [])
                               ;; Build graph context from manifest
                               cell-name (when id->cell-name
                                           (get id->cell-name (keyword cell-id)
                                                (get id->cell-name cell-id)))
                               context (when (and manifest cell-name)
                                         (let [ctx (mv/build-graph-context manifest cell-name)]
                                           (mv/format-graph-context ctx)))]
                           {:id            cell-id
                            :doc           doc
                            :schema        (str "{:input " input-schema " :output " output-schema "}")
                            :requires      requires
                            :resource-docs resource-docs
                            :context       context}))
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

            ;; Phase 4: Post-implementation workflow compilation check
            (when (and manifest (seq passed) (empty? failed) (empty? failed-impl))
              (emit on-event "compile" "started")
              (try
                (let [compile-result (ev/compile-workflow (pr-str manifest))]
                  (if (= :ok (:status compile-result))
                    (emit on-event "compile" "passed")
                    (emit on-event "compile" "failed"
                          :error (:error compile-result))))
                (catch Exception e
                  (emit on-event "compile" "failed"
                        :error (.getMessage e)))))

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

;; ── Interactive orchestration (event-driven) ─────────────────

(defonce ^:private runs (atom {}))

(defn get-run
  "Returns the current state for an orchestration run."
  [run-id]
  (get @runs run-id))

(defn- update-cell-status!
  "Updates a cell's status in the run state and emits an event."
  [run-id cell-id new-status & {:as extra}]
  (swap! runs assoc-in [run-id :cells cell-id :status] new-status)
  (let [run (get @runs run-id)
        on-event (get-in run [:callbacks :on-event])]
    (apply emit on-event "cell_status" (name new-status)
           :cell_id cell-id :run_id run-id
           (mapcat identity extra))))

(defn- run-cell-count [run-id status]
  (count (filter #(= status (:status (val %)))
                 (get-in @runs [run-id :cells]))))

(defn- check-orchestration-complete!
  "Checks if all cells are done and emits orchestration_complete if so."
  [run-id]
  (let [run    (get @runs run-id)
        cells  (:cells run)
        total  (count cells)
        done   (count (filter #(= :done (:status (val %))) cells))]
    (when (= done total)
      (let [on-event (get-in run [:callbacks :on-event])
            cell-ids (keys cells)]
        (when (:store run)
          (store/update-run-tree! (:store run) run-id "completed"
            (json/write-str {:passed (vec cell-ids) :failed []})))
        (emit on-event "complete" "done"
              :run_id run-id :passed total :failed 0)))))

(defn start-orchestration!
  "Starts interactive orchestration: creates run, writes manifest to disk,
   generates tests for all cells in parallel. Emits test_ready per cell.
   Does NOT block — returns the run-id immediately."
  [client {:keys [leaves manifest base-ns store project-path
                  on-event on-chunk manifest-id]
           :or   {base-ns "app" manifest-id ""}}]
  (let [run-id   (str "run-" (System/nanoTime))
        on-event (or on-event (fn [_]))
        on-chunk (or on-chunk (fn [_]))

        ;; Build reverse mapping for graph context
        id->cell-name (when manifest
                        (into {} (map (fn [[cn cd]] [(:id cd) cn]))
                              (:cells manifest)))

        ;; Load resource docs from system.edn
        resource-docs (try
                        (when project-path
                          (let [f (java.io.File. (str project-path "/resources/system.edn"))]
                            (when (.exists f)
                              (let [sys (binding [*read-eval* false]
                                          (read-string (slurp f)))]
                                (->> sys
                                     (keep (fn [[k v]]
                                             (when (and (map? v) (:mycelium/doc v))
                                               [(keyword (name k)) (:mycelium/doc v)])))
                                     (into {}))))))
                        (catch Exception _ nil))

        ;; Build briefs from leaves
        briefs (mapv (fn [leaf]
                       (let [cell-id (or (:cell-id leaf) (:cell_id leaf))
                             doc (or (:doc leaf) "")
                             input-schema (or (:input-schema leaf) (:input_schema leaf) "{}")
                             output-schema (or (:output-schema leaf) (:output_schema leaf) "{}")
                             requires (or (:requires leaf) [])
                             cell-name (when id->cell-name
                                         (get id->cell-name (keyword cell-id)
                                              (get id->cell-name cell-id)))
                             context (when (and manifest cell-name)
                                       (let [ctx (mv/build-graph-context manifest cell-name)]
                                         (mv/format-graph-context ctx)))]
                         {:id            cell-id
                          :doc           doc
                          :schema        (str "{:input " input-schema " :output " output-schema "}")
                          :requires      requires
                          :resource-docs resource-docs
                          :context       context}))
                     leaves)

        ;; Initialize per-cell state
        cells-init (into {} (map (fn [brief]
                                   [(:id brief) {:status :test_generating
                                                 :brief  brief}])
                                 briefs))]

    ;; Create run in store
    (when store
      (store/create-run! store
        {:id run-id :spec-hash "" :manifest-id manifest-id :status "running"}))

    ;; Write manifest to disk
    (when (and project-path manifest)
      (source-gen/write-manifest! project-path base-ns
        (or manifest-id (str (:id manifest)))
        (pr-str manifest)))

    ;; Store run state
    (swap! runs assoc run-id
      {:cells        cells-init
       :base-ns      base-ns
       :manifest     manifest
       :client       client
       :store        store
       :project-path project-path
       :callbacks    {:on-event on-event :on-chunk on-chunk}})

    (emit on-event "orchestration" "started"
          :run_id run-id
          :cell_ids (mapv :id briefs))

    ;; Generate tests for all cells in parallel
    (doseq [brief briefs]
      (future
        (try
          (let [contract (generate-test-contract client
                           {:brief    brief
                            :base-ns  base-ns
                            :store    store
                            :run-id   run-id
                            :on-event on-event
                            :on-chunk on-chunk})]
            (swap! runs assoc-in [run-id :cells (:id brief) :contract] contract)
            (swap! runs assoc-in [run-id :cells (:id brief) :status] :test_ready)
            ;; Emit test_ready with the test code
            (emit on-event "cell_status" "test_ready"
                  :cell_id (:id brief)
                  :run_id run-id
                  :test_code (:test-code contract)
                  :test_body (:test-body contract)))
          (catch Exception e
            (swap! runs assoc-in [run-id :cells (:id brief) :status] :test_error)
            (emit on-event "cell_status" "test_error"
                  :cell_id (:id brief)
                  :run_id run-id
                  :error (.getMessage e))))))

    run-id))

(defn approve-tests!
  "Approves a cell's tests. Writes test file to disk, starts implementation."
  [run-id cell-id]
  (let [run      (get @runs run-id)
        cell     (get-in run [:cells cell-id])
        contract (:contract cell)
        store    (:store run)
        client   (:client run)
        base-ns  (:base-ns run)
        on-event (get-in run [:callbacks :on-event])
        on-chunk (get-in run [:callbacks :on-chunk])]
    (when contract
      ;; Update store
      (when store
        (store/update-test-contract-status! store run-id cell-id "approved"))
      ;; Write test file to disk
      (when (:project-path run)
        (source-gen/write-test! (:project-path run) base-ns cell-id
          (:test-code contract)))
      (update-cell-status! run-id cell-id :test_approved)

      ;; Start implementation in background
      (swap! runs assoc-in [run-id :cells cell-id :status] :implementing)
      (emit on-event "cell_status" "implementing"
            :cell_id cell-id :run_id run-id)
      (future
        (try
          (let [result (implement-from-contract client
                         {:contract     contract
                          :store        store
                          :run-id       run-id
                          :on-event     on-event
                          :on-chunk     on-chunk
                          :max-attempts 3
                          :project-path nil ;; don't write to disk yet
                          :base-ns      base-ns})]
            (if (= :ok (:status result))
              ;; Implementation succeeded — get source from store (where implement-from-contract saved it)
              (let [latest-cell (when store (store/get-latest-cell store cell-id))
                    impl-source (format-source (or (:handler latest-cell) ""))]
                (if (seq impl-source)
                  (do
                    (swap! runs update-in [run-id :cells cell-id]
                           assoc :impl-source impl-source :status :impl_ready)
                    (emit on-event "cell_status" "impl_ready"
                          :cell_id cell-id
                          :run_id run-id
                          :source impl-source
                          :test_output (or (:output result) "")))
                  (do
                    (swap! runs assoc-in [run-id :cells cell-id :status] :impl_error)
                    (emit on-event "cell_status" "impl_error"
                          :cell_id cell-id :run_id run-id
                          :error "Implementation produced no source code"))))
              (do
                (swap! runs assoc-in [run-id :cells cell-id :status] :impl_error)
                (emit on-event "cell_status" "impl_error"
                      :cell_id cell-id
                      :run_id run-id
                      :error (or (:error result) "Implementation failed")))))
          (catch Exception e
            (swap! runs assoc-in [run-id :cells cell-id :status] :impl_error)
            (emit on-event "cell_status" "impl_error"
                  :cell_id cell-id :run_id run-id
                  :error (.getMessage e))))))))

(defn reject-tests!
  "Rejects a cell's tests with feedback. Re-generates tests."
  [run-id cell-id feedback]
  (let [run      (get @runs run-id)
        cell     (get-in run [:cells cell-id])
        contract (:contract cell)
        client   (:client run)
        base-ns  (:base-ns run)
        store    (:store run)
        on-event (get-in run [:callbacks :on-event])
        on-chunk (get-in run [:callbacks :on-chunk])]
    (when contract
      (update-cell-status! run-id cell-id :test_generating)
      (future
        (try
          (let [session (or (:session contract)
                            (llm/create-session (str "test:" cell-id) prompts/cell-prompt))
                annotated (hashline/annotate-hashlines (:test-code contract))
                msg     (str "The tests need changes:\n\n"
                             feedback
                             "\n\n**Current test code:**\n```\n"
                             annotated
                             "\n```\n\nReturn ONLY the corrected `deftest` forms.")
                content (llm/session-send-stream session client msg on-chunk)
                body    (or (extract/extract-first-code-block content) content)
                cell-ns  (cell-ns-name base-ns cell-id)
                test-ns  (test-ns-name base-ns cell-id)
                cell-kw  (let [s (str cell-id)]
                           (keyword (cond-> s (str/starts-with? s ":") (subs 1))))
                test-code (codegen/assemble-test-source
                            {:test-ns  test-ns
                             :cell-ns  cell-ns
                             :cell-id  cell-kw
                             :test-body body})
                new-contract (assoc contract
                               :test-code test-code
                               :test-body body
                               :revision (inc (or (:revision contract) 0)))]
            (when store
              (store/save-test-contract! store
                {:run-id run-id :cell-id cell-id
                 :test-code test-code :test-body body
                 :status "pending"
                 :revision (:revision new-contract)
                 :feedback feedback}))
            (swap! runs assoc-in [run-id :cells cell-id :contract] new-contract)
            (swap! runs assoc-in [run-id :cells cell-id :status] :test_ready)
            (emit on-event "cell_status" "test_ready"
                  :cell_id cell-id :run_id run-id
                  :test_code test-code :test_body body))
          (catch Exception e
            (swap! runs assoc-in [run-id :cells cell-id :status] :test_error)
            (emit on-event "cell_status" "test_error"
                  :cell_id cell-id :run_id run-id
                  :error (.getMessage e))))))))

(defn save-tests!
  "Saves user-edited test code. Updates store, writes to disk, starts implementation."
  [run-id cell-id test-code]
  (let [run      (get @runs run-id)
        cell     (get-in run [:cells cell-id])
        contract (:contract cell)
        store    (:store run)]
    (when contract
      (let [new-revision (inc (or (:revision contract) 0))
            new-contract (assoc contract
                           :test-code test-code
                           :test-body (:test-body contract) ;; preserve original test-body
                           :revision new-revision)]
        ;; Update contract in run state FIRST so approve-tests! uses the edited code
        (swap! runs assoc-in [run-id :cells cell-id :contract] new-contract)
        ;; Update store
        (when store
          (store/save-test-contract! store
            {:run-id run-id :cell-id cell-id
             :test-code test-code
             :test-body (or (:test-body contract) "")
             :status "approved"
             :revision new-revision}))
        ;; Delegate to approve flow (writes edited test-code to disk + starts implementation)
        (approve-tests! run-id cell-id)))))

(defn approve-impl!
  "Approves a cell's implementation. Formats, writes to disk, saves to store."
  [run-id cell-id]
  (let [run         (get @runs run-id)
        cell        (get-in run [:cells cell-id])
        source      (format-source (or (:impl-source cell) ""))
        store       (:store run)
        base-ns     (:base-ns run)
        brief       (:brief cell)
        on-event    (get-in run [:callbacks :on-event])]
    (if-not (seq source)
      (emit on-event "cell_status" "impl_error"
            :cell_id cell-id :run_id run-id
            :error "No implementation source to approve")
      (do
        ;; Write to disk
        (when (:project-path run)
          (source-gen/write-cell! (:project-path run) base-ns cell-id source))
        ;; Save to store
        (when store
          (store/save-cell! store
            {:id         cell-id
             :handler    source
             :schema     (or (:schema brief) "")
             :doc        (or (:doc brief) "")
             :created-by "cell-agent-interactive"}))
        (swap! runs assoc-in [run-id :cells cell-id :status] :done)
        (emit on-event "cell_status" "done"
              :cell_id cell-id :run_id run-id)
        (check-orchestration-complete! run-id)))))

(defn reject-impl!
  "Rejects a cell's implementation with feedback. Re-implements."
  [run-id cell-id feedback]
  (let [run      (get @runs run-id)
        cell     (get-in run [:cells cell-id])
        contract (:contract cell)
        client   (:client run)
        store    (:store run)
        base-ns  (:base-ns run)
        on-event (get-in run [:callbacks :on-event])
        on-chunk (get-in run [:callbacks :on-chunk])]
    (when contract
      (update-cell-status! run-id cell-id :implementing)
      (future
        (try
          (let [session (or (:session contract)
                            (llm/create-session (str "impl:" cell-id) prompts/cell-prompt))
                annotated (hashline/annotate-hashlines
                            (or (:impl-source cell) ""))
                msg     (str "The implementation needs changes:\n\n" feedback
                             (when (seq annotated)
                               (str "\n\n**Current implementation:**\n```\n"
                                    annotated "\n```"))
                             "\n\nPlease fix and return the corrected source "
                             "including `(ns ...)` and `(cell/defcell ...)`.")
                content (llm/session-send-stream session client msg on-chunk)
                source  (format-source
                          (or (extract/extract-first-code-block content) content))]
            ;; Eval + run tests
            (let [eval-res (ev/eval-code source)]
              (let [[test-output passed?]
                    (if (not= :ok (:status eval-res))
                      [(str "Eval error: " (:error eval-res)) false]
                      (let [test-res (ev/run-cell-tests (:test-code contract))]
                        [(or (:output test-res) "")
                         (and (= :ok (:status test-res)) (:passed? test-res))]))]
                (swap! runs update-in [run-id :cells cell-id]
                       assoc :impl-source source :status :impl_ready)
                (emit on-event "cell_status" "impl_ready"
                      :cell_id cell-id :run_id run-id
                      :source source
                      :test_output test-output
                      :tests_passed passed?))))
          (catch Exception e
            (swap! runs assoc-in [run-id :cells cell-id :status] :impl_error)
            (emit on-event "cell_status" "impl_error"
                  :cell_id cell-id :run_id run-id
                  :error (.getMessage e))))))))

(defn save-impl!
  "Saves user-edited implementation. Evals, runs tests. If pass: writes to disk."
  [run-id cell-id source]
  (let [run      (get @runs run-id)
        cell     (get-in run [:cells cell-id])
        contract (:contract cell)
        store    (:store run)
        on-event (get-in run [:callbacks :on-event])
        source   (format-source source)]
    (when contract
      ;; Eval the code
      (let [eval-res (ev/eval-code source)]
        (if (not= :ok (:status eval-res))
          (do
            (swap! runs assoc-in [run-id :cells cell-id :impl-source] source)
            (emit on-event "cell_status" "impl_ready"
                  :cell_id cell-id :run_id run-id
                  :source source
                  :test_output (str "Eval error: " (:error eval-res))
                  :tests_passed false))
          ;; Run tests
          (let [test-res (ev/run-cell-tests (:test-code contract))]
            (if (and (= :ok (:status test-res)) (:passed? test-res))
              (do
                (swap! runs assoc-in [run-id :cells cell-id :impl-source] source)
                (approve-impl! run-id cell-id))
              (do
                (swap! runs assoc-in [run-id :cells cell-id :impl-source] source)
                (emit on-event "cell_status" "impl_ready"
                      :cell_id cell-id :run_id run-id
                      :source source
                      :test_output (or (:output test-res) "Tests failed")
                      :tests_passed false)))))))))
