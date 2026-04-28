# Sporulator Agent-Loop Refactor Plan

## Overview

Replace the monolithic `implement-from-contract` loop with an interactive tool-use dispatch agent
modeled on rlm-sandbox's `design-dispatch-agent.ts`. The model works against an in-process Clojure
REPL (not the filesystem) and emits one tool call per turn as a fenced JSON block. Code graph
analysis uses `core.logic` instead of Prolog.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                      orchestrator.clj                           │
│  implement-from-contract  ─── calls ───►  agent-loop/run!       │
└─────────────────────────────────────────────────────────────────┘
                                              │
                    ┌─────────────────────────┼─────────────────────────┐
                    │                         │                         │
                    ▼                         ▼                         ▼
           ┌──────────────┐          ┌──────────────┐          ┌──────────────┐
           │  agent_loop  │◄────────►│tool_registry │          │  code_graph  │
           │  (FSM)       │  tools   │  (dispatch)  │          │ (core.logic) │
           └──────────────┘          └──────────────┘          └──────────────┘
                    │                         │                         │
                    │                         ▼                         │
                    │                ┌──────────────┐                   │
                    │                │  mcp_bridge  │◄──────────────────┘
                    │                │ (context)    │
                    │                └──────────────┘
                    │                         │
                    ▼                         ▼
           ┌──────────────────────────────────────┐
           │            eval.clj (REPL)            │
           │  eval-code  run-cell-tests  lint-code │
           │  instantiate-cell  verify-contract    │
           └──────────────────────────────────────┘
```

## Component Interactions

### Flow: One Turn

```
1. agent_loop renders prompt (system + conversation history)
2. Agent sends prompt to LLM via llm.clj chat-stream
3. LLM responds with a ```tool-call fence containing JSON
4. agent_loop parses tool call: parse-tool-call
5. agent_loop dispatches to tool_registry/execute!
6. Tool implementation calls into eval.clj / code_graph / mcp_bridge
7. Result is formatted as a string and appended to conversation history
8. If tool was run_tests and tests passed → transition to REVIEW phase
9. Loop continues until done, approve, give_up, or budget exhausted
```

### Phase Machine

```
    ┌──────────┐     get_spec, get_task, get_context,
    │          │     list_siblings, get_sibling,
    │ DISPATCH │     get_callers, get_callees,
    │          │     list_ns, inspect_ns, eval, define,
    └────┬─────┘     write_handler, write_test,
         │           patch_handler, run_tests, lint,
         │           check_schema, give_up
         │
         │ run_tests returns ok:true
         ▼
    ┌──────────┐
    │  REVIEW  │     approve, revise({reason}), give_up({reason})
    │          │
    └────┬─────┘
         │
    ┌────┴─────┐
    │          │
    ▼          ▼
  approve    revise
  (ship)     (back to DISPATCH)
```

---

## Step 1: Add core.logic dependency

**File: `deps.edn`**

Add `org.clojure/core.logic` dependency for graph analysis queries.

**Outcome:** The project can require `clojure.core.logic` for relational graph queries.

---

## Step 2: Create `src/sporulator/code_graph.clj`

### Purpose
Provides code-graph analysis using core.logic. Replaces the Prolog-based graph analysis from rlm-sandbox.

### Data Model
Graph facts are stored as a set of Clojure vectors (triples) in an atom:

```clojure
;; Facts
#{[:calls :a :b]           ;; a calls b
  [:defines :a "ns/file.clj" :function 10]
  [:imports "ns/file.clj" :mycelium.cell]
  [:requires :a :db]       ;; cell :a requires :db resource
  [:produces :a :handle]   ;; cell :a produces :handle key
  [:consumes :a :user-id]} ;; cell :a consumes :user-id key
```

### API

