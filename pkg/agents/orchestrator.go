package agents

import (
	"context"
	"crypto/sha256"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/mycelium-clj/sporulator/pkg/bridge"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// mathPrecisionRules is injected into test and implementation prompts so
// that the LLM uses consistent precision handling derived from the schema.
const mathPrecisionRules = `NUMERICAL PRECISION — read the schema and spec carefully to decide precision for each field:
1. Examine each :double field in the schema. Determine from the spec whether it represents
   a value that needs rounding (e.g. currency, percentages) or should stay as raw :double.
   When rounding is needed, use bigdec with the appropriate scale and rounding mode:
   (.doubleValue (.setScale (bigdec x) <scale> java.math.RoundingMode/HALF_UP))
2. Examine each :int field. Determine from the spec whether the value is computed from
   floating-point math and needs truncation (floor/ceil) or is a direct count.
3. When the spec describes distributing a total across items:
   - round each item's share individually
   - compute the remainder (total minus sum-of-rounded-shares)
   - adjust the last item by the remainder so the sum is exact
4. Tests and implementation MUST agree on precision. Test expectations should be the
   rounded/truncated result, never the raw floating-point intermediate.
   Use a tolerance (e.g. 0.01) for approximate comparisons of rounded values.
5. Use bigdec for intermediate calculations to avoid floating-point accumulation errors.
`

// Orchestrator coordinates the full workflow: manifest design → cell TDD → integration testing.
type Orchestrator struct {
	manager        *Manager
	store          *store.Store
	runID          string // set at the start of each Run()
	cfg            ProjectConfig       // set at the start of each Run()
	onGraphReview  OnGraphReviewFunc   // set at the start of each Run(); nil = auto-approve
	onImplReview   OnImplReviewFunc    // set at the start of each Run(); nil = auto-approve
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
	MaxDepth         int    // max recursion depth; 0 = flat (one-level), negative = use default (3)
	AutoApproveTests bool   // when true, skip test approval gate (current behavior)
	AutoApproveGraph bool   // when true, skip graph approval gate
}

// OrchestratorEvent reports progress during orchestration.
type OrchestratorEvent struct {
	Phase   string `json:"phase"`   // "manifest", "cell_test", "cell_implement", "integration"
	CellID  string `json:"cell_id,omitempty"`
	Status  string `json:"status"`  // "started", "success", "error", "retry"
	Message string `json:"message"`
}

// TestContract holds generated tests for a cell, ready for approval.
type TestContract struct {
	CellID      string    `json:"cell_id"`
	Brief       CellBrief `json:"cell_brief"`
	TestCode    string    `json:"test_code"`    // assembled full test namespace
	TestBody    string    `json:"test_body"`     // raw deftest forms from LLM
	ReviewNotes string    `json:"review_notes"`  // LLM self-review output
	TestNs      string    `json:"test_ns"`
	CellNs      string    `json:"cell_ns"`
	TestFile    string    `json:"test_file"`
	SrcFile     string    `json:"src_file"`
	Session     string    `json:"-"` // nREPL session (preserved for impl phase)
	Agent       *CellAgent `json:"-"` // LLM session (preserved — has conversation context)
	Revision    int       `json:"revision"`
	Err         error     `json:"-"` // non-nil if generation failed
}

// ReviewResponse is a single user decision on a test contract.
type ReviewResponse struct {
	CellID     string `json:"cell_id"`
	Decision   string `json:"decision"`    // "approve", "edit", "revise", "skip"
	EditedCode string `json:"edited_code"` // user-modified test code (for "edit")
	Feedback   string `json:"feedback"`    // user notes (for "edit" or "revise")
}

// OnReviewFunc is the callback type for the test review gate.
// The orchestrator calls it with a batch of contracts and blocks until the user responds.
type OnReviewFunc func([]TestContract) ([]ReviewResponse, error)

// GraphReview presents a validated decomposition graph for user approval.
type GraphReview struct {
	Depth    int                  `json:"depth"`
	NsPrefix string              `json:"ns_prefix"`
	Steps    []GraphReviewStep   `json:"steps"`
	Edges    map[string]string   `json:"edges"`     // stepName → EDN edges
	Dispatches map[string]string `json:"dispatches"` // stepName → EDN dispatches
	Manifest string              `json:"manifest"`   // assembled EDN manifest for reference
}

// GraphReviewStep is a single step in the graph presented for review.
type GraphReviewStep struct {
	Name         string `json:"name"`
	Doc          string `json:"doc"`
	InputSchema  string `json:"input_schema"`
	OutputSchema string `json:"output_schema"`
	IsLeaf       bool   `json:"is_leaf"`
}

// GraphReviewResponse is the user's decision on a graph review.
type GraphReviewResponse struct {
	Decision string `json:"decision"` // "approve", "revise"
	Feedback string `json:"feedback"` // user notes for "revise"
}

// OnGraphReviewFunc is the callback type for the graph review gate.
type OnGraphReviewFunc func(GraphReview) (*GraphReviewResponse, error)

// NewOrchestrator creates an orchestrator.
func NewOrchestrator(mgr *Manager, st *store.Store) *Orchestrator {
	return &Orchestrator{manager: mgr, store: st}
}

// ImplReview presents a completed cell implementation for user approval.
type ImplReview struct {
	CellID   string `json:"cell_id"`
	ImplCode string `json:"impl_code"` // full cell namespace source
	TestCode string `json:"test_code"` // locked test code
	Brief    CellBrief `json:"brief"`  // doc + schema + context
}

// ImplReviewResponse is the user's decision on a cell implementation.
type ImplReviewResponse struct {
	CellID   string `json:"cell_id"`
	Decision string `json:"decision"` // "approve", "revise"
	Feedback string `json:"feedback"` // user notes for "revise"
}

// OnImplReviewFunc is the callback type for the implementation review gate.
// Called with a batch of completed implementations; blocks until user responds.
type OnImplReviewFunc func([]ImplReview) ([]ImplReviewResponse, error)

// ReviewCallbacks holds optional review gate callbacks for Run/RunResumable.
type ReviewCallbacks struct {
	OnTestReview  OnReviewFunc       // test contract review; nil = auto-approve
	OnImplReview  OnImplReviewFunc   // impl review; nil = auto-approve
	OnGraphReview OnGraphReviewFunc  // graph review; nil = auto-approve
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
	onEvent func(OrchestratorEvent),
	callbacks ...ReviewCallbacks) error {

	// Extract optional callbacks
	var reviewFn OnReviewFunc
	if len(callbacks) > 0 {
		reviewFn = callbacks[0].OnTestReview
		o.onGraphReview = callbacks[0].OnGraphReview
		o.onImplReview = callbacks[0].OnImplReview
	}
	o.cfg = cfg

	br := o.manager.GetBridge()
	if br == nil {
		return fmt.Errorf("no REPL bridge connected")
	}

	// Set up run tracking
	o.runID = fmt.Sprintf("run-%d", time.Now().UnixNano())
	specHash := fmt.Sprintf("%x", sha256.Sum256([]byte(cfg.Spec)))[:16]
	if o.store != nil {
		o.store.CreateOrchestrationRun(&store.OrchestrationRun{
			ID:         o.runID,
			SpecHash:   specHash,
			ManifestID: cfg.ManifestID,
			Status:     "running",
		})
	}

	// Phase 1: Decompose spec into tree + manifest
	onEvent(OrchestratorEvent{Phase: "manifest", Status: "started", Message: "Decomposing specification..."})

	manifest, tree, err := o.designManifest(ctx, cfg, br, onChunk, onEvent)
	if err != nil {
		if o.store != nil {
			o.store.UpdateRunStatus(o.runID, "failed")
		}
		return fmt.Errorf("manifest design: %w", err)
	}

	// Persist the decomposition tree
	if o.store != nil {
		treeJSON, _ := SerializeTree(tree)
		o.store.UpdateRunTree(o.runID, "running", treeJSON)
	}

	// Phase 1b: Validate entire graph schema consistency before implementation
	allMismatches := validateTreeSchemas(tree)
	if len(allMismatches) > 0 {
		onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "tree_check",
			Message: fmt.Sprintf("Found %d schema mismatches across full decomposition tree", len(allMismatches))})
		for _, m := range allMismatches {
			detail := fmt.Sprintf(":%s → :%s", m.SourceName, m.TargetName)
			if len(m.Missing) > 0 {
				detail += fmt.Sprintf(" missing=[%s]", strings.Join(m.Missing, ", "))
			}
			if len(m.TypeDiffs) > 0 {
				detail += fmt.Sprintf(" type_diffs=[%s]", strings.Join(m.TypeDiffs, "; "))
			}
			onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "mismatch", Message: detail})
		}
		if o.store != nil {
			o.store.UpdateRunStatus(o.runID, "failed")
		}
		return fmt.Errorf("graph schema validation failed: %d mismatches remain after decomposition", len(allMismatches))
	}
	onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "tree_passed",
		Message: "Full decomposition tree has consistent schemas across all edges"})

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

		// Phase 3: Implement all leaf cells with review gate
		cellErrors := o.implementLeavesWithReview(ctx, cfg, br, tree, leaves, onChunk, onEvent, reviewFn)

		// Phase 3b: Re-decompose failed leaves into sub-workflows
		dcfg := DecompositionConfig{
			MaxStepsPerLevel: cfg.MaxStepsPerLevel,
			MaxDepth:         cfg.MaxDepth,
		}
		if dcfg.MaxStepsPerLevel == 0 {
			dcfg.MaxStepsPerLevel = DefaultDecompositionConfig().MaxStepsPerLevel
		}
		if dcfg.MaxDepth < 0 {
			dcfg.MaxDepth = DefaultDecompositionConfig().MaxDepth
		}

		var failedLeaves []*DecompositionNode
		for i, e := range cellErrors {
			if e != nil {
				failedLeaves = append(failedLeaves, leaves[i])
			}
		}

		if len(failedLeaves) > 0 {
			onEvent(OrchestratorEvent{Phase: "redecompose", Status: "started",
				Message: fmt.Sprintf("Re-decomposing %d failed cells", len(failedLeaves))})

			// Allow re-decomposition one level beyond MaxDepth since this is a recovery step
			redecomposeMaxDepth := dcfg.MaxDepth + 1

			for _, failed := range failedLeaves {
				if failed.Depth+1 > redecomposeMaxDepth {
					onEvent(OrchestratorEvent{Phase: "redecompose", CellID: failed.CellID, Status: "skipped",
						Message: fmt.Sprintf("Skipping %s: already at max depth %d", failed.CellID, failed.Depth)})
					continue
				}

				onEvent(OrchestratorEvent{Phase: "redecompose", CellID: failed.CellID, Status: "decomposing",
					Message: fmt.Sprintf("Breaking down failed cell %s", failed.CellID)})

				// Scope re-decomposition to the failed cell's functionality.
				// The full spec is included as reference for business rules, but the prompt
				// is framed tightly around the cell's locked contract.
				subSpec := buildRedecomposeSpec(failed, cfg.Spec)

				subPrefix := strings.TrimPrefix(failed.CellID, ":")
				subPrefix = strings.ReplaceAll(subPrefix, "/", ".")

				// Use fewer steps for re-decomposition — these are focused recovery sub-workflows
				redecomposeSteps := dcfg.MaxStepsPerLevel
				if redecomposeSteps > 4 {
					redecomposeSteps = 4
				}
				redecomposeCfg := DecompositionConfig{
					MaxStepsPerLevel: redecomposeSteps,
					MaxDepth:         redecomposeMaxDepth,
				}
				subRoot, subErr := o.decompose(ctx, subSpec, subPrefix, nil, failed.Depth+1, redecomposeCfg, onChunk, onEvent)
				if subErr != nil {
					onEvent(OrchestratorEvent{Phase: "redecompose", CellID: failed.CellID, Status: "error",
						Message: fmt.Sprintf("Could not decompose %s: %v", failed.CellID, subErr)})
					continue
				}

				// Update the tree: this leaf becomes a sub-workflow
				failed.IsLeaf = false
				failed.Children = subRoot.Children
				failed.Manifest = subRoot.Manifest

				// Implement the new sub-leaves
				subLeaves := collectLeaves(failed)
				if len(subLeaves) > 0 {
					subLeafNames := make([]string, len(subLeaves))
					for j, sl := range subLeaves {
						subLeafNames[j] = sl.CellID
					}
					onEvent(OrchestratorEvent{Phase: "redecompose", CellID: failed.CellID, Status: "implementing",
						Message: fmt.Sprintf("Implementing %d sub-cells: %s", len(subLeaves), strings.Join(subLeafNames, ", "))})

					subErrors := o.implementLeavesWithReview(ctx, cfg, br, failed, subLeaves, onChunk, onEvent, reviewFn)
					subFails := 0
					for _, se := range subErrors {
						if se != nil {
							subFails++
						}
					}
					if subFails > 0 {
						onEvent(OrchestratorEvent{Phase: "redecompose", CellID: failed.CellID, Status: "partial",
							Message: fmt.Sprintf("%d/%d sub-cells still failing for %s", subFails, len(subLeaves), failed.CellID)})
					} else {
						onEvent(OrchestratorEvent{Phase: "redecompose", CellID: failed.CellID, Status: "success",
							Message: fmt.Sprintf("All sub-cells passed for %s", failed.CellID)})
					}
				}
			}
		}

		failCount := 0
		for _, e := range cellErrors {
			if e != nil {
				failCount++
			}
		}
		if failCount > 0 {
			onEvent(OrchestratorEvent{Phase: "cells", Status: "partial",
				Message: fmt.Sprintf("%d/%d leaf cells had errors (some may have been re-decomposed)", failCount, len(leaves))})
		}

		// Phase 4: Register sub-workflows bottom-up (includes any newly created sub-workflows)
		subWorkflows := collectSubWorkflows(tree)
		if len(subWorkflows) > 0 {
			o.registerSubWorkflows(ctx, br, subWorkflows, onEvent)
		}
	}

	// Phase 5: Integration testing
	onEvent(OrchestratorEvent{Phase: "integration", Status: "started", Message: "Running integration tests..."})

	err = o.integrationTest(ctx, cfg, br, manifest, tree, onChunk, onEvent)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error", Message: err.Error()})
	}

	// Mark run as completed
	if o.store != nil {
		o.store.UpdateRunStatus(o.runID, "completed")
	}

	onEvent(OrchestratorEvent{Phase: "complete", Status: "done", Message: "Orchestration complete"})
	return nil
}

