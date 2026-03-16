package agents

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"

	"github.com/mycelium-clj/sporulator/pkg/bridge"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// Orchestrator coordinates the full workflow: manifest design → cell TDD → integration testing.
type Orchestrator struct {
	manager *Manager
	store   *store.Store
}

// ProjectConfig describes the target Clojure project.
type ProjectConfig struct {
	ProjectPath      string // absolute path to project root
	BaseNamespace    string // e.g. "example.order-lifecycle"
	SourceDir        string // e.g. "src/clj" (relative to ProjectPath)
	TestDir          string // e.g. "test/clj"
	Spec             string // SPEC.md contents
	ManifestID       string // e.g. ":order/placement"
	MaxStepsPerLevel int    // max steps per decomposition level (default 5)
	MaxDepth         int    // max recursion depth (default 3); 0 = flat (one-level decomposition)
}

// OrchestratorEvent reports progress during orchestration.
type OrchestratorEvent struct {
	Phase   string `json:"phase"`   // "manifest", "cell_test", "cell_implement", "integration"
	CellID  string `json:"cell_id,omitempty"`
	Status  string `json:"status"`  // "started", "success", "error", "retry"
	Message string `json:"message"`
}

// NewOrchestrator creates an orchestrator.
func NewOrchestrator(mgr *Manager, st *store.Store) *Orchestrator {
	return &Orchestrator{manager: mgr, store: st}
}

// Run executes the full orchestrated workflow using recursive decomposition.
// onChunk streams LLM tokens (source identifies which agent: "graph", "decompose/*", or cell ID).
// onEvent reports progress.
//
// Phases:
//  1. Decompose spec into a tree of steps (recursive, ≤MaxStepsPerLevel per level)
//  2. Collect all leaf cells from the tree
//  3. Implement leaves in parallel (existing TDD flow)
//  4. Register sub-workflows bottom-up via compose/register-workflow-cell!
//  5. Integration test on root manifest
func (o *Orchestrator) Run(ctx context.Context, cfg ProjectConfig,
	onChunk func(source string, chunk string),
	onEvent func(OrchestratorEvent)) error {

	br := o.manager.GetBridge()
	if br == nil {
		return fmt.Errorf("no REPL bridge connected")
	}

	// Phase 1: Decompose spec into tree + manifest
	onEvent(OrchestratorEvent{Phase: "manifest", Status: "started", Message: "Decomposing specification..."})

	manifest, tree, err := o.designManifest(ctx, cfg, br, onChunk, onEvent)
	if err != nil {
		return fmt.Errorf("manifest design: %w", err)
	}

	// Phase 2: Collect leaf cells from decomposition tree
	leaves := collectLeaves(tree)
	if len(leaves) == 0 {
		// Fallback: extract cell names from the root manifest directly
		onEvent(OrchestratorEvent{Phase: "cells", Status: "extracting",
			Message: "No leaves in tree, extracting from manifest..."})

		cellNames, extractErr := o.extractCellNames(br, manifest)
		if extractErr != nil {
			cellNames = extractCellNamesRegex(manifest)
		}
		if len(cellNames) == 0 {
			return fmt.Errorf("no cells found in manifest or decomposition tree")
		}

		onEvent(OrchestratorEvent{Phase: "cells", Status: "extracted",
			Message: fmt.Sprintf("Found %d cells: %s", len(cellNames), strings.Join(cellNames, ", "))})

		// Phase 3 (flat): Implement cells with TDD
		cellErrors := o.implementCellsParallel(ctx, cfg, br, manifest, cellNames, onChunk, onEvent)
		failCount := 0
		for _, e := range cellErrors {
			if e != nil {
				failCount++
			}
		}
		if failCount > 0 {
			onEvent(OrchestratorEvent{Phase: "cells", Status: "partial",
				Message: fmt.Sprintf("%d/%d cells had errors", failCount, len(cellNames))})
		}
	} else {
		leafNames := make([]string, len(leaves))
		for i, l := range leaves {
			leafNames[i] = l.CellID
		}
		onEvent(OrchestratorEvent{Phase: "cells", Status: "extracted",
			Message: fmt.Sprintf("Found %d leaf cells: %s", len(leaves), strings.Join(leafNames, ", "))})

		// Phase 3: Implement all leaf cells in parallel
		cellErrors := o.implementLeavesParallel(ctx, cfg, br, tree, leaves, onChunk, onEvent)
		failCount := 0
		for _, e := range cellErrors {
			if e != nil {
				failCount++
			}
		}
		if failCount > 0 {
			onEvent(OrchestratorEvent{Phase: "cells", Status: "partial",
				Message: fmt.Sprintf("%d/%d leaf cells had errors", failCount, len(leaves))})
		}

		// Phase 4: Register sub-workflows bottom-up
		subWorkflows := collectSubWorkflows(tree)
		if len(subWorkflows) > 0 {
			o.registerSubWorkflows(ctx, br, subWorkflows, onEvent)
		}
	}

	// Phase 5: Integration testing
	onEvent(OrchestratorEvent{Phase: "integration", Status: "started", Message: "Running integration tests..."})

	err = o.integrationTest(ctx, cfg, br, manifest, onChunk, onEvent)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error", Message: err.Error()})
	}

	onEvent(OrchestratorEvent{Phase: "complete", Status: "done", Message: "Orchestration complete"})
	return nil
}

