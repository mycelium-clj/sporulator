# Sporulator

TDD-driven code generation tool for [Mycelium](https://github.com/mycelium-clj/mycelium) workflows. Sporulator designs workflow graphs, generates cell implementations, and verifies them with automated tests — all orchestrated through LLM agents with human review gates.

## How It Works

Sporulator follows a structured TDD workflow:

1. **Design** — The graph agent designs a workflow manifest (cells, edges, schemas) via LLM conversation
2. **Test** — Test contracts are generated for each cell, self-reviewed, and presented for human approval
3. **Implement** — Cell agents implement each cell against locked tests, with an eval feedback loop that auto-fixes errors
4. **Verify** — Implementations are evaluated in-process, tests are run, and results are tracked
5. **Write** — Source files are written to disk as cells pass their tests

The entire process runs in the same JVM as the generated code — no nREPL bridge needed. Code is evaluated directly via `load-string`, giving immediate feedback on compilation errors, test failures, and schema violations.

## Architecture

```
sporulator/
├── store.clj          # SQLite persistence (cells, manifests, runs, sessions)
├── llm.clj            # OpenAI-compatible LLM client with SSE streaming
├── eval.clj           # In-process code eval with timeout, test runner, lint
├── extract.clj        # Code extraction from LLM responses
├── codegen.clj        # Source code assembly (cells, tests, manifests)
├── prompts.clj        # System prompts and graduated fix prompt construction
├── decompose.clj      # Workflow decomposition and graph context
├── graph_agent.clj    # Manifest design via LLM with validation feedback
├── cell_agent.clj     # Cell implementation via LLM with eval feedback
├── orchestrator.clj   # Full TDD orchestration loop with review gates
├── source_gen.clj     # Write .clj source files to project on disk
├── server.clj         # HTTP + WebSocket server for sporulator-ui
└── tools.clj          # UTCP tool definitions for Claude Code integration
```

## Usage

### As a Library

Add sporulator to your project's `deps.edn`:

```clojure
io.github.mycelium-clj/sporulator {:local/root "../sporulator"}
;; or from git:
;; io.github.mycelium-clj/sporulator {:git/url "..." :git/sha "..."}
```

Start the server from your REPL:

```clojure
(require '[sporulator.store :as store]
         '[sporulator.server :as server])

(def db (store/open ".sporulator/sporulator.db"))

(def srv (server/start!
           {:port         8420
            :store        db
            :project-path (System/getProperty "user.dir")
            :graph-llm    {:api-key "your-key"}   ;; or set GRAPH_API_KEY env var
            :cell-llm     {:api-key "your-key"}})) ;; or set CELL_API_KEY env var

;; Stop when done
(server/stop! srv)
```

When the orchestrator implements cells, source files are automatically written
to `{project-path}/src/` using the configured base namespace.

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `GRAPH_API_KEY` | — | API key for the graph agent LLM (required for graph design) |
| `GRAPH_BASE_URL` | `https://api.deepseek.com` | Base URL for graph agent API |
| `GRAPH_MODEL` | `deepseek-chat` | Model name for graph agent |
| `CELL_API_KEY` | — | API key for the cell agent LLM (required for implementation) |
| `CELL_BASE_URL` | `https://api.deepseek.com` | Base URL for cell agent API |
| `CELL_MODEL` | `deepseek-chat` | Model name for cell agent |

## REST API

The server exposes these endpoints at `http://localhost:8420`:

### Cells
- `GET /api/cells` — list all cells with latest version
- `GET /api/cell?id=:id` — get cell by ID
- `POST /api/cell` — save a cell (creates new version)
- `GET /api/cell/history?id=:id` — version history
- `GET /api/cell/tests?id=:id` — test results

### Manifests
- `GET /api/manifests` — list all manifests
- `GET /api/manifest?id=:id` — get manifest by ID
- `POST /api/manifest` — save a manifest
- `POST /api/manifest/export` — export manifest to disk

### REPL
- `GET /api/repl/status` — connection status (always connected)
- `POST /api/repl/eval` — evaluate Clojure code in-process
- `POST /api/repl/instantiate` — load a cell from store into the JVM

### Sessions
- `GET /api/sessions` — list chat sessions
- `GET /api/session?id=:id` — get session with message history
- `DELETE /api/session?id=:id` — delete session
- `POST /api/session/clear?id=:id` — clear messages

### Source Generation
- `POST /api/source/generate` — generate .clj files from stored cells/manifests

### Tools
- `GET /api/tools/manifest` — UTCP tool manifest for Claude Code registration

## WebSocket API

Connect to `ws://localhost:8420/ws` for streaming operations:

### Client → Server
| Message Type | Description |
|-------------|-------------|
| `graph_chat` | Design/modify workflow manifests via LLM |
| `cell_implement` | Implement a cell with eval feedback loop |
| `cell_iterate` | Send feedback to iterate on a cell |
| `orchestrate` | Run full TDD orchestration |
| `test_review` | Deliver test review responses |
| `graph_review` | Deliver graph review responses |
| `impl_review` | Deliver implementation review responses |

### Server → Client
| Message Type | Description |
|-------------|-------------|
| `stream_chunk` | LLM token fragment |
| `stream_end` | Streaming complete with full content |
| `stream_error` | LLM or processing error |
| `feedback_event` | Eval/validation progress event |
| `orchestrator_event` | Orchestration phase/status update |
| `orchestrator_complete` | Orchestration finished successfully |
| `orchestrator_error` | Orchestration failed |
| `test_review_contracts` | Test contracts awaiting user review |
| `graph_review_data` | Graph structure awaiting user review |
| `impl_review_data` | Implementations awaiting user review |

## Claude Code Integration

Sporulator can register its API as native Claude Code tools via code-mode:

1. Start the sporulator server
2. Register tools:
   ```
   mcp__code-mode__register_manual with:
   {"name": "sporulator",
    "call_template_type": "http",
    "http_method": "GET",
    "url": "http://localhost:8420/api/tools/manifest",
    "content_type": "application/json"}
   ```
3. Available tools: `sporulator.clj_eval`, `sporulator.clj_list_cells`,
   `sporulator.clj_save_cell`, `sporulator.clj_list_manifests`, etc.

## Testing

```bash
clj -M:test
```

98 tests, 422 assertions covering store, eval, graph agent, cell agent, orchestrator, and source generation.

## License

Copyright Yogthos
