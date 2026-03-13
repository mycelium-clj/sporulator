# Sporulator Frontend Plan

Web UI for visual workflow design and agent interaction.

## Architecture

```
┌──────────────────────────────────────────────────┐
│                   Sporulator UI                   │
│                                                   │
│  ┌─────────────────────┐  ┌────────────────────┐  │
│  │   Graph Canvas       │  │   Chat / Detail    │  │
│  │   (React Flow)       │  │   Panel            │  │
│  │                      │  │                    │  │
│  │   ┌───┐    ┌───┐    │  │  [Graph Agent]     │  │
│  │   │ A │───▶│ B │    │  │  or                │  │
│  │   └───┘    └─┬─┘    │  │  [Cell Detail]     │  │
│  │              │       │  │                    │  │
│  │          ┌───▼───┐   │  │                    │  │
│  │          │   C   │   │  │                    │  │
│  │          └───────┘   │  │                    │  │
│  └─────────────────────┘  └────────────────────┘  │
│                                                   │
│  ┌──────────────────────────────────────────────┐  │
│  │   Status Bar: REPL connection, validation    │  │
│  └──────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

## Tech Stack

- **React** + **TypeScript** — UI framework
- **React Flow** — graph visualization and interaction
- **Tailwind CSS** — styling
- **Vite** — build tool
- WebSocket client for real-time backend communication

## Phases

### Phase 1: Project Setup & Graph Canvas
Foundation: Vite + React + TypeScript + React Flow + Tailwind.

- App shell with two-panel layout (graph left, detail right)
- React Flow canvas rendering a manifest as a DAG
- Custom node component showing: cell name, schema summary, status indicator
- Custom edge component showing: transition label, key flow on hover
- Read manifest from backend API, convert to React Flow nodes/edges
- Visual indicators: green (implemented + tests pass), yellow (stub), red (failing), gray (no schema)
- Join groups rendered as grouped/clustered nodes
- Pipeline flows rendered as a linear chain

### Phase 2: Chat Panel — Graph Agent
Right panel for conversing with the graph agent.

- Chat UI: message list + input box
- Messages stream in token-by-token via WebSocket
- Graph agent responses that contain manifest EDN auto-update the canvas
- User can describe requirements in natural language
- Agent suggests cells, edges, schemas — user confirms or modifies
- Manifest diff view: show what the agent wants to change before applying
- Session persistence: conversation history maintained across page reloads

### Phase 3: Cell Detail Panel
Click a node → right panel shows cell detail instead of chat.

- Cell info: id, doc, schema (formatted), requires, version history
- Source code viewer: current handler implementation with syntax highlighting
- Test results: list of test runs with pass/fail, input/output diff on failure
- "Implement" button: spawns a cell agent to generate implementation
- "Iterate" button: send feedback (test failures, schema errors) to cell agent
- Version selector: view/rollback to previous implementations
- Live agent streaming: watch the cell agent write code in real-time

### Phase 4: REPL Integration UI
Connect to and interact with a live Clojure REPL.

- Connection status indicator in status bar
- Connect/disconnect controls (host, port)
- "Instantiate" button on cells: load into running REPL
- "Run Workflow" button: compile + run the manifest in the REPL
- REPL output panel: streaming eval results
- Test runner: run cell tests via REPL, display results inline
- Schema validation feedback: highlight cells with validation errors on the canvas

### Phase 5: Interactive Graph Editing
Direct manipulation of the workflow graph.

- Drag to add new cells (from a palette or by typing a name)
- Draw edges between nodes by dragging from output handle to input handle
- Right-click context menu: delete cell, edit schema, add dispatch predicate
- Edge labels editable: set transition names
- Join creation: select multiple nodes → "Create Join" action
- Auto-layout: dagre or elk layout algorithm for clean graph arrangement
- Undo/redo for graph operations
- Export manifest as EDN file

### Phase 6: Polish & Advanced Features
- Dark/light theme
- Keyboard shortcuts (delete node, undo, zoom)
- Minimap for large graphs
- Schema flow visualization: animate data flowing through edges
- Cell search/filter
- Bulk operations: implement all stubs, run all tests
- Notification system for agent completion, test results
