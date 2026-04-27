# Resource doc convention (`:mycelium/doc` in `system.edn`)

For each resource a cell can `:requires`, the project's
`resources/system.edn` should ship a `:mycelium/doc` string that the
orchestrator lifts into the cell-implementor's prompt. This is
**how the model learns this project's conventions for the resources
it works with** — without the harness baking in library-specific
knowledge.

The Phase-4 hardcoded blocks (`jdbc-handler-shape-block`,
`jdbc-leaf-hints`, `jdbc-test-shape-block`) were a band-aid for
projects that lacked rich resource docs. They've been removed; the
correct channel is the `system.edn` doc.

## Shape

```edn
{:db/sqlite
 {:dbname "todos.sqlite"
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

The orchestrator's `extract-resource-docs` cross-references
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

## Cold-start: what if there's no doc?

The orchestrator falls back to `(no docstring; treat as the real
runtime resource)`. The agent has its training data, the brief, the
test contract, and the `:eval` REPL — it can usually figure out the
library. But for any non-trivial library / convention, the project
should ship a doc; it's the difference between "agent converges in
turn 1" and "agent burns 25 turns groping for the right shape" (Phase
4 validation, JDBC INSERT case).