| Function | Description |
|---|---|
| `build-cell-graph` | Build graph facts from manifest + loaded cells |
| `build-repl-graph` | Build graph facts from loaded REPL namespaces |
| `callers` / `callees` | Who calls / is called by a given cell/function |
| `reachable?` | Can A reach B via call chain? |
| `impact` | Transitive callers affected by a change |
| `dead-code` | Defined but unreachable functions (not in call graph) |
| `cycles` | Circular call dependencies |
| `data-flow` | What data keys flow from A → B through the pipeline |
| `graph-to-logic` | Convert facts to core.logic relations for custom queries |
| `add-edge!` / `add-def!` | Mutation helpers for incremental graph building |

### Implementation Approach

Use `clojure.core.logic/run*` with `membero`, relational goals, and `conde` for
recursive queries. Build the graph incrementally as cells are loaded into the REPL.
Since core.logic doesn't have tabling, implement cycle-safe reachability with
explicit visited-set tracking via `distinct` or by materializing the transitive
closure in a set.

```clojure
;; Example: reachable? via materialized transitive closure
(defn transitive-calls [facts]
  (let [direct (set (for [[_ a b] facts
                          :when (= _ :calls)]
                      [a b]))]
    (loop [closure direct]
      (let [new-edges (for [[a b] closure
                            [c d] closure
                            :when (= b c)
                            :when (not (contains? closure [a d]))]
                        [a d])
            next (into closure new-edges)]
        (if (= closure next)
          next
          (recur next))))))
```

---

## Step 3: Create `src/sporulator/mcp_bridge.clj`

### Purpose
Provides context-exploration tools for the model. This is the "what the model can see"
layer — it bridges between the REPL state and the tool catalog without exposing
the model to raw filesystem operations.

### API

| Function | Description |
|---|---|
| `get-spec` | Returns the cell's doc, schema, requires, and resource docs |
| `get-task` | Returns the overall orchestration task description |
| `get-context` | Returns workflow position (predecessors, successors, graph context) |
| `list-siblings` | Lists other cells being implemented in this run |
| `get-sibling` | Returns spec + handler + tests for a sibling cell |
| `list-loaded-ns` | Lists namespaces loaded in the REPL |
| `inspect-ns` | Returns public vars in a loaded namespace |
| `get-cell-handler` | Returns current handler code for this cell |
| `get-test-code` | Returns current test code for this cell |

### Implementation

All functions are pure data accessors. They query:
- The current REPL state (loaded namespaces, defined vars)
- The orchestration context (manifest, siblings, resource docs)
- The session state (current handler, test code, attempt history)

This is intentionally kept separate from `tool_registry.clj` because the tool
implementations that interact with the REPL (eval, define, write_handler) belong
in the tool layer, while pure context reads belong here.

---

## Step 4: Create `src/sporulator/tool_registry.clj`

### Purpose
Defines the tool catalog (names, args, descriptions), parses tool calls from LLM
responses, dispatches tool execution, and formats results for conversation history.

### Tool Catalog

```
┌─────────────────────┬──────────────────────────────────────────────────┐
│ Tool Name           │ Args                  │ Category                  │
├─────────────────────┼──────────────────────────────────────────────────┤
│ get_spec            │ {}                    │ CONTEXT                   │
│ get_task            │ {}                    │ CONTEXT                   │
│ get_context         │ {}                    │ CONTEXT                   │
│ list_siblings       │ {}                    │ CONTEXT                   │
│ get_sibling         │ {name :string}        │ CONTEXT                   │
│ get_callers         │ {name? :string}       │ GRAPH                     │
│ get_callees         │ {name? :string}       │ GRAPH                     │
│ graph_impact        │ {target :string}      │ GRAPH                     │
│ graph_path          │ {from :string, to :string} │ GRAPH               │
│ list_ns             │ {}                    │ INSPECT                   │
│ inspect_ns          │ {ns :string}          │ INSPECT                   │
│ eval                │ {code :string}        │ EDIT/EXPLORE              │
│ define              │ {code :string}        │ EDIT                      │
│ write_handler       │ {content :string}     │ EDIT                      │
│ write_test          │ {content :string}     │ EDIT                      │
│ patch_handler       │ {search :string, replace :string} │ EDIT          │
│ patch_test          │ {search :string, replace :string} │ EDIT          │
│ run_tests           │ {}                    │ EXEC                      │
│ lint                │ {}                    │ EXEC                      │
│ check_schema        │ {}                    │ VALIDATE                  │
│ done                │ {}                    │ CONTROL (REVIEW only)     │
│ approve             │ {}                    │ CONTROL (REVIEW only)     │
│ revise              │ {reason :string}      │ CONTROL (REVIEW only)     │
│ give_up             │ {reason :string}      │ CONTROL (any phase)       │
└─────────────────────┴──────────────────────────────────────────────────┘
```

