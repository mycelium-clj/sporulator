(ns sporulator.codegen
  "Code generation and validation helpers for the sporulator orchestrator.
   Loaded in the nREPL session at startup. Called by the Go bridge
   to do structured transformations that should never be done with string manipulation.

   All EDN parsing, schema validation, code extraction, and manifest assembly
   happens here — Go only passes opaque strings."
  (:require [mycelium.cell :as cell]
            [mycelium.schema :as schema]
            [mycelium.workflow :as wf]
            [maestro.core :as fsm]
            [malli.core :as m]
            [clojure.string :as str]))

;; ============================================================
;; Schema operations
;; ============================================================

(defn parse-schema
  "Parses a schema EDN string into a map with :input and :output keys.
   Normalizes lite map syntax to proper Malli."
  [schema-edn-str]
  (let [raw (read-string schema-edn-str)]
    {:input  (schema/normalize-schema (:input raw))
     :output (schema/normalize-schema (:output raw))}))

(defn validate-schema
  "Validates a schema (after normalization) is valid Malli.
   Returns nil on success, error message string on failure."
  [schema-form]
  (try
    (m/schema schema-form)
    nil
    (catch Exception e
      (str "INVALID: " (.getMessage e)))))

(defn validate-schema-str
  "Validates a schema EDN string. Normalizes and checks with Malli.
   Returns nil on success, error message on failure."
  [schema-edn-str]
  (try
    (let [normalized (schema/normalize-schema (read-string schema-edn-str))]
      (m/schema normalized)
      nil)
    (catch Exception e
      (str "INVALID: " (.getMessage e)))))

;; ============================================================
;; Code generation
;; ============================================================

(defn assemble-cell-source
  "Generates a complete cell namespace source string.
   schema-map is {:input <schema> :output <schema>} (already normalized).
   extra-requires is a vector of require spec strings like \"[clojure.string :as str]\".
   helpers is a string of helper function definitions.
   fn-body is the (fn [resources data] ...) string."
  [{:keys [cell-ns cell-id doc schema-map requires extra-requires helpers fn-body]}]
  (let [input-schema  (:input schema-map)
        output-schema (:output schema-map)]
    (str "(ns " cell-ns "\n"
         "  (:require [mycelium.cell :as cell]"
         (when (seq extra-requires)
           (str "\n            " (str/join "\n            " extra-requires)))
         "))\n"
         (when (seq helpers)
           (str "\n" helpers "\n"))
         "\n(cell/defcell " cell-id "\n"
         "  {:doc " (pr-str doc) "\n"
         "   :input " (pr-str input-schema) "\n"
         "   :output " (pr-str output-schema)
         (when (seq requires)
           (str "\n   :requires " (pr-str (mapv keyword requires))))
         "}\n"
         "  " fn-body ")\n")))

(defn assemble-stub-cell-source
  "Generates a minimal stub cell source for schema validation.
   Registers the cell with a passthrough handler."
  [{:keys [cell-ns cell-id doc schema-map]}]
  (let [input-schema  (:input schema-map)
        output-schema (:output schema-map)]
    (str "(ns " cell-ns "\n"
         "  (:require [mycelium.cell :as cell]))\n\n"
         "(cell/defcell " cell-id "\n"
         "  {:doc " (pr-str doc) "\n"
         "   :input " (pr-str input-schema) "\n"
         "   :output " (pr-str output-schema) "}\n"
         "  (fn [resources data] data))\n")))

(defn assemble-test-source
  "Generates a complete test namespace source string.
   test-body is a string of deftest forms."
  [{:keys [test-ns cell-ns cell-id test-body]}]
  (str "(ns " test-ns "\n"
       "  (:require [clojure.test :refer [deftest is testing]]\n"
       "            [mycelium.cell :as cell]\n"
       "            [malli.core :as m]\n"
       "            [" cell-ns "]))\n\n"
       "(def cell-spec (cell/get-cell! " cell-id "))\n"
       "(def handler (:handler cell-spec))\n"
       "(def cell-schema (:schema cell-spec))\n\n"
       "(defn approx= [x y tolerance]\n"
       "  (< (Math/abs (- (double x) (double y))) tolerance))\n\n"
       "(defn validate-output\n"
       "  \"Validates that result satisfies the cell's declared output schema.\"\n"
       "  [result]\n"
       "  (when-let [out-schema (:output cell-schema)]\n"
       "    (let [schema (if (vector? out-schema) out-schema [:map])]\n"
       "      (is (m/validate schema result)\n"
       "          (str \"Output schema violation: \"\n"
       "               (pr-str (m/explain schema result)))))))\n\n"
       test-body "\n"))

