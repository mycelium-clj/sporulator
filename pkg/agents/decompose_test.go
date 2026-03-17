package agents

import (
	"fmt"
	"strings"
	"testing"
)

func TestParseDecompositionResponse(t *testing.T) {
	response := "Here are the steps:\n```edn\n" +
		`[{:name "validate-input" :doc "Validate the order data" :input-schema [:map] :output-schema [:map] :requires [] :simple? true}
 {:name "compute-tax" :doc "Calculate tax amounts" :input-schema [:map] :output-schema [:map] :requires [:tax-service] :simple? true}
 {:name "check-fraud" :doc "Run fraud detection" :input-schema [:map] :output-schema [:map] :requires [:db :fraud-api] :simple? false}]` +
		"\n```"

	nodes, err := parseDecompositionResponse(response, "order")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(nodes) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(nodes))
	}

	// Check first node
	if nodes[0].StepName != "validate-input" {
		t.Errorf("node 0 name: %q", nodes[0].StepName)
	}
	if nodes[0].CellID != ":order/validate-input" {
		t.Errorf("node 0 cell ID: %q", nodes[0].CellID)
	}
	if nodes[0].Doc != "Validate the order data" {
		t.Errorf("node 0 doc: %q", nodes[0].Doc)
	}
	if !nodes[0].IsLeaf {
		t.Error("node 0 should be leaf")
	}

	// Check requires on second node
	if len(nodes[1].Requires) != 1 || nodes[1].Requires[0] != "tax-service" {
		t.Errorf("node 1 requires: %v", nodes[1].Requires)
	}

	// Check complex (non-leaf) node
	if nodes[2].IsLeaf {
		t.Error("node 2 should not be leaf (simple? false)")
	}
	if len(nodes[2].Requires) != 2 {
		t.Errorf("node 2 requires: %v", nodes[2].Requires)
	}
}

func TestParseDecompositionResponseNoBlock(t *testing.T) {
	response := "no code blocks here"
	_, err := parseDecompositionResponse(response, "ns")
	if err == nil {
		t.Error("expected error for response with no steps")
	}
}

func TestParseDecompositionResponseNestedSchemas(t *testing.T) {
	response := "```edn\n" +
		`[{:name "validate" :doc "Validate input" :input-schema [:map [:items [:vector :map]]] :output-schema [:map [:valid? :boolean]] :requires [:catalog] :simple? true}]` +
		"\n```"

	nodes, err := parseDecompositionResponse(response, "order")
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(nodes))
	}
	// Schema should round-trip through EDN correctly
	if !strings.Contains(nodes[0].InputSchema, "items") {
		t.Errorf("input schema should contain 'items': %q", nodes[0].InputSchema)
	}
	if !strings.Contains(nodes[0].OutputSchema, "valid?") {
		t.Errorf("output schema should contain 'valid?': %q", nodes[0].OutputSchema)
	}
}

func TestParseEdgeResponse(t *testing.T) {
	response := "```edn\n{:done :process}\n[[:done (constantly true)]]\n```"

	edges, dispatches, targets, err := parseEdgeResponse(response)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if edges != "{:done :process}" {
		t.Errorf("edges: %q", edges)
	}
	if dispatches != "[[:done (constantly true)]]" {
		t.Errorf("dispatches: %q", dispatches)
	}
	if len(targets) == 0 {
		t.Error("expected at least one target")
	}
}

func TestParseEdgeResponseConditional(t *testing.T) {
	response := "```edn\n{:approved :finalize :rejected :end}\n[[:approved (fn [data] (:approved? data))] [:rejected (fn [data] (not (:approved? data)))]]\n```"

	edges, dispatches, _, err := parseEdgeResponse(response)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if !strings.Contains(edges, ":approved") || !strings.Contains(edges, ":rejected") {
		t.Errorf("edges: %q", edges)
	}
	if !strings.Contains(dispatches, ":approved") {
		t.Errorf("dispatches: %q", dispatches)
	}
}

func TestParseEdgeResponseNoEdges(t *testing.T) {
	_, _, _, err := parseEdgeResponse("just some text without EDN")
	if err == nil {
		t.Error("expected error for response with no edges")
	}
}

func TestCollectLeaves(t *testing.T) {
	root := &DecompositionNode{
		StepName: "root",
		IsLeaf:   false,
		Children: []*DecompositionNode{
			{StepName: "a", IsLeaf: true},
			{StepName: "b", IsLeaf: false, Children: []*DecompositionNode{
				{StepName: "b1", IsLeaf: true},
				{StepName: "b2", IsLeaf: true},
			}},
			{StepName: "c", IsLeaf: true},
		},
	}

	leaves := collectLeaves(root)
	if len(leaves) != 4 {
		t.Fatalf("expected 4 leaves (a, b1, b2, c), got %d", len(leaves))
	}

	names := make([]string, len(leaves))
	for i, l := range leaves {
		names[i] = l.StepName
	}
	expected := "a,b1,b2,c"
	got := strings.Join(names, ",")
	if got != expected {
		t.Errorf("leaves: %s (expected %s)", got, expected)
	}
}