// designManifest uses recursive decomposition to build a manifest from the spec.
// Returns the root manifest EDN and the full decomposition tree.
func (o *Orchestrator) designManifest(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) (string, *DecompositionNode, error) {

	// Build the namespace prefix for cell IDs from the manifest ID
	// e.g. ":order/placement" → "order"
	nsPrefix := strings.TrimPrefix(cfg.ManifestID, ":")
	if idx := strings.Index(nsPrefix, "/"); idx >= 0 {
		nsPrefix = nsPrefix[:idx]
	}

	dcfg := DecompositionConfig{
		MaxStepsPerLevel: cfg.MaxStepsPerLevel,
		MaxDepth:         cfg.MaxDepth,
	}
	if dcfg.MaxStepsPerLevel == 0 {
		dcfg.MaxStepsPerLevel = DefaultDecompositionConfig().MaxStepsPerLevel
	}
	if dcfg.MaxDepth == 0 {
		dcfg.MaxDepth = DefaultDecompositionConfig().MaxDepth
	}

	tree, err := o.decompose(ctx, cfg.Spec, nsPrefix, nil, 0, dcfg, onChunk, onEvent)
	if err != nil {
		return "", nil, fmt.Errorf("decomposition: %w", err)
	}

	manifest := tree.Manifest

	// Save manifest to store
	agent := o.manager.GetGraphAgent(cfg.ManifestID)
	version, saveErr := agent.SaveManifest(cfg.ManifestID, "```edn\n"+manifest+"\n```", "decompose-agent")
	if saveErr != nil {
		onEvent(OrchestratorEvent{Phase: "manifest", Status: "warning",
			Message: fmt.Sprintf("Could not save manifest: %v", saveErr)})
	} else {
		onEvent(OrchestratorEvent{Phase: "manifest", Status: "saved",
			Message: fmt.Sprintf("Manifest saved v%d", version)})
	}

	return manifest, tree, nil
}

// implementLeavesParallel implements all leaf cells from the decomposition tree in parallel.
// Each leaf uses its parent's manifest as context for the TDD flow.
// The CellBrief is built directly from the DecompositionNode, ensuring the cell ID,
// schema, and doc match the decomposition contract.
func (o *Orchestrator) implementLeavesParallel(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	tree *DecompositionNode, leaves []*DecompositionNode,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) []error {

	rootManifest := tree.Manifest

	errors := make([]error, len(leaves))
	var wg sync.WaitGroup

	for i, leaf := range leaves {
		wg.Add(1)
		go func(idx int, leaf *DecompositionNode) {
			defer wg.Done()

			// Build CellBrief directly from the decomposition node.
			// This ensures the expected cell ID, schema, and doc are passed through
			// to the LLM prompt and contract verification — no manifest lookup needed.
			brief := CellBrief{
				ID:       leaf.CellID,
				Doc:      leaf.Doc,
				Schema:   fmt.Sprintf("{:input %s :output %s}", defaultSchema(leaf.InputSchema), defaultSchema(leaf.OutputSchema)),
				Requires: leaf.Requires,
			}

			// Use the root manifest for context in prompts
			manifestCtx := rootManifest

			session, err := br.CloneSession()
			if err != nil {
				errors[idx] = fmt.Errorf("clone session for %s: %w", leaf.CellID, err)
				return
			}
			defer br.CloseSession(session)

			errors[idx] = o.implementCellWithTDD(ctx, cfg, br, session, manifestCtx, brief, onChunk, onEvent)
		}(i, leaf)
	}

	wg.Wait()
	return errors
}

// registerSubWorkflows registers non-leaf nodes as workflow cells, bottom-up.
func (o *Orchestrator) registerSubWorkflows(ctx context.Context, br *bridge.Bridge,
	subWorkflows []*DecompositionNode, onEvent func(OrchestratorEvent)) {

	for _, sw := range subWorkflows {
		if sw.Manifest == "" {
			onEvent(OrchestratorEvent{Phase: "register", CellID: sw.CellID, Status: "skipped",
				Message: fmt.Sprintf("No manifest for sub-workflow %s", sw.CellID)})
			continue
		}

		onEvent(OrchestratorEvent{Phase: "register", CellID: sw.CellID, Status: "started",
			Message: fmt.Sprintf("Registering sub-workflow %s", sw.CellID)})

		// Build schema EDN from the node
		schemaEDN := fmt.Sprintf("{:input %s :output %s}",
			defaultSchema(sw.InputSchema), defaultSchema(sw.OutputSchema))

		result, err := br.RegisterWorkflowCell(sw.CellID, sw.Manifest, schemaEDN)
		if err != nil {
			onEvent(OrchestratorEvent{Phase: "register", CellID: sw.CellID, Status: "error",
				Message: fmt.Sprintf("Registration error: %v", err)})
			continue
		}
		if result.IsError() {
			onEvent(OrchestratorEvent{Phase: "register", CellID: sw.CellID, Status: "error",
				Message: fmt.Sprintf("Registration failed: %s %s", result.Ex, result.Err)})
			continue
		}

		onEvent(OrchestratorEvent{Phase: "register", CellID: sw.CellID, Status: "success",
			Message: fmt.Sprintf("Sub-workflow %s registered", sw.CellID)})
	}
}