### Tool Call Protocol

The model emits one tool call per turn, wrapped in a fenced JSON block:

```
Here's my analysis... I'll inspect the spec first.

```tool-call
{"name": "get_spec", "args": {}}
```
```

The harness:
1. Parses the FIRST `\`\`\`tool-call` fence from the response
2. Validates JSON structure (must have `name` string, optional `args` map)
3. Dispatches via `execute-tool!`
4. Returns the result string appended to conversation history
5. A response with no fence → wasted turn, nudge the model

### API

| Function | Description |
|---|---|
| `catalog` | Returns the full tool catalog as data (for prompt rendering) |
| `render-tool-catalog` | Renders the catalog as a prompt string |
| `parse-tool-call` | Extracts `{name, args, parse-error?}` from LLM response |
| `execute-tool!` | Dispatches tool by name, returns result string |
| `tool-allowed-in-phase?` | Checks if a tool is valid for the current phase |

### Tool Implementation Notes

**`eval`** — wraps `ev/eval-code`, returns stdout + result. The model uses this to explore
the REPL: inspect data structures, test expressions one at a time, experiment with APIs.
Unlike the current monolithic approach, the model can incrementally explore before
committing to a full handler.

**`write_handler`** — The key difference from the current approach. Instead of the model
returning a full (fn [resources data] ...) form in a single response, the model can
build the handler incrementally: define helpers via `define`, assemble the main fn,
write it via `write_handler`, then run tests. The handler is assembled by the harness
using `codegen/assemble-cell-source`.

**`patch_handler`** — Uses string search-and-replace (must be unique). Enables surgical
fixes without rewriting the entire handler. Implementation: `clojure.string/replace-first`
with uniqueness check on the search string.

**`run_tests`** — Assembles the full source (cell + test), evals it, runs tests via
`ev/run-cell-tests`. Returns formatted test output. If tests pass, sets the
`last-tests-green` flag on the session, which triggers the REVIEW phase transition.

---

## Step 5: Create `src/sporulator/agent_loop.clj`

### Purpose
The core FSM that drives the interactive tool-use loop. Manages session state, renders
prompts, parses tool calls, manages phase transitions, and enforces the turn budget.

### State Model

```clojure
{:phase          :dispatch | :review | :done
 :turn           0
 :turn-budget    15
 :session        ;; LLM session (from llm.clj)
 :cell-id        ;; keyword
 :cell-ns        ;; string
 :brief          ;; original cell brief
 :test-code      ;; test contract string
 :test-ns-name   ;; string
 :schema-parsed  ;; parsed schema
 :code-graph     ;; code_graph atom
 :mcp-ctx        ;; mcp_bridge context map
 :green-handler  ;; handler code at last green run (for review)
 :green-tests    ;; test code at last green run
 :current-handler ;; current handler code (may differ from green)
 :current-tests   ;; current test code
 :tool-history   ;; [(tool-call result) ...]
 :last-tests-green? ;; boolean — resets on patch/rewrite
 :repair-attempts ;; count of structural repair cycles}
```

### API

| Function | Description |
|---|---|
| `run!` | Main entry point: runs the dispatch loop for a cell |
| `init-session` | Creates the initial session state from orchestration context |
| `render-dispatch-prompt` | Renders the dispatch-phase prompt |
| `render-review-prompt` | Renders the review-phase prompt |
| `process-turn` | Processes one turn: prompt → LLM → parse → execute → update state |
| `transition-to-review` | Handles the dispatch → review transition |
| `handle-dispatch-tool` | Routes a tool call during dispatch phase |
| `handle-review-tool` | Routes a tool call during review phase |
| `finalize` | Assembles the final result map |

