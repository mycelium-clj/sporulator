(ns sporulator.cell-validate
  "Per-cell contract validation that runs at the cell-implementor's
   `complete` step — after tests pass but BEFORE the cell is declared
   green and persisted.

   Catches bugs that per-cell tests can pass right over because the
   tests only assert on a few keys:

   - **Malformed schemas.** The agent invents a Malli shape that
     doesn't actually parse (e.g. `[:? :string]`). Tests pass because
     malli is never asked to validate the schema directly; the bug
     surfaces later when mycelium tries to use it at runtime.

   - **Handler-vs-schema drift.** The handler returns a different
     shape than `:output` declares. Tests pass because they only
     check one or two keys, not the whole shape. The bug surfaces
     when downstream cells (or workflow chain validation) try to
     consume the output.

   The per-cell smoke test sits between two existing layers:
     - `clojure.test` per cell (asserts behaviour on chosen examples)
     - `workflow-smoke` post-orchestration (asserts integration)
   and catches a class of bug that's hard to write tests for but
   trivial to verify mechanically when the contract is right there."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [mycelium.cell :as mycell]))

;; ── Schema-form check ────────────────────────────────────────────

(def ^:private sequence-operators
  "Malli schema heads that operate on SEQUENCES (`:cat`, `:catn`, `:?`,
   `:*`, `:+`, `:alt`, `:altn`, `:repeat`). Inside a flat map entry's
   type position these are almost always a mistake for 'optional', and
   they parse cleanly so neither m/schema nor mg/generate flags them.
   Catching this specific case is the difference between [:?] silently
   passing as 'a sequence of zero-or-one strings' (the malli reading)
   and being recognised as the architect's invented optional shorthand."
  #{:? :* :+ :cat :catn :alt :altn :repeat})

(defn- map-entry-with-seq-op
  "If `map-schema` is a `[:map ...]` whose entries' type positions use
   a top-level sequence-operator schema, returns a vector of `[entry-key
   seq-op]` pairs. Otherwise nil."
  [map-schema]
  (when (and (vector? map-schema) (= :map (first map-schema)))
    (seq
      (keep (fn [entry]
              (when (and (vector? entry) (>= (count entry) 2))
                ;; entry forms: [:k type] OR [:k props type]
                (let [type-pos (last entry)
                      k        (first entry)]
                  (when (and (vector? type-pos)
                             (sequence-operators (first type-pos)))
                    [k (first type-pos)]))))
            (rest map-schema)))))

(defn- check-malli
  "Returns nil if `spec` parses as a valid Malli schema AND uses no
   sequence-operator heads in flat map-entry type positions. Otherwise
   an error string explaining the issue."
  [label spec]
  (when (some? spec)
    (try
      (m/schema spec)
      (if-let [bad-entries (map-entry-with-seq-op spec)]
        (str label " uses sequence-operator schema head(s) inside map "
             "entries:\n"
             (str/join "\n"
               (for [[k op] bad-entries]
                 (str "  - :" (name k) " has type starting with " op
                      " — sequence operators (`:?`, `:*`, `:+`, `:cat`, ...) "
                      "belong inside `[:cat ...]` / `[:sequential ...]`. "
                      "For an OPTIONAL map entry, write "
                      "`[" (pr-str k) " {:optional true} <type>]` instead.")))
             "\nFull schema: " (pr-str spec))
        nil)
      (catch Throwable t
        (str label " is not valid Malli: " (.getMessage t))))))

(defn- dispatched-output?
  "True when output is a map keyed by transition labels with vector
   sub-schemas. Same heuristic mycelium uses."
  [output]
  (and (map? output) (seq output)
       (every? vector? (vals output))))

(defn- validate-schemas
  "Walks the cell's :input and :output schemas. For dispatched
   outputs, validates each per-transition sub-schema. Returns nil on
   success, a multi-line error string on failure."
  [{:keys [input output]}]
  (let [errs (cond-> []
               input
               (conj (check-malli "Input schema" input))

               (and output (dispatched-output? output))
               (into (mapv (fn [[label sub]]
                             (check-malli (str "Output[" label "]") sub))
                           output))

               (and output (not (dispatched-output? output)))
               (conj (check-malli "Output schema" output)))
        errs (filterv some? errs)]
    (when (seq errs) (str/join "\n" errs))))

;; ── Round-trip check ─────────────────────────────────────────────

(defn- generate-sample-input
  "Generates a single example value satisfying the cell's :input
   schema via `mg/generate`. Returns `{:ok? bool :value v :error msg}`."
  [input-schema]
  (cond
    (nil? input-schema)
    {:ok? true :value {}}

    :else
    (try {:ok? true :value (mg/generate input-schema)}
         (catch Throwable t
           {:ok? false :error (.getMessage t)}))))

(defn- match-output-shape
  "Validates `result` against the cell's `:output` schema. For
   dispatched outputs, accepts the result if it matches ANY declared
   transition. Returns
     {:ok? true [:transition label]}
     {:ok? false :error <human-readable explanation>}."
  [output result]
  (cond
    (nil? output) {:ok? true}

    (dispatched-output? output)
    (if-let [match (some (fn [[label sub]]
                           (when (m/validate sub result) label))
                         output)]
      {:ok? true :transition match}
      {:ok? false
       :error (str "Handler returned " (pr-str result) " which does not "
                   "match any declared output transition. Allowed transitions: "
                   (pr-str (vec (keys output))))})

    :else
    (if (m/validate output result)
      {:ok? true}
      {:ok? false
       :error (str "Handler returned " (pr-str result) " which does not "
                   "satisfy the declared :output schema. Malli explanation: "
                   (try (pr-str (me/humanize (m/explain output result)))
                        (catch Throwable _ "(unavailable)")))})))

(defn- build-resources
  "Evaluates each fixture form to produce a runtime resources map."
  [resource-fixtures]
  (reduce-kv (fn [acc k form] (assoc acc k (eval form)))
             {}
             (or resource-fixtures {})))

(defn round-trip!
  "Generates a sample input, calls the handler with resources from
   fixtures, validates the output against the declared :output schema.

   Returns nil on success, an error map on failure:
     {:phase :sample-gen | :handler-throw | :output-shape
      :message <actionable string>}"
  [cell-id resource-fixtures]
  (let [cell    (mycell/get-cell! cell-id)
        schema  (:schema cell)
        handler (:handler cell)
        sample  (generate-sample-input (:input schema))]
    (cond
      (nil? handler)
      {:phase :registry
       :message (str "Cell " cell-id " has no handler in the registry")}

      (not (:ok? sample))
      {:phase :sample-gen
       :message (str "Could not generate a sample input from your :input "
                     "schema. Almost always means the schema uses a form "
                     "malli does not recognise (e.g. `[:? :string]` is not "
                     "valid Malli — use `[:map [:k {:optional true} :string]]` "
                     "for optional keys).\n  Underlying error: "
                     (:error sample))}

      :else
      (let [resources (try (build-resources resource-fixtures)
                           (catch Throwable t
                             {:fixture-error (.getMessage t)}))]
        (if (:fixture-error resources)
          {:phase :fixture
           :message (str "Could not evaluate a :mycelium/fixture form: "
                         (:fixture-error resources))}

          (let [call (try {:ok? true :value (handler resources (:value sample))}
                          (catch Throwable t
                            {:ok? false :error (.getMessage t)
                             :stack (with-out-str
                                      (.printStackTrace t (java.io.PrintWriter. *out*)))}))]
            (cond
              (not (:ok? call))
              {:phase :handler-throw
               :message (str "Handler threw when called with sample input "
                             (pr-str (:value sample)) ":\n  " (:error call))}

              :else
              (let [shape (match-output-shape (:output schema) (:value call))]
                (when-not (:ok? shape)
                  {:phase :output-shape
                   :message (str (:error shape)
                                 "\n  Sample input fed to handler: "
                                 (pr-str (:value sample))
                                 "\n  Handler returned: "
                                 (pr-str (:value call)))})))))))))

;; ── Public entry ─────────────────────────────────────────────────

(defn validate-contract!
  "Runs all per-cell contract checks. Called at the cell-implementor's
   `complete` step, after tests pass.

   `opts`:
     :cell-id           — keyword (required)
     :resource-fixtures — map of injection-key → fixture form
                          (optional; needed for round-trip when the
                          cell has :requires)

   Return:
     {:status :ok}
     {:status :error :phase ... :message <actionable string>}"
  [{:keys [cell-id resource-fixtures]}]
  (let [cell (try (mycell/get-cell! cell-id) (catch Throwable _ nil))]
    (cond
      (nil? cell)
      {:status :error :phase :registry
       :message (str "Cell " cell-id " is not registered — your handler may "
                     "not have loaded into the cell registry. Did the "
                     "(cell/defcell ...) form evaluate?")}

      :else
      (let [schema-err (validate-schemas (:schema cell))]
        (cond
          schema-err
          {:status :error :phase :schema :message schema-err}

          :else
          (if-let [rt-err (round-trip! cell-id resource-fixtures)]
            (assoc rt-err :status :error)
            {:status :ok}))))))

;; ── Pretty-printing ──────────────────────────────────────────────

(defn format-error
  "Renders a validation failure as an actionable feedback message
   for the cell-implementor."
  [{:keys [phase message]}]
  (let [header (case phase
                 :registry      "Contract validation: cell not in registry"
                 :schema        "Contract validation: schema is not valid Malli"
                 :sample-gen    "Contract validation: cannot generate sample input from :input schema"
                 :handler-throw "Contract validation: handler threw on a generated sample input"
                 :output-shape  "Contract validation: handler output does not match declared :output schema"
                 :fixture       "Contract validation: resource fixture failed to evaluate"
                 (str "Contract validation failed (" phase ")"))]
    (str header "\n  " message)))
