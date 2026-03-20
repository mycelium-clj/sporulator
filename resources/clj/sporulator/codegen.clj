(ns sporulator.codegen
  "Code generation and validation helpers for the sporulator orchestrator.
   Loaded in the nREPL session at startup. Called by the Go bridge
   to do structured transformations that should never be done with string manipulation.

   All EDN parsing, schema validation, code extraction, and manifest assembly
   happens here — Go only passes opaque strings."
  (:require [mycelium.cell :as cell]
            [mycelium.schema :as schema]
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
