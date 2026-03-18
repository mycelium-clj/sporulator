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
	if !strings.Contains(manifest, "{}") {
		t.Error("manifest should contain default {} schema")
	}
}

func TestEDNToString(t *testing.T) {
	// nil returns default
	if ednToString(nil) != "{}" {
		t.Error("nil should return {}")
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

func TestExtractEdgePairsLinear(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "validate", OutputSchema: "[:map [:items [:vector :map]]]"},
		{StepName: "process", InputSchema: "[:map [:items [:vector :map]]]", OutputSchema: "[:map [:result :string]]"},
		{StepName: "finalize", InputSchema: "[:map [:result :string]]"},
	}
	walk := &GraphWalkResult{
		Edges: map[string]string{
			"validate": "{:done :process}",
			"process":  "{:done :finalize}",
			"finalize": "{:done :end}",
		},
	}

	pairs := extractEdgePairs(steps, walk)
	if len(pairs) != 2 {
		t.Fatalf("expected 2 pairs, got %d", len(pairs))
	}

	// Find validate→process pair
	found := false
	for _, p := range pairs {
		if p.SourceName == "validate" && p.TargetName == "process" {
			found = true
			if p.SourceOutput != "[:map [:items [:vector :map]]]" {
				t.Errorf("source output: %q", p.SourceOutput)
			}
			if p.TargetInput != "[:map [:items [:vector :map]]]" {
				t.Errorf("target input: %q", p.TargetInput)
			}
		}
	}
	if !found {
		t.Error("validate→process pair not found")
	}
}

func TestExtractEdgePairsBranching(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "check", OutputSchema: "[:map [:approved :boolean]]"},
		{StepName: "approve", InputSchema: "[:map [:approved :boolean]]"},
		{StepName: "reject", InputSchema: "[:map [:reason :string]]"},
	}
	walk := &GraphWalkResult{
		Edges: map[string]string{
			"check":   "{:ok :approve :fail :reject}",
			"approve": "{:done :end}",
			"reject":  "{:done :end}",
		},
	}

	pairs := extractEdgePairs(steps, walk)
	if len(pairs) != 2 {
		t.Fatalf("expected 2 pairs (check→approve, check→reject), got %d", len(pairs))
	}
}

func TestExtractEdgePairsSkipsEnd(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "last", OutputSchema: "[:map]"},
	}
	walk := &GraphWalkResult{
		Edges: map[string]string{
			"last": "{:done :end}",
		},
	}

	pairs := extractEdgePairs(steps, walk)
	if len(pairs) != 0 {
		t.Fatalf("expected 0 pairs (only edge goes to :end), got %d", len(pairs))
	}
}

func TestExtractEdgePairsEmptyWalk(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "a", OutputSchema: "[:map]"},
	}
	walk := &GraphWalkResult{Edges: map[string]string{}}

	pairs := extractEdgePairs(steps, walk)
	if len(pairs) != 0 {
		t.Errorf("expected 0 pairs for empty walk, got %d", len(pairs))
	}
}

func TestIsValidEDN(t *testing.T) {
	if !isValidEDN("[:map [:x :int]]") {
		t.Error("should accept valid EDN")
	}
	if !isValidEDN("[:map]") {
		t.Error("should accept simple EDN")
	}
	if isValidEDN("[:map [:x") {
		t.Error("should reject unclosed bracket")
	}
	if isValidEDN("") {
		t.Error("should reject empty string")
	}
}

func TestExtractMapFieldsLiteSyntax(t *testing.T) {
	fields := extractMapFields("{:subtotal :double :tax :double :items [:vector :map]}")
	if fields == nil {
		t.Fatal("expected non-nil fields for lite syntax")
	}
	if len(fields) != 3 {
		t.Fatalf("expected 3 fields, got %d: %v", len(fields), fields)
	}
	if _, ok := fields["subtotal"]; !ok {
		t.Error("missing 'subtotal' field")
	}
	if _, ok := fields["tax"]; !ok {
		t.Error("missing 'tax' field")
	}
	if _, ok := fields["items"]; !ok {
		t.Error("missing 'items' field")
	}
}