// RunResumable resumes a previous orchestration run if one exists with matching spec hash.
// Passing cells are reloaded from the store; only failed cells are re-implemented.
// If no matching run exists, falls through to a fresh Run().
func (o *Orchestrator) RunResumable(ctx context.Context, cfg ProjectConfig,
	onChunk func(source string, chunk string),
	onEvent func(OrchestratorEvent),
	callbacks ...ReviewCallbacks) error {

	if o.store == nil {
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	br := o.manager.GetBridge()
	if br == nil {
		return fmt.Errorf("no REPL bridge connected")
	}

	// Hash the spec to identify matching runs
	specHash := fmt.Sprintf("%x", sha256.Sum256([]byte(cfg.Spec)))[:16]

	// Look for a previous run with the same manifest and spec hash
	prevRun, err := o.store.GetLatestRunForManifest(cfg.ManifestID)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "warning",
			Message: fmt.Sprintf("Could not check for previous run: %v", err)})
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	if prevRun == nil || prevRun.SpecHash != specHash {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "fresh",
			Message: "No matching previous run found, starting fresh"})
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	onEvent(OrchestratorEvent{Phase: "resume", Status: "found",
		Message: fmt.Sprintf("Found previous run %s (status: %s)", prevRun.ID, prevRun.Status)})

	// Deserialize the tree from the previous run
	if prevRun.TreeJSON == "" {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "warning",
			Message: "Previous run has no tree data, starting fresh"})
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	tree, err := DeserializeTree(prevRun.TreeJSON)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "warning",
			Message: fmt.Sprintf("Could not deserialize tree: %v, starting fresh", err)})
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	// Get the summary of which cells passed
	summary, err := o.store.GetRunSummary(prevRun.ID)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "warning",
			Message: fmt.Sprintf("Could not get run summary: %v, starting fresh", err)})
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	// Create a new run record for this resumption
	o.runID = fmt.Sprintf("run-%d", time.Now().UnixNano())
	o.store.CreateOrchestrationRun(&store.OrchestrationRun{
		ID:         o.runID,
		SpecHash:   specHash,
		ManifestID: cfg.ManifestID,
		Status:     "running",
		TreeJSON:   prevRun.TreeJSON,
	})

	// Load the manifest from the tree
	manifest := tree.Manifest

	// Collect leaves
	leaves := collectLeaves(tree)
	if len(leaves) == 0 {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "error",
			Message: "No leaves in deserialized tree"})
		return o.Run(ctx, cfg, onChunk, onEvent, callbacks...)
	}

	// Separate passed and failed leaves
	var passedLeaves, failedLeaves []*DecompositionNode
	for _, leaf := range leaves {
		if summary[leaf.CellID] {
			passedLeaves = append(passedLeaves, leaf)
		} else {
			failedLeaves = append(failedLeaves, leaf)
		}
	}

	onEvent(OrchestratorEvent{Phase: "resume", Status: "summary",
		Message: fmt.Sprintf("Previous run: %d passed, %d failed — re-implementing failed cells only",
			len(passedLeaves), len(failedLeaves))})

	// Reload passing cells from store into the REPL
	for _, leaf := range passedLeaves {
		onEvent(OrchestratorEvent{Phase: "resume", CellID: leaf.CellID, Status: "reloading",
			Message: fmt.Sprintf("Reloading passing cell %s from store", leaf.CellID)})

		result, err := br.InstantiateCellFromStore(leaf.CellID)
		if err != nil {
			onEvent(OrchestratorEvent{Phase: "resume", CellID: leaf.CellID, Status: "warning",
				Message: fmt.Sprintf("Could not reload %s: %v, will re-implement", leaf.CellID, err)})
			failedLeaves = append(failedLeaves, leaf)
			continue
		}
		if result.IsError() {
			onEvent(OrchestratorEvent{Phase: "resume", CellID: leaf.CellID, Status: "warning",
				Message: fmt.Sprintf("Reload error for %s: %s, will re-implement", leaf.CellID, result.Ex)})
			failedLeaves = append(failedLeaves, leaf)
			continue
		}

		onEvent(OrchestratorEvent{Phase: "resume", CellID: leaf.CellID, Status: "reloaded",
			Message: fmt.Sprintf("Cell %s reloaded from store", leaf.CellID)})
	}

	if len(failedLeaves) == 0 {
		onEvent(OrchestratorEvent{Phase: "resume", Status: "all_passed",
			Message: "All cells already passing, skipping to integration"})
	} else {
		// Implement only failed cells
		leafNames := make([]string, len(failedLeaves))
		for i, l := range failedLeaves {
			leafNames[i] = l.CellID
		}
		onEvent(OrchestratorEvent{Phase: "resume", Status: "implementing",
			Message: fmt.Sprintf("Re-implementing %d cells: %s", len(failedLeaves), strings.Join(leafNames, ", "))})

		// Extract optional onReview callback for resume path
		var resumeReviewFn OnReviewFunc
		if len(callbacks) > 0 {
			resumeReviewFn = callbacks[0].OnTestReview
			o.onGraphReview = callbacks[0].OnGraphReview
		}
		o.cfg = cfg

		cellErrors := o.implementLeavesWithReview(ctx, cfg, br, tree, failedLeaves, onChunk, onEvent, resumeReviewFn)

		failCount := 0
		for _, e := range cellErrors {
			if e != nil {
				failCount++
			}
		}
		if failCount > 0 {
			onEvent(OrchestratorEvent{Phase: "resume", Status: "partial",
				Message: fmt.Sprintf("%d/%d re-implemented cells had errors", failCount, len(failedLeaves))})
		}
	}

	// Register sub-workflows
	subWorkflows := collectSubWorkflows(tree)
	if len(subWorkflows) > 0 {
		o.registerSubWorkflows(ctx, br, subWorkflows, onEvent)
	}

	// Integration testing
	onEvent(OrchestratorEvent{Phase: "integration", Status: "started", Message: "Running integration tests..."})
	err = o.integrationTest(ctx, cfg, br, manifest, tree, onChunk, onEvent)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error", Message: err.Error()})
	}

	// Update run status
	o.store.UpdateRunStatus(o.runID, "completed")

	onEvent(OrchestratorEvent{Phase: "complete", Status: "done", Message: "Resumed orchestration complete"})
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
	if dcfg.MaxDepth < 0 {
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

// implementLeavesWithReview implements all leaf cells with an optional test-review gate.
// Phase 0: register cell schemas and validate with Malli
// Phase A: generate test contracts in parallel
// Phase B: review gate (loops until all approved/skipped) — skipped if onReview is nil or AutoApproveTests
// Phase C: implement approved contracts in parallel
func (o *Orchestrator) implementLeavesWithReview(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	tree *DecompositionNode, leaves []*DecompositionNode,
	onChunk func(string, string), onEvent func(OrchestratorEvent),
	onReview OnReviewFunc) []error {

	rootManifest := tree.Manifest

	// Phase 0: Register cell schemas and validate with Malli
	// This catches invalid schemas before test generation, saving time.
	onEvent(OrchestratorEvent{Phase: "schema_register", Status: "started",
		Message: fmt.Sprintf("Registering schemas for %d cells", len(leaves))})

	for _, leaf := range leaves {
		cellNs := cfg.BaseNamespace + ".cells." + cellIDToNsSuffix(leaf.CellID)
		inputSchema := defaultSchema(leaf.InputSchema)
		outputSchema := defaultSchema(leaf.OutputSchema)
		schema := fmt.Sprintf("{:input %s :output %s}", inputSchema, outputSchema)

		// Write a stub cell file that registers the schema with a passthrough handler
		stubCode := assembleStubCellSource(cellNs, leaf.CellID, leaf.Doc, schema, leaf.Requires)
		srcFile := clojureNsToFile(cfg.ProjectPath, cfg.SourceDir, cellNs)
		writeClojureFile(srcFile, stubCode)

		// Load in REPL to validate the schema
		session, _ := br.CloneSession()
		evalResult, evalErr := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))

		if evalErr != nil || (evalResult != nil && evalResult.IsError()) {
			errMsg := ""
			if evalResult != nil {
				errMsg = evalResult.Ex + " " + evalResult.Err
			}
			onEvent(OrchestratorEvent{Phase: "schema_register", CellID: leaf.CellID, Status: "invalid",
				Message: fmt.Sprintf("Schema invalid for %s: %s", leaf.CellID, truncMsg(errMsg, 200))})

			// Try to fix the schema: ask the LLM to produce valid Malli
			fixedSchema := o.fixCellSchema(ctx, leaf, schema, errMsg, onChunk, onEvent)
			if fixedSchema != "" {
				leaf.InputSchema = extractSubSchema(fixedSchema, "input")
				leaf.OutputSchema = extractSubSchema(fixedSchema, "output")
				schema = fixedSchema

				stubCode = assembleStubCellSource(cellNs, leaf.CellID, leaf.Doc, schema, leaf.Requires)
				writeClojureFile(srcFile, stubCode)
				evalResult2, _ := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))
				if evalResult2 != nil && !evalResult2.IsError() {
					onEvent(OrchestratorEvent{Phase: "schema_register", CellID: leaf.CellID, Status: "fixed",
						Message: fmt.Sprintf("Schema fixed and registered for %s", leaf.CellID)})
				}
			}
		} else {
			onEvent(OrchestratorEvent{Phase: "schema_register", CellID: leaf.CellID, Status: "registered",
				Message: fmt.Sprintf("Schema valid for %s", leaf.CellID)})
		}
		br.CloseSession(session)
	}

	// Phase A: generate test contracts in parallel
	contracts := make([]*TestContract, len(leaves))
	errors := make([]error, len(leaves))
	var wg sync.WaitGroup

	for i, leaf := range leaves {
		wg.Add(1)
		go func(idx int, leaf *DecompositionNode) {
			defer wg.Done()

			brief := CellBrief{
				ID:       leaf.CellID,
				Doc:      leaf.Doc,
				Schema:   fmt.Sprintf("{:input %s :output %s}", defaultSchema(leaf.InputSchema), defaultSchema(leaf.OutputSchema)),
				Requires: leaf.Requires,
			}

			// Enrich brief with graph context (predecessors/successors)
			if parent := findParent(tree, leaf.StepName); parent != nil {
				graphCtx := buildGraphContext(parent, leaf.StepName)
				brief.Context = formatGraphContext(graphCtx)
			}

			session, err := br.CloneSession()
			if err != nil {
				errors[idx] = fmt.Errorf("clone session for %s: %w", leaf.CellID, err)
				return
			}

			contract, genErr := o.generateTestContract(ctx, cfg, br, session, rootManifest, brief, onChunk, onEvent)
			if genErr != nil {
				br.CloseSession(session)
				errors[idx] = genErr
				return
			}
			contracts[idx] = contract
		}(i, leaf)
	}
	wg.Wait()

	// Phase B: review gate
	if onReview != nil && !cfg.AutoApproveTests {
		// Collect valid contracts that need review
		var pending []*TestContract
		for _, c := range contracts {
			if c != nil && c.Err == nil {
				pending = append(pending, c)
			}
		}

		for len(pending) > 0 {
			// Build slice of TestContracts to present
			presentable := make([]TestContract, len(pending))
			for i, c := range pending {
				presentable[i] = *c
			}

			onEvent(OrchestratorEvent{Phase: "test_review", Status: "awaiting_review",
				Message: fmt.Sprintf("Review test contracts for %d cells", len(pending))})

			responses, err := onReview(presentable)
			if err != nil {
				onEvent(OrchestratorEvent{Phase: "test_review", Status: "error",
					Message: fmt.Sprintf("Review error: %v — auto-approving all", err)})
				break // auto-approve on error
			}

			// Build lookup
			respMap := make(map[string]*ReviewResponse)
			for i := range responses {
				respMap[responses[i].CellID] = &responses[i]
			}

			var stillPending []*TestContract
			for _, c := range pending {
				resp, ok := respMap[c.CellID]
				if !ok {
					// No response for this cell — keep pending
					stillPending = append(stillPending, c)
					continue
				}

				switch resp.Decision {
				case "approve":
					onEvent(OrchestratorEvent{Phase: "test_review", CellID: c.CellID, Status: "approved",
						Message: fmt.Sprintf("Tests approved for %s", c.CellID)})
					// Persist to DB
					if o.store != nil {
						o.store.SaveTestContract(&store.TestContractRecord{
							RunID: o.runID, CellID: c.CellID,
							TestCode: c.TestCode, TestBody: c.TestBody,
							ReviewNotes: c.ReviewNotes, Status: "approved",
							Revision: c.Revision,
						})
					}

				case "skip":
					onEvent(OrchestratorEvent{Phase: "test_review", CellID: c.CellID, Status: "skipped",
						Message: fmt.Sprintf("Cell %s skipped", c.CellID)})
					c.Err = fmt.Errorf("skipped by user")
					if o.store != nil {
						o.store.SaveTestContract(&store.TestContractRecord{
							RunID: o.runID, CellID: c.CellID,
							TestCode: c.TestCode, TestBody: c.TestBody,
							Status: "skipped", Revision: c.Revision,
						})
					}

				case "edit":
					// User modified test code directly
					onEvent(OrchestratorEvent{Phase: "test_review", CellID: c.CellID, Status: "editing",
						Message: fmt.Sprintf("Processing user edits for %s", c.CellID)})

					c.Revision++
					editPrompt := fmt.Sprintf(`The user edited the test code directly and provided these notes: %s

Here is their updated code:
`+"```clojure\n%s\n```"+`

Review these tests for correctness — check that they compile, schemas match, and arithmetic is right.
If you find issues, fix them and return corrected deftest forms in a code block.
Otherwise say ALL TESTS VERIFIED.`, resp.Feedback, resp.EditedCode)

					reviewResp, reviewErr := c.Agent.session.SendStream(ctx, c.Agent.client, editPrompt,
						func(chunk string) { onChunk(c.CellID+"/review", chunk) })
					if reviewErr != nil {
						// Use user's edits as-is
						c.TestBody = resp.EditedCode
					} else {
						corrected := extractSelfReviewCorrections(reviewResp)
						if corrected != "" {
							c.TestBody = corrected
						} else {
							c.TestBody = resp.EditedCode
						}
						c.ReviewNotes = reviewResp
					}

					// Reassemble and rewrite test file
					c.TestCode = assembleTestSource(c.TestNs, c.CellNs, c.CellID, c.TestBody)
					writeClojureFile(c.TestFile, c.TestCode)

					// Re-present for next round
					stillPending = append(stillPending, c)

				case "revise":
					// User provided notes, LLM regenerates
					onEvent(OrchestratorEvent{Phase: "test_review", CellID: c.CellID, Status: "revising",
						Message: fmt.Sprintf("Regenerating tests for %s with feedback", c.CellID)})

					c.Revision++
					revisePrompt := fmt.Sprintf(`The user reviewed your tests and wants these changes: %s

Regenerate the deftest forms incorporating this feedback.
Return ONLY deftest forms in a single code block.`, resp.Feedback)

					reviseResp, reviseErr := c.Agent.session.SendStream(ctx, c.Agent.client, revisePrompt,
						func(chunk string) { onChunk(c.CellID+"/revise", chunk) })
					if reviseErr != nil {
						stillPending = append(stillPending, c)
						continue
					}

					newBody := ExtractFirstCodeBlock(reviseResp)
					if newBody == "" {
						stillPending = append(stillPending, c)
						continue
					}
					newBody = requestContinuation(ctx, c.Agent, newBody, onChunk, c.CellID+"/revise")
					c.TestBody = newBody

					// Self-review the regenerated tests
					reviewNotes := o.selfReviewTests(ctx, c, onChunk)
					c.ReviewNotes = reviewNotes

					// Reassemble
					c.TestCode = assembleTestSource(c.TestNs, c.CellNs, c.CellID, c.TestBody)
					writeClojureFile(c.TestFile, c.TestCode)

					stillPending = append(stillPending, c)
				}
			}
			pending = stillPending
		}
	} else {
		// Auto-approve: persist all contracts
		for _, c := range contracts {
			if c != nil && c.Err == nil && o.store != nil {
				o.store.SaveTestContract(&store.TestContractRecord{
					RunID: o.runID, CellID: c.CellID,
					TestCode: c.TestCode, TestBody: c.TestBody,
					ReviewNotes: c.ReviewNotes, Status: "approved",
					Revision: c.Revision,
				})
			}
		}
	}

	// Phase C: implement approved contracts in parallel
	// First, refresh any stale nREPL sessions (can happen after long review gates)
	for _, c := range contracts {
		if c == nil || c.Err != nil {
			continue
		}
		if newSession, refreshed, err := refreshSession(br, c.Session); err != nil {
			c.Err = fmt.Errorf("session refresh failed for %s: %w", c.CellID, err)
			onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: c.CellID, Status: "error",
				Message: fmt.Sprintf("Failed to refresh session for %s, skipping: %v", c.CellID, err)})
		} else if refreshed {
			c.Session = newSession
			onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: c.CellID, Status: "info",
				Message: fmt.Sprintf("Refreshed stale nREPL session for %s", c.CellID)})
		}
	}

	var wg2 sync.WaitGroup
	for i, c := range contracts {
		if c == nil || c.Err != nil {
			if c != nil && c.Err != nil {
				errors[i] = c.Err
			}
			continue
		}
		wg2.Add(1)
		go func(idx int, contract *TestContract) {
			defer wg2.Done()
			defer br.CloseSession(contract.Session)
			errors[idx] = o.implementFromContract(ctx, cfg, br, contract, onChunk, onEvent)
		}(i, c)
	}
	wg2.Wait()

	// Phase D: Implementation review gate
	// Let the user review and approve/revise completed implementations.
	if o.onImplReview != nil {
		var reviews []ImplReview
		var reviewIndices []int // map review index → contracts index
		for i, c := range contracts {
			if errors[i] != nil || c == nil || c.Err != nil {
				continue
			}
			srcFile := c.SrcFile
			implCode := ""
			if data, err := os.ReadFile(srcFile); err == nil {
				implCode = string(data)
			}
			reviews = append(reviews, ImplReview{
				CellID:   c.CellID,
				ImplCode: implCode,
				TestCode: c.TestCode,
				Brief:    c.Brief,
			})
			reviewIndices = append(reviewIndices, i)
		}

		if len(reviews) > 0 {
			onEvent(OrchestratorEvent{Phase: "impl_review", Status: "awaiting_review",
				Message: fmt.Sprintf("Review implementations for %d cells", len(reviews))})

			responses, reviewErr := o.onImplReview(reviews)
			if reviewErr != nil {
				onEvent(OrchestratorEvent{Phase: "impl_review", Status: "error",
					Message: fmt.Sprintf("Implementation review error: %v", reviewErr)})
			} else {
				for _, resp := range responses {
					if resp.Decision == "approve" {
						onEvent(OrchestratorEvent{Phase: "impl_review", CellID: resp.CellID, Status: "approved",
							Message: fmt.Sprintf("Implementation approved for %s", resp.CellID)})
					} else if resp.Decision == "revise" {
						onEvent(OrchestratorEvent{Phase: "impl_review", CellID: resp.CellID, Status: "revising",
							Message: fmt.Sprintf("Revising implementation for %s", resp.CellID)})

						// Find the contract and re-implement with user feedback
						for j, review := range reviews {
							if review.CellID != resp.CellID {
								continue
							}
							idx := reviewIndices[j]
							c := contracts[idx]
							agent := o.manager.NewCellAgent(c.CellID)
							session, _ := br.CloneSession()

							revisePrompt := fmt.Sprintf(`The user reviewed your implementation and wants changes.

User feedback: %s

Current implementation:
`+"```clojure\n%s\n```"+`

Cell requirements: %s
Schema: %s

Fix the implementation based on the feedback. Return ONLY:
1. (OPTIONAL) Helper functions
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

Do NOT include (ns ...) or (cell/defcell ...).`,
								resp.Feedback, review.ImplCode, c.Brief.Doc, c.Brief.Schema)

							fixResp, err := agent.session.SendStream(ctx, agent.client, revisePrompt,
								func(chunk string) { onChunk(c.CellID+"/impl-revise", chunk) })
							if err != nil {
								errors[idx] = fmt.Errorf("impl revision for %s: %w", c.CellID, err)
								break
							}

							rawImpl := ExtractFirstCodeBlock(fixResp)
							if rawImpl == "" {
								break
							}

							fnBody := ExtractFnBody(rawImpl)
							if fnBody == "" {
								fnBody = rawImpl
							}
							helpers := ExtractHelpers(rawImpl)
							extraReqs := ExtractExtraRequires(rawImpl)
							implCode := assembleCellSource(c.CellNs, c.CellID, c.Brief.Doc, c.Brief.Schema,
								c.Brief.Requires, extraReqs, helpers, fnBody)

							writeClojureFile(c.SrcFile, implCode)

							// Reload and re-test
							br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, c.CellNs, c.CellNs))
							br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, c.SrcFile))
							br.CloseSession(session)

							onEvent(OrchestratorEvent{Phase: "impl_review", CellID: c.CellID, Status: "revised",
								Message: fmt.Sprintf("Implementation revised for %s", c.CellID)})
							break
						}
					}
				}
			}
		}
	}

	return errors
}

