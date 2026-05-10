# Manifest Update / Iteration Design (2026-04-25, rev 2)

## Mental model

A workflow has two modes. Users move freely between them.

- **Design mode** — the user collaborates with the architect agent in chat
  to shape the graph. Each chat turn that emits a manifest produces a new
  manifest version. The user sees the latest graph and can keep revising.
- **Implementation mode** — the user approves the current graph. The
  system computes a diff between the current manifest and the last
  green-implemented snapshot, then runs only the affected cells through
  the test + cell-agent pipeline. When everything passes, the current
  manifest becomes the new green snapshot.

Initial design is just "design from an empty manifest, then implement the
whole thing." Update is "design from an existing manifest, then implement
only the diff." Same code path either way.

## Locked decisions

### Diff key — cell-id

Cell-ids are globally unique (encouraged: namespaced like
`:guestbook/validate-handle`). The diff is keyed by cell-id, not step
name. The manifest's step names (`:start`, `:validate-handle` keys under
`:cells`) are just convenient labels for edges and pipeline; renaming a
step is purely a graph rewrite, not a cell change. Two cells with the
same id in two manifests are the same cell.

### What counts as a cell change

The cell contract is `(:doc, :input-schema, :output-schema, :requires)`.

- `:schema-changed` (full regen — new test contract, new implementation)
  - `:input` differs
  - `:output` differs
  - `:requires` differs (handler destructures different keys)
- `:doc-changed` (impl regen, test contract may be reused)
  - `:doc` is the only thing that moved
- `:unchanged` (carry forward)
  - All four contract fields identical
- `:added`
  - Cell-id appears in new manifest, not in last-green snapshot
- `:removed`
  - Cell-id appears in last-green snapshot, not in new manifest

`:on-error` and edge / pipeline changes are *not* cell-level changes.
Cells are isolated programs; they don't know who calls them or where
they fan out. The compile-time workflow validator catches structural
mismatches between connected cells (input-of-B ⊆ output-of-A); if a
mismatch surfaces because the user just wired something incompatible
into the graph, the validator will flag the violation and the user
adjusts — either the graph or the cell contract.

### Carry forward

A cell carries forward verbatim if and only if its contract is unchanged.
Edges around it can move freely; the cell doesn't notice. The store keeps
the prior implementation as-is; the new orchestration run links to that
green version.

### Show the agent the old code (edit mode)

When a cell needs regeneration (`:schema-changed` or `:doc-changed`), the
implementor agent starts with the previous green source pre-loaded into
its workspace:

- `handler.clj` and `helpers.clj` start populated with the last green
  source.
- `test.clj` starts with the new test contract (regenerated for
  `:schema-changed`, reused for `:doc-changed`).
- The agent receives a "what changed" preamble in the initial prompt:

  ```
  This cell has an existing implementation that previously passed tests.
  The contract has changed: <diff summary>.

  Look at handler.clj and helpers.clj. Decide what still applies and what
  needs to be rewritten. Reuse what fits the new requirements; replace
  what doesn't. Run the new tests to verify.
  ```

The agent loop already supports this — initialising `:files` with
non-empty buffers is a one-line change in `init-session`.

### One unified entry point

`orchestrator/orchestrate!` becomes diff-aware. It always computes a
diff between the current manifest and the last green snapshot (which is
the empty manifest on a fresh project). The implementation loop then
acts on `:added` / `:schema-changed` / `:doc-changed` / `:removed`
classifications. There is no separate `update-from-manifest!`.

### In-flight runs — snapshot semantics

The orchestrator snapshots the manifest version at the moment a run
starts. Subsequent design-mode revisions create new manifest versions
that don't disturb the running implementation. When the user starts a
new orchestration, it picks up the latest manifest and diffs against the
last *green* snapshot (not the in-flight one, which may not finish).

In practice the user controls the loop — design mode and implementation
mode are explicit modes, so updating mid-run isn't a normal flow. But
the snapshot semantics are the safe default.

## Architecture

### `sporulator.manifest-diff`

```clojure
(diff old-manifest new-manifest)
;; =>
{:added          [<cell-id> ...]    ;; new in new-manifest
 :removed        [<cell-id> ...]    ;; gone in new-manifest
 :schema-changed [<cell-id> ...]    ;; same id, contract moved
 :doc-changed    [<cell-id> ...]    ;; only :doc differs
 :unchanged      [<cell-id> ...]}

(affected-cells diff)
;; =>
{:regen-tests-and-impl <ids>   ;; :added ∪ :schema-changed
 :regen-impl-only      <ids>   ;; :doc-changed
 :carry-over           <ids>   ;; :unchanged
 :delete               <ids>}  ;; :removed

(format-diff diff manifest)  ;; human-readable banner string
```

`old-manifest` defaults to the empty manifest if there's no prior green
snapshot — fresh runs are a special case of update.

### Store — green snapshots

Add a `green_snapshots` row per (manifest-id, version) when an
orchestration run completes successfully. The snapshot stores the
manifest body that was just successfully implemented, so future diffs
have a stable reference point. We don't hard-delete deprecated cells —
they stay in the cells table with a `deprecated_at` timestamp, so
history is queryable.

### Orchestrator — unified flow

