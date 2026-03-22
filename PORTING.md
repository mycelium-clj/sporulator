# Sporulator: Go → Clojure Port Tracking

Feature parity tracking for porting the Go sporulator backend to idiomatic Clojure.

**Key architectural difference:** The Clojure version runs in the same JVM as the
generated cell code. No nREPL bridge is needed — we can `eval`/`load-string` directly.
This eliminates an entire layer of complexity (the Go `bridge` package).

## Status Legend

- [x] Complete
- [~] Partial — exists but missing features
- [ ] Not started

---

## 1. Store (sporulator.store)

Persistence layer. SQLite via next.jdbc.

| Feature | Go function | Clojure function | Status |
|---------|-------------|-----------------|--------|
| Open DB | `Open()` | `open` | [x] |
| Close DB | `Close()` | `close` | [x] |
| Save cell | `SaveCell()` | `save-cell!` | [x] |
| Get cell version | `GetCell()` | `get-cell` | [x] |
| Get latest cell | `GetLatestCell()` | `get-latest-cell` | [x] |
| List cells (summary) | `ListCells()` | `list-cells` | [x] |
| List cells (full) | — | `list-latest-cells` | [x] |
| Cell history | `GetCellHistory()` | `get-cell-history` | [x] |
| Save manifest | `SaveManifest()` | `save-manifest!` | [x] |
| Get manifest version | `GetManifest()` | `get-manifest` | [x] |
| Get latest manifest | `GetLatestManifest()` | `get-latest-manifest` | [x] |
| List manifests | `ListManifests()` | `list-manifests` | [x] |
| Pin cell version | `PinCellVersion()` | `pin-cell-version!` | [x] |
| Get pinned cells | `GetPinnedCells()` | `get-pinned-cells` | [x] |
| Save test result | `SaveTestResult()` | `save-test-result!` | [x] |
| Get test results | `GetTestResults()` | `get-test-results` | [x] |
| Latest test results | `GetLatestTestResults()` | `get-latest-test-results` | [x] |
| Create run | `CreateRun()` | `create-run!` | [x] |
| Get run | `GetRun()` | `get-run` | [x] |
| Update run status | `UpdateRunStatus()` | `update-run-status!` | [x] |
| Update run tree | `UpdateRunTree()` | `update-run-tree!` | [x] |
| Latest run for manifest | `GetLatestRunForManifest()` | `get-latest-run-for-manifest` | [x] |
| Save cell attempt | `SaveCellAttempt()` | `save-cell-attempt!` | [x] |
| Get cell attempts | `GetCellAttempts()` | `get-cell-attempts` | [x] |
| Run summary | `GetRunSummary()` | `get-run-summary` | [x] |
| Save test contract | `SaveTestContract()` | `save-test-contract!` | [x] |
| Update contract status | `UpdateTestContractStatus()` | `update-test-contract-status!` | [x] |
| Get test contract | `GetTestContract()` | `get-test-contract` | [x] |
| Approved contracts | `GetApprovedTestContracts()` | `get-approved-test-contracts` | [x] |
| Get all contracts | `GetTestContracts()` | `get-test-contracts` | [x] |
| Create chat session | `CreateChatSession()` | `create-chat-session!` | [x] |
| Get chat session | `GetChatSession()` | `get-chat-session` | [x] |
| List chat sessions | `ListChatSessions()` | `list-chat-sessions` | [x] |
| Delete chat session | `DeleteChatSession()` | `delete-chat-session!` | [x] |
| Save chat message | `SaveChatMessage()` | `save-chat-message!` | [x] |
| Load chat messages | `LoadChatMessages()` | `load-chat-messages` | [x] |
| Clear chat messages | `ClearChatMessages()` | `clear-chat-messages!` | [x] |

---

## 2. LLM Client (sporulator.llm)

OpenAI-compatible client with streaming and session management.

