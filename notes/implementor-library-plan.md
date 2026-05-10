# Plan: factor sporulator's agent loop into a standalone library

## Why

What sporulator built in Phase 4 is an **REPL-driven, TDD-shaped,
tool-using LLM agent loop** with hard-won feedback hygiene (Q-shape
staleness fixes, structured failure surfacing, plateau detection,
sanity-check pre-load). Most of that machinery has nothing to do with
Mycelium.

Today the implementor and Mycelium-specific concerns are interleaved:
test contracts derived from cell briefs, codegen producing
`(cell/defcell …)`, the workspace using "handler.clj / helpers.clj /
test.clj" because that's how a Mycelium cell decomposes. None of those
are *necessary* — they're sensible defaults that make sense when the
"task" being implemented is a Mycelium cell.

A second project (or a future self) wanting to use the same loop for
something else — a generic Clojure function, a parsing helper, a
schema migration — would have to copy and rip out Mycelium. Better to
extract it, with clean injection points, and let sporulator be the
first consumer.

The motivation is *not* "make sporulator smaller" — though it does.
The motivation is *the agent loop is a real reusable artifact*. The
hard work in Phase 4 was discovering the right design (transparent
feedback, no orchestration overrides, stale-state hygiene, REPL freedom).
Capturing that as a library prevents re-discovery.

## Naming candidates

Working name in this doc: **`subgoal`**. Rationale: the library's
purpose is to take a single well-scoped subgoal and drive it to a
green test suite. Implies bounded scope, doesn't claim to do more.

Alternatives if `subgoal` doesn't land:
- `tdd-agent` — accurate, slightly generic
- `clj-implementor` — descriptive, Clojure-bound
- `forge` — short, evocative, available
- `loom` — already taken (Java virtual threads)

Pick at extraction time.

## Boundary: what gets pulled out, what stays

### Extract to library (`subgoal`)

Already mostly mycelium-agnostic; would need light decoupling.

| Sporulator file | Lines | Mycelium refs | Notes |
|-----------------|-------|---------------|-------|
| `agent_loop.clj` | 985 | 3 | Almost entirely generic. The mycelium refs are in the system prompt and in `assemble-and-eval`'s call to `codegen/assemble-cell-source`. Both extract via injection. |
| `eval.clj` | 298 | 9 | Generic eval + clojure.test runner. Mycelium refs are in `instantiate-cell` and `verify-cell-contract` — those are the only mycelium-specific ones; pull them out into a sporulator adapter. |
| `tool_registry.clj` | (small) | 0 | Already clean. JSON schemas for the tools the agent calls. |
| `mcp_bridge.clj` | (small) | 0 | The "MCP-shape context" the agent loop hands to tool implementations. Already generic. |
| `extract.clj` | 241 | some | Parse forms / find-defcell — split into generic (read-all-forms-lenient, fn-body extraction) + mycelium-specific (defcell-form? lookup). |
| `code_graph.clj` | 322 | some | Symbol-graph queries over the agent's helpers + handler. Generic in spirit; depends on extract.clj's parsing. |

What this gives a consumer:

```clj
(require '[subgoal.core :as sg])

(sg/run-task!
  {:client    llm-client
   :task      {:description "Compute the SHA-256 of a string."
               :test-code   "(deftest sha256-test ...)"}
   :workspace {:files {"impl.clj" "" "test.clj" ""}
               :ns    "user.tasks.sha256"}
   :budget    {:turns 25}
   :hooks     {:on-event (fn [ev] ...)
               :on-chunk (fn [c] ...)}})
```

Returns:

```clj
{:status :ok        ;; or :stagnated :gave_up :error
 :code   "<assembled source>"
 :files  {"impl.clj" "..." "test.clj" "..."}
 :session <llm-session>}
```

### Stay in sporulator

The Mycelium-specific orchestration layer.

| File | Reason |
|------|--------|
| `orchestrator.clj` | Manifest diff, cell briefs, dispatched-output detection, cell schema parsing, run state, approval flow. All mycelium concepts. |
| `graph_agent.clj` | Architect chat — produces manifests, not generic. |
| `manifest_validate.clj` | Manifest schema validation. |
| `manifest_diff.clj` | Cell-level diff between manifests. |
| `prompts.clj` | Mycelium-specific prompt fragments (cell-prompt, math-precision, etc.). |
| `store.clj` | Sporulator's persistence layer (cells, manifests, runs, snapshots). |
| `server.clj` | WebSocket UI for sporulator. |
| `decompose.clj` | "Break a high-level requirement into mycelium cells." Mycelium concept. |
| `cell_agent.clj` | Sporulator-specific glue. |
| `source_gen.clj` | Writes mycelium cell files / manifests to a project on disk. |
| `feedback.clj`, `tools.clj` | Helpers that may split — feedback-loop is generic, tools.clj is mostly mycelium. |
| `codegen.clj` (174 lines) | **Hybrid.** `assemble-cell-source` produces `(cell/defcell …)` — that's mycelium-shaped. `assemble-stub-cell-source`, `assemble-test-source`, `assemble-manifest` likewise. Split: generic primitives (form rendering, ns wrapping) move to library; mycelium templates stay in sporulator. |

