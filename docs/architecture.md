# Sporulator Architecture

## Overview

Sporulator is a two-tier LLM agent backend for building Mycelium workflows. It pairs a **Graph Agent** (designs workflows) with **Cell Agents** (implement individual cells), backed by SQLite storage and an optional live Clojure nREPL connection.

```
Browser (WebSocket) <-> HTTP/WS API <-> Agent Manager <-> LLM (DeepSeek/OpenAI/etc.)
                                     <-> SQLite Store
                                     <-> nREPL Bridge <-> Clojure REPL (optional)
```

## The Two Agents

### Graph Agent

Long-lived session that designs workflow manifests. Its system prompt teaches it manifest structure (cells, edges, dispatches, joins, pipelines) and schema syntax. It explicitly does NOT implement cells -- only designs the graph. Sessions persist across messages so the user can iterate on a design.

### Cell Agent

Short-lived session that implements a single cell. Given a `CellBrief` (ID, doc, schema, requires, context), it generates a `(cell/defcell ...)` form. Sessions persist for iteration -- a user can say "change the output format" and the agent has full conversation history.

## Data Flow

1. **Design**: User chats via WebSocket `graph_chat` -> Graph Agent streams tokens back via `stream_chunk` messages -> final response in `stream_end`
2. **Implement**: User sends `cell_implement` with a brief -> Cell Agent streams implementation -> `cell_result` with extracted defcell code
3. **Iterate**: User sends `cell_iterate` with feedback -> same Cell Agent session refines the code
4. **Save**: REST `POST /api/cell?id=:ns/name` or `POST /api/manifest?id=:app` saves to SQLite with auto-incrementing versions
5. **Instantiate** (optional): If nREPL is connected, `POST /api/repl/instantiate` evaluates the defcell in a live Clojure process, registering it in Mycelium's cell registry
6. **Test** (optional): Bridge can invoke cell handlers with test inputs, compare expected outputs, and record results

## Storage

SQLite with immutable versioning -- every save creates a new version, nothing is overwritten. Four tables:

- **cells**: handler source, schema, doc, requires, version history
- **manifests**: EDN body with version history
- **manifest_cells**: pins specific cell versions to manifest versions for reproducible compositions
- **test_results**: test outcomes linked to cell ID + version

### Versioning Workflow

```
User: "Create a login cell"
  -> LLM generates defcell code
  -> User saves via POST /api/cell?id=:auth/login
  -> Store creates :auth/login v1

User: "Improve the login cell"
  -> LLM iterates (reuses session history)
  -> User saves again
  -> Store creates :auth/login v2

User: "Build a workflow"
  -> Manifest created with :auth/login in cells
  -> Store creates :login-app v1

User: "Pin latest login to workflow"
  -> PinCellVersion(:login-app, 1, :auth/login, 2)
  -> Workflow now uses v2 of the login cell
```

## Packages

| Package | Role |
|---|---|
| `cmd/sporulator` | CLI entry, wires everything together |
| `pkg/agents` | Manager, GraphAgent, CellAgent, system prompts, code extraction |
| `pkg/api` | REST handlers, WebSocket hub with per-client context cancellation |
| `pkg/llm` | OpenAI-compatible client with streaming SSE, Session with serialized sends |
| `pkg/bridge` | High-level nREPL ops: instantiate cells, run tests, validate schemas, run workflows |
| `pkg/repl` | Low-level nREPL protocol: bencode over TCP, channel-based message routing |
| `pkg/store` | SQLite with WAL mode, auto-migration, versioned CRUD |

## WebSocket Streaming

All LLM interactions stream tokens to the frontend in real time via WebSocket.

### Message Format

```json
{"type": "message_type", "id": "session-or-cell-id", "payload": {...}}
```

### Client -> Server

| Type | Payload | Description |
|---|---|---|
| `graph_chat` | `{"session_id": "...", "message": "..."}` | Chat with the graph agent |
| `cell_implement` | `{"brief": {"id": ":ns/name", "doc": "...", ...}}` | Implement a cell |
| `cell_iterate` | `{"session_id": ":ns/name", "feedback": "..."}` | Iterate on a cell |

### Server -> Client

| Type | Description |
|---|---|
| `stream_chunk` | LLM token chunk (`{"chunk": "..."}`) |
| `stream_end` | Stream complete (`{"content": "full response"}`) |
| `stream_error` | LLM error |
| `cell_result` | Cell implementation (`{"cell_id": "...", "code": "...", "raw": "..."}`) |
| `error` | General error |

### Example Flow

```
Client sends:
  {"type": "graph_chat", "id": "s1", "payload": {"session_id": "s1", "message": "Design a login workflow"}}

Server streams back:
  {"type": "stream_chunk", "id": "s1", "payload": {"chunk": "Here's a"}}
  {"type": "stream_chunk", "id": "s1", "payload": {"chunk": " login workflow"}}
  ...
  {"type": "stream_end", "id": "s1", "payload": {"content": "Here's a login workflow..."}}
```

Context cancellation ensures that if the client disconnects mid-stream, the in-flight LLM API call is cancelled immediately.

## Key Design Decisions

- **Query params for IDs**: Cell IDs like `:math/double` contain slashes, so REST routes use `?id=` instead of path params
- **Separate LLM clients**: Graph and cell agents can use different providers/models (e.g., Claude for graph design, DeepSeek for cell implementation)
- **nREPL is optional**: The tool works purely as an LLM + storage system without a running Clojure process
- **Session serialization**: A per-session `sendMu` mutex prevents concurrent WebSocket calls from corrupting conversation history
- **Immutable versioning**: Cells and manifests are never overwritten, enabling full history and rollback
- **Code extraction**: `ExtractDefcell` uses balanced-paren matching to reliably pull `(cell/defcell ...)` forms from LLM markdown responses

## System Prompts

The two agents are configured with detailed system prompts that define the boundary of each agent's knowledge:

**Graph Agent prompt** teaches:
- Manifest structure: `:id`, `:cells`, `:edges`, `:dispatches`, `:joins`, `:pipeline`
- Edge types: unconditional and conditional
- Schema syntax (lite EDN format)
- Design principles for workflow decomposition

**Cell Agent prompt** teaches:
- `cell/defcell` macro syntax and handler signature `(fn [resources data] ...)`
- Schema syntax (lite and full Malli)
- Key rules: return only new keys, never call other cells, never acquire resources directly
- Resources pattern for external dependencies
- Common mistakes to avoid
