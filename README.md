# Sporulator

Backend service for the Mycelium visual workflow builder. Provides a two-tier LLM agent architecture, SQLite cell storage, nREPL bridge, and HTTP/WebSocket API.

## Architecture

```
Frontend (React) ←→ HTTP/WS API ←→ Agent Manager ←→ LLM Providers
                                 ←→ SQLite Store
                                 ←→ nREPL Bridge ←→ Clojure REPL
```

**Graph Agent** — Long-lived session that designs workflow manifests. Understands the full Mycelium manifest structure (cells, edges, dispatches, joins, pipelines).

**Cell Agent** — Short-lived session that implements individual cells. Generates `defcell` forms with handlers, schemas, and docs.

## Quick Start

### Prerequisites

- Go 1.25+
- An OpenAI-compatible LLM API key (DeepSeek, OpenAI, OpenRouter, etc.)
- (Optional) A running Clojure nREPL with Mycelium on the classpath

### Build

```bash
go build -o sporulator ./cmd/sporulator
```

### Run

```bash
# Minimal — just the API with LLM agents
./sporulator \
  --graph-key YOUR_API_KEY \
  --cell-key YOUR_API_KEY

# Full — with nREPL connection for live cell instantiation
./sporulator \
  --graph-key YOUR_API_KEY \
  --cell-key YOUR_API_KEY \
  --nrepl-port 7888
```

### Environment Variables

Instead of flags, you can set environment variables:

| Variable | Default | Description |
|---|---|---|
| `GRAPH_BASE_URL` | `https://api.deepseek.com` | Graph agent API base URL |
| `GRAPH_API_KEY` | (none) | Graph agent API key |
| `GRAPH_MODEL` | `deepseek-chat` | Graph agent model name |
| `CELL_BASE_URL` | `https://api.deepseek.com` | Cell agent API base URL |
| `CELL_API_KEY` | (none) | Cell agent API key |
| `CELL_MODEL` | `deepseek-chat` | Cell agent model name |

### CLI Flags

| Flag | Default | Description |
|---|---|---|
| `--addr` | `:8420` | HTTP listen address |
| `--db` | `sporulator.db` | SQLite database path |
| `--nrepl-host` | `127.0.0.1` | nREPL host |
| `--nrepl-port` | `0` | nREPL port (connects if > 0) |
| `--graph-url` | env or `https://api.deepseek.com` | Graph agent API URL |
| `--graph-key` | env | Graph agent API key |
| `--graph-model` | env or `deepseek-chat` | Graph agent model |
| `--cell-url` | env or `https://api.deepseek.com` | Cell agent API URL |
| `--cell-key` | env | Cell agent API key |
| `--cell-model` | env or `deepseek-chat` | Cell agent model |

## API Reference

### REST Endpoints

#### Cells

```
GET  /api/cells              — List all cells (latest versions)
GET  /api/cell?id=:ns/name   — Get latest version of a cell
POST /api/cell?id=:ns/name   — Save a new cell version
GET  /api/cell/history?id=:ns/name — Get all versions of a cell
GET  /api/cell/tests?id=:ns/name   — Get test results for a cell
```

**Save cell body:**
```json
{
  "handler": "(cell/defcell :math/double ...)",
  "schema": "{:input {:x :int} :output {:r :int}}",
  "doc": "Doubles the input",
  "created_by": "graph-agent"
}
```

#### Manifests

```
GET  /api/manifests              — List all manifests
GET  /api/manifest?id=:app-name  — Get latest manifest
POST /api/manifest?id=:app-name  — Save a new manifest version
```

**Save manifest body:**
```json
{
  "body": "{:id :todo-app :cells [...] :edges [...]}",
  "created_by": "graph-agent"
}
```

#### REPL (requires nREPL connection)

```
GET  /api/repl/status        — Check nREPL connection status
POST /api/repl/eval          — Evaluate Clojure code
POST /api/repl/instantiate   — Instantiate a cell from the store
```

### WebSocket

Connect to `ws://host:port/ws` for streaming LLM interactions.

**Message format:**
```json
{"type": "message_type", "id": "session-id", "payload": {...}}
```

**Client → Server:**

| Type | Payload | Description |
|---|---|---|
| `graph_chat` | `{"session_id": "...", "message": "..."}` | Chat with the graph agent |
| `cell_implement` | `{"brief": {"id": ":ns/name", "doc": "...", ...}}` | Implement a cell |
| `cell_iterate` | `{"session_id": ":ns/name", "feedback": "..."}` | Iterate on a cell |

**Server → Client:**

| Type | Description |
|---|---|
| `stream_chunk` | LLM token chunk (`{"chunk": "..."}`) |
| `stream_end` | Stream complete (`{"content": "full response"}`) |
| `stream_error` | LLM error |
| `cell_result` | Cell implementation result (`{"cell_id": "...", "code": "..."}`) |
| `error` | General error |

## Development

```bash
# Run tests
go test ./...

# Run tests with nREPL integration (requires running nREPL)
NREPL_PORT=7888 go test ./...

# Build and run
go run ./cmd/sporulator --graph-key $GRAPH_API_KEY --cell-key $CELL_API_KEY
```

## Project Structure

```
cmd/sporulator/     — CLI entrypoint
pkg/
  agents/           — Graph and cell LLM agents, code extraction
  api/              — HTTP handlers, WebSocket hub
  bridge/           — nREPL bridge for live Clojure interaction
  llm/              — OpenAI-compatible chat client with streaming
  repl/             — Low-level nREPL protocol client (bencode over TCP)
  store/            — SQLite storage for cells, manifests, test results
```