## Extension points the library exposes

The Phase 4 work surfaced the right set of seams. Each of these is an
injection point in the library API:

### `:assemble`

The library doesn't know how the agent's editable files become a
loadable artifact. Default: concatenate `impl.clj` + `test.clj` and
eval. Mycelium adapter: assemble-cell-source (handler + helpers +
defcell wrapper).

```clj
:hooks {:assemble
        (fn [files task]
          {:source <assembled-string>
           :load-ns <ns-symbol>     ;; for ns-clearing in Q/R/S
           :test-ns <ns-symbol>})}
```

### `:run-tests`

How "did this attempt pass?" is measured. Default: clojure.test/run-tests
on test-ns, using sporulator's structured-failure reporter. User can
swap (matcho, midje, custom).

```clj
:hooks {:run-tests
        (fn [test-source]
          {:passed? bool
           :summary {:test n :pass n :fail n :error n}
           :failures [{:kind :fail :test "..." :expected ... :actual ...}]
           :output "..."})}
```

### `:system-prompt`

Default: the REPL-freedom + reframe-when-stuck system prompt from K.
User can append (e.g. mycelium adapter appends "Calling convention",
"Dispatched output", "JDBC handler patterns").

```clj
:hooks {:system-prompt-suffix
        (fn [task] "## Calling convention\n...")}
```

### `:tools`

The default tool set is read/write/edit/list/eval/run_tests/lint/
complete/give_up plus the call-graph tools. User can add tools via the
tool registry, with schemas.

```clj
:hooks {:extra-tools
        [{:name "fetch_doc" :schema {...}
          :handler (fn [state args] ...)}]}
```

### `:on-event`

Stream events from the loop (started, file_written, tests_passed,
stagnated, etc). Sporulator wires these to its WebSocket and store.

## Things to design carefully

### Namespace clearing as a first-class concept

Q/R/S are the most important fix Phase 4 produced. The library should
clear the destination namespace before every assemble-and-eval, every
eval-with-cell-scope, every sanity-check eval. The `:assemble` hook
returns `:load-ns` and `:test-ns` precisely so the library can clear
them — making this the *default* behaviour, not an opt-in.

The library should document this as the **ground rule** for any tool
extension: if you load source into a long-lived JVM, you must clear
the destination first. Q-shape bugs are the dominant failure mode for
this kind of agent and should be designed away, not patched in
post-hoc.

### Workspace shape

The fixed `handler.clj` / `helpers.clj` / `test.clj` triplet is a
mycelium-flavoured choice (helpers separate from handler because cells
have a single-fn handler). For other domains the natural shape might
be `impl.clj` + `test.clj`, or `impl/foo.clj` + `impl/bar.clj` +
`test.clj`.

The library should accept `:files` as a map of `path → initial
content` and treat them all as editable. The number and naming is a
consumer concern. The sanity that "no file may have its own (ns …)"
(Fix D) generalises to "the assemble step decides namespacing; agent
files are fragments."

### Test-gen

Phase 4's test-gen sanity check (Fix O) is in sporulator's
orchestrator, not the agent loop. That's correct — generating the
test contract from a brief is a domain concern. The library accepts
test code and runs it. If a consumer wants its own test-gen, that's
its layer.

### Eval scope

Fix L (eval pre-loads cell source so `(my-helper x)` works) is core
to the REPL-freedom design. The library default eval should call
`:assemble` to build a current-state source string, eval it (with
ns-clearing), then evaluate the agent's expression in `:load-ns`.

For consumers that don't have an "assemble" step (e.g. the workspace
is one big single-file impl), `:assemble` becomes the identity — load
the file as-is, evaluate user code in its namespace.

### Test runner abstraction

clojure.test isn't universal but it's the strong default. The library
should accept a `:run-tests` hook that returns the structured-failure
shape M+N established. Adapters for matcho, expectations, etc. map
their reporter into that shape.

