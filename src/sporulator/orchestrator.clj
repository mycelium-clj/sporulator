(ns sporulator.orchestrator
  "TDD orchestrator: generate tests → review → implement → verify.
   Coordinates graph and cell agents through the full workflow."
  (:require [cljfmt.core :as cljfmt]
            [clojure.data.json :as json]
            [zprint.core :as zp]
            [clojure.string :as str]
            [sporulator.agent-loop :as agent-loop]
            [sporulator.cell-agent :as cell-agent]
            [sporulator.codegen :as codegen]
            [sporulator.eval :as ev]
            [sporulator.extract :as extract]
            [sporulator.feedback :refer [feedback-loop]]
            [sporulator.llm :as llm]
            [sporulator.hashline :as hashline]
            [sporulator.manifest-diff :as manifest-diff]
            [sporulator.manifest-validate :as mv]
            [sporulator.prompts :as prompts]
            [sporulator.source-gen :as source-gen]
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

(defn parse-schema-output
  "Parses a brief's :schema string and returns the :output value, or nil
   if the string is unparseable. Tolerates symbol/string keys."
  [schema-str]
  (when (seq (str schema-str))
    (try
      (let [raw (binding [*read-eval* false]
                  (clojure.edn/read-string (str schema-str)))
            kw  (fn kw [v]
                  (cond
                    (and (map? v) (seq v)
                         (some #(or (string? %) (symbol? %)) (keys v)))
                    (into {} (map (fn [[k vv]] [(keyword (name k)) (kw vv)])) v)
                    (map? v) (into {} (map (fn [[k vv]] [k (kw vv)])) v)
                    :else v))]
        (kw (:output raw)))
      (catch Exception _ nil))))

(defn dispatched-output?
  "True if `output` is a dispatched-output map: keyed by transition labels
   (e.g. :success / :failure), each value an inner sub-schema. Recognises
   both the lite-form (map values) and the Malli-form (vector values).

   {:success {:n :int} :failure {:e :string}}            → true
   {:success [:map [:n :int]] :failure [:map [:e :string]]} → true
   {:n :int :x :string}                                   → false (flat)
   {} or [:map ...]                                       → false"
  [output]
  (and (map? output) (seq output)
       (every? (fn [v] (or (and (map? v) (seq v))
                            (vector? v)))
               (vals output))))

(defn- dispatched-shape-block
  "Renders the dispatched-output guidance block for the test/implementor
   prompts when the cell's output is dispatched. `output` is the parsed
   dispatched-output map."
  [output]
  (let [pairs (vec output)
        first-pair (first pairs)
        first-label (name (key first-pair))
        first-keys (cond
                     (map? (val first-pair)) (vec (keys (val first-pair)))
                     :else [])
        sample-key (or (first first-keys) :result)
        labels (mapv (comp name key) pairs)]
    (str "## Dispatched output — read carefully\n"
         "This cell has a dispatched output schema. Mycelium dispatches by\n"
         "looking at which keys appear in the handler's flat return map and\n"
         "matching them against the per-transition sub-schemas declared in\n"
         "`:output`. Possible transitions: "
         (str/join " | " (map #(str "`" % "`") labels))
         ".\n\n"
         "**The handler returns a flat map matching ONE of the transition\n"
         "sub-schemas — it never wraps the result under the transition\n"
         "label.** Tests should assert against the flat shape, not against\n"
         "a nested `{:" first-label " {…}}` form.\n\n"
         "Example shape (assuming `:" first-label "` carries `" sample-key "`):\n"
         "```clojure\n"
         "(deftest test-" first-label "-path\n"
         "  (let [result (handler {} {…})]\n"
         "    (is (contains? result " sample-key "))))\n"
         "\n"
         "(deftest test-other-path\n"
         "  (let [result (handler {} {…})]\n"
         "    (is (contains? result :error))))    ;; or whatever the failure key is\n"
         "```\n"
         "Do NOT do `(:" first-label " result)` or `(get-in result [:"
         first-label " ...])` — there is no such wrapper key.\n")))

(defn- format-resources-block
  "Formats per-resource docstrings into a 'Resources' section.
   `requires` is the vector of resource keywords; `resource-docs` is a map
   from resource keyword to its docstring (extracted from system.edn)."
  [requires resource-docs]
  (when (seq requires)
    (str "**Resources** (passed as the first arg of the handler):\n"
         (str/join "\n"
           (for [r requires
                 :let [k    (keyword (name r))
                       doc  (get resource-docs k
                                  (get resource-docs r))]]
             (if doc
               (str "- `" r "` — " doc)
               (str "- `" r "` (no docstring; treat as the real runtime resource)"))))
         "\n")))

(defn- build-test-prompt
  "Builds the prompt to ask the LLM to write tests for a cell."
  [{:keys [id doc schema requires resource-docs context]}]
  (let [output (parse-schema-output schema)]
    (str "Write tests for the following Mycelium cell using clojure.test.\n\n"
       "**Cell ID:** `" id "`\n"
       "**Requirements:** " doc "\n"
       "**Schema:** `" schema "`\n"
       (format-resources-block requires resource-docs)
       (when context
         (str "\n" context "\n"))
       (when (prompts/needs-math-precision? schema)
         (str "\n" prompts/math-precision-rules "\n"))
       (when (dispatched-output? output)
         (str "\n" (dispatched-shape-block output)))
       "\n## Calling convention — read carefully\n"
       "The handler signature is `(fn [resources data] ...)`.\n"
       "- `resources` is a map. The keys are the cell's `:requires` keywords.\n"
       "  Their values are the **real runtime objects** (a JDBC DataSource\n"
       "  for `:db`, an HTTP client for `:http`, etc.) — NOT mock maps that\n"
       "  pretend to expose framework functions as keyed entries. Do NOT\n"
       "  fake them as `{:execute-one! (fn ...)}` or `{:get (fn ...)}`.\n"
       "  If you need to test a `:db` interaction, use a real in-memory\n"
       "  SQLite DataSource:\n"
       "    `(next.jdbc/get-datasource {:dbtype \"sqlite\" :dbname (str \"/tmp/test-\" (System/nanoTime) \".sqlite\")})`,\n"
       "  create the table the cell needs, exercise the handler against it.\n"
       "- `data` is a flat Clojure map whose keys match the cell's `:input`\n"
       "  schema *directly*. The schema `{:handle :string}` means the\n"
       "  handler receives `{:handle \"alice\"}` — NOT `{:input {:handle \"alice\"}}`.\n"
       "  Never wrap the data under an `:input` or `:output` key.\n\n"
       "Return ONLY the `deftest` forms. Do NOT include `(ns ...)` or `:require` —\n"
       "those are added automatically.\n\n"
       "Example shape (no resources):\n"
       "```clojure\n"
       "(deftest test-basic-case\n"
       "  (let [result (handler {} {:key \"value\"})]\n"
       "    (is (= expected-value (:expected-key result)))))\n"
       "```\n\n"
       "Example shape (with `:db` resource):\n"
       "```clojure\n"
       "(deftest test-db-interaction\n"
       "  (let [tmp (str \"/tmp/test-\" (System/nanoTime) \".sqlite\")\n"
       "        ds  (next.jdbc/get-datasource {:dbtype \"sqlite\" :dbname tmp})]\n"
       "    (next.jdbc/execute! ds [\"CREATE TABLE foo (id INTEGER PRIMARY KEY, x TEXT)\"])\n"
       "    (let [result (handler {:db ds} {:x \"hi\"})]\n"
       "      (is (int? (:id result))))))\n"
       "```\n"
       "If the test needs `next.jdbc`, just use it as `next.jdbc/...` —\n"
       "the test namespace requires it for you.\n")))

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

    ;; Generate test body (strip code fences if present)
    (let [test-prompt (build-test-prompt brief)
          raw-test    (:content (llm/session-send-stream session client test-prompt on-chunk))
          test-body   (or (extract/extract-first-code-block raw-test) raw-test)]

      (emit on-event "cell_test" "written" :cell-id cell-id)

      ;; Self-review
      (let [review-prompt (self-review-prompt brief test-body)
            review-resp   (:content (llm/session-send-stream session client review-prompt on-chunk))
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

(defn- format-lint-errors [lint]
  (str/join "\n" (map #(str "- Line " (:line %) ": " (:message %)) (:errors lint))))

(defn lint-fix-loop
  "Runs clj-kondo on code. If lint errors found, asks LLM to fix syntax
   only (no logic changes). Retries up to max-attempts.
   Returns {:status :ok :code fixed-code} or {:status :error :error msg}."
  [client session code cell-id
   & {:keys [max-attempts on-chunk on-event]
      :or   {max-attempts 3}}]
  ;; Check if code is already clean
  (let [lint (ev/lint-code code)]
    (if-not (seq (:errors lint))
      {:status :ok :code code}
      ;; Lint errors — enter feedback loop
      (let [result (feedback-loop
                     {:client      client
                      :session     (or session (llm/create-session (str "lint:" cell-id) ""))
                      :initial-msg (str "Fix ONLY the syntax errors in this code. "
                                        "Do NOT change any logic.\n\n"
                                        "**Errors:**\n" (format-lint-errors lint)
                                        "\n\n**Code:**\n```clojure\n" code "\n```")
                      :extract-fn  (fn [raw] (or (extract/extract-first-code-block raw) raw))
                      :validate-fn (fn [fixed-code]
                                     (let [lint (ev/lint-code fixed-code)]
                                       (if (seq (:errors lint))
                                         {:error (format-lint-errors lint)}
                                         {:ok fixed-code})))
                      :error-msg-fn (fn [fixed-code error]
                                      (str "Fix ONLY the syntax errors. Do NOT change logic.\n\n"
                                           "**Remaining errors:**\n" error
                                           "\n\n**Code:**\n```clojure\n" fixed-code "\n```"))
                      :on-chunk    (or on-chunk (fn [_]))
                      :on-attempt  (fn [{:keys [attempt error]}]
                                     (when on-event
                                       (on-event {:phase "cell_implement" :status "lint_fix"
                                                  :cell-id cell-id :attempt attempt
                                                  :message (or error "")})))
                      :max-attempts max-attempts})]
        (if (= :ok (:status result))
          {:status :ok :code (:result result)}
          {:status :error :error (:error result)})))))

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
  "Implements a cell from a test contract using the interactive agent loop.
   The agent loop uses tool-call dispatch (DISPATCH -> REVIEW phases) instead
   of the old monolithic eval-feedback retry loop.

   Each cell is isolated — it gets its own call graph built dynamically
   from code it writes.  It does NOT see other cells or the workflow graph.

   Options:
     :contract     — test contract map
     :store        — store instance
     :run-id       — orchestration run ID
     :on-event     — event callback
     :on-chunk     — streaming callback
     :max-attempts — max fix attempts (default 3) — forwarded as turn-budget
     :project-path — project root directory for writing source files
     :base-ns      — base namespace for source files
     :task         — overall orchestration task description"
  [client {:keys [contract store run-id on-event on-chunk max-attempts
                  project-path base-ns task
                  prev-source change-summary]
           :or   {max-attempts 3}}]
  (let [{:keys [cell-id brief test-code cell-ns]} contract
        cell-id-kw (let [s (str cell-id)]
                     (keyword (cond-> s (str/starts-with? s ":") (subs 1))))
        schema-parsed (try
                        (let [raw (binding [*read-eval* false]
                                   (clojure.edn/read-string (:schema brief)))
                              fix-keys (fn fix-keys [v]
                                         (cond
                                           (and (map? v) (seq v)
                                                (every? #(or (string? %) (symbol? %)) (keys v)))
                                           (into {} (map (fn [[k vv]] [(keyword k) (fix-keys vv)])) v)
                                           (and (map? v) (seq v) (every? keyword? (keys v)))
                                           v
                                           (and (vector? v) (= "map" (first v)))
                                           (into [:map] (map (fn [entry]
                                                                (if (vector? entry)
                                                                  [(keyword (first entry)) (keyword (second entry))]
                                                                  (keyword entry)))
                                                              (rest v)))
                                           (string? v) (keyword v)
                                           (symbol? v) (keyword v)
                                           :else v))]
                          {:input  (fix-keys (:input raw))
                           :output (fix-keys (:output raw))})
                        (catch Exception _ {:input [:map] :output [:map]}))]
    (let [prev-parts (when prev-source (extract/extract-cell-source-parts prev-source))]
      (agent-loop/run!
        {:client          client
         :cell-id         cell-id-kw
         :cell-ns         cell-ns
         :brief           brief
         :test-code       test-code
         :schema-parsed   schema-parsed
         :turn-budget     max-attempts
         :on-event        on-event
         :on-chunk        on-chunk
         :store           store
         :run-id          run-id
         :project-path    project-path
         :base-ns         base-ns
         :task            task
         :initial-handler (:handler prev-parts)
         :initial-helpers (:helpers prev-parts)
         :change-summary  change-summary}))))
;; ── Main orchestration ─────────────────────────────────────────

(defn- bare-cell-id
  "Strips the leading colon from a cell-id string. ':guestbook/x' → 'guestbook/x'."
  [cell-id]
  (let [s (str cell-id)]
    (cond-> s (str/starts-with? s ":") (subs 1))))

(defn- cell-id-keyword
  "Normalises a cell-id (string or keyword) to a keyword for diff comparisons."
  [cell-id]
  (if (keyword? cell-id) cell-id (keyword (bare-cell-id cell-id))))

(defn- build-cell-class-index
  "Given a manifest-diff result, returns {cell-id-keyword → :added/:removed/...}."
  [diff-result]
  (reduce (fn [m k]
            (reduce #(assoc %1 %2 k) m (get diff-result k)))
          {}
          [:added :removed :schema-changed :doc-changed :unchanged]))

(defn- prior-cell-source
  "Returns the latest non-deprecated assembled source for a cell-id, or nil."
  [store cell-id]
  (when store
    (when-let [c (store/get-latest-cell store (bare-cell-id cell-id))]
      (:handler c))))

(defn- prior-test-code
  "Returns the test_code from the prior run for a cell-id, or nil."
  [store run-id cell-id]
  (when (and store run-id)
    (let [bare (bare-cell-id cell-id)
          ;; Test contracts are stored with the leading-colon form.
          contract (or (store/get-test-contract store run-id (str ":" bare))
                       (store/get-test-contract store run-id bare))]
      (:test-code contract))))

(defn- carry-over-result
  "Builds a synthetic :ok result for a carried-over cell so downstream
   reporting treats it identically to a freshly-implemented cell."
  [cell-id]
  {:status :ok :cell-id (cell-id-keyword cell-id) :carried-over? true})

(defn orchestrate!
  "Runs the full TDD orchestration for a set of leaf cells, diff-aware:
   compares the supplied manifest against the latest green snapshot for
   `manifest-id` and only regenerates cells whose contract changed.

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
           :as   _opts}]
  (let [run-id  (str "run-" (System/nanoTime))
        on-event (or on-event (fn [_]))
        on-chunk (or on-chunk (fn [_]))
        ;; Canonical manifest-id form (no leading colon) for snapshots
        ;; and run records.
        manifest-id (manifest-diff/normalize-manifest-id manifest-id)]

    ;; Create run in store
    (when store
      (store/create-run! store
        {:id          run-id
         :spec-hash   spec-hash
         :manifest-id manifest-id
         :status      "running"}))

    (emit on-event "manifest" "started" :run-id run-id)

    ;; ── Diff against the previous green snapshot ──────────────────
    (let [prev-snapshot   (when (and store (seq manifest-id))
                            (store/get-latest-green-snapshot store manifest-id))
          prev-manifest   (when prev-snapshot
                            (let [parsed (mv/parse-manifest (:body prev-snapshot))]
                              (when-not (:error parsed) parsed)))
          diff-result     (manifest-diff/diff prev-manifest manifest)
          cell-class      (build-cell-class-index diff-result)
          prev-cells-by-id (manifest-diff/cells-by-id prev-manifest)
          new-cells-by-id  (manifest-diff/cells-by-id manifest)
          actionable?      (fn [cell-id]
                             (let [k (cell-id-keyword cell-id)]
                               (#{:added :schema-changed :doc-changed} (get cell-class k))))]

      ;; Emit carry-over events for unchanged cells
      (doseq [cid (:unchanged diff-result)]
        (emit on-event "cell_carry_over" "skipped" :cell-id cid))

      ;; Handle removed cells: deprecate in store + delete file
      (doseq [cid (:removed diff-result)]
        (when store (store/deprecate-cell! store (bare-cell-id cid)))
        (when (and project-path base-ns)
          (try
            (source-gen/delete-cell! project-path base-ns (bare-cell-id cid))
            (catch Exception _ nil)))
        (emit on-event "cell_remove" "done" :cell-id cid))

      ;; Build reverse mapping: cell-id → cell-name (for graph context lookup)
      (let [id->cell-name (when manifest
                            (into {} (map (fn [[cell-name cell-def]]
                                            [(:id cell-def) cell-name]))
                                  (:cells manifest)))

            ;; Load resource docs from system.edn
            resource-docs (when project-path
                            (extract-resource-docs
                              (read-system-edn (str project-path "/resources/system.edn"))))

            ;; Filter leaves to only those that need regeneration. Carry-over
            ;; and removed cells are handled above; we don't waste LLM calls
            ;; on them.
            actionable-leaves (filterv (fn [leaf]
                                         (actionable? (or (:cell-id leaf)
                                                          (:cell_id leaf))))
                                       leaves)

            ;; Build briefs from actionable leaves
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
                         actionable-leaves)]

      ;; Phase 1: Generate test contracts.
      ;; For :doc-changed cells we reuse the prior run's test contract since
      ;; the schema and resources haven't moved — only the doc.
      (emit on-event "cell_test" "started" :message "Generating test contracts")
      (let [prev-run-id (:run-id prev-snapshot)
            contracts
            (mapv (fn [brief]
                    (let [class (get cell-class (cell-id-keyword (:id brief)))]
                      (try
                        (if (and (= class :doc-changed) prev-run-id)
                          (let [test-code (prior-test-code store prev-run-id (:id brief))]
                            (if (seq test-code)
                              (let [stored-cell-id (str (cell-id-keyword (:id brief)))
                                    contract {:cell-id   stored-cell-id
                                              :test-code test-code
                                              :test-body test-code
                                              :run-id    run-id
                                              :brief     brief
                                              :cell-ns   (cell-ns-name base-ns (:id brief))
                                              :reused?   true}]
                                ;; Persist a row for this run-id so future
                                ;; green snapshots can locate the contract
                                ;; via this run-id without falling back to
                                ;; fresh generation.
                                (when store
                                  (store/save-test-contract! store
                                    {:run-id    run-id
                                     :cell-id   stored-cell-id
                                     :test-code test-code
                                     :test-body test-code
                                     :status    "approved"})
                                  (emit on-event "test_review" "reused"
                                        :cell-id stored-cell-id
                                        :message "doc-only change; reused prior test contract"))
                                contract)
                              ;; No prior contract found; fall back to fresh.
                              (generate-test-contract client
                                {:brief    brief
                                 :base-ns  base-ns
                                 :store    store
                                 :run-id   run-id
                                 :on-event on-event
                                 :on-chunk on-chunk})))
                          (generate-test-contract client
                            {:brief    brief
                             :base-ns  base-ns
                             :store    store
                             :run-id   run-id
                             :on-event on-event
                             :on-chunk on-chunk}))
                        (catch Exception e
                          {:cell-id (:id brief)
                           :error   (.getMessage e)}))))
                  briefs)
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

          ;; Phase 3: Implement approved contracts in parallel.
          ;; Edit-mode opts (prev-source + change-summary) are computed
          ;; per-cell from the diff classification.
          (emit on-event "cell_implement" "started"
                :message (str "Implementing " (count approved) " cells"))
          (let [impl-futures
                (mapv (fn [contract]
                        (future
                          (try
                            (let [cid-kw   (cell-id-keyword (:cell-id contract))
                                  class    (get cell-class cid-kw)
                                  edit?    (#{:doc-changed :schema-changed} class)
                                  prev-src (when edit? (prior-cell-source store cid-kw))
                                  summary  (when edit?
                                             (manifest-diff/change-summary
                                               (get prev-cells-by-id cid-kw)
                                               (get new-cells-by-id cid-kw)))]
                              (implement-from-contract client
                                {:contract        contract
                                 :store           store
                                 :run-id          run-id
                                 :on-event        on-event
                                 :on-chunk        on-chunk
                                 :max-attempts    max-attempts
                                 :project-path    project-path
                                 :base-ns         base-ns
                                 :prev-source     prev-src
                                 :change-summary  summary}))
                            (catch Exception e
                              {:status  :error
                               :cell-id (:cell-id contract)
                               :error   (.getMessage e)}))))
                      approved)
                impl-results (mapv deref impl-futures)
                ;; Synthesize :ok results for cells that were carried over
                ;; — they didn't run through the implementor but downstream
                ;; reporting and the green snapshot still need to count them.
                carry-over-results (mapv carry-over-result (:unchanged diff-result))
                all-results (into impl-results carry-over-results)
                passed  (filterv #(= :ok (:status %)) all-results)
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

            ;; On full success, record a new green snapshot so subsequent
            ;; runs can diff against this manifest version.
            (when (and store (seq manifest-id) manifest
                       (empty? failed) (empty? failed-impl))
              (let [m-row (store/get-latest-manifest store manifest-id)]
                (store/save-green-snapshot! store
                  {:manifest-id      manifest-id
                   :manifest-version (or (:version m-row) 1)
                   :body             (or (:body m-row) (pr-str manifest))
                   :run-id           run-id})))

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
               :results      all-results
               :diff         diff-result}))))))))

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
  "Checks if all cells are done and emits orchestration_complete if so.
   On full success, records a new green snapshot so subsequent runs can
   diff against this manifest version."
  [run-id]
  (let [run    (get @runs run-id)
        cells  (:cells run)
        total  (count cells)
        done   (count (filter #(= :done (:status (val %))) cells))]
    (when (= done total)
      (let [on-event    (get-in run [:callbacks :on-event])
            cell-ids    (keys cells)
            store       (:store run)
            manifest-id (:manifest-id run)
            manifest    (:manifest run)]
        (when store
          (store/update-run-tree! store run-id "completed"
            (json/write-str {:passed (vec cell-ids) :failed []})))
        ;; Record the new green baseline. Diff-aware re-runs against this
        ;; manifest will now see the freshly-implemented cells as
        ;; :unchanged.
        (when (and store (seq manifest-id) manifest)
          (let [m-row (store/get-latest-manifest store manifest-id)]
            (store/save-green-snapshot! store
              {:manifest-id      manifest-id
               :manifest-version (or (:version m-row) 1)
               :body             (or (:body m-row) (pr-str manifest))
               :run-id           run-id})))
        (emit on-event "complete" "done"
              :run_id run-id :passed total :failed 0)))))

(defn start-orchestration!
  "Starts interactive orchestration: creates run, writes manifest to disk,
   generates tests for all cells in parallel. Emits test_ready per cell.
   Does NOT block — returns the run-id immediately.

   Diff-aware: leaves whose contract is unchanged from the latest green
   snapshot are dropped from the active set and emitted as cell_carry_over
   events. Removed cells are deprecated + their files deleted."
  [client {:keys [leaves manifest base-ns store project-path
                  on-event on-chunk manifest-id]
           :or   {base-ns "app" manifest-id ""}}]
  (let [run-id      (str "run-" (System/nanoTime))
        on-event    (or on-event (fn [_]))
        on-chunk    (or on-chunk (fn [_]))
        manifest-id (manifest-diff/normalize-manifest-id manifest-id)

        ;; ── Diff against the previous green snapshot ──────────────
        prev-snapshot (when (and store (seq manifest-id))
                        (store/get-latest-green-snapshot store manifest-id))
        prev-manifest (when prev-snapshot
                        (let [parsed (mv/parse-manifest (:body prev-snapshot))]
                          (when-not (:error parsed) parsed)))
        diff-result   (manifest-diff/diff prev-manifest manifest)
        cell-class    (build-cell-class-index diff-result)
        prev-cells-by-id (manifest-diff/cells-by-id prev-manifest)
        new-cells-by-id  (manifest-diff/cells-by-id manifest)
        actionable?      (fn [cell-id]
                           (#{:added :schema-changed :doc-changed}
                            (get cell-class (cell-id-keyword cell-id))))

        ;; Filter leaves to only those whose contracts changed.
        active-leaves (filterv (fn [leaf]
                                 (actionable? (or (:cell-id leaf)
                                                  (:cell_id leaf))))
                               leaves)
        leaves        active-leaves

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

    ;; Carry-over cells: unchanged from the last green run. Emit an event
    ;; so the UI shows them as already done, and seed them as :done in run
    ;; state so check-orchestration-complete! counts them toward total.
    (let [carry-over-init
          (into {} (for [cid (:unchanged diff-result)]
                     [(str cid) {:status :done :carry-over? true}]))
          cells-init (merge cells-init carry-over-init)]

      ;; Removed cells: deprecate in store + delete file from disk.
      (doseq [cid (:removed diff-result)]
        (when store
          (store/deprecate-cell! store (bare-cell-id cid)))
        (when (and project-path base-ns)
          (try (source-gen/delete-cell! project-path base-ns (bare-cell-id cid))
               (catch Exception _ nil)))
        (emit on-event "cell_remove" "done" :cell_id cid))

      (doseq [cid (:unchanged diff-result)]
        (emit on-event "cell_carry_over" "skipped" :cell_id cid))

      ;; Store run state
      (swap! runs assoc run-id
        {:cells           cells-init
         :base-ns         base-ns
         :manifest        manifest
         :manifest-id     manifest-id
         :client          client
         :store           store
         :project-path    project-path
         :diff            diff-result
         :cell-class      cell-class
         :prev-cells-by-id prev-cells-by-id
         :new-cells-by-id  new-cells-by-id
         :prev-run-id     (:run-id prev-snapshot)
         :callbacks       {:on-event on-event :on-chunk on-chunk}})

      (emit on-event "orchestration" "started"
            :run_id run-id
            :cell_ids (mapv :id briefs)))

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
  "Approves a cell's tests. Writes test file to disk, starts implementation.
   For cells whose contract changed since the last green snapshot, the
   prior implementation source and a change-summary are passed into the
   implementor so it edits in place rather than starting from scratch."
  [run-id cell-id]
  (let [run      (get @runs run-id)
        cell     (get-in run [:cells cell-id])
        contract (:contract cell)
        store    (:store run)
        client   (:client run)
        base-ns  (:base-ns run)
        on-event (get-in run [:callbacks :on-event])
        on-chunk (get-in run [:callbacks :on-chunk])
        ;; Diff context for edit-mode: when this cell's contract is
        ;; :schema-changed or :doc-changed, hand the implementor the prior
        ;; source plus a description of what's different.
        cid-kw   (cell-id-keyword cell-id)
        class    (get (:cell-class run) cid-kw)
        edit?    (#{:doc-changed :schema-changed} class)
        prev-src (when edit? (prior-cell-source store cid-kw))
        summary  (when edit?
                   (manifest-diff/change-summary
                     (get (:prev-cells-by-id run) cid-kw)
                     (get (:new-cells-by-id run) cid-kw)))]
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
                         {:contract        contract
                          :store           store
                          :run-id          run-id
                          :on-event        on-event
                          :on-chunk        on-chunk
                          :max-attempts    15
                          :project-path    nil ;; don't write to disk yet
                          :base-ns         base-ns
                          :prev-source     prev-src
                          :change-summary  summary})]
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
                content (:content (llm/session-send-stream session client msg on-chunk))
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
                content (:content (llm/session-send-stream session client msg on-chunk))
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