// implementLeavesParallel is a backward-compatible wrapper that auto-approves all tests.
func (o *Orchestrator) implementLeavesParallel(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	tree *DecompositionNode, leaves []*DecompositionNode,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) []error {
	autoCfg := cfg
	autoCfg.AutoApproveTests = true
	return o.implementLeavesWithReview(ctx, autoCfg, br, tree, leaves, onChunk, onEvent, nil)
}

// generateTestContract generates tests for a single cell with LLM self-review.
// Returns a TestContract holding the test code, LLM session, and nREPL session
// (both preserved for the implementation phase).
func (o *Orchestrator) generateTestContract(ctx context.Context, cfg ProjectConfig,
	br *bridge.Bridge, session, manifest string, brief CellBrief,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) (*TestContract, error) {

	cellID := brief.ID
	if cellID == "" {
		return nil, fmt.Errorf("CellBrief has empty ID")
	}

	agent := o.manager.NewCellAgent(cellID)

	// Compute namespaces and file paths
	nsSuffix := cellIDToNsSuffix(cellID)
	cellNs := cfg.BaseNamespace + ".cells." + nsSuffix
	testNs := cellNs + "-test"
	srcFile := clojureNsToFile(cfg.ProjectPath, cfg.SourceDir, cellNs)
	testFile := clojureNsToFile(cfg.ProjectPath, cfg.TestDir, testNs)

	onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "started",
		Message: fmt.Sprintf("Writing tests for %s", cellID)})

	// Ask cell agent to write tests
	testPrompt := fmt.Sprintf(`You are implementing cell %s using TDD (test-driven development).

**Step 1: Write the test assertions first.**

Cell details:
- **Cell ID:** %s
- **Implementation Requirements:** %s
- **Schema:** %s
- **Requires:** %s

%s

IMPORTANT: The implementation requirements above are the SINGLE SOURCE OF TRUTH for this cell.
When multiple rules/conditions can apply to the same input, they ALL apply in the documented
order. Test scenarios MUST account for this — if a test triggers rule A AND rule B, the expected
values must reflect BOTH rules applied sequentially, not just one in isolation.

Here is the workflow manifest for context (shows how this cell fits in the graph):
`+"```edn\n%s\n```"+`

The test namespace already has these set up for you:
- handler bound via: (def handler (:handler (cell/get-cell! %s)))
- approx= helper: (defn approx= [x y tolerance] (< (Math/abs (- (double x) (double y))) tolerance))
- validate-output helper: (defn validate-output [result]) — validates result against declared output schema
- clojure.test is required with [deftest is testing]
- malli.core is available as m

Write ONLY deftest forms. Do NOT include:
- (ns ...) declaration
- require forms
- handler binding
- helper function definitions (approx= and validate-output are already available)

Call the handler as: (handler resources data)
Call (validate-output result) in EVERY test to verify schema compliance.

Example:
`+"```clojure"+`
(deftest test-basic-case
  (let [resources {}
        data {:amount 100.0}
        result (handler resources data)]
    (validate-output result)
    (is (approx= (:total result) 110.0 0.01))))
`+"```"+`

Return ONLY deftest forms in a single code block.`,
		cellID, cellID, brief.Doc, brief.Schema,
		strings.Join(brief.Requires, ", "), mathPrecisionRules, manifest, cellID)

	testResponse, err := agent.session.SendStream(ctx, agent.client, testPrompt,
		func(chunk string) { onChunk(cellID+"/test", chunk) })
	if err != nil {
		return nil, fmt.Errorf("test generation for %s: %w", cellID, err)
	}

	testBody := ExtractFirstCodeBlock(testResponse)
	if testBody == "" {
		return nil, fmt.Errorf("no code block found in test response for %s", cellID)
	}
	testBody = requestContinuation(ctx, agent, testBody, onChunk, cellID+"/test")

	// Assemble test code
	var testCode string
	if strings.Contains(testBody, "(ns ") {
		testCode = testBody
	} else {
		testCode = assembleTestSource(testNs, cellNs, cellID, testBody)
	}

	// Write test file
	if err := writeClojureFile(testFile, testCode); err != nil {
		return nil, fmt.Errorf("write test file %s: %w", testFile, err)
	}

	// Lint fix loop
	testCode, _ = o.lintFixLoop(ctx, agent, cellID, testCode, testFile, 3, onChunk, onEvent)

	// Self-review: ask LLM to verify its own test expectations
	contract := &TestContract{
		CellID:   cellID,
		Brief:    brief,
		TestCode: testCode,
		TestBody: testBody,
		TestNs:   testNs,
		CellNs:   cellNs,
		TestFile: testFile,
		SrcFile:  srcFile,
		Session:  session,
		Agent:    agent,
	}

	reviewNotes := o.selfReviewTests(ctx, contract, onChunk)
	contract.ReviewNotes = reviewNotes

	// Persist pending contract to DB
	if o.store != nil {
		o.store.SaveTestContract(&store.TestContractRecord{
			RunID:       o.runID,
			CellID:      cellID,
			TestCode:    contract.TestCode,
			TestBody:    contract.TestBody,
			ReviewNotes: contract.ReviewNotes,
			Status:      "pending",
			Revision:    0,
		})
	}

	onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "written",
		Message: fmt.Sprintf("Test file written: %s", testFile)})

	return contract, nil
}