func TestCollectLeavesNil(t *testing.T) {
	leaves := collectLeaves(nil)
	if len(leaves) != 0 {
		t.Errorf("expected 0 leaves for nil, got %d", len(leaves))
	}
}

func TestCollectLeavesSingleLeaf(t *testing.T) {
	root := &DecompositionNode{StepName: "only", IsLeaf: true}
	leaves := collectLeaves(root)
	if len(leaves) != 1 || leaves[0].StepName != "only" {
		t.Errorf("expected single leaf, got %v", leaves)
	}
}

func TestCollectSubWorkflows(t *testing.T) {
	root := &DecompositionNode{
		StepName: "root",
		IsLeaf:   false,
		Children: []*DecompositionNode{
			{StepName: "a", IsLeaf: true},
			{StepName: "b", IsLeaf: false, Children: []*DecompositionNode{
				{StepName: "b1", IsLeaf: true},
				{StepName: "b-inner", IsLeaf: false, Children: []*DecompositionNode{
					{StepName: "b-inner-1", IsLeaf: true},
				}},
			}},
			{StepName: "c", IsLeaf: true},
		},
	}

	subs := collectSubWorkflows(root)
	if len(subs) != 2 {
		t.Fatalf("expected 2 sub-workflows, got %d", len(subs))
	}

	// Post-order: deepest first
	if subs[0].StepName != "b-inner" {
		t.Errorf("first sub-workflow should be b-inner (deepest), got %s", subs[0].StepName)
	}
	if subs[1].StepName != "b" {
		t.Errorf("second sub-workflow should be b, got %s", subs[1].StepName)
	}
}

func TestCollectSubWorkflowsNil(t *testing.T) {
	subs := collectSubWorkflows(nil)
	if len(subs) != 0 {
		t.Errorf("expected 0 for nil, got %d", len(subs))
	}
}

func TestAssembleManifest(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "validate", CellID: ":order/validate", Doc: "Validate input",
			InputSchema: "[:map]", OutputSchema: "[:map]"},
		{StepName: "process", CellID: ":order/process", Doc: "Process data",
			InputSchema: "[:map]", OutputSchema: "[:map]", Requires: []string{"db"}},
		{StepName: "finalize", CellID: ":order/finalize", Doc: "Finalize output",
			InputSchema: "[:map]", OutputSchema: "[:map]"},
	}

	walk := &GraphWalkResult{
		Edges: map[string]string{
			"validate": "{:done :process}",
			"process":  "{:done :finalize}",
			"finalize": "{:done :end}",
		},
		Dispatches: map[string]string{
			"validate": "[[:done (constantly true)]]",
			"process":  "[[:done (constantly true)]]",
			"finalize": "[[:done (constantly true)]]",
		},
	}

	manifest := assembleManifest(":order/workflow", "order", steps, walk)

	if !strings.Contains(manifest, ":start") {
		t.Error("manifest should contain :start")
	}
	if !strings.Contains(manifest, ":order/validate") {
		t.Error("manifest should contain :order/validate")
	}
	if !strings.Contains(manifest, ":order/process") {
		t.Error("manifest should contain :order/process")
	}
	if !strings.Contains(manifest, ":order/finalize") {
		t.Error("manifest should contain :order/finalize")
	}
	if !strings.Contains(manifest, ":db") {
		t.Error("manifest should contain :db requires")
	}
	if !strings.Contains(manifest, ":edges") {
		t.Error("manifest should contain :edges")
	}
	if !strings.Contains(manifest, ":dispatches") {
		t.Error("manifest should contain :dispatches")
	}
}

func TestAssembleManifestDefaultSchemas(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "only", CellID: ":ns/only", Doc: "Only step"},
	}
	walk := &GraphWalkResult{
		Edges:      map[string]string{"only": "{:done :end}"},
		Dispatches: map[string]string{"only": "[[:done (constantly true)]]"},
	}

	manifest := assembleManifest(":ns/wf", "ns", steps, walk)
	if !strings.Contains(manifest, "[:map]") {
		t.Error("manifest should contain default [:map] schema")
	}
}

