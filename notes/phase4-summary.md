# Phase 4: contract-aware prompts + agent loop overhaul (close-out)

## Goal

Take a working scaffold (Phases 1–3 had landed contract-aware prompts
and migrated `reject-impl!` to the agent loop) and prove it
end-to-end against a real architect-driven workflow revision: add a
`rate-limit-handle` cell to the guestbook manifest, have the
diff-aware orchestrator regenerate the affected cells, watch the
implementor agent succeed.

Final result: **5/5 cells implement cleanly, 7–11 tool calls per
cell**, no manual rejects, no stagnations. From a 2/5 baseline.

## What we shipped (A → S)

The fixes split into three buckets — *prompts steer*, *codegen
enforces*, *runtime gives clean feedback*.

### Codegen + schema correctness

| Fix | What |
|-----|------|
| – | `assemble-cell-source` renders dispatched-output sub-schemas in vector form (`[:map [:k v]]`). Otherwise `defcell` doesn't see the dispatched shape and the workflow's schema-chain validator treats `:success`/`:failure` as data keys. |
| C   | `assemble-test-source` requires `[next.jdbc.result-set :as rs]` so tests can use `as-unqualified-maps`. |
| –   | Cell-id canonicalisation at every public mutation entry point — `bare-cell-id` once at the boundary, store and run-state lookups stop disagreeing. |

### Prompts (architect / test-gen / implementor)

| Fix | What |
|-----|------|
| Phase 1 | Test-gen prompt teaches the dispatched-output convention: assertions check flat shapes, never wrapped under transition labels. |
| Phase 2 | Implementor system prompt teaches the same convention from the handler side. |
| B | Test-gen prompt warns about `next.jdbc/execute!`'s qualified-keyword default and points at qualified-key assertions or `as-unqualified-maps`. |
| E | Test-gen prompt discourages exact error-string assertions unless the brief specifies them. |
| I | Implementor prompt ships canonical `INSERT … RETURNING id` patterns when the cell `:requires :db`. |
| K | Reframed system prompt: "you're working in a live REPL — use it." Replaces the constraint-heavy "workflow discipline" framing that we tried first. |

### Orchestrator / runtime

| Fix | What |
|-----|------|
| A | `approve-tests!`/`reject-impl!`/batch path drop `:prev-source` — fresh-mode by default. Edit-mode anchored agents on broken prior patterns. |
| D | `helpers.clj` rejects top-level `(ns …)` forms with a clear error so the agent doesn't loop on namespace confusion. |
| F | Turn budget bumps to 25 for `:requires` cells. |
| L | `:eval` pre-loads the assembled cell source so the agent can call helpers and exercise the handler directly in the REPL. |
| M+N | `run_tests` returns `TESTS: M/N passing` + a focused first-failure block (`expected:` / `actual:` / `message:`). Agent fixes one test at a time. |
| P | Plateau detection: if pass count hasn't moved for `N` consecutive `run_tests`, the result gets a "rethink / decompose" hint. |
| O | Orchestrator-side test-gen sanity check: each candidate contract is run against a passthrough stub; if it doesn't load or every test errors, the LLM gets one repair attempt. |
| **Q** | `assemble-and-eval` clears cell-ns + test-ns before re-eval'ing — kills the ghost-deftest-from-previous-runs bug that masked many earlier "failures". |
| R | `:eval` pre-load also clears cell-ns — same root cause as Q on the eval surface. |
| S | Test-gen sanity check also clears both namespaces — same root cause as Q on the orchestrator surface. |

### Wrong-direction fixes that we reverted

G/H/J pushed the model *toward* committing code by appending warnings
and eventually refusing to execute non-progress tools. They worked on
the simple cells but the harder cells ignored or routed around them,
because constraint-heavy designs aren't what code-writing agents
need. Two references confirmed the right direction:

- *How to build an agent* (ampcode.com): "no correction loops, no
  second-guessing. You execute the tool and send the response up.
  Delegates orchestration entirely to the model's reasoning."
- *The Emperor Has No Clothes* (mihaileric.com): the minimum-viable
  loop is just LLM + tools + transparent feedback.

K replaced G/H/J with an explicit reframe: encourage the agent to
exercise helpers and the handler in the REPL, and only intervene
with a soft hint after a clear plateau.

## The bug that mattered most: Q (and friends R, S)

Eight runs of the same orchestration produced wildly different
results — sometimes 4/5, sometimes 2/5 — even with identical prompts
and tools. Tool traces showed agents writing seemingly correct code
that nonetheless "failed" tests.

Root cause: when sporulator's REPL ran an orchestration, the cell
namespace and the test namespace persisted across runs. clojure.test
walks every `^:test` var in the test ns, so deftests from earlier
orchestrations *that no longer existed in the current contract*
still ran and reported as failures. The agent saw failures it could
not reconcile with the file it had actually written, because those
failures were ghosts.

Once Q (the `remove-ns` clear) landed, the next run was 5/5 cleanly.
The same cells that had stagnated for nine runs converged in 7–11
tool calls each. R and S applied the same clear to the `:eval` and
sanity-check surfaces, so feedback there is also derived from
current-only state.

## Lesson

For an LLM coding agent, the highest-leverage work is on **feedback
fidelity**, not orchestration cleverness. Every byte the harness
returns to the model is either:

- **derived from current state** (good — the model can reason about it),
  or
- **derived from stale or incidental state** (bad — the model's
  reasoning is being applied to a fiction, and any "fix" it produces
  is structurally unverifiable).

Most of the apparent agent-side failures we hit were actually the
harness handing the model fiction. See `notes/feedback-staleness-audit.md`
for the systematic audit that this realisation prompted.

## Final scoreboard

| Run | Highest applied fix | Result |
|-----|---------------------|--------|
| 1 | (baseline) | 2/5 |
| 6 | I | 4/5 |
| 8 | K+L | 3/5 |
| 11 | M+N+P+O | 2/5 (Q-bug masked) |
| **12** | **Q** | **5/5** |

Tests: 194 / 775 / 0 at close-out.

Next phases should treat the staleness audit findings as the working
agenda, not invent new orchestrator cleverness. The model is
capable; let it see truth.