| Feature | Go function | Clojure function | Status |
|---------|-------------|-----------------|--------|
| Create client | `NewClient()` | `create-client` | [x] |
| Chat (non-streaming) | `Chat()` | `chat` | [x] |
| Chat (streaming SSE) | `ChatStream()` | `chat-stream` | [x] |
| Create session | `NewSession()` | `create-session` | [x] |
| Session messages | `Messages()` | `session-messages` | [x] |
| Session history | `History()` | `session-history` | [x] |
| Session reset | `Reset()` | `session-reset!` | [x] |
| Set messages | `SetMessages()` | `session-set-messages!` | [x] |
| Send (non-streaming) | `Send()` | `session-send` | [x] |
| Send (streaming) | `SendStream()` | `session-send-stream` | [x] |
| Dual client support | graph + cell clients | graph-llm + cell-llm in server | [x] |

---

## 3. Extract (sporulator.extract)

Code extraction from LLM responses. **Already complete in Clojure.**

| Feature | Go function | Clojure function | Status |
|---------|-------------|-----------------|--------|
| Extract code blocks | `ExtractCodeBlocks()` | `extract-code-blocks` | [x] |
| Extract first block | `ExtractFirstCodeBlock()` | `extract-first-code-block` | [x] |
| Extract defcell | `ExtractDefcell()` | `extract-defcell` | [x] |
| Extract fn body | `ExtractFnBody()` | `extract-fn-body` | [x] |
| Extract helpers | `ExtractHelpers()` | `extract-helpers` | [x] |
| Extra requires | `ExtractExtraRequires()` | `extract-extra-requires` | [x] |
| Paren depth | `ParenDepth()` | `paren-depth` | [x] |
| Is truncated | `IsTruncated()` | `truncated?` | [x] |
| Balance parens | `BalanceParens()` | `balance-parens` | [x] |
| Looks like Clojure | `LooksLikeClojure()` | `looks-like-clojure?` | [x] |
| Self-review corrections | — | `extract-self-review-corrections` | [x] |
| Extract all defcells | — | `extract-all-defcells` | [x] |

---

## 4. Codegen (sporulator.codegen)

Source code assembly. **Already complete in Clojure.**

| Feature | Go function | Clojure function | Status |
|---------|-------------|-----------------|--------|
| Assemble cell source | `AssembleCellSource()` | `assemble-cell-source` | [x] |
| Assemble stub source | `AssembleStubCellSource()` | `assemble-stub-cell-source` | [x] |
| Assemble test source | `AssembleTestSource()` | `assemble-test-source` | [x] |
| Assemble manifest | `AssembleManifest()` | `assemble-manifest` | [x] |

---

## 5. Prompts (sporulator.prompts)

System prompts and fix prompt construction. **Already complete in Clojure.**

| Feature | Go constant/function | Clojure var/function | Status |
|---------|---------------------|---------------------|--------|
| Graph agent prompt | `DefaultGraphPrompt` | `graph-prompt` | [x] |
| Cell agent prompt | `DefaultCellPrompt` | `cell-prompt` | [x] |
| Fix tier escalation | `FixTier()` | `fix-tier` | [x] |
| First failing test | `extractFirstFailingTest()` | `extract-first-failing-test` | [x] |
| Standard fix prompt | `buildFixPrompt()` | `build-fix-prompt` | [x] |
| Graduated fix prompt | `buildGraduatedFixPrompt()` | `build-graduated-fix-prompt` | [x] |

---

## 6. Decompose (sporulator.decompose)

Workflow decomposition and graph context.