func TestExtractMapFieldsGenericLite(t *testing.T) {
	if fields := extractMapFields("{}"); fields != nil {
		t.Errorf("expected nil for generic {}, got %v", fields)
	}
}

func TestCheckEdgeCompatibilityLiteSyntax(t *testing.T) {
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "{:items [:vector :map] :subtotal :double}",
		TargetName:   "b",
		TargetInput:  "{:items [:vector :map] :subtotal :double}",
	}
	if m := checkEdgeCompatibility(pair); m != nil {
		t.Errorf("lite syntax compatible schemas should match, got: %+v", m)
	}
}

func TestCheckEdgeCompatibilityLiteMissing(t *testing.T) {
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "{:items [:vector :map]}",
		TargetName:   "b",
		TargetInput:  "{:items [:vector :map] :subtotal :double}",
	}
	m := checkEdgeCompatibility(pair)
	if m == nil {
		t.Fatal("expected mismatch for missing field in lite syntax")
	}
	if len(m.Missing) != 1 || m.Missing[0] != "subtotal" {
		t.Errorf("expected missing 'subtotal', got %v", m.Missing)
	}
}

func TestValidateTreeSchemasClean(t *testing.T) {
	root := &DecompositionNode{
		StepName: "root",
		IsLeaf:   false,
		Manifest: `{:id :test/wf :cells {:start {:id :ns/a} :b {:id :ns/b}} :edges {:start :b} :dispatches {:start [[:done (constantly true)]]}}`,
		Children: []*DecompositionNode{
			{StepName: "a", IsLeaf: true, OutputSchema: "{:x :int}", InputSchema: "{}"},
			{StepName: "b", IsLeaf: true, InputSchema: "{:x :int}", OutputSchema: "{}"},
		},
	}
	mismatches := validateTreeSchemas(root)
	if len(mismatches) != 0 {
		t.Errorf("expected 0 mismatches, got %d: %+v", len(mismatches), mismatches)
	}
}

func TestValidateTreeSchemasMismatch(t *testing.T) {
	root := &DecompositionNode{
		StepName: "root",
		IsLeaf:   false,
		Manifest: `{:id :test/wf :cells {:start {:id :ns/a} :b {:id :ns/b}} :edges {:start :b} :dispatches {:start [[:done (constantly true)]]}}`,
		Children: []*DecompositionNode{
			{StepName: "a", IsLeaf: true, OutputSchema: "{:x :int}", InputSchema: "{}"},
			{StepName: "b", IsLeaf: true, InputSchema: "{:x :int :y :string}", OutputSchema: "{}"},
		},
	}
	mismatches := validateTreeSchemas(root)
	if len(mismatches) != 1 {
		t.Fatalf("expected 1 mismatch, got %d: %+v", len(mismatches), mismatches)
	}
	if len(mismatches[0].Missing) != 1 || mismatches[0].Missing[0] != "y" {
		t.Errorf("expected missing 'y', got %v", mismatches[0].Missing)
	}
}

func TestValidateTreeSchemasRecursive(t *testing.T) {
	// Sub-workflow with internal mismatch should be caught
	root := &DecompositionNode{
		StepName: "root",
		IsLeaf:   false,
		Manifest: `{:id :test/wf :cells {:start {:id :ns/sub}} :edges {:start :end} :dispatches {:start [[:done (constantly true)]]}}`,
		Children: []*DecompositionNode{
			{StepName: "sub", IsLeaf: false,
				Manifest: `{:id :sub/wf :cells {:start {:id :ns/c} :d {:id :ns/d}} :edges {:start :d} :dispatches {:start [[:done (constantly true)]]}}`,
				Children: []*DecompositionNode{
					{StepName: "c", IsLeaf: true, OutputSchema: "{:val :string}", InputSchema: "{}"},
					{StepName: "d", IsLeaf: true, InputSchema: "{:val :int}", OutputSchema: "{}"},
				},
			},
		},
	}
	mismatches := validateTreeSchemas(root)
	if len(mismatches) != 1 {
		t.Fatalf("expected 1 mismatch in sub-workflow, got %d: %+v", len(mismatches), mismatches)
	}
	if len(mismatches[0].TypeDiffs) != 1 {
		t.Errorf("expected type diff for 'val', got %+v", mismatches[0])
	}
}