### Prompt Structure

#### System Prompt (injected once, similar to `prompts.clj/cell-prompt`)

```
You are implementing a Mycelium cell in Clojure via TDD. You work step-by-step,
emitting exactly ONE tool call per response as a fenced JSON block.

## Protocol
On every turn, emit:
```tool-call
{"name": "<tool>", "args": {...}}
```

## Phases
- DISPATCH: Explore context, write tests, write handler, run tests.
- REVIEW: When tests pass, review the result. Approve, revise, or give_up.

## Rules
- NEVER write (ns ...) or (cell/defcell ...) — the harness assembles those.
- Write ONLY the handler body: (fn [resources data] ...)
- DO NOT read files or run shell commands — use the provided tools.
- The REPL evaluates your code; you inspect results via eval().
```

#### Dispatch Prompt (per turn)

```
You are implementing cell `:order/compute-tax`.

**Task:** <overall task description>

**Schema:**
{:input {:subtotal :double :state :string} :output {:tax :double}}

**Resources:** [:db] — database connection

---

Available tools:
CONTEXT: get_spec, get_task, get_context, list_siblings, get_sibling
GRAPH: get_callers, get_callees, graph_impact, graph_path
INSPECT: list_ns, inspect_ns
EDIT: eval, define, write_handler, write_test, patch_handler, patch_test
EXEC: run_tests, lint
CONTROL: give_up

---

--- Conversation so far ---
Turn 1:
Tool: get_spec
Result: Cell :order/compute-tax, schema: {...}, requires: [:db], doc: "..."

Turn 2:
Tool: write_test
Result: ok — wrote test file (142 chars)

Turn 3:
Tool: run_tests
Result: FAILED — 0 passed, 3 failed...
  FAIL in test-compute-tax: expected: 8.5, actual: nil

---

Your next tool call:
```

#### Review Prompt (per turn)

```
REVIEW PHASE — tests just went green.

Target: :order/compute-tax
Cell NS: myapp.cells.order.compute-tax

SPEC:
{:input {:subtotal :double :state :string} :output {:tax :double}}

Final handler:
```clojure
(fn [resources data]
  (let [rate (get-in data [:state] ...)]
    ...))
```

Final test file:
```clojure
(deftest test-compute-tax ...)
```

Your move: approve(), revise({reason}), or give_up({reason}).

Default to approve. Revise only when you spot a concrete spec-vs-tests
mismatch. Do NOT revise for cosmetic reasons.
```

### Turn Processing Logic

```clojure
(defn process-turn [state]
  (let [prompt  (if (= :review (:phase state))
                  (render-review-prompt state)
                  (render-dispatch-prompt state))
        response (llm/session-send-stream (:session state) client prompt on-chunk)
        call     (tool-registry/parse-tool-call response)]
    (cond
      ;; No fence found — waste turn
      (nil? call)
      (-> state
          (update :turn inc)
          (add-to-history {:name "__no_call__" :args {}}
                          "No tool-call fence found. Emit exactly one tool call per turn."))

      ;; JSON parse error — waste turn
      (:parse-error call)
      (-> state
          (update :turn inc)
          (add-to-history call (str "Parse error: " (:parse-error call))))

      ;; Phase-specific routing
      (= :review (:phase state))
      (handle-review-tool state call)

      :else
      (handle-dispatch-tool state call))))
```

---

## Step 6: Modify `src/sporulator/orchestrator.clj`

### Changes to `implement-from-contract`

Replace the monolithic loop (lines 453-634) with a call to the new agent loop:

```clojure
(defn implement-from-contract
  [client {:keys [contract store run-id on-event on-chunk max-attempts
                  project-path base-ns task]
           :or   {max-attempts 3}}]
  (let [{:keys [cell-id brief test-code cell-ns]} contract
        graph   (code-graph/build-cell-graph ...)  ;; or get from orchestration context
        mcp-ctx (mcp-bridge/build-context ...)]
    (agent-loop/run!
      {:client        client
       :cell-id       cell-id
       :cell-ns       cell-ns
       :brief         brief
       :test-code     test-code
       :schema-parsed schema-parsed
       :code-graph    graph
       :mcp-ctx       mcp-ctx
       :turn-budget   15
       :on-event      on-event
       :on-chunk      on-chunk
       :store         store
       :run-id        run-id
       :project-path  project-path
       :base-ns       base-ns
       :task          task})))
```

### Changes to `orchestrate!`

Pass the manifest and task context through so the agent loop can provide context tools:

```clojure
;; In orchestrate!, when building briefs, add:
;; :task — the overall orchestration description
;; :manifest-ctx — graph context for each cell
```

---

## Step 7: Modify `src/sporulator/cell_agent.clj`

### Changes to `implement-with-feedback`

Update to use the new agent loop instead of `feedback-loop`:

```clojure
(defn implement-with-feedback
  [client brief on-chunk
   & {:keys [on-feedback max-attempts code-graph mcp-ctx]
      :or   {max-attempts 3}}]
  (let [cell-kw    (->keyword (:id brief))
        result     (agent-loop/run!
                     {:client      client
                      :cell-id     cell-kw
                      :brief       brief
                      :code-graph  code-graph
                      :mcp-ctx     mcp-ctx
                      :turn-budget 15
                      :on-chunk    on-chunk})]
    (if (= :ok (:status result))
      {:status  :ok
       :cell-id (:id brief)
       :code    (:code result)
       :raw     (:raw result)
       :session (:session result)}
      {:status  :error
       :cell-id (:id brief)
       :error   (:error result)
       :session (:session result)})))
```

---

## Step 8: Modify `deps.edn`

Add `core.logic`:

```clojure
:deps {...
       org.clojure/core.logic {:mvn/version "1.0.1"}}
```

---

## Implementation Order

| Step | File | Description | Dependency |
|------|------|-------------|------------|
| 1 | `deps.edn` | Add core.logic dependency | None |
| 2 | `code_graph.clj` | Code graph analysis with core.logic | Step 1 |
| 3 | `mcp_bridge.clj` | Context-exploration tools | None (pure data access) |
| 4 | `tool_registry.clj` | Tool catalog, parsing, dispatch | Steps 2, 3 |
| 5 | `agent_loop.clj` | Core FSM, prompt rendering, phase machine | Steps 2, 3, 4 |
| 6 | `orchestrator.clj` | Replace `implement-from-contract` loop | Step 5 |
| 7 | `cell_agent.clj` | Update `implement-with-feedback` | Step 5 |

---

## Key Design Decisions

### 1. Why one tool per turn?
Following rlm-sandbox's proven pattern: one tool per turn keeps the model focused and
the conversation deterministic. Each tool result informs the next choice. The model can
chain multiple tool calls across turns but never batch them — this prevents runaway
sequences where 5 edits happen before validation.

### 2. Why REPL-only (no filesystem)?
The model should not read/write files directly because:
- The cell source is assembled by `codegen` from structured data, not free-form text
- Test code is generated and stored contractually, not written to disk
- The REPL provides the source of truth for what's loaded and what's defined
- This keeps the model in "explore the REPL state" mode rather than "guess file paths"

### 3. Why core.logic instead of Prolog?
- Already a Clojure dependency (no JS interop, no Tau Prolog library)
- Relational queries read naturally and compose well
- The graph is small (workflow-level, not project-level), so core.logic's performance
  characteristics are more than adequate
- Transitive closure via materialized set is simpler and avoids tabled execution concerns