| Feature | Go function | Clojure function | Status |
|---------|-------------|-----------------|--------|
| Parse decomposition | `ParseDecompositionResponse()` | `parse-decomposition-response` | [x] |
| Collect leaves | `CollectLeaves()` | `collect-leaves` | [x] |
| Collect sub-workflows | `CollectSubWorkflows()` | `collect-sub-workflows` | [x] |
| Parse edge targets | `ParseEdgeTargets()` | `parse-edge-targets` | [x] |
| Build graph context | `BuildGraphContext()` | `build-graph-context` | [x] |
| Find parent | `FindParent()` | `find-parent` | [x] |
| Format graph context | `FormatGraphContext()` | `format-graph-context` | [x] |
| Serialize tree | `SerializeTree()` | `serialize-tree` | [x] |
| Deserialize tree | `DeserializeTree()` | `deserialize-tree` | [x] |
| Schema mismatch detection | `validateEdgeSchemas()` | — | [ ] |
| Schema correction parsing | `parseSchemaCorrections()` | — | [ ] |
| Apply schema corrections | `applySchemaCorrections()` | — | [ ] |
| Edge fix prompt | `buildGroupedEdgeFixPrompt()` | — | [ ] |
| Full decompose workflow | `Decompose()` | — | [ ] |

---

## 7. Graph Agent (sporulator.graph-agent)

Long-lived graph design conversations.

| Feature | Go method | Clojure function | Status |
|---------|-----------|-----------------|--------|
| Get/create session | `GetGraphAgent()` | `get-or-create-session` | [x] |
| Chat streaming | `ChatStream()` | `chat-stream` | [x] |
| Chat with manifest context | `ChatWithManifest()` | handled inline in server | [x] |
| Stream with feedback loop | `ChatStreamWithFeedback()` | `chat-stream-with-feedback` | [x] |
| Manifest detection | `looksLikeManifest()` | `looks-like-manifest?` | [x] |
| Manifest validation | — | `validate-manifest-edn` | [x] |
| Extract manifest | — | `extract-manifest` | [x] |
| Save manifest from response | `SaveManifest()` | `save-response-manifest!` | [x] |
| Persist turns to DB | `persistTurn()` | `persist-turn!` | [x] |
| Restore history from DB | in `GetGraphAgent()` | in `get-or-create-session` | [x] |
| Reset session | `Reset()` | `reset-session!` | [x] |
| Reset all sessions | — | `reset-all-sessions!` | [x] |
| History | `History()` | via `llm/session-history` | [x] |

---

## 8. Cell Agent (sporulator.cell-agent)

Cell implementation agent with eval feedback loops.

| Feature | Go method | Clojure function | Status |
|---------|-----------|-----------------|--------|
| Cell brief | `CellBrief` struct | plain map | [x] |
| Cell result | `CellResult` struct | plain map | [x] |
| Build cell prompt | `buildCellPrompt()` | `build-cell-prompt` | [x] |
| Extract result | `buildResult()` | `build-result` | [x] |
| Implement streaming | `ImplementStream()` | `implement-stream` | [x] |
| Implement with feedback | `ImplementWithFeedback()` | `implement-with-feedback` | [x] |
| Iterate on cell | `Iterate()` / `IterateStream()` | via session-send-stream | [x] |
| Save cell result | `Save()` | `save-cell!` | [x] |
| Parallel implementation | `ImplementCells()` | `implement-cells` | [x] |
| Feedback events | `FeedbackEvent` | plain map | [x] |

---

## 9. Eval (replaces Go bridge)

**Clojure advantage:** No nREPL bridge needed. Code runs in the same JVM.
Use `eval`/`load-string` directly. This replaces the entire Go `bridge` package.

| Feature | Go bridge method | Clojure function | Status |
|---------|-----------------|-----------------|--------|
| Eval code | `Eval()` | `eval-code` | [x] |
| Eval in namespace | `EvalInNs()` | via `eval-code` with ns form | [x] |
| Instantiate cell | `InstantiateCell()` | `instantiate-cell` | [x] |
| Test cell | `TestCell()` | `run-cell-tests` | [x] |
| Run cell tests | `RunCellTests()` | `run-cell-tests` | [x] |
| Validate manifest EDN | `ValidateManifestEDN()` | `graph-agent/validate-manifest-edn` | [x] |
| Compile workflow | `CompileWorkflow()` | `mycelium.core/compile` | [ ] |
| Run workflow | `RunWorkflow()` | `mycelium.core/run` | [ ] |
| Run with trace | `RunWorkflowWithTrace()` | run + capture trace | [ ] |
| Validate schema | `ValidateSchema()` | `validate-schema` | [x] |
| Verify cell contract | `VerifyCellContract()` | `verify-cell-contract` | [x] |
| Lint code | `lintFixLoop()` (lint part) | `lint-code` | [x] |
| Merge test corrections | `mergeTestCorrections()` | `merge-test-corrections` | [x] |