func TestParseSchemaCorrectionsEmpty(t *testing.T) {
	response := `COMPATIBLE 1 — schemas match, both use [:map [:items [:vector :map]]]
COMPATIBLE 2 — result field present in both`

	corrections := parseSchemaCorrections(response)
	if len(corrections) != 0 {
		t.Errorf("expected 0 corrections, got %d", len(corrections))
	}
}

func TestParseSchemaCorrectionsWithFixes(t *testing.T) {
	response := `COMPATIBLE 1 — validate→process schemas match
INCOMPATIBLE 2 — process output is missing :tax-amount field needed by finalize input

CORRECTED :process output-schema
[:map [:result :string] [:tax-amount :double] [:total :double]]

CORRECTED :finalize input-schema
[:map [:result :string] [:tax-amount :double]]`

	corrections := parseSchemaCorrections(response)
	if len(corrections) != 2 {
		t.Fatalf("expected 2 corrections, got %d", len(corrections))
	}

	pc := corrections["process"]
	if pc == nil {
		t.Fatal("expected correction for 'process'")
	}
	if pc.OutputSchema == "" {
		t.Error("expected output schema correction for process")
	}
	if !strings.Contains(pc.OutputSchema, "tax-amount") {
		t.Errorf("process output should contain tax-amount: %q", pc.OutputSchema)
	}

	fc := corrections["finalize"]
	if fc == nil {
		t.Fatal("expected correction for 'finalize'")
	}
	if fc.InputSchema == "" {
		t.Error("expected input schema correction for finalize")
	}
}

func TestParseSchemaCorrectionsCodeFence(t *testing.T) {
	response := `INCOMPATIBLE 1 — missing fields

CORRECTED :validate output-schema
` + "```edn\n" + `[:map [:items [:vector :map]] [:validated :boolean]]` + "\n```"

	corrections := parseSchemaCorrections(response)
	if len(corrections) != 1 {
		t.Fatalf("expected 1 correction, got %d", len(corrections))
	}
	vc := corrections["validate"]
	if vc == nil {
		t.Fatal("expected correction for 'validate'")
	}
	if !strings.Contains(vc.OutputSchema, "validated") {
		t.Errorf("should contain 'validated': %q", vc.OutputSchema)
	}
	if strings.Contains(vc.OutputSchema, "```") {
		t.Errorf("should strip code fences: %q", vc.OutputSchema)
	}
}

func TestExtractMapFields(t *testing.T) {
	fields := extractMapFields("[:map [:items [:vector :map]] [:subtotal :double]]")
	if fields == nil {
		t.Fatal("expected non-nil fields")
	}
	if len(fields) != 2 {
		t.Fatalf("expected 2 fields, got %d: %v", len(fields), fields)
	}
	if _, ok := fields["items"]; !ok {
		t.Error("missing 'items' field")
	}
	if _, ok := fields["subtotal"]; !ok {
		t.Error("missing 'subtotal' field")
	}
}

func TestExtractMapFieldsGeneric(t *testing.T) {
	// Generic [:map] should return nil (no fields to check)
	if fields := extractMapFields("[:map]"); fields != nil {
		t.Errorf("expected nil for generic [:map], got %v", fields)
	}
	if fields := extractMapFields(""); fields != nil {
		t.Errorf("expected nil for empty string, got %v", fields)
	}
}

func TestExtractMapFieldsNotMap(t *testing.T) {
	// [:vector :int] is not a map schema
	if fields := extractMapFields("[:vector :int]"); fields != nil {
		t.Errorf("expected nil for non-map schema, got %v", fields)
	}
}

func TestCheckEdgeCompatibilityCompatible(t *testing.T) {
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map [:items [:vector :map]] [:subtotal :double]]",
		TargetName:   "b",
		TargetInput:  "[:map [:items [:vector :map]] [:subtotal :double]]",
	}
	if m := checkEdgeCompatibility(pair); m != nil {
		t.Errorf("expected compatible, got mismatch: %+v", m)
	}
}

