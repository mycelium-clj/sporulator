# Feedback / staleness audit

## Why this matters

Phase 4 closed when Fix Q removed stale test-namespace pollution.
That single change took the success rate from 2/5 to 5/5. The
implication is general: **when the harness shows the model state
that doesn't reflect what the model just did, every subsequent
"fix" the model makes is structurally unverifiable.**

This doc is the audit pass that finding triggered. Each entry asks:
*does the model see something derived from current state, or from
stale / incidental / wrong state?*

## Confirmed bugs (fixed in Phase 4)

### Q — `run_tests` saw deftests from previous orchestration runs

`assemble-and-eval` re-loaded the cell source and the test source
into the same JVM that previous runs had populated. clojure.test's
`run-tests` walks every `^:test` var in a namespace, so deftests
from earlier contracts that no longer existed in the current
test_body still ran and reported failures. The agent saw `:test 2
:pass 1 :fail 1` for what was structurally a 1-test contract.

Fix: prepend
```
(when (find-ns 'cell-ns) (remove-ns 'cell-ns))
(when (find-ns 'test-ns) (remove-ns 'test-ns))
```
to the eval-code input, before the cell source. Verified manually:
same scenario goes from `:test 2 :fail 1` to `:test 1 :pass 1`.

### R — `:eval` returned stale results from removed helpers

The `:eval` tool pre-loads the assembled cell source so the agent
can call helpers and exercise the handler directly. But the cell-ns
isn't cleared first, so old defs persist. Reproducer:

1. `write_file helpers.clj "(defn foo [] 42)"`
2. `eval (foo)` → `42` ✓
3. `write_file helpers.clj "(defn bar [] 99)"` (foo removed)
4. `eval (foo)` → still `42`, no error. Bug.

The agent gets false-positive signals from `eval` that don't survive
`run_tests`. Fix: same `remove-ns` clear as Q, applied in the
`:eval` handler.

### S — Test-gen sanity check accumulated stale stub cells + deftests

`check-test-body` (the orchestrator's pre-approval check from Fix O)
loads a passthrough stub cell + the candidate test code, then runs
the tests. Without the namespace clear, stale stub vars and stale
deftests from earlier sanity checks bled into the result, making the
"all tests errored" detection unreliable.

Fix: same `remove-ns` clear, applied before the sanity-check eval.

## Patterns we've now confirmed

The Q/R/S triad all share the same shape: **the harness re-evaluates
source into a long-lived JVM, but doesn't first reset the
destination namespace**. Anything else that does this is suspect.

The clear is cheap (one `remove-ns` per affected ns), idempotent,
and well-localised. It belongs at every boundary where the harness
loads source meant to represent "the current state of the cell" or
"the current state of the tests".

## Outstanding suspects (not yet fixed)

The audit surfaced these. None has the catastrophic blast-radius of
Q, but each is the same shape and worth fixing as we encounter them.

### Sanity-check stubs leak into the runtime cell registry

The orchestrator's test-gen sanity check calls
`codegen/assemble-stub-cell-source` and eval-loads it, which side-
effects `(cell/defcell :id …)` into mycelium's *live* cell registry.
After sanity check, the registry holds a passthrough stub for the
cell we're about to implement. If the agent then crashes/stagnates
before its `cell/defcell` overwrites the stub, any subsequent
workflow run in the same JVM uses the stub.

Mitigation candidates:
- Run sanity check in a child classloader / temp ns so it never
  touches the global registry.
- After sanity check, explicitly `set-cell-spec` back to whatever
  was there (or `clear-cell-spec` for a fresh cell-id).

### `(runs)` atom never expires

`sporulator.orchestrator/runs` accumulates state for every
orchestration, forever. Stale runs masked at least one debugging
session in this phase (we patched the wrong run because `(first
keys)` returned an old one). For long-running dev REPLs this is
a slow leak; for production it's a slow correctness hazard if a
new run is keyed by anything that collides.

Mitigation candidate: GC entries older than N hours on each
`start-orchestration!`.

### Eval errors omit context the agent could use

`eval-code` returns `:error (or (ex-message cause) (str (type
cause)))`. For most Clojure errors the message is informative
("Unable to resolve symbol: X in this context") but for some, the
class is more telling than the message. Including
`(.getSimpleName (class cause))` and the first 3 stack frames in
*user code* (filter out clojure.lang/java) would help on the
opaque-message cases.

Not a feedback-staleness bug per se — more a feedback-density bug.
Lower priority.

### Cell-id form inconsistency in events

`cell_status` events emit `:cell_id` as a bare string (good); some
older paths still emit it as a keyword (`:guestbook/x`). Frontend
has to handle both. Not stale, just inconsistent.

### Inconsistent feedback after `complete` is rejected

When the agent calls `complete` but tests aren't green, the harness
returns `(or (:output result) "")` followed by "Fix the failure and
run_tests again, or give_up." That `:output` field is the raw
clojure.test text dump — not the structured first-failure summary
that `run_tests` itself uses (M+N). The agent gets two formats
depending on which tool surface it's on.

Fix candidate: `complete` should reuse `format-run-tests-failure`
when it has structured failures available.

### Architect chat sees its own prior responses unconditionally

The architect chat session is persistent (graph-agent uses
`get-or-create-session`). When a manifest revision request comes in,
the LLM sees its entire prior context — including obsolete manifest
proposals it produced earlier in the conversation. There's no
freshness boundary; old proposals influence new ones.

This is correct for some workflows (continuous evolution of the
same workflow) and wrong for others (revisiting from a clean
slate). A "reset chat" / "branch chat" affordance would let users
choose.

## Where to look for more

The suspect surface for staleness bugs is: **anywhere the harness
holds long-lived state across LLM calls and re-feeds derived data
back to the model.** The pattern looks like:

1. Some atom / mutable system gets populated.
2. The model generates new text that should re-populate it.
3. The harness eagerly merges new with old instead of replacing.
4. The model receives a view of the merged state.
5. The model's reasoning applies to that view.

If step 3 is wrong, the model can't get unstuck because the view
contradicts what it wrote. Q, R, S are all step-3 violations on
namespaces. The runs atom is one on a Clojure atom. Sanity-check
registry leak is one on the cell registry. Architect chat may be
one on the LLM session.

Not every long-lived-state interaction is a bug — some are
intentional (architect chat persistence). The discriminator is
whether the model is being asked to reason about state it can
*verify* with the tools it has. If it can't verify, it's likely
seeing fiction.

## Ground rule for new tooling

Every tool that re-evaluates user-authored source into a long-lived
JVM must clear its destination namespace first, or it contributes
to a Q-shaped bug. New tools touching namespaces should either:

- Use a freshly-created namespace per call.
- Explicitly `remove-ns` before re-loading.
- Use `:reload-all` semantics (which `require` provides for already-
  loaded namespaces).

The choice depends on the surface, but the question always has to
be answered.