// defaultSchema returns the schema or "[:map]" if empty.
func defaultSchema(schema string) string {
	if schema == "" {
		return "[:map]"
	}
	return schema
}

// extractCellNames gets the cell step names from the manifest via REPL.
func (o *Orchestrator) extractCellNames(br *bridge.Bridge, manifest string) ([]string, error) {
	escaped := clojureEscape(manifest)
	code := fmt.Sprintf(`(let [m (read-string "%s")]
  (pr-str (vec (map name (keys (:cells m))))))`, escaped)

	result, err := br.Eval(code)
	if err != nil {
		return nil, fmt.Errorf("eval: %w", err)
	}
	if result.IsError() {
		return nil, fmt.Errorf("repl error: %s %s", result.Ex, result.Err)
	}

	return parseCellNames(result.Value), nil
}

// extractCellSpec gets a single cell's spec from the manifest via REPL.
func (o *Orchestrator) extractCellSpec(br *bridge.Bridge, manifest, stepName string) (CellBrief, error) {
	escaped := clojureEscape(manifest)
	code := fmt.Sprintf(`(let [m (read-string "%s")
      c (get-in m [:cells :%s])]
  (str "ID:" (:id c) "\n"
       "DOC:" (:doc c "") "\n"
       "SCHEMA:" (pr-str (:schema c)) "\n"
       "REQUIRES:" (pr-str (vec (map name (:requires c []))))))`, escaped, stepName)

	result, err := br.Eval(code)
	if err != nil {
		return CellBrief{}, fmt.Errorf("eval: %w", err)
	}
	if result.IsError() {
		return CellBrief{}, fmt.Errorf("repl error: %s", result.Ex)
	}

	return parseCellSpec(result.Value, stepName), nil
}

// implementCellsParallel runs TDD implementation for all cells concurrently.
// Each cell gets its own nREPL session for isolation.
func (o *Orchestrator) implementCellsParallel(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	manifest string, cellNames []string,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) []error {

	errors := make([]error, len(cellNames))
	var wg sync.WaitGroup

	for i, name := range cellNames {
		wg.Add(1)
		go func(idx int, cellName string) {
			defer wg.Done()
			// Create an isolated nREPL session for this cell agent
			session, err := br.CloneSession()
			if err != nil {
				errors[idx] = fmt.Errorf("clone session for %s: %w", cellName, err)
				return
			}
			defer br.CloseSession(session)

			// Extract brief from manifest for flat-path cells
			brief, specErr := o.extractCellSpec(br, manifest, cellName)
			if specErr != nil {
				errors[idx] = fmt.Errorf("extract spec for %s: %w", cellName, specErr)
				return
			}

			errors[idx] = o.implementCellWithTDD(ctx, cfg, br, session, manifest, brief, onChunk, onEvent)
		}(i, name)
	}

	wg.Wait()
	return errors
}