func TestCheckEdgeCompatibilityExtraOutputFields(t *testing.T) {
	// Source has extra field :bonus — that's fine, target ignores it
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map [:items [:vector :map]] [:subtotal :double] [:bonus :double]]",
		TargetName:   "b",
		TargetInput:  "[:map [:items [:vector :map]] [:subtotal :double]]",
	}
	if m := checkEdgeCompatibility(pair); m != nil {
		t.Errorf("extra output fields should be compatible, got mismatch: %+v", m)
	}
}

func TestCheckEdgeCompatibilityMissingFields(t *testing.T) {
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map [:items [:vector :map]]]",
		TargetName:   "b",
		TargetInput:  "[:map [:items [:vector :map]] [:subtotal :double] [:tax :double]]",
	}
	m := checkEdgeCompatibility(pair)
	if m == nil {
		t.Fatal("expected mismatch for missing fields")
	}
	if len(m.Missing) != 2 {
		t.Errorf("expected 2 missing fields, got %d: %v", len(m.Missing), m.Missing)
	}
}

func TestCheckEdgeCompatibilityTypeMismatch(t *testing.T) {
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map [:count :string]]",
		TargetName:   "b",
		TargetInput:  "[:map [:count :int]]",
	}
	m := checkEdgeCompatibility(pair)
	if m == nil {
		t.Fatal("expected mismatch for type difference")
	}
	if len(m.TypeDiffs) != 1 {
		t.Errorf("expected 1 type diff, got %d: %v", len(m.TypeDiffs), m.TypeDiffs)
	}
}

func TestCheckEdgeCompatibilityIntToDouble(t *testing.T) {
	// :int output feeding :double input is safe widening
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map [:amount :int]]",
		TargetName:   "b",
		TargetInput:  "[:map [:amount :double]]",
	}
	if m := checkEdgeCompatibility(pair); m != nil {
		t.Errorf("int→double widening should be compatible, got: %+v", m)
	}
}

func TestCheckEdgeCompatibilityGenericInput(t *testing.T) {
	// Target accepts generic [:map] — always compatible
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map [:x :int]]",
		TargetName:   "b",
		TargetInput:  "[:map]",
	}
	if m := checkEdgeCompatibility(pair); m != nil {
		t.Errorf("generic input should be compatible, got: %+v", m)
	}
}

func TestCheckEdgeCompatibilityGenericOutputSpecificInput(t *testing.T) {
	// Source is generic [:map] but target expects specific fields — mismatch
	pair := EdgePair{
		SourceName:   "a",
		SourceOutput: "[:map]",
		TargetName:   "b",
		TargetInput:  "[:map [:x :int] [:y :string]]",
	}
	m := checkEdgeCompatibility(pair)
	if m == nil {
		t.Fatal("generic output with specific input should be a mismatch")
	}
	if len(m.Missing) != 2 {
		t.Errorf("expected 2 missing fields, got %d", len(m.Missing))
	}
}

func TestFindAllMismatches(t *testing.T) {
	pairs := []EdgePair{
		{SourceName: "a", SourceOutput: "[:map [:x :int]]", TargetName: "b", TargetInput: "[:map [:x :int]]"},
		{SourceName: "b", SourceOutput: "[:map [:x :int]]", TargetName: "c", TargetInput: "[:map [:x :int] [:y :string]]"},
		{SourceName: "c", SourceOutput: "[:map [:result :string]]", TargetName: "d", TargetInput: "[:map [:result :string]]"},
	}

	mismatches := findAllMismatches(pairs)
	if len(mismatches) != 1 {
		t.Fatalf("expected 1 mismatch (b→c), got %d", len(mismatches))
	}
	if mismatches[0].SourceName != "b" || mismatches[0].TargetName != "c" {
		t.Errorf("wrong mismatch: %s → %s", mismatches[0].SourceName, mismatches[0].TargetName)
	}
	if len(mismatches[0].Missing) != 1 || mismatches[0].Missing[0] != "y" {
		t.Errorf("expected missing field 'y', got %v", mismatches[0].Missing)
	}
}