```
orchestrate! takes the current manifest:
  1. Load last green snapshot (or empty if none).
  2. Compute diff.
  3. For :delete cells: deprecate in store, delete on disk.
  4. For :added: build brief, generate test contract,
     review-gate, run agent loop with empty workspace.
  5. For :schema-changed: build brief, generate fresh test contract,
     review-gate, run agent loop with previous green source pre-loaded.
  6. For :doc-changed: build brief, reuse last approved test contract,
     run agent loop with previous green source pre-loaded.
  7. For :unchanged: nothing — already green.
  8. Compile + validate the assembled workflow against the new manifest.
  9. On success, write a new green snapshot.
```

Per-cell events emitted by the orchestrator stay the same shape, with
one addition: `:carry-over` cells emit a single `cell_carry_over` event
so the UI can show them as already-done in the per-cell progress panel.

### Cell agent — edit mode

`agent-loop/init-session` accepts:
- `:initial-handler` — pre-populate handler.clj
- `:initial-helpers` — pre-populate helpers.clj
- `:change-summary`  — string describing what's different from the
  prior contract; injected into the initial prompt under "What changed."

If `:initial-handler` is empty/nil, behavior is identical to today
(fresh start). The orchestrator chooses pre-population on `:doc-changed`
and `:schema-changed`; fresh on `:added`.

### UI

The chat panel and graph canvas already support iterative manifest
revision — the architect agent has been emitting new manifest versions
all along. Two new affordances:

1. **Diff banner.** When the latest manifest diverges from the last
   green snapshot, show a non-blocking banner with a structured summary:

   ```
   Workflow has uncommitted changes since last implementation:
     + 3 new cells   (handle-error, message-error, store-error)
     ~ 3 cells will rebuild   (validate-handle, validate-message, persist-entry)
     - 0 deleted
     = 1 unchanged   (build-confirmation — keep existing impl)

     [Implement Changes]   [Continue Designing]
   ```

   Implementing fires the existing `start_orchestration` WS message
   (no new server-side message types).

2. **Cell-level diff preview.** Clicking on a flagged cell in the
   graph shows the schema delta side-by-side (old vs new). Optional
   for a first cut.

The graph canvas can color-code nodes by their diff status:
- new (green outline)
- changed (amber)
- carry-over (greyed)

This gives a glanceable view of "what will happen when I hit Implement."

## Validation scenario

The test for the whole feature:

1. Start with an empty project. Design + implement the guestbook
   linear pipeline (validate-handle → validate-message → persist-entry
   → build-confirmation). All cells go through `:added`. Green snapshot
   recorded.

2. In chat, ask: "Add error handling. Each validating cell should
   dispatch on :success / :failure, and errors should be formatted by
   dedicated error cells (handle-error, message-error, store-error)."

3. Architect agent emits a new manifest. Diff banner shows:
   - 3 added (the error cells)
   - 3 schema-changed (the validators + persist-entry now have
     dispatched outputs)
   - 1 unchanged (build-confirmation)

4. User clicks "Implement Changes."

5. Orchestrator:
   - Adds 3 fresh implementations for the error cells (empty workspace).
   - Re-runs the validators + persist-entry with prior green source
     pre-loaded ("here's what you used to do, here's what changed:
     output is now `{:success ... :failure ...}` — adapt").
   - build-confirmation is skipped entirely; carry-over event emitted.

6. Workflow compiles. New green snapshot recorded.

7. Run the workflow with a happy-path input — should succeed.
8. Run with an invalid handle — should flow through handle-error and
   produce a structured error response.

If steps 6–8 succeed, the design works.

## Implementation order

**Phase 1 — diff machinery (small, self-contained, no UX impact)**

- `sporulator.manifest-diff` with `diff`, `affected-cells`, `format-diff`.
- Tests covering: added / removed / schema-changed / doc-changed /
  unchanged, multi-cell mixed cases, edge-only changes, on-error-only
  changes, fresh-from-empty, identical-manifests.
- `store/save-green-snapshot!` and `store/get-latest-green-snapshot`.
- `store/deprecate-cell!`.

**Phase 2 — diff-aware orchestrator (no UX changes yet)**

- `orchestrate!` queries `get-latest-green-snapshot`, runs `diff`,
  branches per classification, writes a new green snapshot on success.
- `agent-loop/init-session` accepts `:initial-handler`,
  `:initial-helpers`, `:change-summary`.
- Orchestrator emits `cell_carry_over` events.
- End-to-end test: re-run guestbook orchestration with the
  current manifest — should report all unchanged, do nothing, snapshot.
  Then revise the manifest, run again — should regen only the diff.

**Phase 3 — UI**

- Compute the diff client-side (or via a `/api/diff` endpoint).
- Diff banner with summary + Implement button.
- Per-node coloring for added / changed / carry-over.
- Optional: per-cell schema delta on click.

## Open questions (smaller / for the build)

- **Should the green snapshot include cell handler hashes?** When the
  manifest is unchanged but a cell file got hand-edited on disk, do we
  trust the hand edit or regen? My instinct: the manifest is authoritative.
  Hand-edits to cell files are out of scope for the orchestrator — if
  the user wants to update a cell, they revise the manifest's `:doc` (or
  hand-edit and bump the doc to trigger a `:doc-changed`).

- **Empty `change-summary` for `:added` cells?** No prior code, no diff
  to show. Just hand the agent the brief, like today.

- **Where does `format-diff` live for the UI?** Compute it in Clojure
  (`/api/diff?manifest=<id>`) so the UI doesn't have to reinvent the
  classification logic. The format-diff string is a compact summary;
  the structured map is what drives the banner UI.