// implementCellWithTDD implements a single cell using TDD:
// 1. Ask cell agent to write tests
// 2. Write test file, eval in REPL
// 3. Ask cell agent to implement the cell
// 4. Write source file, eval in REPL
// 5. Verify cell contract (correct ID registered)
// 6. Run tests, iterate on failures
// Each cell gets its own nREPL session for isolation.
// The CellBrief is passed directly from the caller (decomposition tree or manifest extraction),
// ensuring contract expectations (cell ID, schema, doc) are authoritative.
func (o *Orchestrator) implementCellWithTDD(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	session, manifest string, brief CellBrief,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) error {

	cellID := brief.ID
	if cellID == "" {
		return fmt.Errorf("CellBrief has empty ID")
	}
	agent := o.manager.NewCellAgent(cellID)

	// Compute namespaces and file paths from cell ID
	// Use cellIDToNsSuffix to derive a unique namespace that avoids collisions
	// between cells with the same step name in different sub-workflows
	nsSuffix := cellIDToNsSuffix(cellID)
	cellNs := cfg.BaseNamespace + ".cells." + nsSuffix
	testNs := cellNs + "-test"
	srcFile := clojureNsToFile(cfg.ProjectPath, cfg.SourceDir, cellNs)
	testFile := clojureNsToFile(cfg.ProjectPath, cfg.TestDir, testNs)

	onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "started",
		Message: fmt.Sprintf("Writing tests for %s", cellID)})

	// Turn 1: Ask cell agent to write tests
	testPrompt := fmt.Sprintf(`You are implementing cell %s using TDD (test-driven development).

**Step 1: Write the test file first.**

Here is the full specification:
<spec>
%s
</spec>

Here is the workflow manifest for context:
`+"```edn\n%s\n```"+`

Cell details:
- **Cell ID:** %s (EXACT — tests MUST use this ID)
- **Doc:** %s
- **Schema:** %s
- **Requires:** %s

Write the test namespace `+"`%s`"+` using clojure.test.
The test file should:
- Require the cell namespace %s (which will contain the defcell)
- Define test cases that cover the business logic described in the spec
- Test the cell handler by calling: (let [handler (:handler (cell/get-cell! %s))] (handler resources data))
- Include edge cases and boundary conditions
- Set up realistic test data (resources, input maps) based on the spec
- If you need a helper function (e.g. for approximate equality), define it in the test namespace — do NOT use undefined symbols
- Use only clojure.test/is, clojure.test/deftest, clojure.test/testing — no external test libraries

CRITICAL: The cell ID is %s — use EXACTLY this keyword in (cell/get-cell! %s) calls.

Return ONLY the complete test namespace code in a single code block. Do NOT implement the cell yet.`,
		cellID, cfg.Spec, manifest, cellID, brief.Doc, brief.Schema,
		strings.Join(brief.Requires, ", "), testNs, cellNs, cellID, cellID, cellID)

	testResponse, err := agent.session.SendStream(ctx, agent.client, testPrompt,
		func(chunk string) { onChunk(cellID+"/test", chunk) })
	if err != nil {
		return fmt.Errorf("test generation for %s: %w", cellID, err)
	}

	testCode := ExtractFirstCodeBlock(testResponse)
	if testCode == "" {
		return fmt.Errorf("no code block found in test response for %s", cellID)
	}
	testCode = requestContinuation(ctx, agent, testCode, onChunk, cellID+"/test")

	// Write test file
	if err := writeClojureFile(testFile, testCode); err != nil {
		return fmt.Errorf("write test file %s: %w", testFile, err)
	}

	// Lint test file with clj-kondo
	if lintErrors := lintClojureFile(testFile); lintErrors != "" {
		onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "lint_error",
			Message: fmt.Sprintf("Test lint errors: %s", lintErrors)})
		lintResult := &bridge.EvalResult{Ex: "clj-kondo lint errors", Err: lintErrors}
		fixResult, fixErr := o.fixTestCode(ctx, agent, cellID, testCode, lintResult, testFile, br, session, onChunk)
		if fixErr != nil {
			onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "error",
				Message: fmt.Sprintf("Could not fix test lint errors: %v", fixErr)})
		} else {
			testCode = fixResult
		}
	}

	onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "written",
		Message: fmt.Sprintf("Test file written: %s", testFile)})

	// Turn 2: Ask cell agent to implement the cell
	onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "started",
		Message: fmt.Sprintf("Implementing %s", cellID)})

	implPrompt := fmt.Sprintf(`Good. Now implement the cell to pass those tests.

Write the source namespace `+"`%s`"+` containing the cell/defcell form.

CRITICAL CONTRACT — your code MUST satisfy ALL of these:
1. The cell ID MUST be EXACTLY %s — the first argument to cell/defcell
2. The schema MUST be %s
3. The handler takes two arguments: [resources data]

Example structure:
`+"```clojure"+`
(ns %s
  (:require [mycelium.cell :as cell]))

(cell/defcell %s
  {:doc "%s"
   :schema %s}
  (fn [resources data]
    ;; your implementation here
    ))
`+"```"+`

The namespace should:
- Require [mycelium.cell :as cell] and any other dependencies needed
- Contain the complete (cell/defcell %s ...) form with EXACTLY that cell ID
- Implement all the business logic described in the spec and tested in the test file

Return ONLY the complete source namespace code in a single code block.`,
		cellNs, cellID, brief.Schema, cellNs, cellID, brief.Doc, brief.Schema, cellID)

	implResponse, err := agent.session.SendStream(ctx, agent.client, implPrompt,
		func(chunk string) { onChunk(cellID+"/impl", chunk) })
	if err != nil {
		return fmt.Errorf("implementation for %s: %w", cellID, err)
	}

	implCode := ExtractFirstCodeBlock(implResponse)
	if implCode == "" {
		return fmt.Errorf("no code block found in implementation response for %s", cellID)
	}
	implCode = requestContinuation(ctx, agent, implCode, onChunk, cellID+"/impl")

	// Write source file
	if err := writeClojureFile(srcFile, implCode); err != nil {
		return fmt.Errorf("write source file %s: %w", srcFile, err)
	}

	// Lint with clj-kondo before loading in REPL
	if lintErrors := lintClojureFile(srcFile); lintErrors != "" {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "lint_error",
			Message: fmt.Sprintf("Lint errors: %s", lintErrors)})
		// Feed lint errors to LLM for fix before even trying REPL
		lintResult := &bridge.EvalResult{Ex: "clj-kondo lint errors", Err: lintErrors}
		fixResult, fixErr := o.fixCellCode(ctx, agent, cellID, implCode, lintResult, srcFile, br, session, onChunk)
		if fixErr != nil {
			onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
				Message: fmt.Sprintf("Could not fix lint errors: %v", fixErr)})
		} else {
			implCode = fixResult
		}
	}

	onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "written",
		Message: fmt.Sprintf("Source file written: %s", srcFile)})

	// Load implementation in REPL via load-file in this cell's session
	// Remove existing ns first to clear stale definitions from previous runs
	br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, cellNs, cellNs))
	evalResult, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))
	if err != nil {
		return fmt.Errorf("eval implementation %s: %w", cellID, err)
	}

	if evalResult.IsError() {
		// Try to fix with one iteration
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
			Message: fmt.Sprintf("Compilation error: %s", evalResult.Ex)})

		fixResult, fixErr := o.fixCellCode(ctx, agent, cellID, implCode, evalResult, srcFile, br, session, onChunk)
		if fixErr != nil {
			return fmt.Errorf("fix implementation %s: %w", cellID, fixErr)
		}
		implCode = fixResult
	}

	// Contract verification: ensure the cell registered under the expected ID.
	// This catches the common failure where the LLM uses a different cell ID
	// than what the manifest/decomposition tree expects.
	implCode, err = o.verifyCellContract(ctx, agent, br, session, cellID, brief, implCode, srcFile, onChunk, onEvent)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
			Message: fmt.Sprintf("Contract verification failed: %v", err)})
		// Continue to test phase anyway — tests will also catch the issue
	}

	// Load test file in REPL — remove stale ns first
	br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, testNs, testNs))
	testEvalResult, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, testFile))
	if err != nil {
		return fmt.Errorf("eval test %s: %w", cellID, err)
	}
	if testEvalResult.IsError() {
		onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "error",
			Message: fmt.Sprintf("Test compilation error: %s", testEvalResult.Ex)})

		fixResult, fixErr := o.fixTestCode(ctx, agent, cellID, testCode, testEvalResult, testFile, br, session, onChunk)
		if fixErr != nil {
			return fmt.Errorf("fix tests %s: %w", cellID, fixErr)
		}
		testCode = fixResult
	}

	// Run tests with fix cycle
	maxAttempts := 5
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "running",
			Message: fmt.Sprintf("Running tests (attempt %d/%d)", attempt, maxAttempts)})

		testOutput, testErr := runTestsInSession(br, session, srcFile, testFile, cellNs, testNs)
		if testErr != nil {
			onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "error",
				Message: fmt.Sprintf("Test run error: %v", testErr)})
			break
		}

		if isTestSuccess(testOutput) {
			onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "passed",
				Message: fmt.Sprintf("All tests passed for %s", cellID)})

			// Save to store
			_, saveErr := o.store.SaveCell(&store.Cell{
				ID:        cellID,
				Handler:   ExtractDefcell(implCode),
				Schema:    brief.Schema,
				Doc:       brief.Doc,
				Requires:  fmt.Sprintf("[%s]", strings.Join(brief.Requires, " ")),
				CreatedBy: "cell-agent-tdd",
			})
			if saveErr != nil {
				onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "warning",
					Message: fmt.Sprintf("Save to store failed: %v", saveErr)})
			}
			return nil
		}

		if attempt >= maxAttempts {
			onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "failed",
				Message: fmt.Sprintf("Tests still failing after %d attempts", maxAttempts)})
			break
		}

		// Ask cell agent to fix
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "fixing",
			Message: "Tests failed, asking for fix..."})

		fixPrompt := fmt.Sprintf(`The tests are failing. Test output:

`+"```\n%s\n```"+`

Here are the tests for reference:

`+"```clojure\n%s\n```"+`

Fix the cell implementation to pass all tests.
Return the COMPLETE updated source namespace in a single code block.
Do NOT include any explanation outside the code block.
CRITICAL: The cell ID MUST remain EXACTLY %s in the (cell/defcell ...) form.`, testOutput, testCode, cellID)

		fixResponse, fixErr := agent.session.SendStream(ctx, agent.client, fixPrompt,
			func(chunk string) { onChunk(cellID+"/fix", chunk) })
		if fixErr != nil {
			return fmt.Errorf("fix iteration %d for %s: %w", attempt, cellID, fixErr)
		}

		extracted := ExtractFirstCodeBlock(fixResponse)
		if extracted == "" {
			// No code block found — keep previous version and continue to next attempt
			continue
		}
		extracted = requestContinuation(ctx, agent, extracted, onChunk, cellID+"/fix")
		implCode = extracted
		if err := writeClojureFile(srcFile, implCode); err != nil {
			return fmt.Errorf("write fixed source %s: %w", srcFile, err)
		}

		evalResult, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))
		if err != nil {
			return fmt.Errorf("eval fixed implementation %s: %w", cellID, err)
		}
		if evalResult.IsError() {
			// Compilation error — try to fix immediately rather than wasting a test run
			onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
				Message: fmt.Sprintf("Fix compilation error: %s", evalResult.Ex)})

			fixResult, compFixErr := o.fixCellCode(ctx, agent, cellID, implCode, evalResult, srcFile, br, session, onChunk)
			if compFixErr != nil {
				onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
					Message: fmt.Sprintf("Could not fix compilation: %v", compFixErr)})
				// Continue to next attempt — runTests reload will show the error
			} else {
				implCode = fixResult
			}
		} else {
			// Load succeeded — verify contract
			implCode, _ = o.verifyCellContract(ctx, agent, br, session, cellID, brief, implCode, srcFile, onChunk, onEvent)
		}
	}

	return nil
}

