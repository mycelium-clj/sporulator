# Familiar-Environment Findings (2026-04-25)

## Summary

Refactored the cell implementor to match patterns LLMs are trained on:
native `tool_use` protocol, a virtual filesystem (handler.clj / helpers.clj /
test.clj), and soft phase gating with a single `complete` finalization tool.
Validated against deepseek-reasoner on two cells from the order-lifecycle
spec — the model produces correct, well-structured cell source on the first
or second `run_tests`, including reaching for helpers organically when the
problem invites decomposition.

The implementor now feels to the model like Claude Code with a constrained
in-process REPL: a tiny project the model can navigate with tools it already
knows.

## Problem framing

Before this work, the implementor:

1. Spoke a fenced-JSON tool-call convention parsed via regex from the LLM's
   text content. Models are trained on the structured `tool_use` API — the
   convention worked but lost reliability and parallelism.
2. Stored handler code as a parsed s-expression in an atom (`:current-handler`).
   Edits did `pr-str` → string-replace → `read-string`, which mangled
   whitespace and broke on quoted strings. Models are trained on file
   abstractions (Read / Edit / Write).
3. Hard-gated tools by phase: DISPATCH only allowed write/run/lint;
   REVIEW only allowed approve/revise/give_up. The model couldn't read its
   code in REVIEW, couldn't refine tests after writing, and had to navigate
   an artificial protocol on top of the actual work.

These were three separate frictions. The agent loop was trained-on-different-
tools wearing a costume.

## Phase 1 — Native tool_use protocol

Replaced the fenced-JSON parser. Tools are now declared as OpenAI tool_use
JSONSchema function definitions and the model emits structured `tool_calls`
content blocks. The agent loop reads `(:tool-calls response)` directly.

`llm.clj`:
- `chat` and `chat-stream` accept optional `:tools` / `:tool-choice` and
  surface `:tool-calls` in the response. The streaming accumulator handles
  delta-fragmented `function.arguments`.
- `session-send-stream` persists assistant turns including `tool_calls` so
  the protocol round-trips correctly.
- `session-continue-stream` resumes a session after tool results have been
  appended (no new user message). This is what the agent loop calls between
  turns — the session itself carries history natively, so the user prompt
  is only sent once at startup.
- `session-append-tool-result!` appends `{:role "tool" :tool_call_id ... :content ...}`.

`tool_registry.clj`:
- Dropped `parse-tool-call` / `tool-call-fence-re` / `render-tool-catalog`.
- Tools live in a `tool-schemas` map keyed by tool name with full JSONSchema
  parameter definitions including `enum` constraints for path arguments.

The system prompt no longer mentions any wire format.

## Phase 2 — Virtual filesystem

Replaced parsed-form state with three named string buffers:

- `handler.clj` — the `(fn [resources data] ...)` form
- `helpers.clj` — top-level `defn` / `def` forms
- `test.clj` — pre-populated with the locked test contract

Tool surface (replacing `write_handler` / `write_test` / `patch_handler` /
`patch_test` / `define`):

- `read_file(path)` — returns `cat -n`-style line-numbered content,
  `(empty)` if the buffer is blank
- `write_file(path, content)` — overwrite
- `edit_file(path, old_string, new_string, [replace_all])` — Claude Code
  semantics: unique exact-string match by default, error on ambiguous match
  with hint to use `replace_all`
- `list_files()` — table of name / lines / chars

Files are parsed on demand (at `run_tests` time) rather than being kept
parsed in state. Mid-edit malformed source is tolerated (the cell-graph
rebuild silently skips on parse failure); `run_tests` surfaces parse errors
clearly so the agent can recover. The `mcp_bridge` ns is unchanged — the
agent_loop builds the legacy ctx shape from `:files` on demand.

## Phase 3 — Soft phase gating + complete

Collapsed `:dispatch` and `:review` into a single `:working` phase. Dropped
`done` / `approve` / `revise`. The locked contract is the brief (id, doc,
schema, requires); test.clj is editable so the agent can refine cases as it
learns. Everything else is the agent's working space.

A single `complete` tool replaces the review gate:

- If `:last-tests-green?` is true (a recent `run_tests` passed), finalize
  with the green snapshot.
- Otherwise, run tests now. Pass → snapshot + finalize. Fail → return the
  failure as a tool result with a hint and stay in working mode.
- Catches the empty-handler case explicitly.

`dispatch-tool-call` no longer phase-gates — it only rejects unknown tools.
Budget exhaustion always stagnates (no auto-success-on-green magic). The
agent declares done explicitly via `complete` or aborts via `give_up`.

`agent_loop_test.clj` covers the freedom guarantees:
- `free-iteration-handler-then-tests-then-handler-test` — write wrong
  handler, fail, edit, pass, complete
- `agent-edits-tests-then-handler-test` — agent reads test.clj, augments
  it, then implements
- `complete-runs-tests-on-its-own-test` — complete works without a prior
  run_tests
- `complete-blocks-on-failing-tests-test` — complete with failing tests
  returns "complete blocked"; agent recovers
- `no-phase-rejection-test` — interleaves all kinds of tools throughout;
  asserts no tool result mentions phase rejection

Test counts: 142 tests, 601 assertions across the suite. The new
`agent_loop_test.clj` ns contributes 18 tests / 49 assertions.

## Live validation

Driver: `notes/live_check.clj`. Loaded via `(load-file ...)` against a
running sporulator with deepseek-reasoner configured. Two cells from
`../order-lifecycle/SPEC.md`.