// selfReviewTests sends the self-review prompt to the LLM in the same session
// and applies any corrections it finds. Corrections are merged into the existing
// tests by replacing only the deftest forms that were corrected, preserving all
// tests the review marked as CORRECT.
func (o *Orchestrator) selfReviewTests(ctx context.Context, c *TestContract,
	onChunk func(string, string)) string {

	reviewPrompt := fmt.Sprintf(`You just wrote tests for cell %s. Review them critically
against the implementation requirements below.

## Implementation Requirements (the source of truth for this cell):
%s

## Schema:
%s

## Review checklist — for EACH test case:
1. Does the test scenario match what the requirements describe? Are all relevant
   rules/conditions being tested (including how they compose/stack with each other)?
2. Derive each expected numeric value step-by-step. Show your arithmetic.
   If multiple rules apply to the same data, apply them ALL in the documented order.
3. Do the test inputs match the declared input schema? Do expected outputs match
   the output schema?

For each test:
- CORRECT: [name] — [reasoning]
- WRONG: [name] — [error] — [corrected value with derivation]

If any are WRONG, return ONLY the corrected deftest forms in a code block.
Do NOT include tests that are already correct — only the ones you are fixing.
If all CORRECT, say "ALL TESTS VERIFIED" (no code block).`,
		c.CellID, c.Brief.Doc, c.Brief.Schema)

	reviewResp, err := c.Agent.session.SendStream(ctx, c.Agent.client, reviewPrompt,
		func(chunk string) { onChunk(c.CellID+"/self-review", chunk) })
	if err != nil {
		return "self-review failed: " + err.Error()
	}

	// Check if corrections were provided
	corrected := extractSelfReviewCorrections(reviewResp)
	if corrected != "" {
		// Merge corrections into existing tests instead of replacing all
		c.TestBody = mergeTestCorrections(c.TestBody, corrected)
		c.TestCode = assembleTestSource(c.TestNs, c.CellNs, c.CellID, c.TestBody)
		writeClojureFile(c.TestFile, c.TestCode)
	}

	return reviewResp
}