// verifyCellContract checks that a cell registered in the REPL matches the expected contract
// from the decomposition tree. If the cell ID is wrong, it feeds a clear error to the LLM
// and retries. Returns the (possibly fixed) implementation code.
func (o *Orchestrator) verifyCellContract(ctx context.Context, agent *CellAgent, br *bridge.Bridge,
	session, cellID string, brief CellBrief, implCode, srcFile string,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) (string, error) {

	contract := bridge.CellContract{
		CellID:       cellID,
		InputSchema:  brief.Schema,
		OutputSchema: brief.Schema,
		Doc:          brief.Doc,
	}

	contractResult, contractErr := br.VerifyCellContract(contract)
	if contractErr != nil {
		return implCode, fmt.Errorf("contract check connection error: %w", contractErr)
	}

	if !contractResult.IsError() {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "contract_ok",
			Message: fmt.Sprintf("Cell %s registered correctly", cellID)})
		return implCode, nil
	}

	// Contract violation — the cell wasn't registered under the expected ID.
	// Feed a very explicit error to the LLM.
	onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "contract_violation",
		Message: fmt.Sprintf("Contract violation: %s", contractResult.Err)})

	contractFixResult := &bridge.EvalResult{
		Ex: "CONTRACT VIOLATION: Cell ID mismatch",
		Err: fmt.Sprintf(`The cell was NOT registered under the expected ID %s.

Your (cell/defcell ...) form MUST use EXACTLY %s as the first argument.

CORRECT:
  (cell/defcell %s
    {:doc "..." :schema {...}}
    (fn [resources data] ...))

WRONG (any other keyword):
  (cell/defcell :some-other-id ...)

Fix the cell ID and return the COMPLETE corrected namespace.`, cellID, cellID, cellID),
	}

	fixResult, fixErr := o.fixCellCode(ctx, agent, cellID, implCode, contractFixResult, srcFile, br, session, onChunk)
	if fixErr != nil {
		return implCode, fmt.Errorf("contract fix failed: %w", fixErr)
	}

	// Re-verify after fix
	contractResult2, _ := br.VerifyCellContract(contract)
	if contractResult2 != nil && !contractResult2.IsError() {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "contract_ok",
			Message: fmt.Sprintf("Cell %s registered correctly after fix", cellID)})
	} else {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "contract_violation",
			Message: fmt.Sprintf("Cell %s still not registered after contract fix", cellID)})
	}

	return fixResult, nil
}