### 4. Why two-phase (DISPATCH → REVIEW)?
The critical failure mode in the current approach is "model hits green, then fiddles
with code until it breaks again." The REVIEW phase gates this: once tests pass, the
model can only approve, revise (with explicit reason, back to dispatch), or give_up.
It cannot make further edits directly — if it wants to revise, it must re-prove green.

### 5. How does the turn budget work?
Default 15 turns (modeled on rlm-sandbox). If exhausted:
- In dispatch phase: return `{:status :stagnated}` so the orchestrator can decompose
  or mark as failed
- In review phase: auto-approve (the implementation IS green, the model just didn't
  confirm — ship what we have)

### 6. How does the handler flow work?
```
Model calls get_spec → sees schema
Model calls eval("(+ 1 2)") → explores REPL, tests expressions
Model calls define for helpers → (defn calc-tax [rate subtotal] ...) evaluated in REPL
Model calls write_handler → harness stores (fn [resources data] ...) in session state
Model calls write_test → harness stores test code in session state
Model calls run_tests → harness assembles full source, evals, runs tests
  → If green: transition to REVIEW, snapshot green-handler + green-tests
  → If red: return failure output, stay in DISPATCH
Model calls patch_handler → surgical fix on current handler
Model calls run_tests → re-prove
  → If green: REVIEW
Model calls approve → harness assembles final source, writes file, returns :ok
```

### 7. How are handlers assembled?
The model writes ONLY the `(fn [resources data] ...)` body. The harness uses
`codegen/assemble-cell-source` to wrap it in the proper ns + defcell form. This
ensures structural correctness (ns name, cell ID, schema) are always correct
regardless of what the model writes.

---

## Error Handling Strategy

| Scenario | Behavior |
|---|---|
| No tool-call fence in response | Waste turn; nudge model to emit one tool call |
| Invalid JSON in fence | Waste turn; return parse error as tool result |
| Unknown tool name | Return "unknown tool" error with list of valid tools |
| Tool called in wrong phase | Return "not available in this phase" error |
| Turn budget exhausted in dispatch | Return `{:status :stagnated}` |
| Turn budget exhausted in review | Auto-approve (implementation is green) |
| LLM API error | Return `{:status :error :error msg}` |
| run_tests fails after 5+ dispatch turns | Inject failure memory hints (from `prompts.clj` fix tiers) |
| Structural validation fails | Run auto-fix loop (ns name, cell ID corrections) before eval |

---

## Graduated Feedback (inherited from prompts.clj)

When tests fail repeatedly, the agent loop can inject hints following the existing
graduated fix prompt tiers:

- **Attempts 1-2:** Standard — include all test output with full context
- **Attempts 3-4:** Narrowed — focus on first failing test only
- **Attempts 5+:** Fresh — suggest starting from scratch

These are injected as extra context in the dispatch prompt when test failures are
consecutive, not as separate user messages (to keep the conversation history clean).

---

## Stagnation Handling

When `{:status :stagnated}` is returned:
1. The orchestrator can call `reflect-on-stagnation` (new function in `agent_loop.clj`)
   which sends a single-turn "stuck" prompt to the LLM asking for a diagnosis
2. If the diagnosis suggests the cell is too complex → decompose via graph agent
3. If the diagnosis suggests test contract is wrong → flag for human review
4. If the diagnosis is unclear → retry with fresh session (discard conversation history)

This replaces the current hard 3-retry limit with a smarter recovery path.

---

## Files Summary

| File | Status | Lines (est.) |
|------|--------|-------------|
| `deps.edn` | Modify | +1 line |
| `src/sporulator/code_graph.clj` | New | ~250 lines |
| `src/sporulator/mcp_bridge.clj` | New | ~150 lines |
| `src/sporulator/tool_registry.clj` | New | ~350 lines |
| `src/sporulator/agent_loop.clj` | New | ~400 lines |
| `src/sporulator/orchestrator.clj` | Modify | -180 lines (simplify implement-from-contract) |
| `src/sporulator/cell_agent.clj` | Modify | -60 lines (simplify implement-with-feedback) |
| **Total** | | ~1150 new, ~240 removed |