// extractSelfReviewCorrections extracts corrected deftest forms from a self-review response.
// Returns empty string if the response indicates all tests are correct ("ALL TESTS VERIFIED").
func extractSelfReviewCorrections(response string) string {
	if strings.Contains(response, "ALL TESTS VERIFIED") {
		return ""
	}
	code := ExtractFirstCodeBlock(response)
	if code == "" {
		return ""
	}
	// Only return if it looks like deftest forms
	if strings.Contains(code, "deftest") {
		return code
	}
	return ""
}

// mergeTestCorrections merges corrected deftest forms into the original test body.
// For each corrected test, if a test with the same name exists in the original, it is
// replaced. If no match is found, the corrected test is appended. Tests in the original
// that were not corrected are preserved as-is.
func mergeTestCorrections(original, corrections string) string {
	originalTests := splitDeftests(original)
	correctedTests := splitDeftests(corrections)

	if len(correctedTests) == 0 {
		return original
	}

	// Build map of corrected test name → code
	correctedByName := make(map[string]string)
	for _, ct := range correctedTests {
		name := extractDeftestName(ct)
		if name != "" {
			correctedByName[name] = ct
		}
	}

	// Replace matching tests, keep non-matching ones
	var result []string
	replaced := make(map[string]bool)
	for _, ot := range originalTests {
		name := extractDeftestName(ot)
		if correction, ok := correctedByName[name]; ok {
			result = append(result, correction)
			replaced[name] = true
		} else {
			result = append(result, ot)
		}
	}

	// Append any corrected tests that didn't match an original (new tests)
	for _, ct := range correctedTests {
		name := extractDeftestName(ct)
		if !replaced[name] {
			result = append(result, ct)
		}
	}

	return strings.Join(result, "\n\n")
}

// splitDeftests splits a Clojure source string into individual deftest forms.
// Each form is returned as a complete string including the (deftest ...) wrapper.
func splitDeftests(source string) []string {
	var tests []string
	lines := strings.Split(source, "\n")
	var current []string
	depth := 0
	inTest := false

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "(deftest ") {
			inTest = true
			current = []string{line}
			depth = 0
			for _, ch := range line {
				if ch == '(' {
					depth++
				} else if ch == ')' {
					depth--
				}
			}
			if depth <= 0 && inTest {
				tests = append(tests, strings.Join(current, "\n"))
				inTest = false
				current = nil
			}
			continue
		}
		if inTest {
			current = append(current, line)
			for _, ch := range line {
				if ch == '(' {
					depth++
				} else if ch == ')' {
					depth--
				}
			}
			if depth <= 0 {
				tests = append(tests, strings.Join(current, "\n"))
				inTest = false
				current = nil
			}
		}
	}
	// Handle unclosed form
	if inTest && len(current) > 0 {
		tests = append(tests, strings.Join(current, "\n"))
	}

	return tests
}

// extractDeftestName returns the test name from a deftest form.
// e.g. "(deftest test-foo-bar" → "test-foo-bar"
func extractDeftestName(form string) string {
	re := regexp.MustCompile(`\(deftest\s+([\w\-\?\!]+)`)
	m := re.FindStringSubmatch(form)
	if len(m) >= 2 {
		return m[1]
	}
	return ""
}