// fixCellCode asks the cell agent to fix a compilation error and retries eval.
func (o *Orchestrator) fixCellCode(ctx context.Context, agent *CellAgent, cellID, code string,
	evalResult *bridge.EvalResult, filePath string, br *bridge.Bridge, session string,
	onChunk func(string, string)) (string, error) {

	errMsg := evalResult.Ex
	if evalResult.Err != "" {
		errMsg += "\n" + evalResult.Err
	}

	fixPrompt := fmt.Sprintf(`The implementation had a compilation error:

%s

Here is the code that failed:

`+"```clojure\n%s\n```"+`

Fix the issue and return the COMPLETE corrected namespace in a single code block.
Do NOT include any explanation outside the code block.
The code must start with (ns ...) and contain a (cell/defcell %s ...) form.
CRITICAL: The cell ID MUST be EXACTLY %s.`, errMsg, code, cellID, cellID)

	fixResponse, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
		func(chunk string) { onChunk(cellID+"/fix", chunk) })
	if err != nil {
		return "", err
	}

	fixed := ExtractFirstCodeBlock(fixResponse)
	if fixed == "" {
		return code, fmt.Errorf("no code block in fix response")
	}
	fixed = requestContinuation(ctx, agent, fixed, onChunk, cellID+"/fix")

	if err := writeClojureFile(filePath, fixed); err != nil {
		return "", err
	}

	result, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, filePath))
	if err != nil {
		return "", err
	}
	if result.IsError() {
		return fixed, fmt.Errorf("still failing after fix: %s", result.Ex)
	}

	return fixed, nil
}

// fixTestCode asks the cell agent to fix a test compilation error.
func (o *Orchestrator) fixTestCode(ctx context.Context, agent *CellAgent, cellID, code string,
	evalResult *bridge.EvalResult, filePath string, br *bridge.Bridge, session string,
	onChunk func(string, string)) (string, error) {

	errMsg := evalResult.Ex
	if evalResult.Err != "" {
		errMsg += "\n" + evalResult.Err
	}

	fixPrompt := fmt.Sprintf(`The test file had a compilation error:

%s

Here is the test code that failed:

`+"```clojure\n%s\n```"+`

Fix the test file and return the COMPLETE corrected test namespace in a single code block.
Do NOT include any explanation outside the code block.
The code must start with (ns ...) and contain deftest forms.`, errMsg, code)

	fixResponse, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
		func(chunk string) { onChunk(cellID+"/test-fix", chunk) })
	if err != nil {
		return "", err
	}

	fixed := ExtractFirstCodeBlock(fixResponse)
	if fixed == "" {
		return code, fmt.Errorf("no code block in test fix response")
	}
	fixed = requestContinuation(ctx, agent, fixed, onChunk, cellID+"/test-fix")

	if err := writeClojureFile(filePath, fixed); err != nil {
		return "", err
	}

	result, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, filePath))
	if err != nil {
		return "", err
	}
	if result.IsError() {
		return fixed, fmt.Errorf("test still failing after fix: %s", result.Ex)
	}

	return fixed, nil
}