func TestEDNToString(t *testing.T) {
	// nil returns default
	if ednToString(nil) != "[:map]" {
		t.Error("nil should return [:map]")
	}
	// string values
	result := ednToString("hello")
	if !strings.Contains(result, "hello") {
		t.Errorf("string: %q", result)
	}
}

func TestCellIDToNsSuffix(t *testing.T) {
	tests := []struct {
		cellID   string
		expected string
	}{
		{":order/validate-input", "validate-input"},
		{":order.validate-and-enrich-order/validate-input", "validate-and-enrich-order.validate-input"},
		{":order.sub.deep/step", "sub.deep.step"},
		{":ns/only", "only"},
	}
	for _, tc := range tests {
		got := cellIDToNsSuffix(tc.cellID)
		if got != tc.expected {
			t.Errorf("cellIDToNsSuffix(%q) = %q, want %q", tc.cellID, got, tc.expected)
		}
	}
}

func TestParseManifestCellNames(t *testing.T) {
	manifest := `{:id :test/wf
 :cells {:start {:id :ns/a :doc "first"}
         :process {:id :ns/b :doc "second"}}
 :edges {:start {:done :process}}}`

	names, err := parseManifestCellNames(manifest)
	if err != nil {
		t.Fatalf("parse error: %v", err)
	}
	if len(names) != 2 {
		t.Fatalf("expected 2 names, got %d: %v", len(names), names)
	}
}

func TestCountUnvisitedTargets(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "a"}, {StepName: "b"}, {StepName: "c"},
	}
	visited := map[string]bool{"a": true}

	// From "a", two unvisited: b, c
	if got := countUnvisitedTargets(steps, visited, "a"); got != 2 {
		t.Errorf("expected 2, got %d", got)
	}

	// Visit b too
	visited["b"] = true
	if got := countUnvisitedTargets(steps, visited, "a"); got != 1 {
		t.Errorf("expected 1, got %d", got)
	}

	// Visit c too
	visited["c"] = true
	if got := countUnvisitedTargets(steps, visited, "a"); got != 0 {
		t.Errorf("expected 0, got %d", got)
	}
}

func TestFindSingleUnvisitedTarget(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "a"}, {StepName: "b"}, {StepName: "c"},
	}
	visited := map[string]bool{"a": true, "b": true}

	target := findSingleUnvisitedTarget(steps, visited, "a")
	if target != "c" {
		t.Errorf("expected 'c', got %q", target)
	}
}

func TestSerializeDeserializeTree(t *testing.T) {
	root := &DecompositionNode{
		StepName:     "root",
		CellID:       ":ns/root",
		Doc:          "Root node",
		InputSchema:  "[:map]",
		OutputSchema: "[:map]",
		IsLeaf:       false,
		Depth:        0,
		Children: []*DecompositionNode{
			{StepName: "a", CellID: ":ns/a", Doc: "Step A", IsLeaf: true, Depth: 1},
			{StepName: "b", CellID: ":ns/b", Doc: "Step B", IsLeaf: true, Depth: 1,
				Requires: []string{"db"}},
		},
	}

	jsonStr, err := SerializeTree(root)
	if err != nil {
		t.Fatalf("serialize: %v", err)
	}

	restored, err := DeserializeTree(jsonStr)
	if err != nil {
		t.Fatalf("deserialize: %v", err)
	}

	if restored.StepName != "root" {
		t.Errorf("root name: %q", restored.StepName)
	}
	if len(restored.Children) != 2 {
		t.Fatalf("expected 2 children, got %d", len(restored.Children))
	}
	if restored.Children[0].StepName != "a" {
		t.Errorf("child 0: %q", restored.Children[0].StepName)
	}
	if len(restored.Children[1].Requires) == 0 || restored.Children[1].Requires[0] != "db" {
		t.Errorf("child 1 requires: %v", restored.Children[1].Requires)
	}
}