func TestBuildSchemaFixPrompt(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "a", Doc: "Step A", InputSchema: "[:map]", OutputSchema: "[:map [:x :int]]"},
		{StepName: "b", Doc: "Step B", InputSchema: "[:map [:x :int] [:y :string]]", OutputSchema: "[:map]"},
	}
	mismatches := []SchemaMismatch{
		{SourceName: "a", TargetName: "b", Missing: []string{"y"}},
	}

	prompt := buildSchemaFixPrompt("test spec", steps, mismatches)
	if !strings.Contains(prompt, "MISSING from :a output: y") {
		t.Error("prompt should show missing field")
	}
	if !strings.Contains(prompt, "CORRECTED") {
		t.Error("prompt should explain CORRECTED format")
	}
	if !strings.Contains(prompt, "test spec") {
		t.Error("prompt should include the spec")
	}
}

func TestTypesCompatible(t *testing.T) {
	tests := []struct {
		output, input string
		want          bool
	}{
		{":int", ":int", true},
		{":string", ":string", true},
		{":int", ":double", true},  // safe widening
		{":double", ":int", false}, // narrowing
		{":string", ":int", false},
		// Structural equality — same map fields in different order
		{
			`[:vector{:name :string,:category :string,:price :double,:weight :double,:warehouse :string,:product-id :string}]`,
			`[:vector{:price :double,:weight :double,:warehouse :string,:product-id :string,:name :string,:category :string}]`,
			true,
		},
		// Structural equality — lite syntax maps in different order
		{
			`{:card :string,:gift-card-balance :double,:loyalty-points :int,:state :string}`,
			`{:state :string,:card :string,:gift-card-balance :double,:loyalty-points :int}`,
			true,
		},
		// Structural inequality — different field values
		{
			`{:name :string,:price :double}`,
			`{:name :string,:price :int}`,
			false,
		},
	}
	for _, tc := range tests {
		got := typesCompatible(tc.output, tc.input)
		if got != tc.want {
			t.Errorf("typesCompatible(%s, %s) = %v, want %v", tc.output, tc.input, got, tc.want)
		}
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

// --- Tests for iterative graph construction ---

func TestBuildBoundaryConstrainedSpec(t *testing.T) {
	step := &DecompositionNode{
		StepName:     "apply-promotions",
		Doc:          "Apply promotional discounts to order items",
		InputSchema:  "{:items [:vector {:product-id :string :price :double}] :coupons [:vector :string]}",
		OutputSchema: "{:items [:vector {:product-id :string :price :double :discount :double}] :total-discount :double}",
	}

	result := buildBoundaryConstrainedSpec(step, "test spec content")

	// Must contain the boundary constraint section
	if !strings.Contains(result, "BOUNDARY CONSTRAINT") {
		t.Error("should contain BOUNDARY CONSTRAINT header")
	}

	// Must contain both schemas
	if !strings.Contains(result, ":items") {
		t.Error("should contain input schema fields")
	}
	if !strings.Contains(result, ":total-discount") {
		t.Error("should contain output schema fields")
	}

	// Must explain the constraint
	if !strings.Contains(result, "first cell MUST accept") {
		t.Error("should explain first-cell constraint")
	}
	if !strings.Contains(result, "last cell MUST produce") {
		t.Error("should explain last-cell constraint")
	}

	// Must include the full spec
	if !strings.Contains(result, "test spec content") {
		t.Error("should include the full spec")
	}

	// Must include parent step info
	if !strings.Contains(result, "apply-promotions") {
		t.Error("should include parent step name")
	}
	if !strings.Contains(result, "Apply promotional discounts") {
		t.Error("should include parent step doc")
	}
}

func TestBuildBoundaryConstrainedSpecEmptySchemas(t *testing.T) {
	step := &DecompositionNode{
		StepName:     "simple-step",
		Doc:          "A simple step",
		InputSchema:  "",
		OutputSchema: "",
	}

	result := buildBoundaryConstrainedSpec(step, "spec")

	// Should use default "{}" for empty schemas
	if !strings.Contains(result, "{}") {
		t.Error("should use default schema for empty schemas")
	}
}

func TestTruncSchema(t *testing.T) {
	tests := []struct {
		input    string
		max      int
		expected string
	}{
		{"short", 10, "short"},
		{"{:items [:vector {:product-id :string :price :double}]}", 20, "{:items [:vector {:p..."},
		{"multi\nline\nschema", 20, "multi line schema"},
		{"", 10, ""},
	}
	for _, tc := range tests {
		got := truncSchema(tc.input, tc.max)
		if got != tc.expected {
			t.Errorf("truncSchema(%q, %d) = %q, want %q", tc.input, tc.max, got, tc.expected)
		}
	}
}

func TestBuildEdgeFixPrompt(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "compute-tax", Doc: "Calculate tax amounts",
			InputSchema: "{:items [:vector :map] :state :string}", OutputSchema: "{:items [:vector :map] :tax :double}"},
		{StepName: "compute-shipping", Doc: "Calculate shipping costs",
			InputSchema: "{:items [:vector :map] :tax :double :state :string}", OutputSchema: "{:shipping :double}"},
	}
	mismatch := SchemaMismatch{
		SourceName: "compute-tax",
		TargetName: "compute-shipping",
		Missing:    []string{"state"},
	}

	prompt := buildEdgeFixPrompt("test spec", steps, mismatch)

	// Should show both cells
	if !strings.Contains(prompt, "SOURCE cell :compute-tax") {
		t.Error("should show source cell")
	}
	if !strings.Contains(prompt, "TARGET cell :compute-shipping") {
		t.Error("should show target cell")
	}

	// Should show the problem
	if !strings.Contains(prompt, "state") {
		t.Error("should mention the missing field")
	}

	// Should ask for corrections in the right format
	if !strings.Contains(prompt, "CORRECTED") {
		t.Error("should include CORRECTED format instruction")
	}

	// Should be focused on ONE edge
	if !strings.Contains(prompt, "Fix ONE schema incompatibility") {
		t.Error("should emphasize single-edge focus")
	}

	// Should include cell docs
	if !strings.Contains(prompt, "Calculate tax amounts") {
		t.Error("should include source doc")
	}
	if !strings.Contains(prompt, "Calculate shipping costs") {
		t.Error("should include target doc")
	}
}