// integrationTest asks the graph agent to write and run integration tests.
func (o *Orchestrator) integrationTest(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	manifest string,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) error {

	// First compile the workflow now that all cells are loaded
	onEvent(OrchestratorEvent{Phase: "integration", Status: "compiling",
		Message: "Compiling workflow manifest with loaded cells..."})

	compileResult, compileErr := br.CompileWorkflow(manifest, "")
	if compileErr != nil {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error",
			Message: fmt.Sprintf("Workflow compile connection error: %v", compileErr)})
	} else if compileResult.IsError() {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error",
			Message: fmt.Sprintf("Workflow compile error: %s %s", compileResult.Ex, compileResult.Err)})
	} else {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "compiled",
			Message: "Workflow compiled successfully"})
	}

	agent := o.manager.GetGraphAgent(cfg.ManifestID)

	prompt := fmt.Sprintf(`All cells have been implemented and loaded in the REPL.

Now write integration test code to test the full workflow end-to-end.

The manifest:
`+"```edn\n%s\n```"+`

Write Clojure code that:
1. Requires [mycelium.core :as myc]
2. Compiles the workflow: (def wf (myc/compile-workflow <manifest-edn>))
3. Sets up test resources and input data based on the spec
4. Runs the workflow: (myc/run-workflow wf resources input)
5. Prints the results clearly with (println (pr-str result))

IMPORTANT constraints:
- Do NOT use clojure.test or deftest — this is a script evaluated in the REPL, not a test namespace
- Do NOT define any function named run-tests
- Do NOT use any undefined helper functions — define everything you use
- Use simple (println ...) and (assert ...) for verification
- Keep it simple: 2-3 test scenarios maximum

Return the code in a single code block. I will evaluate it in the REPL.`, manifest)

	response, err := agent.ChatStream(ctx, prompt,
		func(chunk string) { onChunk("graph/integration", chunk) })
	if err != nil {
		return fmt.Errorf("integration test generation: %w", err)
	}

	testCode := ExtractFirstCodeBlock(response)

	// Eval integration tests
	result, err := br.Eval(testCode)
	if err != nil {
		return fmt.Errorf("eval integration tests: %w", err)
	}

	output := result.Value
	if result.Out != "" {
		output = result.Out + "\n" + output
	}

	if result.IsError() {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error",
			Message: fmt.Sprintf("Integration test error: %s\n%s", result.Ex, result.Err)})

		// Feed error back to graph agent for fixing
		fixPrompt := fmt.Sprintf("The integration test had this error:\n\n```\n%s\n%s\n```\n\nFix the test code and return the corrected version in a code block.",
			result.Ex, result.Err)

		fixResponse, fixErr := agent.ChatStream(ctx, fixPrompt,
			func(chunk string) { onChunk("graph/integration-fix", chunk) })
		if fixErr != nil {
			return fixErr
		}

		fixedCode := ExtractFirstCodeBlock(fixResponse)
		fixResult, fixEvalErr := br.Eval(fixedCode)
		if fixEvalErr != nil {
			return fixEvalErr
		}

		if fixResult.IsError() {
			return fmt.Errorf("integration tests still failing: %s", fixResult.Ex)
		}
		output = fixResult.Value
		if fixResult.Out != "" {
			output = fixResult.Out + "\n" + output
		}
	}

	onEvent(OrchestratorEvent{Phase: "integration", Status: "complete",
		Message: fmt.Sprintf("Integration test output:\n%s", output)})

	return nil
}

// --- Helpers ---

// clojureEscape escapes a string for embedding in a Clojure string literal.
func clojureEscape(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	s = strings.ReplaceAll(s, "\n", `\n`)
	s = strings.ReplaceAll(s, "\t", `\t`)
	return s
}

// parseCellNames parses REPL output like ["validate-items" "reserve-inventory"].
func parseCellNames(output string) []string {
	output = strings.TrimSpace(output)
	// Remove surrounding quotes if pr-str wrapped it
	if len(output) >= 2 && output[0] == '"' && output[len(output)-1] == '"' {
		output = output[1 : len(output)-1]
		output = strings.ReplaceAll(output, `\"`, `"`)
		output = strings.ReplaceAll(output, `\\`, `\`)
	}
	output = strings.Trim(output, "[]")
	parts := strings.Fields(output)
	var names []string
	for _, p := range parts {
		p = strings.Trim(p, `"`)
		if p != "" {
			names = append(names, p)
		}
	}
	return names
}

// parseCellSpec parses the output from extractCellSpec into a CellBrief.
func parseCellSpec(output, stepName string) CellBrief {
	brief := CellBrief{}
	// Output is a string like: "ID::order/validate-items\nDOC:...\nSCHEMA:...\nREQUIRES:..."
	// But it may be wrapped in quotes from pr-str
	output = strings.TrimSpace(output)
	if len(output) >= 2 && output[0] == '"' && output[len(output)-1] == '"' {
		output = output[1 : len(output)-1]
		output = strings.ReplaceAll(output, `\"`, `"`)
		output = strings.ReplaceAll(output, `\\`, `\`)
		output = strings.ReplaceAll(output, `\n`, "\n")
	}

	for _, line := range strings.Split(output, "\n") {
		if strings.HasPrefix(line, "ID:") {
			brief.ID = strings.TrimPrefix(line, "ID:")
		} else if strings.HasPrefix(line, "DOC:") {
			brief.Doc = strings.TrimPrefix(line, "DOC:")
		} else if strings.HasPrefix(line, "SCHEMA:") {
			brief.Schema = strings.TrimPrefix(line, "SCHEMA:")
		} else if strings.HasPrefix(line, "REQUIRES:") {
			reqStr := strings.TrimPrefix(line, "REQUIRES:")
			reqStr = strings.Trim(reqStr, "[]")
			for _, r := range strings.Fields(reqStr) {
				r = strings.Trim(r, `"`)
				if r != "" {
					brief.Requires = append(brief.Requires, r)
				}
			}
		}
	}

	return brief
}

