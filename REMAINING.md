# Remaining Features Implementation Plan

All items below are missing from the Clojure port. Implement in order using TDD.
Each round: write failing tests → implement → verify all tests pass.

---

## Round 1: Eval — Schema Validation, Lint, Test Corrections [DONE]

Add workflow-level operations to `sporulator.eval`. These are needed by the
orchestrator for integration testing and schema validation.

### 1.1 `compile-workflow`
Compiles a manifest EDN string into a runnable workflow using mycelium.
```clojure
(compile-workflow manifest-edn-str)
;; => {:status :ok :workflow compiled} or {:status :error :error msg}
```

### 1.2 `run-workflow`
Runs a compiled workflow with input data, captures output + trace.
```clojure
(run-workflow manifest-edn-str input resources)
;; => {:status :ok :result data :trace [...]} or {:status :error :error msg}
```

### 1.3 `validate-schema`
Validates data against a Malli schema string.
```clojure
(validate-schema schema-str data)
;; => {:valid? true} or {:valid? false :explanation ...}
```

### 1.4 `lint-code`
Runs clj-kondo on a code string via shell. Returns errors or nil.
```clojure
(lint-code code)
;; => nil (clean) or {:errors [{:line :col :message}...]}
```

---

## Round 2: Orchestrator — Merge Test Corrections [DONE]

Pure function. Used when self-review returns corrected test forms.

### 2.1 `merge-test-corrections`
Merges corrected deftest forms into original test body.
Algorithm: split both into deftest forms by name, prefer corrections,
append new tests.
```clojure
(merge-test-corrections original-body corrections-body)
;; => merged test body string
```

---

## Round 3: Orchestrator — Lint Fix Loop [DONE]

Iterative syntax fixing: lint → ask LLM to fix → re-lint → retry.

### 3.1 `lint-fix-loop`
Runs linter, if errors asks LLM to fix syntax only, retries up to N times.
```clojure
(lint-fix-loop client session code cell-id
  :max-attempts 3 :on-chunk fn :on-event fn)
;; => {:status :ok :code fixed-code} or {:status :error :error msg}
```

---

## Round 4: Orchestrator — Implementation Review Gate [DONE]

Add impl_review gate to the orchestrator. After cells are implemented and
tests pass, send implementations to the user for review.

### 4.1 `impl-review-gate` in orchestrator
After implement-from-contract succeeds, collect impl results and send
to `on-impl-review` callback. Handle approve/revise responses.

### 4.2 `handle-impl-review` in server
WebSocket handler that delivers impl review responses to the gate.
Sends `impl_review_data` outbound message with cell implementations.

---

## Round 5: Orchestrator — Resume Previous Run [DONE]

Resume a previous orchestration from where it left off.

### 5.1 `resume!` in orchestrator
Check store for previous run with matching manifest-id + spec-hash.
Reload passed cells, re-implement only failed cells.
```clojure
(resume! client {:manifest-id ":app" :spec "..." :store store ...})
;; => same result as orchestrate!
```

### 5.2 `handle-orchestrate-resume` in server
WebSocket handler for `orchestrate_resume` message type.

---

## Round 6: Orchestrator — Schema Validation & Integration Testing [DONE]

### 6.1 `validate-edge-schemas`
Check schema compatibility between connected cells in a manifest.
Detect mismatches (cell A outputs `:x` but cell B expects `:y`).

### 6.2 `fix-schema-chain-errors`
Ask LLM to fix implementations when output schemas don't match
downstream input schemas.

### 6.3 `integration-test`
After all cells implemented: compile full workflow, run with test data,
validate end-to-end. Fix schema mismatches if found.

---

## Round 7: Server — Source Generation & Graph Review [DONE]

### 7.1 `POST /api/source/generate`
Generate source files from stored cells. Takes output_dir and base_namespace.
Writes `.clj` files for each cell in the store.

### 7.2 `graph_review` WebSocket handler
Send decomposed graph to user for review before implementation.
Handle approve/revise responses.

---

## Priority

Rounds 1-3 are prerequisites for Rounds 4-6.
Round 7 is independent and can be done anytime.

**Critical path:** 1 → 2 → 3 → 4 → 5 → 6
**Independent:** 7