// implementFromContract implements a cell against locked (approved) test contracts.
// Uses the preserved LLM session (which has full conversation context from test generation).
func (o *Orchestrator) implementFromContract(ctx context.Context, cfg ProjectConfig,
	br *bridge.Bridge, contract *TestContract,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) error {

	cellID := contract.CellID
	brief := contract.Brief
	agent := contract.Agent
	session := contract.Session
	cellNs := contract.CellNs
	testNs := contract.TestNs
	srcFile := contract.SrcFile
	testFile := contract.TestFile
	testCode := contract.TestCode

	runID := o.runID

	// Ask cell agent to implement the cell
	onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "started",
		Message: fmt.Sprintf("Implementing %s", cellID)})

	// Build context section from brief
	contextSection := ""
	if brief.Context != "" {
		contextSection = "\n" + brief.Context
	}

	implPrompt := fmt.Sprintf(`Good. Now implement the cell to pass those tests.

## Cell Contract
- **Cell ID:** %s
- **Implementation Requirements (source of truth):** %s
- **Schema:** %s
- **Namespace:** %s
%s

IMPORTANT:
- The implementation requirements above and the tests are your guide. If tests and
  requirements appear to conflict, the requirements take precedence.
- Your handler MUST return a map that satisfies the output schema exactly.
  Every key declared in the output schema must be present with the correct type.
- The tests call (validate-output result) which checks your output against the Malli schema.

The cell namespace and defcell wrapper are generated for you.

Write ONLY:
1. (OPTIONAL) Helper functions — define any helper functions you need
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

If you need extra requires beyond [mycelium.cell :as cell], list each as a comment:
;; REQUIRE: [clojure.string :as str]
;; REQUIRE: [clojure.set :as set]

%s

Example:
`+"```clojure"+`
;; REQUIRE: [clojure.string :as str]

(defn round2 [x]
  (.doubleValue (.setScale (bigdec x) 2 java.math.RoundingMode/HALF_UP)))

(fn [resources data]
  (let [items (:items data)
        total (reduce + (map :price items))]
    {:total (round2 total)}))
`+"```"+`

Return ONLY helper functions and the (fn ...) form in a single code block.
Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.`,
		cellID, brief.Doc, brief.Schema, cellNs, contextSection, mathPrecisionRules)

	implResponse, err := agent.session.SendStream(ctx, agent.client, implPrompt,
		func(chunk string) { onChunk(cellID+"/impl", chunk) })
	if err != nil {
		return fmt.Errorf("implementation for %s: %w", cellID, err)
	}

	rawImpl := ExtractFirstCodeBlock(implResponse)
	if rawImpl == "" {
		return fmt.Errorf("no code block found in implementation response for %s", cellID)
	}
	rawImpl = requestContinuation(ctx, agent, rawImpl, onChunk, cellID+"/impl")

	// Assemble implementation
	var implCode string
	if strings.Contains(rawImpl, "(ns ") {
		implCode = rawImpl
	} else {
		fnBody := ExtractFnBody(rawImpl)
		if fnBody == "" {
			fnBody = rawImpl
		}
		helpers := ExtractHelpers(rawImpl)
		extraReqs := ExtractExtraRequires(rawImpl)
		implCode = assembleCellSource(cellNs, cellID, brief.Doc, brief.Schema,
			brief.Requires, extraReqs, helpers, fnBody)
	}

	// Write source file
	if err := writeClojureFile(srcFile, implCode); err != nil {
		return fmt.Errorf("write source file %s: %w", srcFile, err)
	}

	// Lint fix loop
	implCode, _ = o.lintFixLoop(ctx, agent, cellID, implCode, srcFile, 3, onChunk, onEvent)

	onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "written",
		Message: fmt.Sprintf("Source file written: %s", srcFile)})

	// Load implementation in REPL
	br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, cellNs, cellNs))
	evalResult, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))
	if err != nil {
		return fmt.Errorf("eval implementation %s: %w", cellID, err)
	}

	if evalResult.IsError() {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
			Message: fmt.Sprintf("Compilation error: %s", evalResult.Ex)})

		fixResult, fixErr := o.fixCellCode(ctx, agent, cellID, implCode, evalResult, srcFile, br, session, brief, cellNs, onChunk)
		if fixErr != nil {
			return fmt.Errorf("fix implementation %s: %w", cellID, fixErr)
		}
		implCode = fixResult
	}

	// Contract verification
	implCode, err = o.verifyCellContract(ctx, agent, br, session, cellID, brief, implCode, srcFile, cellNs, onChunk, onEvent)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
			Message: fmt.Sprintf("Contract verification failed: %v", err)})
	}

	// Load test file
	br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, testNs, testNs))
	testEvalResult, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, testFile))
	if err != nil {
		return fmt.Errorf("eval test %s: %w", cellID, err)
	}
	if testEvalResult.IsError() {
		onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "error",
			Message: fmt.Sprintf("Test compilation error: %s", testEvalResult.Ex)})

		fixResult, fixErr := o.fixTestCode(ctx, agent, cellID, testCode, testEvalResult, testFile, br, session, testNs, cellNs, onChunk)
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

		if o.store != nil && runID != "" {
			o.store.SaveCellAttempt(&store.CellAttempt{
				RunID:         runID,
				CellID:        cellID,
				AttemptType:   "test",
				AttemptNumber: attempt,
				Code:          implCode,
				TestCode:      testCode,
				Output:        testOutput,
				Passed:        isTestSuccess(testOutput),
			})
		}

		if isTestSuccess(testOutput) {
			onEvent(OrchestratorEvent{Phase: "cell_test", CellID: cellID, Status: "passed",
				Message: fmt.Sprintf("All tests passed for %s", cellID)})

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
			return fmt.Errorf("tests still failing after %d attempts for %s", maxAttempts, cellID)
		}

		// Ask cell agent to fix
		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "fixing",
			Message: "Tests failed, asking for fix..."})

		fixPrompt := buildGraduatedFixPrompt(FixPromptParams{
			TestOutput:   testOutput,
			TestCode:     testCode,
			ImplCode:     implCode,
			Brief:        brief,
			CellID:       cellID,
			Attempt:      attempt,
			MaxAttempts:  maxAttempts,
			GraphContext: brief.Context,
		})

		fixResponse, fixErr := agent.session.SendStream(ctx, agent.client, fixPrompt,
			func(chunk string) { onChunk(cellID+"/fix", chunk) })
		if fixErr != nil {
			return fmt.Errorf("fix iteration %d for %s: %w", attempt, cellID, fixErr)
		}

		extracted := ExtractFirstCodeBlock(fixResponse)
		if extracted == "" {
			continue
		}
		extracted = requestContinuation(ctx, agent, extracted, onChunk, cellID+"/fix")

		if strings.Contains(extracted, "(ns ") {
			implCode = extracted
		} else {
			fnBody := ExtractFnBody(extracted)
			if fnBody == "" {
				fnBody = extracted
			}
			helpers := ExtractHelpers(extracted)
			extraReqs := ExtractExtraRequires(extracted)
			implCode = assembleCellSource(cellNs, cellID, brief.Doc, brief.Schema,
				brief.Requires, extraReqs, helpers, fnBody)
		}
		if err := writeClojureFile(srcFile, implCode); err != nil {
			return fmt.Errorf("write fixed source %s: %w", srcFile, err)
		}

		implCode, _ = o.lintFixLoop(ctx, agent, cellID, implCode, srcFile, 2, onChunk, onEvent)

		evalResult, err := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))
		if err != nil {
			return fmt.Errorf("eval fixed implementation %s: %w", cellID, err)
		}
		if evalResult.IsError() {
			onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
				Message: fmt.Sprintf("Fix compilation error: %s", evalResult.Ex)})

			fixResult, compFixErr := o.fixCellCode(ctx, agent, cellID, implCode, evalResult, srcFile, br, session, brief, cellNs, onChunk)
			if compFixErr != nil {
				onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "error",
					Message: fmt.Sprintf("Could not fix compilation: %v", compFixErr)})
			} else {
				implCode = fixResult
			}
		} else {
			implCode, _ = o.verifyCellContract(ctx, agent, br, session, cellID, brief, implCode, srcFile, cellNs, onChunk, onEvent)
		}
	}

	return nil
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

// refreshSession checks if an nREPL session is still alive by evaluating a trivial expression.
// If the session is stale, it closes it and clones a new one.
// Returns (session, wasRefreshed, error).
func refreshSession(br *bridge.Bridge, session string) (string, bool, error) {
	// Quick liveness check
	result, err := br.EvalInSession(session, "1")
	if err == nil && result.Ex == "" {
		return session, false, nil
	}

	// Session is stale — close it and clone a new one
	br.CloseSession(session)
	newSession, cloneErr := br.CloneSession()
	if cloneErr != nil {
		return "", false, fmt.Errorf("clone replacement session: %w", cloneErr)
	}
	return newSession, true, nil
}