;; ============================================================
;; Manifest assembly
;; ============================================================

(defn assemble-manifest
  "Assembles an EDN manifest string from structured data.
   steps is a vector of {:name :doc :input-schema :output-schema :requires :leaf?}
   edges is a map of step-name → edge-edn-string
   dispatches is a map of step-name → dispatch-edn-string
   The first step is always mapped to :start."
  [{:keys [id ns-prefix steps edges dispatches]}]
  (let [step-key (fn [i step]
                   (if (zero? i) ":start" (str ":" (:name step))))]
    (str "{:id " id "\n"
         " :cells\n {"
         (str/join "\n  "
           (map-indexed
             (fn [i step]
               (str (step-key i step)
                    " {:id :" ns-prefix "/" (:name step) "\n"
                    "         :doc " (pr-str (:doc step)) "\n"
                    "         :schema {:input " (pr-str (:input-schema step))
                    " :output " (pr-str (:output-schema step)) "}"
                    (when (seq (:requires step))
                      (str "\n         :requires " (pr-str (mapv keyword (:requires step)))))
                    "}"))
             steps))
         "}\n"
         " :edges\n {"
         (str/join "\n  "
           (map-indexed
             (fn [i step]
               (let [k (step-key i step)]
                 (when-let [e (get edges (:name step))]
                   (str k " " e))))
             steps))
         "}\n"
         " :dispatches\n {"
         (str/join "\n  "
           (map-indexed
             (fn [i step]
               (let [k (step-key i step)]
                 (when-let [d (get dispatches (:name step))]
                   (str k " " d))))
             steps))
         "}}")))

;; ============================================================
;; LLM response extraction
;; ============================================================

(defn extract-code-block
  "Extracts the first code block from an LLM response.
   Handles ```clojure ... ```, ```edn ... ```, and bare ``` ... ```."
  [response]
  (when response
    (let [pattern #"(?s)```(?:clojure|edn|clj)?\s*\n(.*?)```"
          m (re-find pattern response)]
      (when m (str/trim (second m))))))

