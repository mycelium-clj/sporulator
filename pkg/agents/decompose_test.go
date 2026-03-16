package agents

import (
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
