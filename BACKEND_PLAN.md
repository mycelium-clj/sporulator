# Sporulator Backend Plan

Go backend that orchestrates LLM agents and manages the cell/manifest lifecycle for Mycelium workflows.

## Architecture

```
┌─────────────────────────────────────────┐
│            Sporulator Backend           │
│                                         │
│  HTTP/WebSocket API ◄──── Frontend      │
│         │                               │
│  ┌──────┼──────────┬──────────┐         │
│  ▼      ▼          ▼          ▼         │
│ SQLite  Agent      nREPL      LLM       │
│ Store   Manager    Client     Client    │
└─────────────────────────────────────────┘
```

## Phases

### Phase 1: nREPL Client (`pkg/repl`)
Port of node-nrepl-client to Go. Client-only (no server management).

- TCP connection to a running nREPL server
- Bencode encode/decode over the wire
- Chunked message reassembly (partial TCP reads)
- Message routing by UUID (request → response matching via channels)
- nREPL operations: eval, clone, close, describe, loadFile, lsSessions, interrupt, stdin
- Session management (track active sessions)
- Integration test against a real nREPL server

### Phase 2: SQLite Store (`pkg/store`)
Cell and manifest persistence with immutable versioning.

Tables:
- `cells` — id, version, schema (EDN), handler (Clojure source), doc, requires (EDN), created_at, created_by
- `manifests` — id, version, body (full manifest EDN), created_at, created_by
- `manifest_cells` — links manifest versions to cell versions
- `test_results` — cell_id, cell_version, input (EDN), expected (EDN), actual (EDN), passed, run_at

Operations:
- SaveCell / GetCell / GetLatestCell / ListCells / GetCellHistory
- SaveManifest / GetManifest / GetLatestManifest
- PinCellVersion (link a manifest to specific cell versions)
- SaveTestResult / GetTestResults

### Phase 3: LLM Client (`pkg/llm`)
Unified client for OpenAI-compatible APIs (Claude, DeepSeek, etc.).

- Single HTTP client wrapper — both Claude and DeepSeek use OpenAI-compatible chat completions
- Streaming support (SSE) for real-time token delivery to frontend
- Conversation session management (message history)
- System prompt loading from templates
- Two prompt templates:
  - Graph agent: manifest semantics, workflow structure, requirements gathering
  - Cell agent: cell implementation guide (the cell-instructions.md content)

### Phase 4: Agent Manager (`pkg/agents`)
Orchestrates graph agent and cell agents.

- GraphAgent: long-lived session, manifest-aware, produces/updates manifest EDN
  - Understands workflow structure: cells, edges, dispatches, joins, pipeline
  - Can query the store: "what cells exist?", "what cells produce key X?"
  - Validates manifest structure before saving
- CellAgent: short-lived, spawned per-cell, produces defcell implementations
  - Receives: cell brief (id, schema, doc, requires)
  - Returns: Clojure source code
  - Can iterate: receives test failures, schema errors, produces fixed code
- Agent lifecycle: create, stream responses, iterate, complete
- Parallel cell agent execution (multiple cells implemented concurrently)

### Phase 5: REPL Bridge (`pkg/bridge`)
Connects agent output to a live Clojure system via nREPL.

- Instantiate a cell: eval the defcell form in the connected REPL
- Run cell tests: eval test expressions, capture results
- Schema validation: eval Malli validation against cell output
- Workflow compilation: eval compile-workflow, capture errors
- Hot-reload: re-instantiate a single cell without restarting

### Phase 6: HTTP/WebSocket API (`pkg/api`)
Serves the frontend and provides real-time updates.

REST endpoints:
- `GET /api/cells` — list cells with latest version info
- `GET /api/cells/:id` — cell detail with version history
- `POST /api/cells/:id` — save new cell version
- `GET /api/manifests` — list manifests
- `GET /api/manifests/:id` — manifest detail
- `POST /api/manifests/:id` — save new manifest version
- `POST /api/repl/eval` — eval arbitrary Clojure in connected REPL
- `POST /api/repl/connect` — connect to nREPL server
- `GET /api/repl/status` — connection status

WebSocket:
- `ws://host/ws` — bidirectional channel for:
  - Agent message streaming (token-by-token from LLM)
  - Chat messages (user → graph agent, user → cell agent)
  - Test result notifications
  - REPL output streaming
  - Cell/manifest update notifications

## Dependencies

- `github.com/jackpal/bencode-go` or `github.com/zeebo/bencode` — bencode codec
- `github.com/google/uuid` — message IDs
- `github.com/mattn/go-sqlite3` or `modernc.org/sqlite` — SQLite driver
- `github.com/gorilla/websocket` — WebSocket support
- Standard library for HTTP server, TCP client, JSON