// defaultSchema returns the schema or "[:map]" if empty.
func defaultSchema(schema string) string {
	if schema == "" {
		return "{}"
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

// implementCellWithTDD implements a single cell using TDD (generate tests → implement → iterate).
// This is a convenience wrapper used by implementCellsParallel (flat path).
// It delegates to generateTestContract + implementFromContract with auto-approval.
func (o *Orchestrator) implementCellWithTDD(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	session, manifest string, brief CellBrief,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) error {

	contract, err := o.generateTestContract(ctx, cfg, br, session, manifest, brief, onChunk, onEvent)
	if err != nil {
		return err
	}
	return o.implementFromContract(ctx, cfg, br, contract, onChunk, onEvent)
}

// verifyCellContract checks that a cell registered in the REPL matches the expected contract
// from the decomposition tree. If the cell ID is wrong, it feeds a clear error to the LLM
// and retries. Returns the (possibly fixed) implementation code.
func (o *Orchestrator) verifyCellContract(ctx context.Context, agent *CellAgent, br *bridge.Bridge,
	session, cellID string, brief CellBrief, implCode, srcFile, cellNs string,
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

	fixResult, fixErr := o.fixCellCode(ctx, agent, cellID, implCode, contractFixResult, srcFile, br, session, brief, cellNs, onChunk)
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

// lintFixLoop runs clj-kondo on a file and asks LLM to fix syntax errors.
// Does NOT count against the TDD test-attempt budget.
// Returns the fixed code or error after maxLintAttempts.
func (o *Orchestrator) lintFixLoop(ctx context.Context, agent *CellAgent, cellID, code, filePath string,
	maxLintAttempts int, onChunk func(string, string), onEvent func(OrchestratorEvent)) (string, error) {

	for i := 0; i < maxLintAttempts; i++ {
		lintErrors := lintClojureFile(filePath)
		if lintErrors == "" {
			return code, nil // clean
		}

		onEvent(OrchestratorEvent{Phase: "cell_implement", CellID: cellID, Status: "lint_fix",
			Message: fmt.Sprintf("Lint error (fix %d/%d): %s", i+1, maxLintAttempts, lintErrors)})

		// Targeted syntax-only fix prompt
		fixPrompt := fmt.Sprintf(`The code has syntax errors detected by clj-kondo:

%s

Here is the code:

`+"```clojure\n%s\n```"+`

Fix ONLY the syntax errors. Do not change any logic.
Return the COMPLETE corrected code in a single code block.`, lintErrors, code)

		fixResponse, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
			func(chunk string) { onChunk(cellID+"/lint-fix", chunk) })
		if err != nil {
			return code, fmt.Errorf("lint fix %d: %w", i+1, err)
		}

		fixed := ExtractFirstCodeBlock(fixResponse)
		if fixed == "" {
			continue
		}
		fixed = requestContinuation(ctx, agent, fixed, onChunk, cellID+"/lint-fix")
		code = fixed

		if err := writeClojureFile(filePath, code); err != nil {
			return code, err
		}
	}

	// Check one more time after all attempts
	if lintErrors := lintClojureFile(filePath); lintErrors != "" {
		return code, fmt.Errorf("lint errors persist after %d attempts: %s", maxLintAttempts, lintErrors)
	}
	return code, nil
}

// fixCellCode asks the cell agent to fix a compilation error and retries eval.
// Uses deterministic scaffolding: the LLM only returns fn body + helpers,
// which are assembled into the full namespace in Go.
func (o *Orchestrator) fixCellCode(ctx context.Context, agent *CellAgent, cellID, code string,
	evalResult *bridge.EvalResult, filePath string, br *bridge.Bridge, session string,
	brief CellBrief, cellNs string,
	onChunk func(string, string)) (string, error) {

	errMsg := evalResult.Ex
	if evalResult.Err != "" {
		errMsg += "\n" + evalResult.Err
	}

	fixPrompt := fmt.Sprintf(`The implementation had a compilation error:

%s

Here is the code that failed:

`+"```clojure\n%s\n```"+`

Fix the issue. Return ONLY:
1. (OPTIONAL) Helper functions
2. (REQUIRED) (fn [resources data] ...) — MUST be the LAST form

If you need extra requires, add: ;; REQUIRE: [lib.name :as alias]
Do NOT include (ns ...) or (cell/defcell ...) — those are generated for you.
CRITICAL: The cell ID is %s.`, errMsg, code, cellID)

	fixResponse, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
		func(chunk string) { onChunk(cellID+"/fix", chunk) })
	if err != nil {
		return "", err
	}

	extracted := ExtractFirstCodeBlock(fixResponse)
	if extracted == "" {
		return code, fmt.Errorf("no code block in fix response")
	}
	extracted = requestContinuation(ctx, agent, extracted, onChunk, cellID+"/fix")

	// Assemble deterministically or use as-is if full namespace
	var fixed string
	if strings.Contains(extracted, "(ns ") {
		fixed = extracted
	} else {
		fnBody := ExtractFnBody(extracted)
		if fnBody == "" {
			fnBody = extracted
		}
		helpers := ExtractHelpers(extracted)
		extraReqs := ExtractExtraRequires(extracted)
		fixed = assembleCellSource(cellNs, cellID, brief.Doc, brief.Schema,
			brief.Requires, extraReqs, helpers, fnBody)
	}

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
// Uses deterministic scaffolding: the LLM only returns deftest forms,
// which are assembled into the full test namespace in Go.
func (o *Orchestrator) fixTestCode(ctx context.Context, agent *CellAgent, cellID, code string,
	evalResult *bridge.EvalResult, filePath string, br *bridge.Bridge, session string,
	testNs, cellNs string,
	onChunk func(string, string)) (string, error) {

	errMsg := evalResult.Ex
	if evalResult.Err != "" {
		errMsg += "\n" + evalResult.Err
	}

	fixPrompt := fmt.Sprintf(`The test file had a compilation error:

%s

Here is the test code that failed:

`+"```clojure\n%s\n```"+`

Fix the test. Return ONLY deftest forms. Do NOT include:
- (ns ...) declaration
- require forms
- handler binding
- helper function definitions (approx= is already available)

Call the handler as: (handler resources data)`, errMsg, code)

	fixResponse, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
		func(chunk string) { onChunk(cellID+"/test-fix", chunk) })
	if err != nil {
		return "", err
	}

	extracted := ExtractFirstCodeBlock(fixResponse)
	if extracted == "" {
		return code, fmt.Errorf("no code block in test fix response")
	}
	extracted = requestContinuation(ctx, agent, extracted, onChunk, cellID+"/test-fix")

	// Assemble deterministically or use as-is if full namespace
	var fixed string
	if strings.Contains(extracted, "(ns ") {
		fixed = extracted
	} else {
		fixed = assembleTestSource(testNs, cellNs, cellID, extracted)
	}

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
	manifest string, tree *DecompositionNode,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) error {

	// Phase 1: Validate all cell schemas are valid Malli before attempting compilation
	onEvent(OrchestratorEvent{Phase: "integration", Status: "validating_schemas",
		Message: "Validating cell schemas..."})

	schemaResult, schemaErr := br.ValidateCellSchemas(manifest)
	if schemaErr != nil {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "warning",
			Message: fmt.Sprintf("Schema validation connection error: %v", schemaErr)})
	} else if schemaResult != nil && !schemaResult.IsError() {
		schemaOutput := unquoteResult(schemaResult.Value)
		if schemaOutput != "[]" && schemaOutput != "" {
			// There are schema validation errors — fix them before compiling
			onEvent(OrchestratorEvent{Phase: "integration", Status: "schema_errors",
				Message: fmt.Sprintf("Invalid Malli schemas found: %s", schemaOutput)})

			if err := o.fixInvalidSchemas(ctx, cfg, br, tree, schemaOutput, onChunk, onEvent); err != nil {
				onEvent(OrchestratorEvent{Phase: "integration", Status: "warning",
					Message: fmt.Sprintf("Could not fix all invalid schemas: %v", err)})
			}
		} else {
			onEvent(OrchestratorEvent{Phase: "integration", Status: "schemas_valid",
				Message: "All cell schemas are valid Malli"})
		}
	}

	// Phase 2: Compile the workflow — schema chain validation
	maxCompileAttempts := 3
	compiled := false
	for attempt := 1; attempt <= maxCompileAttempts; attempt++ {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "compiling",
			Message: fmt.Sprintf("Compiling workflow (attempt %d/%d)...", attempt, maxCompileAttempts)})

		compileResult, compileErr := br.CompileWorkflow(manifest, "{:coerce? true}")
		if compileErr != nil {
			onEvent(OrchestratorEvent{Phase: "integration", Status: "error",
				Message: fmt.Sprintf("Workflow compile connection error: %v", compileErr)})
			break
		}

		if !compileResult.IsError() {
			onEvent(OrchestratorEvent{Phase: "integration", Status: "compiled",
				Message: "Workflow compiled successfully"})
			compiled = true
			break
		}

		// Compilation failed — parse the error and try to fix the offending cell
		errMsg := compileResult.Ex + " " + compileResult.Err
		onEvent(OrchestratorEvent{Phase: "integration", Status: "compile_error",
			Message: fmt.Sprintf("Compile error (attempt %d): %s", attempt, truncMsg(errMsg, 300))})

		if attempt >= maxCompileAttempts {
			break
		}

		// Try to fix: use SchemaChainCheck for structured error, then fix the cell
		chainResult, chainErr := br.SchemaChainCheck(manifest)
		if chainErr != nil {
			break
		}
		chainOutput := unquoteResult(chainResult.Value)

		fixed := o.fixSchemaChainErrors(ctx, cfg, br, tree, chainOutput, errMsg, onChunk, onEvent)
		if !fixed {
			break
		}
	}

	if !compiled {
		onEvent(OrchestratorEvent{Phase: "integration", Status: "error",
			Message: "Workflow compilation failed after all attempts"})
		return fmt.Errorf("workflow compilation failed")
	}

	// Phase 3: Run integration tests
	agent := o.manager.GetGraphAgent(cfg.ManifestID)

	prompt := fmt.Sprintf(`All cells have been implemented and loaded in the REPL.
The workflow compiles successfully.

Now write integration test code to test the full workflow end-to-end.

The manifest:
`+"```edn\n%s\n```"+`

Write Clojure code that:
1. Requires [mycelium.core :as myc]
2. Compiles the workflow: (def wf (myc/compile-workflow %s {:coerce? true}))
3. Sets up test resources and input data
4. Runs the workflow: (myc/run-workflow wf resources input)
5. Prints the results clearly with (println (pr-str result))

IMPORTANT constraints:
- Do NOT use clojure.test or deftest — this is a script evaluated in the REPL
- Do NOT define any function named run-tests
- Use simple (println ...) and (assert ...) for verification
- Keep it simple: 2-3 test scenarios maximum

Return the code in a single code block.`, manifest, manifest)

	response, err := agent.ChatStream(ctx, prompt,
		func(chunk string) { onChunk("graph/integration", chunk) })
	if err != nil {
		return fmt.Errorf("integration test generation: %w", err)
	}

	testCode := ExtractFirstCodeBlock(response)
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

// fixInvalidSchemas fixes cells that have Malli-invalid schemas by asking the cell
// agent to regenerate with a corrected schema.
func (o *Orchestrator) fixInvalidSchemas(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	tree *DecompositionNode, errorsStr string,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) error {

	// Parse errors like [{:cell-id ":order/foo" :phase ":input" :error "..." :schema "..."}]
	// For each erroring cell, ask the cell agent to fix its schema
	leaves := collectLeaves(tree)
	for _, leaf := range leaves {
		if !strings.Contains(errorsStr, leaf.CellID) {
			continue
		}

		onEvent(OrchestratorEvent{Phase: "integration", CellID: leaf.CellID, Status: "fixing_schema",
			Message: fmt.Sprintf("Fixing invalid schema for %s", leaf.CellID)})

		agent := o.manager.NewCellAgent(leaf.CellID)
		cellNs := cfg.BaseNamespace + ".cells." + cellIDToNsSuffix(leaf.CellID)
		srcFile := clojureNsToFile(cfg.ProjectPath, cfg.SourceDir, cellNs)

		fixPrompt := fmt.Sprintf(`The cell %s has an invalid Malli schema that fails validation.

Error details: %s

The declared schema is: %s

Fix the cell's schema declaration so it is valid Malli syntax. Common issues:
- Using bare keywords like :map instead of [:map ...]
- Missing vector wrappers around map entries
- Using unsupported Malli types

Return the complete corrected cell namespace in a code block.`, leaf.CellID, errorsStr, leaf.InputSchema+" "+leaf.OutputSchema)

		fixResp, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
			func(chunk string) { onChunk(leaf.CellID+"/schema-fix", chunk) })
		if err != nil {
			continue
		}

		fixedCode := ExtractFirstCodeBlock(fixResp)
		if fixedCode == "" {
			continue
		}

		if err := writeClojureFile(srcFile, fixedCode); err != nil {
			continue
		}

		// Reload the fixed cell
		session, _ := br.CloneSession()
		br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, cellNs, cellNs))
		br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))

		onEvent(OrchestratorEvent{Phase: "integration", CellID: leaf.CellID, Status: "schema_fixed",
			Message: fmt.Sprintf("Reloaded %s with fixed schema", leaf.CellID)})
	}

	return nil
}

