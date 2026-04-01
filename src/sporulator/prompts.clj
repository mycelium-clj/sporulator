(ns sporulator.prompts
  "System prompts for graph and cell agents, and graduated fix prompt building.
   Translated from Go prompts.go and fix_prompt.go."
  (:require [clojure.string :as str]))

;; =============================================================
;; Math precision rules (injected into test and impl prompts)
;; =============================================================

(def math-precision-rules
  "NUMERICAL PRECISION — read the schema and spec carefully to decide precision for each field:
1. Examine each :double field in the schema. Determine from the spec whether it represents
   a value that needs rounding (e.g. currency, percentages) or should stay as raw :double.
   When rounding is needed, use bigdec with the appropriate scale and rounding mode:
   (.doubleValue (.setScale (bigdec x) <scale> java.math.RoundingMode/HALF_UP))
2. Examine each :int field. Determine from the spec whether the value is computed from
   floating-point math and needs truncation (floor/ceil) or is a direct count.
3. When the spec describes distributing a total across items:
   - round each item's share individually
   - compute the remainder (total minus sum-of-rounded-shares)
   - adjust the last item by the remainder so the sum is exact
4. Tests and implementation MUST agree on precision. Test expectations should be the
   rounded/truncated result, never the raw floating-point intermediate.
   Use a tolerance (e.g. 0.01) for approximate comparisons of rounded values.
5. Use bigdec for intermediate calculations to avoid floating-point accumulation errors.")

;; =============================================================
;; Conditional math precision
;; =============================================================

(defn needs-math-precision?
  "Returns true if the schema string contains numeric types (:double, :int)
   that would benefit from math precision rules."
  [schema-str]
  (boolean
    (and (string? schema-str)
         (re-find #":(?:double|int(?:eger)?|float|decimal|number)\b" schema-str))))

;; =============================================================
;; Fix tier escalation
;; =============================================================

(defn fix-tier
  "Returns the escalation tier for a given attempt number.
   :standard → :narrowed → :fresh"
  [attempt]
  (cond
    (<= attempt 1) :standard
    (= attempt 2)  :narrowed
    :else           :fresh))

(defn fix-tier-for-model
  "Returns the escalation tier for a given attempt and model.
   DeepSeek models start narrowed (less context) since they benefit
   from focused prompts over information-dense ones."
  [attempt model-name]
  (let [deepseek? (and (string? model-name)
                       (str/starts-with? (str/lower-case model-name) "deepseek"))]
    (if deepseek?
      ;; DeepSeek: narrowed → fresh (skip standard)
      (if (<= attempt 1) :narrowed :fresh)
      ;; Other models: standard escalation
      (fix-tier attempt))))

;; =============================================================
;; First failing test extraction
;; =============================================================

(def ^:private fail-pattern #"(?m)^(FAIL|ERROR) in \(")

(defn extract-first-failing-test
  "Extracts the first FAIL/ERROR block from clojure.test output.
   Returns empty string if no failures found."
  [output]
  (let [output (or output "")
        matches (re-seq fail-pattern output)]
    (if (empty? matches)
      ""
      ;; Find positions of all FAIL/ERROR lines
      (let [matcher (re-matcher fail-pattern output)
            positions (loop [positions []]
                        (if (.find matcher)
                          (recur (conj positions (.start matcher)))
                          positions))]
        (if (empty? positions)
          ""
          (let [start (first positions)
                end (if (>= (count positions) 2)
                      (second positions)
                      (let [ran-idx (str/index-of output "\nRan " start)]
                        (if ran-idx ran-idx (count output))))]
            (str/trim (subs output start end))))))))

;; =============================================================
;; Fix prompt building
;; =============================================================

(defn- build-extra-context [{:keys [graph-context brief]}]
  (let [sb (StringBuilder.)]
    (when (not (str/blank? graph-context))
      (.append sb "\n")
      (.append sb graph-context))
    (when (and (:context brief)
               (not (str/blank? (:context brief)))
               (not= (:context brief) graph-context))
      (.append sb "\n")
      (.append sb (:context brief)))
    (str sb)))

(defn build-fix-prompt
  "Constructs the standard fix prompt with full context."
  [{:keys [test-output test-code impl-code brief cell-id attempt max-attempts] :as params}]
  (let [extra (build-extra-context params)]
    (str "The tests are failing (attempt " attempt "/" max-attempts ").\n\n"
         "## Cell Contract\n"
         "- **Cell ID:** " cell-id "\n"
         "- **Implementation Requirements:** " (:doc brief) "\n"
         "- **Schema:** " (:schema brief) "\n"
         extra
         "\n## Current Implementation\n```clojure\n" impl-code "\n```\n\n"
         "## Test Output\n```\n" test-output "\n```\n\n"
         "## Test Code\n```clojure\n" test-code "\n```\n\n"
         (when (needs-math-precision? (:schema brief))
           (str math-precision-rules "\n\n"))
         "Fix the implementation. Return ONLY:\n"
         "1. (OPTIONAL) Helper functions — define any helper functions you need\n"
         "2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form\n\n"
         "If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:\n"
         ";; REQUIRE: [clojure.string :as str]\n\n"
         "Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.\n"
         "CRITICAL: The cell ID is " cell-id ".")))

(defn- build-narrowed-fix-prompt
  "Focuses on the first failing test to reduce cognitive load."
  [{:keys [test-output test-code impl-code brief cell-id attempt] :as params}]
  (let [first-failure (extract-first-failing-test test-output)]
    (if (str/blank? first-failure)
      ;; Fallback to standard if can't extract
      (build-fix-prompt params)
      (let [extra (build-extra-context params)]
        (str "The tests are STILL failing after " attempt " attempts. "
             "Focus on fixing this specific failure first:\n\n"
             "## First Failing Test\n```\n" first-failure "\n```\n\n"
             "## Cell Contract\n"
             "- **Cell ID:** " cell-id "\n"
             "- **Implementation Requirements:** " (:doc brief) "\n"
             "- **Schema:** " (:schema brief) "\n"
             extra
             "\n## Current Implementation\n```clojure\n" impl-code "\n```\n\n"
             "## Full Test Code\n```clojure\n" test-code "\n```\n\n"
             (when (needs-math-precision? (:schema brief))
               (str math-precision-rules "\n\n"))
             "Focus on fixing this specific failure first. Trace through the logic step-by-step:\n"
             "1. What input does this test provide?\n"
             "2. What does your current code do with that input?\n"
             "3. Where does the actual output diverge from expected?\n\n"
             "Return ONLY:\n"
             "1. (OPTIONAL) Helper functions\n"
             "2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form\n\n"
             "If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:\n"
             ";; REQUIRE: [clojure.string :as str]\n\n"
             "Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.\n"
             "CRITICAL: The cell ID is " cell-id ".")))))

(defn- build-fresh-fix-prompt
  "Instructs the LLM to start from scratch."
  [{:keys [test-output test-code brief cell-id attempt] :as params}]
  (let [extra (build-extra-context params)]
    (str "After " attempt " failed attempts, implement this cell from scratch. "
         "Discard your previous approach entirely.\n\n"
         "## Cell Contract\n"
         "- **Cell ID:** " cell-id "\n"
         "- **Implementation Requirements:** " (:doc brief) "\n"
         "- **Schema:** " (:schema brief) "\n"
         extra
         "\n## Tests That Must Pass\n```clojure\n" test-code "\n```\n\n"
         "## Most Recent Test Output (showing what went wrong)\n```\n" test-output "\n```\n\n"
         (when (needs-math-precision? (:schema brief))
           (str math-precision-rules "\n\n"))
         "Implement the cell from scratch. Think step by step:\n"
         "1. Read each test carefully to understand the expected behavior.\n"
         "2. Design a clean implementation that satisfies ALL tests.\n"
         "3. Pay close attention to data types, rounding, and edge cases.\n\n"
         "Return ONLY:\n"
         "1. (OPTIONAL) Helper functions\n"
         "2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form\n\n"
         "If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:\n"
         ";; REQUIRE: [clojure.string :as str]\n\n"
         "Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.\n"
         "CRITICAL: The cell ID is " cell-id ".")))

(defn build-graduated-fix-prompt
  "Constructs a fix prompt with escalating context per attempt tier."
  [params]
  (case (fix-tier (:attempt params))
    :narrowed (build-narrowed-fix-prompt params)
    :fresh    (build-fresh-fix-prompt params)
    (build-fix-prompt params)))

;; =============================================================
;; System prompts (translated from Go prompts.go)
;; =============================================================

(def graph-prompt
  "System prompt for the graph agent. Understands manifest structure
   and workflow design but NOT cell implementation."
  "You are a Mycelium workflow architect. You design workflow graphs by creating and modifying manifests. You do NOT implement cells — that is handled by separate cell agents.

## Manifest Structure

A manifest is an EDN map that defines a complete workflow:

```clojure
{:id    :workflow-name
 :cells {:start     {:id       :namespace/first-cell
                     :doc      \"Entry point — every manifest MUST have a :start cell\"
                     :schema   {:input  {:key :type}
                                :output {:key :type}}
                     :on-error nil
                     :requires []}
         :step-name {:id       :namespace/cell-id
                     :doc      \"What this cell does\"
                     :schema   {:input  {:key :type}
                                :output {:key :type}}
                     :on-error nil
                     :requires [:db]}}
 :edges {:start  :step-name
         :step-name {:transition-name :step-b}
         :step-b :end}
 :dispatches {:step-name [[:transition-name (fn [data] (predicate data))]]}}
```

## Cell References

Each entry in :cells maps a workflow step name to a cell specification:
- `:id` — keyword referencing a registered cell (e.g. `:order/compute-tax`)
- `:doc` — what this cell does (helps cell agents implement it)
- `:schema` — input/output contract using Malli or lite syntax
- `:on-error` — error handler cell or nil
- `:requires` — resource dependencies (e.g. `[:db :cache]`)

## Edge Types

**Unconditional:** `:edges {:step-a :step-b}` — always routes step-a → step-b

**Conditional:** `:edges {:step-a {:valid :step-b, :invalid :step-c}}` — routes based on dispatch predicates

When using conditional edges, define dispatch predicates:
```clojure
:dispatches {:step-a [[:valid   (fn [data] (:valid? data))]
                      [:invalid (fn [data] (not (:valid? data)))]]}
```

## Pipeline Shorthand

For linear flows with no branching. The first element MUST be `:start`:
```clojure
{:id       :simple-flow
 :pipeline [:start :process :render]
 :cells    {:start   {:id :app/parse   ...}
            :process {:id :app/process ...}
            :render  {:id :app/render  ...}}}
```
Pipeline expands to: `:edges {:start :process, :process :render, :render :end}`
Pipeline is mutually exclusive with :edges and :dispatches.

## Joins (Parallel Execution)

```clojure
:joins {:fetch-data {:cells    [:fetch-user :fetch-config]
                     :strategy :parallel}}
:edges {:start      :fetch-data
        :fetch-data :render
        :render     :end}
```
Join member cells have NO entries in :edges. They receive the same input snapshot and run in parallel. Their output keys must not overlap.

## Schema Syntax

Lite syntax (auto-converts to Malli):
```clojure
{:input  {:subtotal :double, :state :string}
 :output {:tax :double}}
```

Per-transition output schemas (for branching cells):
```clojure
{:input  {:user-id :string}
 :output {:found     [:map [:profile [:map [:name :string]]]]
          :not-found [:map [:error-message :string]]}}
```

Common types: :string :int :double :boolean :keyword :any :uuid
Collections: [:vector :string], [:set :keyword]
Enums: [:enum :pending :shipped :delivered]

## Key Design Principles

1. **Cells are isolated** — they only see their input data and resources.
2. **Key propagation** — data accumulates through the workflow.
3. **Schema contracts** — every cell declares what it needs and produces.
4. **Resources are injected** — declared in :requires.

## Your Role

You design and modify manifests. You do NOT implement cells.

## Output Format

**CRITICAL:** When creating or modifying a manifest, wrap it in a fenced code block tagged `edn`.
**When modifying**, output the COMPLETE updated manifest — not a diff.
Keep explanations brief. Focus on the manifest EDN.

## What You Don't Do

- Do NOT implement cell handlers
- Do NOT output usage examples or require statements
- Do NOT invent Mycelium APIs
- Do NOT add features the user didn't ask for
- Do NOT describe the manifest structure back to the user")

(def cell-prompt
  "System prompt for cell agents. Understands cell implementation
   but NOT workflow design."
  "You are implementing cells for the Mycelium workflow framework in Clojure. A cell is a pure, self-contained data transformation step — like a microservice with a schema contract.

## Cell Structure

Every cell source file must start with an `(ns ...)` form that requires `mycelium.cell`:

```clojure
(ns myapp.cells.cell-name
  (:require [mycelium.cell :as cell]))
```

Use `cell/defcell` to register cells. The opts map MUST include a `:doc` string:

```clojure
(cell/defcell :namespace/cell-name
  {:doc    \"Brief description of what this cell does\"
   :input  [:map [:required-input-key :type]]
   :output [:map [:produced-output-key :type]]}
  (fn [resources data]
    {:produced-output-key computed-value}))
```

**defcell takes exactly 3 arguments:** cell-id (keyword), opts (map with required :doc), handler (fn).

## Handler Signature

```clojure
(fn [resources data] -> data-map)
```

- **resources**: Map of external dependencies injected by the application (e.g. {:db datasource, :cache cache-client}). Destructure with `(let [{:keys [db cache]} resources] ...)`. Each resource's type and API will be documented in the cell's **Required resources** section.
- **data**: Accumulating data map from prior cells
- **Returns**: Map of NEW keys only (key propagation is on by default)

## Key Rules

1. **Return only new keys** — don't return the full data map
2. **Never import or call other cells** — cells are isolated
3. **Never acquire resources** — everything comes through resources
4. **Output must satisfy the schema**
5. **Cells are context-free** — work regardless of workflow placement
6. **Pure when possible** — side effects go through resources
7. **Always require `[mycelium.cell :as cell]`** — never use `cell.core` or just `cell`

## Testing Cells

```clojure
(deftest test-cell-name
  (let [handler (:handler (cell/get-cell! :ns/cell-id))
        result (handler resources input-data)]
    (is (= expected-value (:output-key result)))))
```

## Output Format

Return a single ```clojure fenced code block containing exactly this structure:

```clojure
(ns <cell-namespace>
  (:require [mycelium.cell :as cell]
            ;; add other requires as needed
            ))

(cell/defcell <cell-id>
  {:doc \"<purpose>\"
   :input <input-schema>
   :output <output-schema>}
  (fn [resources data]
    ;; your implementation here
    ))
```

Do NOT return anything outside the code block. Do NOT include explanations.")