---

## 10. Orchestrator — NOT YET STARTED

Full TDD workflow: decompose → test → implement → integrate.

| Feature | Go method | Clojure function | Status |
|---------|-----------|-----------------|--------|
| Run full workflow | `Run()` | `orchestrate!` | [x] |
| Generate test contracts | `generateTestContract()` | `generate-test-contract` | [x] |
| Self-review tests | `selfReviewTests()` | inline in `generate-test-contract` | [x] |
| Implement from contract | `implementFromContract()` | `implement-from-contract` | [x] |
| Graduated fix prompts | `fixCellCode()` | via `prompts/build-graduated-fix-prompt` | [x] |
| Review gates (test) | `OnTestReview` callback | `on-test-review` + `create-review-gate` | [x] |
| Verify cell contract | `verifyCellContract()` | `eval/verify-cell-contract` | [x] |
| Event emission | `OrchestratorEvent` | `emit` callback | [x] |
| Run tracking (store) | `CreateRun/UpdateRunStatus` | via store functions | [x] |
| Cell attempt tracking | `SaveCellAttempt` | via store functions | [x] |
| Resume workflow | `RunResumable()` | `resume!` | [x] |
| Merge test corrections | `mergeTestCorrections()` | `eval/merge-test-corrections` | [x] |
| Lint fix loop | `lintFixLoop()` | `lint-fix-loop` | [x] |
| Integration test loop | integration testing | — | [ ] |
| Schema validation loop | `validateEdgeSchemas()` | — | [ ] |
| Review gates (graph) | `OnGraphReview` callback | — | [ ] |
| Review gates (impl) | `OnImplReview` callback | — | [ ] |

---

## 11. Server (sporulator.server)

HTTP + WebSocket server for sporulator-ui.

### REST Endpoints

| Endpoint | Method | Go handler | Status |
|----------|--------|-----------|--------|
| `/api/cells` | GET | `handleListCells` | [x] |
| `/api/cell` | GET | `handleGetCell` | [x] |
| `/api/cell` | POST | `handleSaveCell` | [x] |
| `/api/cell/history` | GET | `handleCellHistory` | [x] |
| `/api/cell/tests` | GET | `handleCellTests` | [x] |
| `/api/manifests` | GET | `handleListManifests` | [x] |
| `/api/manifest` | GET | `handleGetManifest` | [x] |
| `/api/manifest` | POST | `handleSaveManifest` | [x] |
| `/api/manifest/export` | POST | `handleExportManifest` | [x] |
| `/api/repl/eval` | POST | `handleReplEval` | [x] in-process eval |
| `/api/repl/instantiate` | POST | `handleReplInstantiate` | [x] |
| `/api/repl/status` | GET | `handleReplStatus` | [x] always connected |
| `/api/repl/project-path` | GET | `handleReplProjectPath` | [x] |
| `/api/source/generate` | POST | `handleSourceGenerate` | [ ] |
| `/api/sessions` | GET | `handleListSessions` | [x] |
| `/api/session` | GET | `handleGetSession` | [x] |
| `/api/session` | DELETE | `handleDeleteSession` | [x] |
| `/api/session/clear` | POST | `handleClearSession` | [x] |

### WebSocket Message Types