(defn extract-fn-body
  "Extracts the last (fn [...] ...) form from a code string.
   Uses the Clojure reader for correct parsing."
  [code]
  (when (and code (str/includes? code "(fn "))
    (try
      (let [forms (read-string (str "[" code "]"))
            fn-forms (filter #(and (list? %) (= 'fn (first %))) forms)]
        (when (seq fn-forms)
          (pr-str (last fn-forms))))
      (catch Exception _
        ;; Fallback: find last "(fn " and balance parens
        (let [idx (str/last-index-of code "(fn ")]
          (when idx
            (loop [i idx depth 0 started false]
              (if (>= i (count code))
                (subs code idx)
                (let [ch (nth code i)]
                  (cond
                    (= ch \() (recur (inc i) (inc depth) true)
                    (= ch \)) (if (= depth 1)
                                (subs code idx (inc i))
                                (recur (inc i) (dec depth) started))
                    :else (recur (inc i) depth started)))))))))))

(defn extract-helpers
  "Extracts helper function definitions (everything before the last fn form)."
  [code]
  (when (and code (str/includes? code "(fn "))
    (let [idx (str/last-index-of code "(fn ")]
      (when (and idx (pos? idx))
        (let [before (str/trim (subs code 0 idx))]
          (when (seq before)
            ;; Remove any ;; REQUIRE: lines
            (->> (str/split-lines before)
                 (remove #(str/starts-with? (str/trim %) ";; REQUIRE:"))
                 (str/join "\n")
                 str/trim)))))))

(defn extract-extra-requires
  "Extracts ;; REQUIRE: [...] comments from code.
   Returns a vector of require spec strings."
  [code]
  (when code
    (->> (str/split-lines code)
         (keep (fn [line]
                 (let [trimmed (str/trim line)]
                   (when (str/starts-with? trimmed ";; REQUIRE:")
                     (str/trim (subs trimmed (count ";; REQUIRE:")))))))
         vec)))

;; ============================================================
;; High-level pipelines (Go calls these end-to-end)
;; ============================================================

(defn llm-response->cell-source
  "Full pipeline: raw LLM code response → assembled cell source.
   Extracts fn-body, helpers, and extra-requires from the LLM output,
   then assembles a complete cell namespace with the correct defcell form.
   Returns the source string ready to write to a file."
  [{:keys [cell-ns cell-id doc schema-edn requires raw-code]}]
  (let [code      (or (extract-code-block raw-code) raw-code)
        fn-body   (or (extract-fn-body code) code)
        helpers   (extract-helpers code)
        extra-reqs (extract-extra-requires code)]
    (assemble-cell-source
      {:cell-ns cell-ns
       :cell-id cell-id
       :doc doc
       :schema-map (parse-schema schema-edn)
       :requires requires
       :extra-requires extra-reqs
       :helpers helpers
       :fn-body fn-body})))

(defn llm-response->test-source
  "Full pipeline: raw LLM test response → assembled test source.
   Extracts code block and wraps in test namespace boilerplate."
  [{:keys [test-ns cell-ns cell-id raw-response]}]
  (let [test-body (or (extract-code-block raw-response) raw-response)]
    (assemble-test-source
      {:test-ns test-ns
       :cell-ns cell-ns
       :cell-id cell-id
       :test-body test-body})))

(defn load-and-check-cell
  "Loads a cell source file, evaluates it in the REPL, and verifies registration.
   Returns {:ok true :schema {...}} on success, {:error \"...\"} on failure."
  [cell-ns cell-id source-file]
  (try
    (when (find-ns (symbol cell-ns))
      (remove-ns (symbol cell-ns)))
    (load-file source-file)
    (let [spec (cell/get-cell! (read-string cell-id))]
      (cond
        (nil? spec)
        {:error (str "Cell " cell-id " not registered after loading " source-file)}

        (nil? (:handler spec))
        {:error (str "Cell " cell-id " has no handler")}

        (nil? (:schema spec))
        {:error (str "Cell " cell-id " has nil schema — defcell opts may be missing :input/:output")}

        :else
        {:ok true
         :input-keys (set (keep (fn [e] (when (vector? e) (first e)))
                                (rest (get-in spec [:schema :input]))))
         :output-keys (set (keep (fn [e] (when (vector? e) (first e)))
                                 (rest (get-in spec [:schema :output]))))}))
    (catch Exception e
      {:error (str "Failed to load " source-file ": " (.getMessage e))})))

(defn run-cell-tests
  "Loads a test file, runs the tests, returns structured results.
   Returns {:passed? bool :tests N :failures N :errors N :output \"...\"}."
  [cell-ns test-ns source-file test-file]
  (try
    ;; Reload cell and test namespaces
    (when (find-ns (symbol cell-ns))
      (remove-ns (symbol cell-ns)))
    (when (find-ns (symbol test-ns))
      (remove-ns (symbol test-ns)))
    (load-file source-file)
    (load-file test-file)
    (let [output (with-out-str
                   (clojure.test/run-tests (find-ns (symbol test-ns))))
          results (parse-test-results output)]
      (assoc results :output output))
    (catch Exception e
      {:passed? false :error (.getMessage e)
       :output (str "Exception: " (.getMessage e))})))

;; ============================================================
;; Integration testing with trace analysis
;; ============================================================

(defn run-workflow-with-trace
  "Compiles and runs a workflow, returning the result with trace.
   Returns {:ok true :result <data> :trace [...]} on success,
   {:error \"...\" :phase :compile|:run} on failure."
  [manifest-edn resources input]
  (try
    (let [manifest (if (string? manifest-edn)
                     (read-string manifest-edn)
                     manifest-edn)
          compiled (wf/compile-workflow manifest {:coerce? true})
          result   (fsm/run compiled resources {:data input})]
      {:ok true
       :result (dissoc result :mycelium/trace)
       :trace  (:mycelium/trace result)})
    (catch clojure.lang.ExceptionInfo e
      {:error (.getMessage e)
       :phase (if (str/includes? (.getMessage e) "Schema chain")
                :compile :run)
       :data  (ex-data e)})
    (catch Exception e
      {:error (.getMessage e) :phase :run})))

(defn analyze-trace-failure
  "Given a workflow trace and expected output values, identifies which cell
   first produced wrong data. Compares each trace entry's :data against
   what downstream cells need.

   expected is a map of keys to expected values in the final result.
   Returns nil if trace looks correct, or a map describing the failure:
   {:cell-name :cell-id :issue \"...\" :actual <val> :expected <val> :data <snapshot>}"
  [trace expected-output actual-output]
  (when (and trace (seq expected-output))
    ;; Find keys that are wrong in the final output
    (let [wrong-keys (reduce-kv
                       (fn [acc k expected-v]
                         (let [actual-v (get actual-output k ::missing)]
                           (if (or (= actual-v ::missing)
                                   (not= (str expected-v) (str actual-v)))
                             (conj acc {:key k :expected expected-v :actual actual-v})
                             acc)))
                       []
                       expected-output)]
      (when (seq wrong-keys)
        ;; Walk trace backwards to find the first cell that should have produced these keys
        (let [wrong-key-set (set (map :key wrong-keys))]
          {:wrong-keys wrong-keys
           :trace-steps
           (mapv (fn [entry]
                   {:cell (:cell entry)
                    :cell-id (:cell-id entry)
                    :transition (:transition entry)
                    :relevant-keys
                    (select-keys (:data entry) wrong-key-set)})
                 trace)})))))

(defn format-trace-for-llm
  "Formats a workflow trace for inclusion in an LLM prompt.
   Shows each cell's name, transition, and a subset of its output data."
  [trace & {:keys [max-keys] :or {max-keys 10}}]
  (when (seq trace)
    (str/join "\n"
      (map-indexed
        (fn [i entry]
          (let [data (:data entry)
                keys-to-show (take max-keys (keys data))
                data-summary (select-keys data keys-to-show)
                more (- (count data) (count keys-to-show))]
            (str "  " (inc i) ". " (:cell entry) " (" (:cell-id entry) ")"
                 " → " (or (:transition entry) "unconditional")
                 "\n     " (pr-str data-summary)
                 (when (pos? more) (str " ... +" more " more keys")))))
        trace))))

(defn build-cell-fix-context
  "Given a trace and a cell-id, extracts the context needed to fix that cell:
   what data it received (from the previous trace entry) and what it produced."
  [trace cell-id]
  (let [entries (vec trace)
        idx (first (keep-indexed
                     (fn [i entry] (when (= (str (:cell-id entry)) (str cell-id)) i))
                     entries))]
    (when idx
      {:cell-id cell-id
       :cell-name (:cell entries idx)
       :input-data (if (pos? idx)
                     (:data (nth entries (dec idx)))
                     {})
       :output-data (:data (nth entries idx))
       :transition (:transition (nth entries idx))})))

;; ============================================================
;; Cell inspection (from REPL registry)
;; ============================================================

(defn cell-schema-keys
  "Returns the set of keys from a registered cell's input or output schema.
   phase is :input or :output."
  [cell-id phase]
  (try
    (let [spec (cell/get-cell! cell-id)
          s (get-in spec [:schema phase])]
      (cond
        (nil? s) #{}
        (and (vector? s) (= :map (first s)))
        (set (keep (fn [entry] (when (vector? entry) (first entry))) (rest s)))
        (map? s) (set (keys s))
        :else #{}))
    (catch Exception _ #{})))

(defn check-cell-registered
  "Checks that a cell is registered and has the expected schema.
   Returns {:ok true} or {:error \"...\"}"
  [cell-id]
  (try
    (let [spec (cell/get-cell! cell-id)]
      (if (and spec (:handler spec) (:schema spec))
        {:ok true
         :has-input (some? (get-in spec [:schema :input]))
         :has-output (some? (get-in spec [:schema :output]))}
        {:error (str "Cell " cell-id " registered but missing "
                     (cond
                       (nil? spec) "entirely"
                       (nil? (:handler spec)) ":handler"
                       (nil? (:schema spec)) ":schema"))}))
    (catch Exception e
      {:error (str "Cell " cell-id " not found: " (.getMessage e))})))

;; ============================================================
;; Test result parsing
;; ============================================================

(defn parse-test-results
  "Parses clojure.test output string into structured results.
   Returns {:passed? bool :tests int :assertions int :failures int :errors int
            :failure-details [...]}"
  [test-output]
  (let [summary-pattern #"Ran (\d+) tests containing (\d+) assertions.\s*(\d+) failures, (\d+) errors."
        summary-match (re-find summary-pattern (or test-output ""))
        fail-pattern #"(?s)FAIL in \((\S+)\).*?expected:.*?actual:.*?(?=\n(?:FAIL|ERROR|Ran|\z))"
        failures (when test-output (re-seq fail-pattern test-output))]
    (if summary-match
      {:passed? (and (= "0" (nth summary-match 3))
                     (= "0" (nth summary-match 4)))
       :tests (parse-long (nth summary-match 1))
       :assertions (parse-long (nth summary-match 2))
       :failures (parse-long (nth summary-match 3))
       :errors (parse-long (nth summary-match 4))
       :failure-details (mapv first failures)}
      {:passed? false
       :tests 0 :assertions 0 :failures 0 :errors 0
       :parse-error "Could not parse test output"
       :raw test-output})))