## Migration phasing

The carve-out doesn't have to be one PR. Phasing reduces risk:

### Phase A — extract eval + codegen primitives (1–2 days)

- Pull `eval.clj` (sans `instantiate-cell` / `verify-cell-contract`)
  into a new `subgoal.eval` namespace. Generic eval-code + run-cell-tests.
- Pull `codegen.clj` primitives (form rendering, file wrapping) into
  `subgoal.codegen`. Leave `assemble-cell-source` etc. in sporulator
  with a thin call into the library.
- Pull `tool_registry.clj` into `subgoal.tool-registry`. Mostly intact.

This is the smallest cut: lots of generic helpers, no API design
debate, no consumer impact.

### Phase B — extract agent_loop with injection points (3–5 days)

- Move `agent_loop.clj` into `subgoal.agent-loop`.
- Replace direct calls to `codegen/assemble-cell-source` with calls
  to a hook supplied via the run-task options.
- Replace the system prompt with `default-system-prompt` + an
  `:append-to-system-prompt` hook.
- Sporulator's orchestrator now calls
  `(subgoal.agent-loop/run! {... hooks})`. Mycelium specifics
  (dispatched-output guidance, JDBC handler hints, calling convention)
  go in via `:append-to-system-prompt`. The cell assemble call goes in
  via `:assemble`.

### Phase C — sporulator depends on the library (1 day)

- Add subgoal as a `deps.edn` dep (`:git/url` initially).
- Delete the original sporulator copies.
- Sporulator-side reduces to:
  - `subgoal-mycelium` — cell-shaped assemble hook, mycelium prompt
    appendices, store integration.
  - Existing orchestrator + manifest + graph-agent + server.

### Phase D — publish + iterate (open-ended)

- README with the minimum-viable example.
- Migrate a non-mycelium project onto subgoal as a second consumer.
- Use that to discover what's still over-fitted.

## Open design questions

1. **JVM hosting model.** Sporulator runs the agent loop in the same
   JVM the user's REPL is running. Convenient for development;
   conflates state with the user's session. Should the library default
   to a sandboxed classloader so namespaces never leak across runs?
   (Q-shape bugs become impossible if state is per-run.)

2. **Mycelium-shape extension points becoming over-fitted.** The
   dispatched-output guidance, JDBC handler hints, and helpers.clj
   constraints (Fix D) feel domain-specific to "cells with `:requires`
   resources and dispatched outputs." Should those be modelled as a
   *plugin* (`subgoal-mycelium`) that the library knows nothing about,
   or as opt-in modules the library ships? The minimal-tools principle
   from the article patterns suggests the former.

3. **Test-gen.** Should the library *include* a test-gen capability
   (a thin LLM call that produces test code from a description), or
   require the consumer to supply test-code already? Sporulator does
   the former because the orchestrator drives both phases; for a
   library consumer, the latter is more honest.

4. **Persistence.** Sporulator persists cells, manifests, runs.
   Should the library persist anything (run history, attempts) or
   stay stateless? Stateless is simpler; consumers add persistence on
   top.

5. **MCP exposure.** The user's framing mentions "include access to
   MCP-like tooling we implemented." The current implementation
   exposes tools via JSON-schema in `tool_registry.clj`. A library
   could publish those schemas as an actual MCP server, so other
   agents (Claude Code, etc.) can use the implementor as a
   sub-agent. This is a future direction; not blocking on the carve-out.

## Lessons captured (from `notes/feedback-staleness-audit.md`)

The library should be designed against the failure modes Phase 4
identified, not just the happy path.

- **Stale state masquerading as truth** — Q-shape bugs are the
  dominant failure for any harness that re-evaluates user-authored
  source into a long-lived JVM. Default to clearing the destination.
- **Constraint-heavy orchestration backfires** — G/H/J taught us
  that. Library should default to no warnings, no blocks, no
  orchestration overrides.
- **Feedback fidelity beats turn budget** — when the model sees
  what's actually wrong (M+N) and has the affordance to verify hypotheses
  (L's eval pre-load), small turn budgets suffice.
- **Format consistency matters** — T showed the same failure
  presented two different ways made the agent triage twice.

These should land as principles in the library README, not just
notes in sporulator.

## Out of scope

- Refactoring the architect (graph_agent.clj). That's mycelium-flavoured.
- Refactoring server.clj — sporulator-specific UI.
- Building a CLI for the library — secondary; consumers can build
  their own.
- Test runner adapters beyond clojure.test — consumer can add when
  needed.