| Message type | Direction | Go handler | Status |
|-------------|-----------|-----------|--------|
| `graph_chat` | in | `handleGraphChat` | [x] streaming + feedback + persistence |
| `graph_chat` | in | `handleGraphChat` | [x] streaming + feedback + persistence |
| `cell_implement` | in | `handleCellImplement` | [x] |
| `cell_iterate` | in | `handleCellIterate` | [x] |
| `orchestrate` | in | `handleOrchestrate` | [x] |
| `test_review` | in | `handleTestReview` | [x] |
| `graph_review` | in | `handleGraphReview` | [ ] |
| `impl_review` | in | `handleImplReview` | [ ] |
| `stream_chunk` | out | — | [x] |
| `stream_end` | out | — | [x] |
| `stream_error` | out | — | [x] |
| `error` | out | — | [x] |
| `feedback_event` | out | — | [x] |
| `orchestrator_event` | out | — | [x] |
| `orchestrator_complete` | out | — | [x] |
| `orchestrator_error` | out | — | [x] |
| `test_review_contracts` | out | — | [x] |
| `graph_review_data` | out | — | [ ] |
| `impl_review_data` | out | — | [ ] |

---

## Implementation Order

Work bottom-up. Each phase builds on the previous.

### Phase 1: Store — Chat Session Persistence
Add chat_sessions and chat_messages table operations to `sporulator.store`.
Wire into graph agent for turn persistence and session REST endpoints.

### Phase 2: Graph Agent — Feedback Loop
- `looksLikeManifest` — detect manifest in LLM response
- `SaveManifest` — extract and persist manifest from response
- `ChatStreamWithFeedback` — validate manifest EDN via `read-string`,
  auto-fix on parse errors (up to 3 retries)
- Persist conversation turns to DB
- Restore sessions from DB on startup

### Phase 3: Eval Layer (replaces Go bridge)
In-process code evaluation. No nREPL needed.
- `eval-code` — safe eval with timeout and error capture
- `instantiate-cell` — load cell source via codegen + eval
- `run-tests` — assemble test source, eval, capture results
- `validate-manifest` — read EDN + schema validation
- `compile-workflow` — load manifest into mycelium
- `run-workflow` — execute workflow with tracing

### Phase 4: Cell Agent
- Cell brief → prompt construction
- Implement with streaming
- Implement with eval feedback loop (eval → error → fix → retry)
- Iterate on existing cells
- Save to store
- Parallel cell implementation via futures

### Phase 5: Orchestrator
Full TDD workflow. This is the big one (~3000 lines in Go).
- Decompose workflow (recursive graph agent)
- Generate test contracts per cell
- Self-review tests
- Review gate: user approves/edits tests
- Implement cells against locked test contracts
- Eval feedback loops with graduated fix prompts
- Integration testing with schema validation
- Review gate: user approves implementations

### Phase 6: Server — Complete WebSocket + REST
Wire everything into server.clj:
- All WebSocket message handlers
- All REST endpoints (save cell, save manifest, sessions, source gen)
- Review gates via core.async channels or promises
- Dual LLM client support (graph + cell)

---

## Clojure-Specific Design Notes

### No Bridge Needed
Go needed an nREPL bridge to talk to the Clojure runtime. In Clojure,
we ARE the runtime. Use `load-string`, `eval`, `requiring-resolve`.

### Review Gates
Go used goroutine channels. Clojure equivalent: `core.async` channels
or `promise`/`deliver` pairs. A review gate is a promise that the
orchestrator blocks on; the WebSocket handler delivers the user's response.

### Parallel Cell Implementation
Go used goroutines + WaitGroup. Clojure: `pmap`, `future`, or
`core.async/pipeline`. Futures with a bounded thread pool via
`ExecutorService` is simplest.

### Error Handling
Go used error returns. Clojure: use `ex-info` with data maps.
The orchestrator should catch errors per-cell and continue with others.

### State Management
Go used mutexes for shared state. Clojure: atoms for simple state,
refs for coordinated state, agents for async updates.
