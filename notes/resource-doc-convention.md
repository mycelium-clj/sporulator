# Resource doc convention (`:mycelium/doc` + `:mycelium/fixture` in `system.edn`)

For each resource a cell can `:requires`, the project's
`resources/system.edn` should ship two pieces of metadata that the
orchestrator lifts into the cell-implementor's harness:

1. `:mycelium/doc` — a string explaining the resource's purpose,
   shape, conventions, and don'ts (the project's library-specific
   knowledge for this resource).
2. `:mycelium/fixture` — a Clojure form (read as data) that, when
   evaluated, returns a freshly-initialized resource value. The
   harness pre-binds this under `(:k resources)` for the agent's
   `:eval` tool, so the agent can call its handler against a real
   instance without inventing setup boilerplate.

Together these are **how the model learns this project's conventions
for the resources it works with** — without the harness baking in
library-specific knowledge.

The Phase-4 hardcoded blocks (`jdbc-handler-shape-block`,
`jdbc-leaf-hints`, `jdbc-test-shape-block`) were a band-aid for
projects that lacked rich resource docs. They've been removed; the
correct channel is the `system.edn` doc + fixture.

## Shape

```edn
{:db/sqlite
 {:dbname "todos.sqlite"
  :mycelium/fixture
  (let [f  (java.io.File/createTempFile "fix-" ".sqlite")
        _  (.deleteOnExit f)
        ds (next.jdbc/get-datasource
             {:dbtype "sqlite" :dbname (.getAbsolutePath f)})]
    (next.jdbc/execute! ds
      ["CREATE TABLE todos (id INTEGER PRIMARY KEY AUTOINCREMENT,
                            title TEXT NOT NULL, ...)"])
    ds)
  :mycelium/doc
  "## Purpose
   <One paragraph: what role does this resource play in the app?>

   ## Shape
   <What is this thing technically? Library, type, where it comes from.>

   ## Conventions / patterns
   <How to use it correctly in this project. Code samples for the
   common operations.>

   ## Don'ts
   <The shapes that LOOK reasonable but don't work in this project.
   Saves the agent from rediscovering them.>
   "}

 :reitit.routes/pages
 {:db #ig/ref :db/sqlite}}
```

The orchestrator's `extract-resource-docs` and
`extract-resource-fixtures` both cross-reference
`:reitit.routes/pages` to map integrant keys back to the names
cells use in their `:requires`. So `:db/sqlite` becomes `:db` for
cells.

## What goes in each section

### Purpose
Orient the model: *what role does this resource play here?* Examples:
- `:db` — "the persistence layer for the TodoMVC app — adding,
  toggling, editing, deleting, listing all touch the `todos` table"
- `:http` — "the upstream API client used by the order-pricing
  cell to fetch live exchange rates from currency.example.com"
- `:cache` — "in-process LRU cache for catalog lookups; valid
  across requests within the same JVM"

The agent now knows *why* the resource exists, before it sees how
to call it. Cells become self-explanatory: a cell's `:requires :db`
line cross-references this paragraph automatically.

### Shape
What kind of thing is this? Library/type/source:
- "A `next.jdbc` DataSource over SQLite."
- "An `http-kit` client preconfigured with auth headers."
- "A `core.cache` LRUCache atom seeded from `cache.edn` at boot."

### Conventions / patterns
Concrete code patterns the cell will reach for. Show, don't just
tell. Include:
- The most common operations (insert, query, update, fetch,
  put, get, …)
- Argument shapes (`{:builder-fn rs/as-unqualified-maps}`,
  request maps with `:headers`, …)
- What return values look like, and how to interpret them.
- Error semantics (what exceptions, what they mean, how to
  surface them via the cell's failure transition).

This is the section that prevents JDBC-style fumbling — when the
canonical pattern is right there in the doc, the model writes it.

### Don'ts
The shapes that LOOK reasonable but don't work. Saves the agent
from rediscovering them empirically. Examples:
- `:return-keys true` for SQLite (doesn't reliably surface `:id`).
- Mocking the DataSource (tests pass real ones).
- Calling the upstream auth endpoint per-request (rate-limited).

These also save tokens — without them the agent burns turns
trying anti-patterns it'd reach for from training data.

## `:mycelium/fixture` — what goes here

A single Clojure form (read as data, evaluated per-`:eval`) that
returns a fresh, ready-to-use instance of this resource. The agent's
`:eval` tool wraps user code in:

```clojure
(def resources {:db <fixture-form-evaluated>, ...})
;; user code
```

so the agent calls

```clojure
((:handler (mycelium.cell/get-cell! :foo/bar)) resources {:k v})
```

against the same shape of resource the runtime will pass. Each `:eval`
gets a clean instance — state does not leak between evals.

Properties to aim for:
- **Self-contained.** The form must be evaluable on its own; the
  harness pre-requires every Clojure namespace mentioned by qualified
  symbols in the form (it auto-detects `next.jdbc/...` etc.).
- **Fresh state.** Each `:eval` creates a new instance — fresh schema,
  empty tables, fresh in-memory cache, etc. Don't share state across
  calls within a session.
- **Schema-applied.** For a DB resource, run `CREATE TABLE` (and any
  seed inserts) inside the fixture. The agent should not have to set
  up the table itself.
- **Realistic library/return shape.** Use the same library and config
  shape your runtime uses. The point is for the agent to verify what
  `next.jdbc/execute!` actually returns under your project's idioms.
- **Cheap.** It runs on every `:eval` call. An in-memory or temp-file
  sqlite is fine; a 30-second initial load is not.

For SQLite specifically, prefer a temp file over `:memory:` —
`next.jdbc/get-datasource` over `:memory:` opens a fresh in-memory DB
per connection, so a schema applied via one connection isn't visible
to the next.

The harness extracts these via `extract-resource-fixtures` and threads
them into the brief under `:resource-fixtures`. `agent-loop`'s `:eval`
handler then splices a `(def resources {...})` prelude with the
relevant subset (filtered by the cell's `:requires`).

## Why not generate this doc automatically?

Because the conventions are project-decisions, not universal facts.
SQLite + next.jdbc has at least three reasonable INSERT-returning-id
shapes (`:return-keys`, RETURNING + builder-fn, RETURNING + qualified
keys, last_insert_rowid()). Which one *this* project uses depends on
how its tests are written, what its other cells look like, what the
team prefers. The author of the project picks once and writes it
down; the orchestrator carries that choice into every cell-implementor
prompt.

The architect (graph-agent) could be extended to also produce the
`system.edn` alongside the manifest — that's a future direction.
For now, the project author writes it.

## Cold-start: what if there's no doc / fixture?

If `:mycelium/doc` is absent, the orchestrator falls back to
`(no docstring; treat as the real runtime resource)` in the prompt.

If `:mycelium/fixture` is absent, the agent's `:eval` tool falls back
to its prior behavior — no `resources` is pre-bound, and the agent has
to construct its own resource (calling `next.jdbc/get-datasource`
inside `:eval` etc.). For trivial pure cells, this is fine; for any
cell with `:requires`, ship a fixture — the agent's first `:eval` is
typically `(:db resources)` to confirm the shape, and skipping that
shape-discovery turn matters.

The agent has its training data, the brief, the test contract, and the
`:eval` REPL — it can usually figure out the library. But for any
non-trivial library / convention, the project should ship a doc + a
fixture; it's the difference between "agent converges in turn 1" and
"agent burns 25 turns groping for the right shape" (Phase 4
validation, JDBC INSERT case).