// fixSchemaChainErrors attempts to fix schema chain mismatches by identifying
// the upstream cell that's missing output keys and asking the cell agent to add them.
func (o *Orchestrator) fixSchemaChainErrors(ctx context.Context, cfg ProjectConfig, br *bridge.Bridge,
	tree *DecompositionNode, chainOutput, errMsg string,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) bool {

	// The chain error message format is:
	// "Schema chain error: :order/calculate-tax at :calculate-tax requires keys #{:items-detail}
	//  but only #{:x :y} available"
	// Also check the structured output from SchemaChainCheck for :error key

	// Find cells that need fixing from the error message
	leaves := collectLeaves(tree)
	fixedAny := false

	for _, leaf := range leaves {
		if !strings.Contains(errMsg, leaf.CellID) && !strings.Contains(chainOutput, leaf.CellID) {
			continue
		}

		onEvent(OrchestratorEvent{Phase: "integration", CellID: leaf.CellID, Status: "fixing_chain",
			Message: fmt.Sprintf("Fixing schema chain for %s", leaf.CellID)})

		agent := o.manager.NewCellAgent(leaf.CellID)
		cellNs := cfg.BaseNamespace + ".cells." + cellIDToNsSuffix(leaf.CellID)
		srcFile := clojureNsToFile(cfg.ProjectPath, cfg.SourceDir, cellNs)

		// Read current source
		currentCode := ""
		if data, err := os.ReadFile(srcFile); err == nil {
			currentCode = string(data)
		}

		fixPrompt := fmt.Sprintf(`The workflow fails to compile because of a schema chain error involving cell %s.

Compilation error:
%s

Structured chain check output:
%s

Your current implementation:
`+"```clojure\n%s\n```"+`

The cell's declared output schema MUST include all keys that downstream cells need.
Check: does your handler return all the keys declared in the output schema?
Make sure you pass through all input keys that downstream cells expect (the data map
is accumulating — downstream cells may need keys from earlier cells that flow through yours).

Fix the cell to ensure its output matches its declared schema. Return the complete
corrected namespace in a code block.`, leaf.CellID, truncMsg(errMsg, 500), truncMsg(chainOutput, 500), currentCode)

		fixResp, err := agent.session.SendStream(ctx, agent.client, fixPrompt,
			func(chunk string) { onChunk(leaf.CellID+"/chain-fix", chunk) })
		if err != nil {
			continue
		}

		fixedCode := ExtractFirstCodeBlock(fixResp)
		if fixedCode == "" {
			continue
		}

		if err := writeClojureFile(srcFile, fixedCode); err != nil {
			continue
		}

		// Reload
		session, _ := br.CloneSession()
		br.EvalInSession(session, fmt.Sprintf(`(when (find-ns '%s) (remove-ns '%s))`, cellNs, cellNs))
		evalResult, _ := br.EvalInSession(session, fmt.Sprintf(`(load-file "%s")`, srcFile))
		if evalResult != nil && !evalResult.IsError() {
			fixedAny = true
			onEvent(OrchestratorEvent{Phase: "integration", CellID: leaf.CellID, Status: "chain_fixed",
				Message: fmt.Sprintf("Reloaded %s with schema chain fix", leaf.CellID)})
		}
	}

	return fixedAny
}

// truncMsg truncates a message to maxLen characters.
func truncMsg(s string, maxLen int) string {
	if len(s) <= maxLen {
		return s
	}
	return s[:maxLen] + "..."
}

// unquoteResult removes pr-str quoting from REPL output.
func unquoteResult(s string) string {
	s = strings.TrimSpace(s)
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		s = s[1 : len(s)-1]
		s = strings.ReplaceAll(s, `\"`, `"`)
		s = strings.ReplaceAll(s, `\\`, `\`)
	}
	return s
}

// assembleStubCellSource builds a minimal cell namespace with the schema and a passthrough handler.
// Used in Phase 0 to validate that the schema is valid Malli before investing in test generation.
func assembleStubCellSource(cellNs, cellID, doc, schema string, requires []string) string {
	var b strings.Builder
	fmt.Fprintf(&b, "(ns %s\n", cellNs)
	b.WriteString("  (:require [mycelium.cell :as cell]))\n\n")
	b.WriteString("(cell/defcell ")
	b.WriteString(cellID)
	b.WriteString("\n  {:doc \"")
	b.WriteString(strings.ReplaceAll(doc, `"`, `\"`))
	b.WriteString("\"\n   :schema ")
	b.WriteString(schema)
	b.WriteString("}\n  (fn [resources data] data))\n")
	return b.String()
}

// fixCellSchema asks the LLM to produce a corrected Malli schema for a cell.
func (o *Orchestrator) fixCellSchema(ctx context.Context, leaf *DecompositionNode,
	currentSchema, errMsg string,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) string {

	agent := o.manager.NewCellAgent(leaf.CellID + "/schema-fix")

	prompt := fmt.Sprintf(`The Malli schema for cell %s is invalid and fails to compile.

Error: %s

Current schema: %s

Fix this schema so it is valid Malli using lite map syntax.

Rules for lite map syntax:
- Maps: {:field-name :type :other-field :type2}
- Vectors: [:vector {:nested-field :type}]
- Optional: {:error [:maybe :string]}  (NOT {[:maybe :error] :string})
- Enums: [:enum :a :b :c]
- Map-of: [:map-of :string :double]
- Primitives: :string :int :double :boolean :keyword :any

Return ONLY the corrected schema as: {:input <input-schema> :output <output-schema>}
in a code block. No explanation needed.`, leaf.CellID, truncMsg(errMsg, 300), currentSchema)

	resp, err := agent.session.SendStream(ctx, agent.client, prompt,
		func(chunk string) { onChunk(leaf.CellID+"/schema-fix", chunk) })
	if err != nil {
		return ""
	}

	code := ExtractFirstCodeBlock(resp)
	if code == "" || !strings.Contains(code, ":input") {
		return ""
	}
	return strings.TrimSpace(code)
}

// extractSubSchema pulls the :input or :output part from a {:input ... :output ...} schema string.
func extractSubSchema(fullSchema, key string) string {
	// Simple extraction: find :input or :output and take the next balanced form
	search := ":" + key + " "
	idx := strings.Index(fullSchema, search)
	if idx < 0 {
		search = ":" + key + "\n"
		idx = strings.Index(fullSchema, search)
	}
	if idx < 0 {
		return "{}"
	}
	rest := fullSchema[idx+len(search):]
	rest = strings.TrimSpace(rest)

	// Find the end of the balanced form
	depth := 0
	end := 0
	for i, ch := range rest {
		if ch == '{' || ch == '[' || ch == '(' {
			depth++
		} else if ch == '}' || ch == ']' || ch == ')' {
			depth--
			if depth == 0 {
				end = i + 1
				break
			}
		}
	}
	if end > 0 {
		return rest[:end]
	}
	return "{}"
}

// assembleCellSource builds the complete cell namespace deterministically.
// Only fnBody and helpers come from the LLM.
func assembleCellSource(cellNs, cellID, doc, schema string,
	requires []string, extraReqs []string, helpers, fnBody string) string {
	var b strings.Builder

	// Namespace declaration
	fmt.Fprintf(&b, "(ns %s\n", cellNs)
	b.WriteString("  (:require [mycelium.cell :as cell]")
	for _, r := range extraReqs {
		fmt.Fprintf(&b, "\n            %s", r)
	}
	b.WriteString("))\n")

	// Helper functions
	if helpers != "" {
		b.WriteString("\n")
		b.WriteString(helpers)
		b.WriteString("\n")
	}

	// defcell form
	b.WriteString("\n(cell/defcell ")
	b.WriteString(cellID)
	b.WriteString("\n  {:doc \"")
	b.WriteString(strings.ReplaceAll(doc, `"`, `\"`))
	b.WriteString("\"\n   :schema ")
	b.WriteString(schema)
	b.WriteString("}\n  ")
	b.WriteString(fnBody)
	b.WriteString(")\n")

	return b.String()
}

// assembleTestSource builds the complete test namespace deterministically.
// Only testBody (deftest forms) comes from the LLM.
func assembleTestSource(testNs, cellNs, cellID string, testBody string) string {
	var b strings.Builder

	fmt.Fprintf(&b, "(ns %s\n", testNs)
	fmt.Fprintf(&b, "  (:require [clojure.test :refer [deftest is testing]]\n")
	fmt.Fprintf(&b, "            [mycelium.cell :as cell]\n")
	fmt.Fprintf(&b, "            [malli.core :as m]\n")
	fmt.Fprintf(&b, "            [%s]))\n", cellNs)
	b.WriteString("\n")
	fmt.Fprintf(&b, "(def cell-spec (cell/get-cell! %s))\n", cellID)
	b.WriteString("(def handler (:handler cell-spec))\n")
	b.WriteString("(def cell-schema (:schema cell-spec))\n")
	b.WriteString("\n")
	b.WriteString("(defn approx= [x y tolerance]\n")
	b.WriteString("  (< (Math/abs (- (double x) (double y))) tolerance))\n")
	b.WriteString("\n")
	b.WriteString("(defn validate-output\n")
	b.WriteString("  \"Validates that result satisfies the cell's declared output schema.\"\n")
	b.WriteString("  [result]\n")
	b.WriteString("  (when-let [out-schema (:output cell-schema)]\n")
	b.WriteString("    (let [schema (if (vector? out-schema) out-schema [:map])]\n")
	b.WriteString("      (is (m/validate schema result)\n")
	b.WriteString("          (str \"Output schema violation: \"\n")
	b.WriteString("               (pr-str (m/explain schema result)))))))\n")
	b.WriteString("\n")
	b.WriteString(testBody)
	b.WriteString("\n")

	return b.String()
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