// runTestsInSession runs clojure.test tests in a specific nREPL session.
// Uses load-file with absolute paths to avoid classpath dependency.
// Removes namespaces before reloading to clear stale test/fn definitions
// from previous fix iterations.
func runTestsInSession(br *bridge.Bridge, session, cellFile, testFile, cellNs, testNs string) (string, error) {
	code := fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))
(when (find-ns '%s) (remove-ns '%s))
(load-file "%s")
(load-file "%s")
(with-out-str (clojure.test/run-tests '%s))`, cellNs, cellNs, testNs, testNs, cellFile, testFile, testNs)

	result, err := br.EvalInSession(session, code)
	if err != nil {
		return "", err
	}
	if result.IsError() {
		return fmt.Sprintf("Error: %s\n%s", result.Ex, result.Err), nil
	}

	output := result.Value
	if result.Out != "" {
		output = result.Out + "\n" + output
	}
	return output, nil
}

// isTestSuccess checks if test output indicates all tests passed.
func isTestSuccess(output string) bool {
	return strings.Contains(output, "0 failures") && strings.Contains(output, "0 errors")
}

// clojureNsToFile converts a Clojure namespace to a file path.
// "example.order-lifecycle.cells.order" → <project>/<dir>/example/order_lifecycle/cells/order.clj
func clojureNsToFile(projectPath, dir, ns string) string {
	parts := strings.Split(ns, ".")
	path := filepath.Join(parts...)
	path = strings.ReplaceAll(path, "-", "_")
	return filepath.Join(projectPath, dir, path+".clj")
}

// writeClojureFile writes a Clojure source file, creating directories as needed.
func writeClojureFile(path, content string) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0755); err != nil {
		return fmt.Errorf("mkdir %s: %w", dir, err)
	}
	return os.WriteFile(path, []byte(content), 0644)
}

// lintClojureFile runs clj-kondo on a file and returns any errors.
// Returns empty string if the file is clean, or the error output otherwise.
// If clj-kondo is not available, returns empty string (lint is best-effort).
func lintClojureFile(path string) string {
	cmd := exec.Command("clj-kondo", "--lint", path)
	output, err := cmd.CombinedOutput()
	if err != nil {
		// Exit code != 0 means lint errors found
		result := string(output)
		// Filter to only error-level diagnostics (skip warnings and info lines)
		var errors []string
		for _, line := range strings.Split(result, "\n") {
			if strings.Contains(line, ": error:") {
				// Skip namespace-name-mismatch since we use load-file, not require
				if !strings.Contains(line, "Namespace name does not match file name") {
					errors = append(errors, line)
				}
			}
		}
		if len(errors) > 0 {
			return strings.Join(errors, "\n")
		}
	}
	return ""
}

// requestContinuation detects if extracted code is truncated (unbalanced parens)
// and requests the LLM to continue generating the rest of the code.
// Returns the complete code or the original if it was already complete.
func requestContinuation(ctx context.Context, agent *CellAgent, code string, onChunk func(string, string), chunkID string) string {
	if !IsTruncated(code) {
		return code
	}

	continuePrompt := `Your previous response was truncated — the code has unbalanced parentheses.
Continue EXACTLY from where you left off. Output ONLY the remaining code (no explanation, no code fences, no repeated code).
Start from the exact point the previous response ended.`

	contResponse, err := agent.session.SendStream(ctx, agent.client, continuePrompt,
		func(chunk string) { onChunk(chunkID, chunk) })
	if err != nil {
		return balanceParens(code) // fallback: force-balance
	}

	// Strip any fence markers from the continuation
	cont := stripFenceMarkers(strings.TrimSpace(contResponse))
	combined := code + "\n" + cont

	// If still unbalanced, force-balance
	return balanceParens(combined)
}

// cellIDToNsSuffix converts a cell ID keyword to a unique namespace suffix.
// Examples:
//
//	":order/validate-input" → "validate-input"
//	":order.validate-and-enrich-order/validate-input" → "validate-and-enrich-order.validate-input"
//
// This ensures cells in different sub-workflows with the same step name get unique namespaces.
func cellIDToNsSuffix(cellID string) string {
	id := strings.TrimPrefix(cellID, ":")
	// Replace "/" with "." to flatten the namespace/name separator
	id = strings.ReplaceAll(id, "/", ".")
	// Strip the root segment (first dot-separated part, e.g. "order")
	if idx := strings.Index(id, "."); idx >= 0 {
		id = id[idx+1:]
	}
	return id
}

// extractCellNamesRegex is a fallback that extracts cell step names from manifest text.
// Tries EDN parsing first, then falls back to regex patterns.
func extractCellNamesRegex(manifest string) []string {
	// Try EDN parsing first
	names, err := parseManifestCellNames(manifest)
	if err == nil && len(names) > 0 {
		return names
	}

	// Fallback: regex for `:step-name {:id :namespace/` patterns
	cellIDRe := regexp.MustCompile(`:(\w[\w-]*)\s*\{\s*:id\s+:`)
	matches := cellIDRe.FindAllStringSubmatch(manifest, -1)
	var result []string
	seen := map[string]bool{}
	for _, m := range matches {
		name := m[1]
		if name != "cells" && !seen[name] {
			seen[name] = true
			result = append(result, name)
		}
	}
	return result
}
