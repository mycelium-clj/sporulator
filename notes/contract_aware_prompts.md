# Contract-aware prompts: structured fix plan (2026-04-26)

## Context

The diff-aware orchestrator now correctly skips carry-over cells, regenerates
schema-changed cells in edit mode, and saves green snapshots. But the live
validation against the guestbook + error-branches scenario surfaced quality
problems with what the test-gen and implementor LLMs actually produce. None
of these are architectural — they're prompt issues that look very fixable.

The unifying theme: **the cell's contract (its `:input`/`:output` schema and
`:requires`) carries enough information to drive the prompt deterministically
— but right now we don't surface that signal.**

## Issues observed in the live run

Three regenerated cells, three different interpretations of the same kind of
schema change:

| Cell | What we got | What we wanted |
|---|---|---|
| `validate-handle` (via agent loop) | Returns nested `{:success {:validated-handle h}}` — and tests check the same nested shape | Flat `{:validated-handle h}` on success / `{:error "..."}` on failure |
| `validate-message` (via reject-impl) | Schema declaration has `:failure` at the cell-opts level, not under `:output`; handler returns flat shapes correctly | Schema `:output` is the dispatched-output map; handler returns flat |
| `persist-entry` (via reject-impl) | Clean `[:or [:map ...] [:map ...]]` schema, flat returns, but `(ns myapp.cells.persist-entry ...)` doesn't match the file path `app/cells/persist_entry.clj` | Same logic, but `(ns app.cells.persist-entry ...)` |

Causes by issue:

- **A. Test-contract generator doesn't teach the dispatched-output convention.**
  When the brief's `:output` is `{:success {...} :failure {...}}`, the test-gen
  LLM has no idea whether tests should check flat or nested return shapes. It
  guesses inconsistently across cells with identical schema shape.

- **B. Implementor prompt doesn't teach it either.** The agent's system prompt
  has a "Calling convention" block for the data argument, but doesn't cover
  what the *return* shape should look like when output is dispatched. The
  agent matches whatever the test contract happens to expect, which we already
  established is inconsistent.

- **C. `reject-impl!` uses the legacy direct-prompt path, not the agent loop.**
  When the user rejects an implementation (or the agent stagnates and the
  user retries), the recovery flow calls `llm/session-send-stream` for a
  one-shot "corrected source including (ns ...) and (cell/defcell ...)"
  response. That bypasses the file-shaped tooling and the contract-driven
  schema generation.

- **D. The legacy reject-impl path lets the LLM emit its own `(ns ...)`
  declaration**, which can drift from the file's actual path on disk
  (`myapp.cells.persist-entry` vs `app.cells.persist-entry`). Loading the
  file at runtime fails.

- **E. The legacy reject-impl path also emits its own schema declaration**
  inside `(cell/defcell ...)`, which is why we see different schema shapes
  across cells. The agent loop's path uses `codegen/assemble-cell-source`
  with the brief's `:schema` — same source of truth every time, no drift.

Plus two smaller items:

- **F. Stale `runs` atom entries.** Abandoned runs sit in memory forever.
- **G. Test-contract LLM uses `cell/get-cell!` to look up the registered
  handler.** That works because the assembled cell loads via `eval-code`, but
  it's coupling that didn't need to be. A simpler test fixture would let
  tests exercise `handler` directly with no mycelium runtime in scope.

## Guiding principle

For each cell, the brief is the authoritative description of what the cell
should do. Both the test-gen LLM and the implementor LLM should derive their
expectations from it deterministically, with as little room for invention as
possible. Anywhere a prompt currently leaves shape decisions to the LLM, we
add explicit guidance keyed off the contract.

## Phase 1 — Test-contract generator becomes contract-aware

**Change.** Update `orchestrator/build-test-prompt` to detect dispatched
outputs and inject convention guidance into the prompt:

- If `:output` is a dispatched-output map (`{:success {...} :failure {...}}`,
  or any map whose keys are namespaced or look like dispatch labels),
  add an explicit block:

  > This cell has a dispatched output schema. Mycelium dispatches based on
  > which keys appear in the handler's flat return map. Your tests should
  > check flat shapes — `{:validated-handle h}` on the success path,
  > `{:error "..."}` on the failure path — never nested under `:success` or
  > `:failure`.

- If `:output` is a flat map / Malli schema, the prompt stays as-is.

- Surface a concrete worked example with a tiny dispatched-output cell so
  the LLM has a template.

**Validation.**
1. Manually call `generate-test-contract` for a synthetic cell whose
   `:output` is `{:success {:n :int} :failure {:reason :string}}`.
2. Inspect the generated test body; assert it asserts on flat
   `{:n ...}` / `{:reason "..."}` shapes, not nested.
3. Repeat for a flat-output cell — confirm no behavior regression.
4. Re-trigger the validate-handle cell's test-gen in the running project.
   Verify the new test contract checks the flat dispatched shape.

**Done criteria.** A schema-changed cell with dispatched output produces a
test contract that the implementor can satisfy with a flat return map, with
no LLM ambiguity.