func TestBuildEdgeFixPromptTypeDiff(t *testing.T) {
	steps := []*DecompositionNode{
		{StepName: "a", Doc: "Step A", InputSchema: "{}", OutputSchema: "{:count :string}"},
		{StepName: "b", Doc: "Step B", InputSchema: "{:count :int}", OutputSchema: "{}"},
	}
	mismatch := SchemaMismatch{
		SourceName: "a",
		TargetName: "b",
		TypeDiffs:  []string{"count: output=:string input=:int"},
	}

	prompt := buildEdgeFixPrompt("spec", steps, mismatch)

	if !strings.Contains(prompt, "Type mismatches") {
		t.Error("should show type mismatch section")
	}
	if !strings.Contains(prompt, "count: output=:string input=:int") {
		t.Error("should include specific type diff")
	}
}

func TestBuildEdgeFixPromptMissingSteps(t *testing.T) {
	// Edge case: step not found in steps slice (dangling reference)
	steps := []*DecompositionNode{
		{StepName: "a", Doc: "Step A", InputSchema: "{}", OutputSchema: "{:x :int}"},
	}
	mismatch := SchemaMismatch{
		SourceName: "a",
		TargetName: "unknown",
		Missing:    []string{"x"},
	}

	prompt := buildEdgeFixPrompt("spec", steps, mismatch)

	// Should still produce a valid prompt even with missing target
	if !strings.Contains(prompt, "SOURCE cell :a") {
		t.Error("should show source cell")
	}
	if !strings.Contains(prompt, "TARGET cell :unknown") {
		t.Error("should show target cell name even if not found")
	}
}