func TestDeterministicEdgesTwoStepFlow(t *testing.T) {
	// Simulate walkGraph for a 2-step linear flow: a -> b -> :end
	// "a" has 1 unvisited target (b) -> deterministic
	// "b" has 0 unvisited targets -> deterministic (goes to :end)
	// Both edges should be deterministic (no LLM needed)
	steps := []*DecompositionNode{
		{StepName: "a", CellID: ":ns/a"},
		{StepName: "b", CellID: ":ns/b"},
	}

	visited := make(map[string]bool)
	result := &GraphWalkResult{
		Edges:      make(map[string]string),
		Dispatches: make(map[string]string),
	}

	// Simulate BFS with deterministic fast path
	frontier := []string{"a"}
	for len(frontier) > 0 {
		current := frontier[0]
		frontier = frontier[1:]
		if visited[current] {
			continue
		}
		visited[current] = true

		unvisited := countUnvisitedTargets(steps, visited, current)
		if unvisited <= 1 {
			target := ":end"
			if unvisited == 1 {
				target = ":" + findSingleUnvisitedTarget(steps, visited, current)
				frontier = append(frontier, findSingleUnvisitedTarget(steps, visited, current))
			}
			result.Edges[current] = fmt.Sprintf("{:done %s}", target)
			result.Dispatches[current] = "[[:done (constantly true)]]"
			result.DeterministicEdges++
		}
	}

	// Both should be deterministic
	if result.DeterministicEdges != 2 {
		t.Errorf("expected 2 deterministic edges, got %d", result.DeterministicEdges)
	}
	if result.Edges["a"] != "{:done :b}" {
		t.Errorf("a edge: %q", result.Edges["a"])
	}
	if result.Edges["b"] != "{:done :end}" {
		t.Errorf("b edge: %q", result.Edges["b"])
	}
}

func TestDeterministicEdgesThreeStepFlow(t *testing.T) {
	// For a 3-step flow a -> b -> c -> :end:
	// "a" has 2 unvisited targets (b, c) -> needs LLM (not deterministic)
	// "b" has 1 unvisited target (c) -> deterministic
	// "c" has 0 unvisited targets -> deterministic (goes to :end)
	// This simulates the deterministic portion (b and c after a is handled by LLM)
	steps := []*DecompositionNode{
		{StepName: "a", CellID: ":ns/a"},
		{StepName: "b", CellID: ":ns/b"},
		{StepName: "c", CellID: ":ns/c"},
	}

	visited := make(map[string]bool)
	result := &GraphWalkResult{
		Edges:      make(map[string]string),
		Dispatches: make(map[string]string),
	}

	// Simulate BFS: "a" is visited first but has 2 unvisited targets (needs LLM)
	visited["a"] = true
	unvisitedFromA := countUnvisitedTargets(steps, visited, "a")
	if unvisitedFromA != 2 {
		t.Fatalf("expected 2 unvisited from a, got %d", unvisitedFromA)
	}
	// LLM would decide a -> b, so simulate that
	result.Edges["a"] = "{:done :b}"
	result.Dispatches["a"] = "[[:done (constantly true)]]"
	result.LLMEdges++

	// Now continue BFS from "b" - deterministic fast path should apply
	frontier := []string{"b"}
	for len(frontier) > 0 {
		current := frontier[0]
		frontier = frontier[1:]
		if visited[current] {
			continue
		}
		visited[current] = true

		unvisited := countUnvisitedTargets(steps, visited, current)
		if unvisited <= 1 {
			target := ":end"
			if unvisited == 1 {
				target = ":" + findSingleUnvisitedTarget(steps, visited, current)
				frontier = append(frontier, findSingleUnvisitedTarget(steps, visited, current))
			}
			result.Edges[current] = fmt.Sprintf("{:done %s}", target)
			result.Dispatches[current] = "[[:done (constantly true)]]"
			result.DeterministicEdges++
		}
	}

	// 1 LLM edge (a), 2 deterministic edges (b, c)
	if result.LLMEdges != 1 {
		t.Errorf("expected 1 LLM edge, got %d", result.LLMEdges)
	}
	if result.DeterministicEdges != 2 {
		t.Errorf("expected 2 deterministic edges, got %d", result.DeterministicEdges)
	}
	if result.Edges["b"] != "{:done :c}" {
		t.Errorf("b edge: %q", result.Edges["b"])
	}
	if result.Edges["c"] != "{:done :end}" {
		t.Errorf("c edge: %q", result.Edges["c"])
	}
}

func TestDeterministicEdgeSingleStep(t *testing.T) {
	// A single step has 0 unvisited targets -> deterministic to :end
	steps := []*DecompositionNode{
		{StepName: "only", CellID: ":ns/only"},
	}

	visited := make(map[string]bool)
	visited["only"] = true
	unvisited := countUnvisitedTargets(steps, visited, "only")
	if unvisited != 0 {
		t.Fatalf("expected 0 unvisited, got %d", unvisited)
	}

	result := &GraphWalkResult{
		Edges:      make(map[string]string),
		Dispatches: make(map[string]string),
	}
	result.Edges["only"] = fmt.Sprintf("{:done :end}")
	result.Dispatches["only"] = "[[:done (constantly true)]]"
	result.DeterministicEdges++

	if result.DeterministicEdges != 1 {
		t.Errorf("expected 1 deterministic edge, got %d", result.DeterministicEdges)
	}
	if result.Edges["only"] != "{:done :end}" {
		t.Errorf("only edge: %q", result.Edges["only"])
	}
}