## Phase 2 — Implementor prompt becomes contract-aware

**Change.** Update `agent_loop/system-prompt` (and possibly
`render-initial-prompt`) so the calling-convention block also covers return
shapes:

- For dispatched outputs: "Return ONE of the flat shapes named in the
  dispatched output schema. Do not wrap under `:success` or `:failure`. Pick
  the shape based on whether your validation/work succeeded."

- For flat outputs: existing behavior.

The check happens at `init-session` — we already have `:schema-parsed`
available, so we can branch the prompt content based on whether
`(:output schema-parsed)` is dispatched or flat.

**Validation.**
1. Use `notes/live_check.clj` driver against a small synthetic dispatched
   cell — assert the agent returns the flat shape, tests pass first try,
   no nested wrapping.
2. Re-run validate-handle in the running project (after Phase 1 lands so
   tests are right): agent should now produce the right return shape.

**Done criteria.** Agent loop never wraps results under `:success`/`:failure`
when the schema is dispatched, regardless of how the test contract is shaped.

## Phase 3 — Migrate `reject-impl!` to the agent loop

**Change.** Replace the legacy direct-prompt body in `reject-impl!` with a
call into `implement-from-contract` carrying:

- `:prev-source`     — current `:impl-source` (or the latest store cell)
- `:change-summary`  — synthesised from the user's feedback string, e.g.
  `"User feedback on the previous implementation:\n  ..."`

This gives the recovery path the same edit-mode behaviour as the diff-aware
schema-change path: agent sees prior code, gets a "what's wrong / what to
revise" preamble, and uses `read_file` / `edit_file` to iterate.

The `(ns ...)` problem (issue D) goes away as a side effect — the agent loop
never lets the LLM emit its own ns declaration; `codegen/assemble-cell-source`
generates a clean one from `:cell-ns`.

The schema-declaration drift (issue E) also goes away — the agent loop's
codegen always uses the brief's `:schema-parsed`.

**Validation.**
1. After Phase 2, re-trigger the broken `validate-handle` cell with a
   `Reject Impl + feedback` flow. The agent should iterate via
   `read_file`/`edit_file` against the prior source, fix the nesting,
   converge in a few turns.
2. Inspect the saved cell on disk — the `(ns ...)` declaration should
   exactly match the file path, and the schema declaration should match the
   brief.
3. Make sure the existing UI flow (`reject_impl` WS message → `reject-impl!`)
   still works end-to-end.

**Done criteria.** Both the success-path (`approve-tests!`) and the
recovery-path (`reject-impl!`) go through one code path
(`implement-from-contract`), so cells from either path have consistent
namespace declarations and consistent schema declarations.

## Phase 4 — End-to-end re-validation against the project

**Change.** No code change — this is the proof step.

**Setup.**
1. Hand-fix the three currently-broken cells on disk so the workflow is in
   a known-good state. Confirm by loading them in the REPL + invoking the
   workflow with a happy-path input and an error-path input.
2. Save a clean green snapshot for guestbook v2.

**Validation.**
1. In chat, ask the architect for one more revision — say, "rate-limit the
   handle to 3 submissions per minute" (adds a new cell, makes
   validate-handle's contract slightly different).
2. Diff banner should show: `+ 1 added (rate-limit)` and possibly
   `~ 1 doc changed (validate-handle)` — depending on what the architect
   does.
3. Click Implement Changes. Watch:
   - Carry-over cells stay `:done` immediately.
   - Schema/doc-changed cell goes through edit-mode with prior source
     pre-loaded.
   - New cell goes through fresh.
4. Both LLM-driven cells should converge first try (Phases 1 & 2 ensured
   contract guidance).
5. Approve all impls. Snapshot bumps. Diff returns to empty.
6. Run the workflow with a real `(handler {} {:handle "alice" :message
   "hi"})` and verify the rate-limit dispatch works.

**Done criteria.** A workflow revision involving a dispatched-output change
goes from chat to running cells with no manual reject-impl loops, no hand
fixes, no namespace mismatches.

## Phase 5 — Cleanup follow-ups

Order doesn't matter; small items.

- **F. `runs` atom GC.** When status moves to "completed" or "abandoned",
  expire the entry from `(deref runs)` after some grace period (e.g. on
  the next orchestration start, drop runs older than N hours).
- **G. Independent test fixture.** Have `assemble-test-source` emit a
  `(def handler ...)` inline rather than relying on `cell/get-cell!` to
  look up the registered cell at load time. Smaller blast radius for
  registration races.
- **UI polish.** Per-cell schema delta on click; clear stale
  `cellProgress` from localStorage when a manifest changes meaningfully.
- **Tests.** A unit test in `manifest-diff-test` covering dispatched-output
  shapes (currently the fixture uses `{:input ... :output ...}` flats).

## Sequencing

Strict order: 1 → 2 → 3 → 4. Phase 5 items can land in parallel with any of
the above; they don't unblock anything else.

We test each phase against the *running* project before starting the next —
no batched commits until the validation step passes. That way each fix is
provably correct in isolation before composing.
