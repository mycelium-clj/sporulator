(ns sporulator.workflow-smoke
  "Post-implementation smoke test: after all cells in a manifest are
   green, compile the workflow and run a synthetic request through it.
   Catches integration-level mismatches that per-cell verification
   can't see, because each cell is verified in isolation:

   - **Chain-validator errors** (compile phase): a cell's declared
     output schema doesn't supply the keys downstream cells consume.
     Fired by `mycelium.core/pre-compile`.
   - **Schema-form errors** (synthetic phase): a cell's input/output
     schema uses a form malli can't validate (e.g. invented shapes
     like `[:? :string]`). These don't trip `pre-compile`'s static
     analyser but surface as a `:mycelium/schema-error` the moment a
     real request flows through.

   Returns a structured result the orchestrator can:
   - Surface to the UI as a smoke_test event.
   - Use to identify the offending `:cell-id` and feed back to the
     cell-implementor as a re-run hint (future work).

   The smoke test reuses the project's `:mycelium/fixture` forms (the
   same ones the cell-implementor pre-binds for `:eval`) so the
   resources passed to the synthetic run match what the runtime would
   pass — no mocking."
  (:require [clojure.string :as str]
            [malli.generator :as mg]
            [mycelium.cell :as mycell]
            [mycelium.core :as myc]))

;; ── Sample-input generation ──────────────────────────────────────

(defn- start-cell-id
  "The id of the workflow's :start cell."
  [manifest]
  (get-in manifest [:cells :start :id]))

(defn- start-input-schema
  "The (already-normalized) :input schema of the workflow's :start
   cell, as registered in the mycelium cell registry. Returns nil if
   the cell isn't registered or has no input schema."
  [manifest]
  (let [cell-id (start-cell-id manifest)
        cell    (try (mycell/get-cell! cell-id) (catch Throwable _ nil))]
    (get-in cell [:schema :input])))

(defn- generate-sample-input
  "Generates a sample valid input from the :start cell's input schema.
   Returns the value or `{:error msg :schema schema}` if generation
   fails (e.g. malli rejects an invented schema form like `[:? :string]`)."
  [manifest]
  (if-let [schema (start-input-schema manifest)]
    (try
      {:ok? true :value (mg/generate schema)}
      (catch Throwable t
        {:ok?     false
         :error   (.getMessage t)
         :schema  schema
         :cell-id (start-cell-id manifest)}))
    {:ok? true :value {}}))

;; ── Resource fixtures ────────────────────────────────────────────

(defn- build-resources
  "Evaluates each fixture form in `resource-fixtures` and returns a
   map keyed by injection key. Failures bubble up as exceptions —
   they would have been caught at the per-cell `:eval` step too."
  [resource-fixtures]
  (reduce-kv (fn [acc k form] (assoc acc k (eval form)))
             {}
             resource-fixtures))

;; ── Phases ───────────────────────────────────────────────────────

(defn- compile-phase
  "Wraps `myc/pre-compile` so chain-validator errors come back as
   structured data instead of an exception."
  [manifest]
  (try
    {:status :ok :compiled (myc/pre-compile manifest)}
    (catch clojure.lang.ExceptionInfo e
      (let [data    (ex-data e)
            errors  (:errors data)
            first-e (first errors)]
        {:status   :error
         :phase    :compile
         :message  (.getMessage e)
         :cell-id  (:cell-id first-e)
         :details  {:errors errors}}))
    (catch Throwable t
      {:status :error :phase :compile
       :message (.getMessage t)})))

(defn- localize-runtime-error
  "Pulls the `:mycelium/schema-error` out of a halted run's data
   payload, if present. The schema error names the cell that
   rejected the data, the phase (`:input` or `:output`), and the
   missing/invalid keys."
  [run-result-or-exception]
  (let [data (cond
               (instance? clojure.lang.ExceptionInfo run-result-or-exception)
               (get-in (ex-data run-result-or-exception) [:data])

               (map? run-result-or-exception)
               run-result-or-exception)]
    (get data :mycelium/schema-error)))

(defn- last-cell-name->id
  "Converts a maestro `:last-state-id` (which uses
   `:mycelium.workflow/<cell-name>` keys) back to the manifest's
   cell-id. Returns nil if the state isn't a cell state (e.g. the
   `:maestro.core/start` pseudo-state)."
  [manifest last-state-id]
  (when (and last-state-id
             (= "mycelium.workflow" (namespace last-state-id)))
    (let [cell-name (keyword (name last-state-id))]
      (get-in manifest [:cells cell-name :id]))))