### Run 1 — :order/fraud-check

Three-band classifier: total > 5000 → :reject; > 2000 → :review;
else :approve. Single field input.

```
turns:    11
elapsed:  ~30s
status:   :ok
tool log: get_spec → read_file → read_file → read_file → write_file
        → run_tests → edit_file → run_tests → lint → check_schema → complete
```

Notable behaviours:
- Surveyed all three buffers before writing anything.
- First `run_tests` failed; recovered by `edit_file` rather than rewriting.
- Used `lint` and `check_schema` opportunistically — both are optional, the
  agent chose them. Soft gating is observable in practice.

Final source: a clean inline `cond` over `:total`. No helpers. Behaviour
verified by registering the cell and exercising the handler directly:
- `{:total 100.0}` → `{:status :approve}`
- `{:total 3000.0}` → `{:status :review}`
- `{:total 6000.0}` → `{:status :reject}`

### Run 2 — :order/item-tax-rate

Per-state, per-category tax rate with price-dependent exemptions. Four
states (CA, NY, OR, TX) with distinct rules; eight assertion groups.

```
turns:    7
elapsed:  ~15s
status:   :ok
tool log: get_spec → list_files → read_file → write_file
        → write_file → run_tests → complete
```

Notable behaviours:
- Two `write_file` calls — one for `helpers.clj`, one for `handler.clj`.
- Tests passed on the first `run_tests`.
- The harder cell finished faster because the structural fit was obvious.

Final source structure (abridged):

```clojure
(defn tax-rate-ca "CA: base 7.25%; electronics get a +1.5% surcharge."
  [{:keys [category]}]
  (if (= category :electronics) 0.0875 0.0725))

(defn tax-rate-ny "NY: base 8.875%; clothing under $110 exempt; books always exempt."
  [{:keys [category item-price]}]
  (cond (= category :books) 0.0
        (and (= category :clothing) (< item-price 110.0)) 0.0
        :else 0.08875))

(defn tax-rate-or "OR: everything is 0%." [_] 0.0)

(defn tax-rate-tx "TX: base 6.25%; digital items are exempt."
  [{:keys [category]}]
  (if (= category :digital) 0.0 0.0625))

(def state-rates {"CA" tax-rate-ca, "NY" tax-rate-ny,
                  "OR" tax-rate-or, "TX" tax-rate-tx})

(cell/defcell :order/item-tax-rate {...}
  (fn [resources data]
    (let [state (:state data)
          rate-fn (get state-rates state)]
      (if rate-fn
        {:rate (rate-fn data)}
        (throw (ex-info (str "Unknown state: " state) {:state state}))))))
```

This is senior-engineer-quality structure: one helper per state, a
data-driven dispatch map, a thin handler that throws on unknown state
rather than silently defaulting. The model arrived at this organically
because `helpers.clj` is a natural place to put per-state logic — no
planning phase required.

All eight test cases verified against the registered cell.

## Takeaways

1. **Protocol fit dominates.** The single biggest reliability win was
   moving to native `tool_use`. Once the model wasn't translating between
   training and a custom convention, it stopped emitting malformed
   tool calls and started using tools idiomatically.

2. **Files invite decomposition for free.** The `helpers.clj` buffer is a
   first-class affordance. Without any prompt about decomposition, the
   model used helpers when the problem warranted it (item-tax-rate) and
   skipped them when it didn't (fraud-check). This is what we hoped the
   planning phase would teach the model — it turns out the file abstraction
   was the actual lever.

3. **Soft gating beats hard rails.** The agent freely interleaved `lint`
   and `check_schema` between writes (fraud-check) and skipped them
   entirely when not needed (tax-rate). Both are reasonable choices.
   Hard-gating either side would have removed valid behaviours.

4. **`complete` as a verification gate.** Folding "I'm done" + final test
   run + finalization into one tool removes the artificial review phase
   while keeping the safety check. The agent calls it once it's confident,
   the harness has the last word.

5. **The brief is the only locked contract.** Schema, doc, requires —
   immutable by virtue of not being exposed as writable. test.clj is
   editable because that matches reality (TDD often reveals the test was
   wrong). The architect-above level still imposes the actual contract;
   the implementor has freedom inside it.

## Implications for the planning phase

The original motivation for a `propose_leaf` / `propose_subgraph` planning
phase was that the model needed structural guidance about whether to
decompose. The live runs suggest the file abstraction handles most of
that automatically:

- Helpers emerge when natural (item-tax-rate).
- They don't get forced when unnecessary (fraud-check).
- The model uses them with idiomatic Clojure structure (dispatch map).

The planning phase still has a role: deciding when a cell is too big for
even a helpers-rich single cell and should genuinely become a sub-graph.
That decision is rarer than we initially scoped, and the file substrate
makes the leaf path comfortable enough that the bar for choosing
"subgraph-needed" should be high.

## Where to find the work

- `src/sporulator/llm.clj` — native tool_use API
- `src/sporulator/tool_registry.clj` — JSONSchema tool defs
- `src/sporulator/agent_loop.clj` — single-phase agent loop, file ops,
  `complete` semantics
- `test/sporulator/agent_loop_test.clj` — 18 behaviour tests, soft-gating
  validations, file-op semantics
- `test/sporulator/llm_test.clj` — tool_use protocol round-trip tests
- `notes/live_check.clj` — live driver for reproducing the two runs