(defn- runtime-phase
  "Runs `(myc/run-compiled compiled resources sample)` and returns
   `{:status :ok}` or a structured error. `manifest` is needed to
   localise errors back to specific cell-ids."
  [manifest compiled resources sample]
  (let [result (try
                 {:ok? true :value (myc/run-compiled compiled resources sample)}
                 (catch clojure.lang.ExceptionInfo e
                   {:ok? false :exception e})
                 (catch Throwable t
                   {:ok? false :exception t}))]
    (cond
      (and (:ok? result)
           (map? (:value result))
           (:mycelium/input-error (:value result)))
      {:status :error :phase :runtime
       :message "Workflow input failed schema validation"
       :cell-id (start-cell-id manifest)
       :details {:input-error (:mycelium/input-error (:value result))
                 :sample-input sample}}

      (not (:ok? result))
      (let [d              (ex-data (:exception result))
            schema-err     (get-in d [:data :mycelium/schema-error])
            last-cell-id   (or (:cell-id schema-err)
                               (last-cell-name->id manifest (:last-state-id d)))
            current-state  (:current-state-id d)
            trace          (get-in d [:data :mycelium/trace])
            error-result   (get-in d [:data :error])]
        {:status  :error
         :phase   :runtime
         :message (cond
                    schema-err
                    (str "Schema validation failed at " (:cell-id schema-err)
                         " (" (:phase schema-err) "): "
                         (first (str/split-lines (or (:message schema-err) ""))))

                    (= :maestro.core/error current-state)
                    (str "Workflow halted in error after " (or last-cell-id "(unknown cell)")
                         (when error-result (str ": " (pr-str error-result))))

                    :else
                    (.getMessage (:exception result)))
         :cell-id last-cell-id
         :details (cond-> {:sample-input sample
                           :current-state current-state
                           :last-state    (:last-state-id d)
                           :trace         trace}
                    schema-err   (assoc :schema-error schema-err)
                    error-result (assoc :workflow-error error-result))})

      :else
      {:status :ok :result (:value result)})))

;; ── Public entry ─────────────────────────────────────────────────

(defn smoke-test!
  "Runs the smoke test against an already-loaded manifest. Cells
   referenced by the manifest must already be registered in
   `mycelium.cell` (the orchestrator loads them as it implements
   each one).

   `opts`:
     :manifest          — parsed manifest map (required)
     :resource-fixtures — fixtures map keyed by injection key (the
                          orchestrator extracts these once per run via
                          `extract-resource-fixtures` and passes them
                          here). Each value is a Clojure form (read as
                          data) that evaluates to a fresh resource.
     :sample-input      — known-valid input map to send to the workflow
                          (optional; falls back to malli generator on
                          the :start cell's input schema). Override
                          this when the schema is too loose for a
                          generator to produce a routable input — e.g.
                          an `:intent` of `:keyword` rather than
                          `[:enum ...]`.

   Return shape:
     {:status :ok :result <data>}                              ;; happy path
     {:status :error :phase :compile  ...:cell-id ...:details ...}
     {:status :error :phase :synthetic ...:cell-id ...:details ...}
     {:status :error :phase :runtime  ...:cell-id ...:details ...}"
  [{:keys [manifest resource-fixtures sample-input]}]
  (let [fixtures  (or resource-fixtures {})
        compile-r (compile-phase manifest)]
    (cond
      (= :error (:status compile-r))
      compile-r

      :else
      (let [compiled (:compiled compile-r)
            sample   (if sample-input
                       {:ok? true :value sample-input}
                       (generate-sample-input manifest))]
        (cond
          (not (:ok? sample))
          {:status  :error
           :phase   :synthetic
           :message (str "Could not generate a sample input from the :start "
                         "cell's input schema — this almost always means the "
                         "schema uses a form malli does not recognise. "
                         "Underlying error: " (:error sample))
           :cell-id (:cell-id sample)
           :details {:schema (:schema sample)
                     :error  (:error sample)}}

          :else
          (let [resources (try (build-resources fixtures)
                               (catch Throwable t
                                 {:fixture-eval-error (.getMessage t)}))]
            (if (:fixture-eval-error resources)
              {:status :error :phase :fixture
               :message (str "Failed to evaluate a :mycelium/fixture form: "
                             (:fixture-eval-error resources))}
              (runtime-phase manifest compiled resources (:value sample)))))))))

;; ── Pretty-printing for events / logs ────────────────────────────

(defn format-error
  "Renders a smoke-test error result as a multi-line human-readable
   string. Suitable for logging, UI messages, or feeding back to the
   cell-implementor as a re-run hint."
  [{:keys [phase message cell-id details]}]
  (let [header (case phase
                 :compile   "Workflow pre-compile failed (chain validator)"
                 :synthetic "Synthetic-input generation failed (likely bad schema form)"
                 :runtime   "Synthetic request failed at runtime"
                 :fixture   "Resource fixture failed to evaluate"
                 (str "Smoke test failed (" phase ")"))]
    (str header "\n"
         (when cell-id (str "  Offending cell: " cell-id "\n"))
         "  " message
         (when (:errors details)
           (str "\n  Chain-validator errors:\n"
                (str/join "\n"
                  (for [e (:errors details)]
                    (str "  - " (:cell-id e) " at " (:cell-name e)
                         " requires keys " (:missing-keys e)
                         ", available " (:available-keys e))))))
         (when-let [se (:schema-error details)]
           (str "\n  Schema phase: " (:phase se)
                (when (:failed-keys se)
                  (str "\n  Failed keys: "
                       (str/join ", "
                         (for [[k m] (:failed-keys se)]
                           (str k " (" (:message m) ")")))))))
         (when (and (= :runtime phase) (not cell-id))
           (str "\n  No specific cell could be localised — workflow likely "
                "halted because a dispatch predicate did not match. Last "
                "state: " (:last-state details))))))
