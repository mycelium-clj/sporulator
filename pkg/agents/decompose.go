package agents

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"regexp"
	"strings"

	"github.com/mycelium-clj/sporulator/pkg/bridge"
	edn "olympos.io/encoding/edn"
)

// DecompositionNode represents a step in a recursively decomposed workflow.
// Leaf nodes become individual cells; non-leaf nodes become sub-workflows
// registered via compose/register-workflow-cell!.
type DecompositionNode struct {
	StepName     string               // e.g. "validate-input"
	CellID       string               // e.g. ":order/validate-input"
	Doc          string               // what this step does
	InputSchema  string               // EDN schema for input
	OutputSchema string               // EDN schema for output
	Requires     []string             // resource dependencies
	IsLeaf       bool                 // true if implementable as one cell
	Children     []*DecompositionNode // sub-steps if !IsLeaf
	Manifest     string               // assembled EDN manifest for non-leaf nodes
	WalkResult   *GraphWalkResult     // edge topology for this level (non-leaf nodes)
	Depth        int                  // recursion depth (0 = root)
}

// DecompositionConfig controls decomposition behavior.
type DecompositionConfig struct {
	MaxStepsPerLevel int // max steps per decomposition level (default 5)
	MaxDepth         int // max recursion depth (default 3)
}

// GraphWalkResult holds the edges and dispatches decided during BFS graph walk.
type GraphWalkResult struct {
	Edges              map[string]string // stepName → EDN edges entry e.g. "{:done :process}"
	Dispatches         map[string]string // stepName → EDN dispatches entry e.g. "[[:done (constantly true)]]"
	DeterministicEdges int               // count of edges decided without LLM (linear fast path)
	LLMEdges           int               // count of edges decided via LLM call
}

// DefaultDecompositionConfig returns sensible defaults.
func DefaultDecompositionConfig() DecompositionConfig {
	return DecompositionConfig{
		MaxStepsPerLevel: 5,
		MaxDepth:         3,
	}
}

// SerializeTree marshals a DecompositionNode tree to JSON.
func SerializeTree(root *DecompositionNode) (string, error) {
	data, err := json.Marshal(root)
	if err != nil {
		return "", fmt.Errorf("serialize tree: %w", err)
	}
	return string(data), nil
}

// DeserializeTree unmarshals a JSON string into a DecompositionNode tree.
func DeserializeTree(jsonStr string) (*DecompositionNode, error) {
	var root DecompositionNode
	if err := json.Unmarshal([]byte(jsonStr), &root); err != nil {
		return nil, fmt.Errorf("deserialize tree: %w", err)
	}
	return &root, nil
}

// --- Pure functions ---

// collectLeaves returns all leaf nodes in the decomposition tree (depth-first).
func collectLeaves(root *DecompositionNode) []*DecompositionNode {
	if root == nil {
		return nil
	}
	if root.IsLeaf {
		return []*DecompositionNode{root}
	}
	var leaves []*DecompositionNode
	for _, child := range root.Children {
		leaves = append(leaves, collectLeaves(child)...)
	}
	return leaves
}

// collectSubWorkflows returns non-leaf nodes in post-order (deepest first).
// This ordering ensures children are registered before parents.
func collectSubWorkflows(root *DecompositionNode) []*DecompositionNode {
	if root == nil {
		return nil
	}
	var result []*DecompositionNode
	for _, child := range root.Children {
		if !child.IsLeaf {
			result = append(result, collectSubWorkflows(child)...)
			result = append(result, child)
		}
	}
	return result
}

// assembleManifest builds an EDN manifest string from steps and walk results.
// For non-leaf steps, the cell ID references the sub-workflow cell.
func assembleManifest(id, nsPrefix string, steps []*DecompositionNode, walk *GraphWalkResult) string {
	var b strings.Builder

	b.WriteString("{:id ")
	b.WriteString(id)
	b.WriteString("\n :cells\n {")

	for i, step := range steps {
		if i > 0 {
			b.WriteString("\n  ")
		}
		stepKey := ":" + step.StepName
		if i == 0 {
			stepKey = ":start"
		}

		b.WriteString(stepKey)
		b.WriteString(" {:id ")
		b.WriteString(step.CellID)

		b.WriteString("\n")
		b.WriteString("         :doc \"")
		b.WriteString(strings.ReplaceAll(step.Doc, `"`, `\"`))
		b.WriteString("\"")

		b.WriteString("\n")
		b.WriteString("         :schema {:input ")
		if step.InputSchema != "" {
			b.WriteString(step.InputSchema)
		} else {
			b.WriteString("{}")
		}
		b.WriteString(" :output ")
		if step.OutputSchema != "" {
			b.WriteString(step.OutputSchema)
		} else {
			b.WriteString("{}")
		}
		b.WriteString("}")

		if len(step.Requires) > 0 {
			b.WriteString("\n")
			b.WriteString("         :requires [")
			for j, r := range step.Requires {
				if j > 0 {
					b.WriteString(" ")
				}
				b.WriteString(":" + r)
			}
			b.WriteString("]")
		}

		b.WriteString("}")
	}

	b.WriteString("}\n :edges\n {")

	// Build edges from walk result, mapping first step to :start
	for i, step := range steps {
		stepKey := ":" + step.StepName
		if i == 0 {
			stepKey = ":start"
		}
		if edges, ok := walk.Edges[step.StepName]; ok {
			if i > 0 {
				b.WriteString("\n  ")
			}
			b.WriteString(stepKey)
			b.WriteString(" ")
			b.WriteString(edges)
		}
	}

	b.WriteString("}\n :dispatches\n {")

	// Build dispatches from walk result
	for i, step := range steps {
		stepKey := ":" + step.StepName
		if i == 0 {
			stepKey = ":start"
		}
		if dispatches, ok := walk.Dispatches[step.StepName]; ok {
			if i > 0 {
				b.WriteString("\n  ")
			}
			b.WriteString(stepKey)
			b.WriteString(" ")
			b.WriteString(dispatches)
		}
	}

	b.WriteString("}}")

	return b.String()
}

// --- Parsing functions ---

// decompositionStep is the EDN-deserialized form of a step from the LLM.
type decompositionStep struct {
	Name         string      `edn:"name"`
	Doc          string      `edn:"doc"`
	InputSchema  interface{} `edn:"input-schema"`
	OutputSchema interface{} `edn:"output-schema"`
	Requires     []edn.Keyword `edn:"requires"`
	Simple       bool        `edn:"simple?"`
}

// parseDecompositionResponse parses the LLM's decomposition response using EDN deserialization.
// Expects an EDN vector of maps in a code block.
func parseDecompositionResponse(response, nsPrefix string) ([]*DecompositionNode, error) {
	block := ExtractFirstCodeBlock(response)
	if block == "" {
		block = response
	}

	var steps []decompositionStep
	if err := edn.Unmarshal([]byte(block), &steps); err != nil {
		return nil, fmt.Errorf("EDN parse error: %w", err)
	}

	if len(steps) == 0 {
		return nil, fmt.Errorf("no steps found in decomposition response")
	}

	var nodes []*DecompositionNode
	for _, step := range steps {
		if step.Name == "" {
			continue
		}

		node := &DecompositionNode{
			StepName:     step.Name,
			CellID:       ":" + nsPrefix + "/" + step.Name,
			Doc:          step.Doc,
			InputSchema:  ednToString(step.InputSchema),
			OutputSchema: ednToString(step.OutputSchema),
			IsLeaf:       step.Simple,
		}

		for _, r := range step.Requires {
			node.Requires = append(node.Requires, string(r))
		}

		nodes = append(nodes, node)
	}

	if len(nodes) == 0 {
		return nil, fmt.Errorf("no valid steps parsed from decomposition response")
	}

	return nodes, nil
}

// ednToString marshals an arbitrary EDN value back to its string representation.
// Returns "{}" (generic lite map) if the value is nil or marshaling fails.
func ednToString(v interface{}) string {
	if v == nil {
		return "{}"
	}
	var buf bytes.Buffer
	if err := edn.NewEncoder(&buf).Encode(v); err != nil {
		return "{}"
	}
	result := strings.TrimSpace(buf.String())
	if result == "" {
		return "{}"
	}
	return result
}

// countUnvisitedTargets counts how many unvisited non-end steps remain as potential targets.
func countUnvisitedTargets(steps []*DecompositionNode, visited map[string]bool, current string) int {
	count := 0
	for _, s := range steps {
		if !visited[s.StepName] && s.StepName != current {
			count++
		}
	}
	return count
}

// findSingleUnvisitedTarget returns the name of the single unvisited target (excluding current).
// Should only be called when countUnvisitedTargets returns 1.
func findSingleUnvisitedTarget(steps []*DecompositionNode, visited map[string]bool, current string) string {
	for _, s := range steps {
		if !visited[s.StepName] && s.StepName != current {
			return s.StepName
		}
	}
	return ""
}

// parseManifestCellNames extracts cell step names from a manifest EDN string
// using the EDN library. Returns step names (keys of the :cells map).
func parseManifestCellNames(manifestEDN string) ([]string, error) {
	var manifest map[edn.Keyword]interface{}
	if err := edn.Unmarshal([]byte(manifestEDN), &manifest); err != nil {
		return nil, fmt.Errorf("EDN parse error: %w", err)
	}

	cellsRaw, ok := manifest[edn.Keyword("cells")]
	if !ok {
		return nil, fmt.Errorf("no :cells key in manifest")
	}

	cells, ok := cellsRaw.(map[interface{}]interface{})
	if !ok {
		return nil, fmt.Errorf(":cells is not a map")
	}

	var names []string
	for k := range cells {
		switch key := k.(type) {
		case edn.Keyword:
			names = append(names, string(key))
		}
	}
	return names, nil
}

// parseEdgeResponse parses a single cell's edges and dispatches from an LLM response.
// Expects two EDN forms: edges map and dispatches vector (which contains Clojure fns).
func parseEdgeResponse(response string) (edges, dispatches string, targets []string, err error) {
	block := ExtractFirstCodeBlock(response)
	if block == "" {
		block = response
	}

	// Extract the edges map by finding the first balanced {...} in the block.
	edgesStr := extractFirstBalancedBraces(block)
	if edgesStr == "" {
		return "", "", nil, fmt.Errorf("no edges found in response")
	}
	edges = edgesStr

	// Parse the edges EDN to extract target keywords structurally
	var parsed interface{}
	if err := edn.Unmarshal([]byte(edgesStr), &parsed); err == nil {
		targets = extractTargetKeywords(parsed)
	}

	// Find dispatches: [[:keyword (fn ...)]] or [[:keyword (constantly true)]]
	// Dispatches contain Clojure function literals which aren't valid EDN,
	// so we extract them as a raw string using bracket matching.
	dispatchStr := extractFirstBalancedBrackets(block)
	if dispatchStr != "" {
		dispatches = dispatchStr
	} else {
		// Try subsequent code blocks
		blocks := ExtractCodeBlocks(response)
		for _, b := range blocks {
			if strings.Contains(b, "[[") {
				dispatches = b
				break
			}
		}
	}

	if dispatches == "" {
		return "", "", nil, fmt.Errorf("no dispatches found in response")
	}

	return edges, dispatches, targets, nil
}

// extractFirstBalancedBraces finds the first balanced {...} substring.
func extractFirstBalancedBraces(s string) string {
	start := strings.IndexByte(s, '{')
	if start < 0 {
		return ""
	}
	depth := 0
	for i := start; i < len(s); i++ {
		switch s[i] {
		case '{':
			depth++
		case '}':
			depth--
			if depth == 0 {
				return s[start : i+1]
			}
		}
	}
	return ""
}

// extractFirstBalancedBrackets finds the first balanced [[...]] substring.
func extractFirstBalancedBrackets(s string) string {
	start := strings.Index(s, "[[")
	if start < 0 {
		return ""
	}
	depth := 0
	for i := start; i < len(s); i++ {
		switch s[i] {
		case '[':
			depth++
		case ']':
			depth--
			if depth == 0 {
				return s[start : i+1]
			}
		}
	}
	return ""
}

// --- Core LLM-driven functions ---

// decompose recursively breaks a specification into a tree of steps.
// Each level has at most cfg.MaxStepsPerLevel steps.
// Complex steps are recursed into sub-workflows up to cfg.MaxDepth.
func (o *Orchestrator) decompose(ctx context.Context, spec, nsPrefix string,
	parentSteps []string, depth int, cfg DecompositionConfig,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) (*DecompositionNode, error) {

	nodeID := fmt.Sprintf("decompose-d%d-%s", depth, nsPrefix)
	agent := o.manager.NewDecomposeAgent(nodeID)

	// Build decomposition prompt
	prompt := buildDecomposePrompt(spec, nsPrefix, parentSteps, cfg.MaxStepsPerLevel)

	onEvent(OrchestratorEvent{Phase: "decompose", Status: "started",
		Message: fmt.Sprintf("Decomposing at depth %d (ns: %s)", depth, nsPrefix)})

	response, err := agent.ChatStream(ctx, prompt,
		func(chunk string) { onChunk("decompose/"+nodeID, chunk) })
	if err != nil {
		return nil, fmt.Errorf("decompose at depth %d: %w", depth, err)
	}

	steps, err := parseDecompositionResponse(response, nsPrefix)
	if err != nil {
		// Retry with more explicit instruction
		retryPrompt := `Your response could not be parsed. Return ONLY an EDN vector of maps in a code block.

Each map must have these keys:
- :name — a short keyword name (string, e.g. "validate-input")
- :doc — what this step does (string)
- :input-schema — EDN schema (e.g. [:map])
- :output-schema — EDN schema (e.g. [:map])
- :requires — vector of resource keywords (e.g. [:db])
- :simple? — true or false

Example:
` + "```edn\n" + `[{:name "validate" :doc "Validate the input data" :input-schema [:map] :output-schema [:map] :requires [] :simple? true}
 {:name "process" :doc "Process the validated data" :input-schema [:map] :output-schema [:map] :requires [:db] :simple? false}]
` + "```"

		response, err = agent.ChatStream(ctx, retryPrompt,
			func(chunk string) { onChunk("decompose/"+nodeID, chunk) })
		if err != nil {
			return nil, fmt.Errorf("decompose retry at depth %d: %w", depth, err)
		}
		steps, err = parseDecompositionResponse(response, nsPrefix)
		if err != nil {
			return nil, fmt.Errorf("parse decomposition at depth %d: %w", depth, err)
		}
	}

	onEvent(OrchestratorEvent{Phase: "decompose", Status: "parsed",
		Message: fmt.Sprintf("Depth %d: %d steps", depth, len(steps))})

	// Set depth on all steps
	for _, step := range steps {
		step.Depth = depth
	}

	// At max depth, force all steps to be leaves
	if depth >= cfg.MaxDepth {
		for _, step := range steps {
			step.IsLeaf = true
		}
	}

	// --- Level-by-level validation: walk + validate + fix BEFORE recursion ---
	// This ensures the current level's contracts are locked before descending.

	// Walk the graph to determine edges and dispatches
	walk, err := o.walkGraph(ctx, steps, agent, onChunk, onEvent)
	if err != nil {
		return nil, fmt.Errorf("graph walk at depth %d: %w", depth, err)
	}

	// Validate and reconcile schema compatibility across all edges (edge-by-edge)
	if err := o.validateEdgeSchemas(ctx, spec, steps, walk, agent, onChunk, onEvent); err != nil {
		return nil, fmt.Errorf("edge schema validation at depth %d: %w", depth, err)
	}

	onEvent(OrchestratorEvent{Phase: "decompose", Status: "level_locked",
		Message: fmt.Sprintf("Depth %d: schemas locked for %d steps", depth, len(steps))})

	// --- Recurse into non-leaf children with boundary constraints ---
	// Each child's outer contract (input→output) is already fixed by the
	// validated schemas above. Sub-decomposition must stay within those bounds.
	if depth < cfg.MaxDepth {
		for _, step := range steps {
			if !step.IsLeaf {
				onEvent(OrchestratorEvent{Phase: "decompose", Status: "recursing",
					Message: fmt.Sprintf("Recursing into complex step: %s (boundary: %s → %s)",
						step.StepName, truncSchema(step.InputSchema, 60), truncSchema(step.OutputSchema, 60))})

				subPrefix := nsPrefix + "." + step.StepName

				// Build boundary-constrained spec: sub-workflow must accept
				// parent's input schema and produce parent's output schema
				subSpec := buildBoundaryConstrainedSpec(step, spec)

				stepNames := make([]string, len(steps))
				for i, s := range steps {
					stepNames[i] = s.StepName
				}

				subRoot, subErr := o.decompose(ctx, subSpec, subPrefix, stepNames, depth+1, cfg, onChunk, onEvent)
				if subErr != nil {
					// Fall back to treating this as a leaf
					onEvent(OrchestratorEvent{Phase: "decompose", Status: "warning",
						Message: fmt.Sprintf("Could not decompose %s, treating as leaf: %v", step.StepName, subErr)})
					step.IsLeaf = true
					continue
				}
				step.Children = subRoot.Children
				step.Manifest = subRoot.Manifest
				step.WalkResult = subRoot.WalkResult
			}
		}
	}

	// Assemble manifest for this level
	manifest := assembleManifest(":"+nsPrefix+"/workflow", nsPrefix, steps, walk)

	// Validate via bridge
	br := o.manager.GetBridge()
	if br != nil {
		o.validateAssembledManifest(br, manifest, onEvent)
	}

	// Build root node
	root := &DecompositionNode{
		StepName:   nsPrefix,
		CellID:     ":" + nsPrefix + "/workflow",
		Doc:        "Root workflow for " + nsPrefix,
		IsLeaf:     false,
		Children:   steps,
		Manifest:   manifest,
		WalkResult: walk,
		Depth:      depth,
	}

	return root, nil
}

// walkGraph performs a BFS from :start, asking the LLM about edges/dispatches one cell at a time.
func (o *Orchestrator) walkGraph(ctx context.Context, steps []*DecompositionNode,
	agent *GraphAgent,
	onChunk func(string, string), onEvent func(OrchestratorEvent)) (*GraphWalkResult, error) {

	result := &GraphWalkResult{
		Edges:      make(map[string]string),
		Dispatches: make(map[string]string),
	}

	if len(steps) == 0 {
		return result, nil
	}

	// Build step name set for validation
	stepNames := make(map[string]bool)
	for _, s := range steps {
		stepNames[s.StepName] = true
	}
	stepNames["end"] = true // terminal

	// Track visited and frontier for BFS
	visited := make(map[string]bool)
	frontier := []string{steps[0].StepName} // start with first step

	// Build step lookup
	stepByName := make(map[string]*DecompositionNode)
	for _, s := range steps {
		stepByName[s.StepName] = s
	}

	// Summary of edges decided so far
	var edgeSummary strings.Builder

	// Outer loop: BFS + unreachable patching.
	// After each BFS pass, if any steps are unreachable we patch
	// the last :end-targeting edge to route to the first unreachable step,
	// then continue BFS from there. This handles specs with independent
	// branches (e.g. placement + returns + modifications).
	for {
		for len(frontier) > 0 {
			current := frontier[0]
			frontier = frontier[1:]

			if visited[current] || current == "end" {
				continue
			}
			visited[current] = true

			step := stepByName[current]
			if step == nil {
				continue
			}

			// Build list of available targets
			var availableTargets []string
			for _, s := range steps {
				if !visited[s.StepName] && s.StepName != current {
					availableTargets = append(availableTargets, ":"+s.StepName)
				}
			}
			availableTargets = append(availableTargets, ":end")

			// Fast path: single unvisited non-:end target → deterministic linear edge
			unvisitedNonEnd := countUnvisitedTargets(steps, visited, current)
			if unvisitedNonEnd <= 1 {
				target := ":end"
				if unvisitedNonEnd == 1 {
					singleTarget := findSingleUnvisitedTarget(steps, visited, current)
					target = ":" + singleTarget
					if !visited[singleTarget] {
						frontier = append(frontier, singleTarget)
					}
				}
				result.Edges[current] = fmt.Sprintf("{:done %s}", target)
				result.Dispatches[current] = "[[:done (constantly true)]]"
				result.DeterministicEdges++

				edgeSummary.WriteString(fmt.Sprintf(":%s → {:done %s}\n", current, target))

				onEvent(OrchestratorEvent{Phase: "graph_walk", CellID: step.CellID, Status: "deterministic",
					Message: fmt.Sprintf("%s edges: {:done %s} (deterministic)", step.StepName, target)})
				continue // skip LLM call
			}

			result.LLMEdges++

			prompt := buildGraphWalkPrompt(step, availableTargets, edgeSummary.String(), steps)

			onEvent(OrchestratorEvent{Phase: "graph_walk", CellID: step.CellID, Status: "started",
				Message: fmt.Sprintf("Deciding edges for %s", step.StepName)})

			response, err := agent.ChatStream(ctx, prompt,
				func(chunk string) { onChunk("walk/"+current, chunk) })
			if err != nil {
				return nil, fmt.Errorf("graph walk for %s: %w", current, err)
			}

			edges, dispatches, targets, parseErr := parseEdgeResponse(response)
			if parseErr != nil {
				// Retry with explicit format
				retryPrompt := fmt.Sprintf(`Could not parse edges for %s. Return EXACTLY two EDN forms:

1. Edges map: {:done :next-step} or {:ok :step-a :fail :step-b}
2. Dispatches vector: [[:done (constantly true)]] or [[:ok (fn [data] ...)] [:fail (fn [data] ...)]]

Available targets: %s
Use :end for terminal transitions.`, step.StepName, strings.Join(availableTargets, " "))

				response, err = agent.ChatStream(ctx, retryPrompt,
					func(chunk string) { onChunk("walk/"+current, chunk) })
				if err != nil {
					return nil, fmt.Errorf("graph walk retry for %s: %w", current, err)
				}
				edges, dispatches, targets, parseErr = parseEdgeResponse(response)
				if parseErr != nil {
					// Last resort: unconditional to next step or end
					nextTarget := ":end"
					for _, s := range steps {
						if !visited[s.StepName] && s.StepName != current {
							nextTarget = ":" + s.StepName
							break
						}
					}
					edges = fmt.Sprintf("{:done %s}", nextTarget)
					dispatches = "[[:done (constantly true)]]"
					targets = []string{strings.TrimPrefix(nextTarget, ":")}
					onEvent(OrchestratorEvent{Phase: "graph_walk", CellID: step.CellID, Status: "warning",
						Message: fmt.Sprintf("Using fallback edge for %s → %s", current, nextTarget)})
				}
			}

			// Validate targets exist
			for _, t := range targets {
				if !stepNames[t] {
					onEvent(OrchestratorEvent{Phase: "graph_walk", CellID: step.CellID, Status: "warning",
						Message: fmt.Sprintf("Target :%s does not exist, skipping", t)})
				}
			}

			result.Edges[current] = edges
			result.Dispatches[current] = dispatches

			// Add unvisited targets to frontier
			for _, t := range targets {
				if stepNames[t] && !visited[t] {
					frontier = append(frontier, t)
				}
			}

			// Update edge summary for next iteration
			edgeSummary.WriteString(fmt.Sprintf(":%s → %s\n", current, edges))

			onEvent(OrchestratorEvent{Phase: "graph_walk", CellID: step.CellID, Status: "decided",
				Message: fmt.Sprintf("%s edges: %s", step.StepName, edges)})
		}

		// Check for unreachable steps
		var unreachable []string
		for _, s := range steps {
			if !visited[s.StepName] {
				unreachable = append(unreachable, s.StepName)
			}
		}
		if len(unreachable) == 0 {
			break // all steps reachable
		}

		// Patch: find last visited step that targets :end, redirect to first unreachable.
		// This creates a reachable path from :start through the graph to the unreachable steps.
		patched := false
		for i := len(steps) - 1; i >= 0; i-- {
			name := steps[i].StepName
			if !visited[name] {
				continue
			}
			edges, ok := result.Edges[name]
			if !ok {
				continue
			}
			if strings.Contains(edges, ":end") {
				newTarget := ":" + unreachable[0]
				result.Edges[name] = strings.Replace(edges, ":end", newTarget, 1)
				onEvent(OrchestratorEvent{Phase: "graph_walk", Status: "patched",
					Message: fmt.Sprintf("Patched %s: :end → %s to fix reachability", name, newTarget)})
				edgeSummary.WriteString(fmt.Sprintf("(patched :%s → %s)\n", name, result.Edges[name]))
				patched = true
				break
			}
		}

		if !patched {
			for _, u := range unreachable {
				onEvent(OrchestratorEvent{Phase: "graph_walk", Status: "warning",
					Message: fmt.Sprintf("Unreachable step: %s (no :end edge to patch)", u)})
			}
			break
		}

		// Continue BFS from unreachable steps
		frontier = unreachable
	}

	return result, nil
}

// validateAssembledManifest checks the assembled manifest via the bridge.
func (o *Orchestrator) validateAssembledManifest(br *bridge.Bridge, manifest string, onEvent func(OrchestratorEvent)) {
	result, err := br.ValidateManifestEDN(manifest)
	if err != nil {
		onEvent(OrchestratorEvent{Phase: "decompose", Status: "warning",
			Message: fmt.Sprintf("Manifest validation connection error: %v", err)})
		return
	}
	if result.IsError() {
		onEvent(OrchestratorEvent{Phase: "decompose", Status: "warning",
			Message: fmt.Sprintf("Manifest validation error: %s %s", result.Ex, result.Err)})
	} else {
		onEvent(OrchestratorEvent{Phase: "decompose", Status: "validated",
			Message: fmt.Sprintf("Manifest validated: %s", result.Value)})
	}
}

// --- Prompt builders ---

func buildDecomposePrompt(spec, nsPrefix string, parentSteps []string, maxSteps int) string {
	var b strings.Builder

	fmt.Fprintf(&b, `Break this specification into at most %d high-level steps.

<spec>
%s
</spec>

`, maxSteps, spec)

	if len(parentSteps) > 0 {
		fmt.Fprintf(&b, "This is a sub-decomposition. Parent workflow steps: %s\n\n", strings.Join(parentSteps, ", "))
	}

	b.WriteString(`For each step return an EDN map with:
- :name — short keyword name (string, e.g. "validate-input")
- :doc — what this step does (1-2 sentences, string)
- :input-schema — lite Malli schema with field-level detail (see example)
- :output-schema — lite Malli schema with field-level detail
- :requires — vector of resource keywords (e.g. [:db :cache])
- :simple? — true if implementable as one cell (~80 lines of Clojure), false if complex

IMPORTANT — schemas use lite Malli syntax: {:field-name :type} for maps.
  :string, :int, :double, :boolean, :keyword for primitives
  [:vector <type>] for collections, {:field :type} for maps/structs
  [:map-of :keyword :type] for dynamic maps, [:maybe :type] for optional

Return the steps as an EDN vector in a code block. Example:

`)
	b.WriteString("```edn\n")
	b.WriteString(`[{:name "calculate-totals" :doc "Calculate order totals with tax" :input-schema {:items [:vector {:product-id :string :quantity :int :unit-price :double}] :tax-rate :double} :output-schema {:subtotal :double :tax-amount :double :total :double} :requires [:catalog] :simple? true}
 {:name "process-payment" :doc "Charge the customer" :input-schema {:total :double :payment-method :keyword} :output-schema {:transaction-id :string :status :keyword} :requires [:payment-gateway] :simple? true}]`)
	b.WriteString("\n```\n")

	fmt.Fprintf(&b, "\nCell IDs will be namespaced as :%s/<step-name>.", nsPrefix)

	return b.String()
}

// buildBoundaryConstrainedSpec creates a sub-decomposition prompt that constrains
// the sub-workflow to accept the parent cell's input schema and produce its output schema.
// This ensures sub-decomposition stays within the already-locked contract.
func buildBoundaryConstrainedSpec(parentStep *DecompositionNode, fullSpec string) string {
	var b strings.Builder

	fmt.Fprintf(&b, `Sub-task of the parent workflow.

Parent step: %s
Description: %s

BOUNDARY CONSTRAINT — this sub-workflow has a locked contract:
  Input schema (first cell MUST accept):  %s
  Output schema (last cell MUST produce): %s

The sub-workflow's first cell must accept the parent's input schema exactly.
The sub-workflow's last cell must produce the parent's output schema exactly.
Internal cells can have any schemas as long as they chain correctly from input to output.
Do NOT add or remove fields from the boundary schemas.

Full spec:
%s`,
		parentStep.StepName, parentStep.Doc,
		defaultSchema(parentStep.InputSchema),
		defaultSchema(parentStep.OutputSchema),
		fullSpec)

	return b.String()
}

// truncSchema truncates a schema string for display in log messages.
func truncSchema(s string, max int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) <= max {
		return s
	}
	return s[:max] + "..."
}

func buildGraphWalkPrompt(step *DecompositionNode, availableTargets []string, edgeSummary string, allSteps []*DecompositionNode) string {
	var b strings.Builder

	fmt.Fprintf(&b, `Cell :%s does: "%s"
Schema: input %s, output %s

Available targets: %s
`,
		step.StepName, step.Doc,
		step.InputSchema, step.OutputSchema,
		strings.Join(availableTargets, " "))

	// Show target input schemas so the LLM knows data requirements
	b.WriteString("\nTarget input schemas:\n")
	targetSet := make(map[string]bool)
	for _, t := range availableTargets {
		targetSet[t] = true
	}
	for _, s := range allSteps {
		if targetSet[s.StepName] {
			fmt.Fprintf(&b, "  :%s input: %s\n", s.StepName, defaultSchema(s.InputSchema))
		}
	}
	b.WriteString("\n")

	if edgeSummary != "" {
		fmt.Fprintf(&b, "Already connected:\n%s\n", edgeSummary)
	}

	b.WriteString(`
IMPORTANT: Only create an edge to a target if this cell's output contains the
fields that target needs, or if intervening cells will compute them. Do NOT
create shortcut edges that skip computation steps — data must flow through
all computation cells in sequence. Use :end for error/terminal outcomes.

What are this cell's outcomes? Return TWO EDN forms in a code block:

1. Edges map: {:<outcome-keyword> :<target-step>}
   For unconditional: {:done :next-step}
   For conditional: {:approved :step-a :rejected :step-b}

2. Dispatches vector: [[:outcome (fn [data] predicate)]]
   For unconditional: [[:done (constantly true)]]
   For conditional: [[:approved (fn [data] (:approved? data))] [:rejected (fn [data] (not (:approved? data)))]]

Example:
`)
	b.WriteString("```edn\n")
	b.WriteString("{:done :next-step}\n[[:done (constantly true)]]\n")
	b.WriteString("```\n")

	return b.String()
}

// --- Whole-tree schema validation ---

// validateTreeSchemas walks the entire decomposition tree and checks schema
// compatibility at every level. Returns all mismatches found across the tree.
// This is a purely deterministic check — no LLM calls.
func validateTreeSchemas(root *DecompositionNode) []SchemaMismatch {
	if root == nil || root.IsLeaf {
		return nil
	}

	var allMismatches []SchemaMismatch

	// Check sibling edges at this level using the manifest
	if root.Manifest != "" {
		siblingPairs := extractSiblingEdgePairs(root.Children, root.Manifest)
		allMismatches = append(allMismatches, findAllMismatches(siblingPairs)...)
	}

	// Recurse into non-leaf children (sub-workflows)
	for _, child := range root.Children {
		if !child.IsLeaf {
			allMismatches = append(allMismatches, validateTreeSchemas(child)...)
		}
	}

	return allMismatches
}

// extractSiblingEdgePairs extracts edge pairs from a manifest's :edges section
// by matching them to the step nodes' schemas. This allows checking edges at
// a level without needing the GraphWalkResult (which is only available during decompose).
func extractSiblingEdgePairs(steps []*DecompositionNode, manifestEDN string) []EdgePair {
	stepByName := make(map[string]*DecompositionNode)
	for _, s := range steps {
		stepByName[s.StepName] = s
	}
	// Also map "start" to the first step (manifests use :start for the first cell)
	if len(steps) > 0 {
		stepByName["start"] = steps[0]
	}

	// Parse edges from the manifest
	var manifest map[edn.Keyword]interface{}
	if err := edn.Unmarshal([]byte(manifestEDN), &manifest); err != nil {
		return nil
	}

	edgesRaw, ok := manifest[edn.Keyword("edges")]
	if !ok {
		return nil
	}
	edges, ok := edgesRaw.(map[interface{}]interface{})
	if !ok {
		return nil
	}

	var pairs []EdgePair
	for sourceKey, targetVal := range edges {
		sourceKW, ok := sourceKey.(edn.Keyword)
		if !ok {
			continue
		}
		sourceName := string(sourceKW)
		source := stepByName[sourceName]
		if source == nil {
			continue
		}

		for _, targetName := range extractTargetKeywords(targetVal) {
			if targetName == "end" {
				continue
			}
			target := stepByName[targetName]
			if target == nil {
				continue
			}
			pairs = append(pairs, EdgePair{
				SourceName:   source.StepName,
				SourceOutput: source.OutputSchema,
				TargetName:   target.StepName,
				TargetInput:  target.InputSchema,
			})
		}
	}

	return deduplicateEdgePairs(pairs)
}

// --- Edge schema validation ---

// EdgePair represents a data-flow relationship between two connected cells.
type EdgePair struct {
	SourceName   string
	SourceOutput string // EDN output schema
	TargetName   string
	TargetInput  string // EDN input schema
}

// SchemaMismatch describes a specific incompatibility between connected cells.
type SchemaMismatch struct {
	SourceName string
	TargetName string
	Missing    []string // fields required by target but absent from source output
	TypeDiffs  []string // fields present in both but with incompatible types
}

// extractTargetKeywords extracts target step names from a parsed EDN edge value.
// Handles both simple keywords (:next-step) and maps ({:ok :step-a :fail :step-b}).
func extractTargetKeywords(v interface{}) []string {
	var targets []string
	switch tv := v.(type) {
	case edn.Keyword:
		targets = append(targets, string(tv))
	case map[interface{}]interface{}:
		for _, val := range tv {
			if kw, ok := val.(edn.Keyword); ok {
				targets = append(targets, string(kw))
			}
		}
	}
	return targets
}

// extractEdgePairs parses the walk result to find all (source → target) schema pairs.
func extractEdgePairs(steps []*DecompositionNode, walk *GraphWalkResult) []EdgePair {
	stepByName := make(map[string]*DecompositionNode)
	for _, s := range steps {
		stepByName[s.StepName] = s
	}

	var pairs []EdgePair
	for sourceName, edgesEDN := range walk.Edges {
		source := stepByName[sourceName]
		if source == nil {
			continue
		}

		// Parse the edge EDN string into structured data
		var parsed interface{}
		if err := edn.Unmarshal([]byte(edgesEDN), &parsed); err != nil {
			continue
		}

		for _, targetName := range extractTargetKeywords(parsed) {
			if targetName == "end" {
				continue
			}
			target := stepByName[targetName]
			if target == nil {
				continue
			}
			pairs = append(pairs, EdgePair{
				SourceName:   sourceName,
				SourceOutput: source.OutputSchema,
				TargetName:   targetName,
				TargetInput:  target.InputSchema,
			})
		}
	}
	return pairs
}

// deduplicateEdgePairs removes duplicate source→target pairs.
func deduplicateEdgePairs(pairs []EdgePair) []EdgePair {
	seen := make(map[string]bool)
	var unique []EdgePair
	for _, p := range pairs {
		key := p.SourceName + "→" + p.TargetName
		if !seen[key] {
			seen[key] = true
			unique = append(unique, p)
		}
	}
	return unique
}

// extractMapFields parses a Malli schema and returns a map of field-name → type-string.
// Supports both lite syntax {:field :type} and standard [:map [:field :type] ...].
// Returns nil if the schema is generic (no fields to check) or unparseable.
func extractMapFields(schemaEDN string) map[string]string {
	if schemaEDN == "" || schemaEDN == "{}" || schemaEDN == "[:map]" {
		return nil // generic map, no fields to check
	}
	var v interface{}
	if err := edn.Unmarshal([]byte(schemaEDN), &v); err != nil {
		return nil
	}

	switch parsed := v.(type) {
	case map[interface{}]interface{}:
		// Lite syntax: {:field :type, :field2 :type2}
		fields := make(map[string]string)
		for k, val := range parsed {
			kw, ok := k.(edn.Keyword)
			if !ok {
				continue
			}
			fields[string(kw)] = ednToString(val)
		}
		if len(fields) == 0 {
			return nil
		}
		return fields

	case []interface{}:
		// Standard Malli: [:map [:field :type] ...]
		if len(parsed) == 0 {
			return nil
		}
		kw, ok := parsed[0].(edn.Keyword)
		if !ok || string(kw) != "map" {
			return nil
		}
		fields := make(map[string]string)
		for _, entry := range parsed[1:] {
			fieldVec, ok := entry.([]interface{})
			if !ok || len(fieldVec) < 2 {
				continue
			}
			fieldName, ok := fieldVec[0].(edn.Keyword)
			if !ok {
				continue
			}
			fields[string(fieldName)] = ednToString(fieldVec[1])
		}
		if len(fields) == 0 {
			return nil
		}
		return fields

	default:
		return nil
	}
}

// checkEdgeCompatibility deterministically checks whether source output fields
// satisfy target input fields. Returns nil if compatible.
func checkEdgeCompatibility(pair EdgePair) *SchemaMismatch {
	outputFields := extractMapFields(pair.SourceOutput)
	inputFields := extractMapFields(pair.TargetInput)

	// If either side is a generic [:map] or unparseable, we can't check programmatically
	if inputFields == nil {
		return nil // target accepts anything
	}
	if outputFields == nil {
		// Source is generic but target expects specific fields — mismatch
		if len(inputFields) > 0 {
			var missing []string
			for field := range inputFields {
				missing = append(missing, field)
			}
			return &SchemaMismatch{
				SourceName: pair.SourceName,
				TargetName: pair.TargetName,
				Missing:    missing,
			}
		}
		return nil
	}

	var missing []string
	var typeDiffs []string

	for field, inputType := range inputFields {
		outputType, exists := outputFields[field]
		if !exists {
			missing = append(missing, field)
			continue
		}
		// Check type compatibility (exact match or known-safe widening)
		if !typesCompatible(outputType, inputType) {
			typeDiffs = append(typeDiffs, fmt.Sprintf("%s: output=%s input=%s", field, outputType, inputType))
		}
	}

	if len(missing) == 0 && len(typeDiffs) == 0 {
		return nil
	}
	return &SchemaMismatch{
		SourceName: pair.SourceName,
		TargetName: pair.TargetName,
		Missing:    missing,
		TypeDiffs:  typeDiffs,
	}
}

// typesCompatible checks if an output type can feed an input type.
// Handles exact matches, safe widening (:int → :double), and structural
// EDN equality (maps with same fields in different order are equal).
func typesCompatible(outputType, inputType string) bool {
	if outputType == inputType {
		return true
	}
	// Safe widening
	if outputType == ":int" && inputType == ":double" {
		return true
	}
	// Normalize and compare — EDN serialization may differ in whitespace
	if strings.TrimSpace(outputType) == strings.TrimSpace(inputType) {
		return true
	}
	// Structural comparison — handles maps with same keys in different order
	return ednStructuralEqual(outputType, inputType)
}

// ednStructuralEqual parses two EDN strings and compares them structurally,
// treating maps as unordered (key order doesn't matter).
func ednStructuralEqual(a, b string) bool {
	var va, vb interface{}
	if err := edn.Unmarshal([]byte(a), &va); err != nil {
		return false
	}
	if err := edn.Unmarshal([]byte(b), &vb); err != nil {
		return false
	}
	return deepEDNEqual(va, vb)
}

// deepEDNEqual recursively compares two parsed EDN values, treating maps
// as unordered collections of key-value pairs.
func deepEDNEqual(a, b interface{}) bool {
	if a == nil && b == nil {
		return true
	}
	if a == nil || b == nil {
		return false
	}

	switch av := a.(type) {
	case map[interface{}]interface{}:
		bv, ok := b.(map[interface{}]interface{})
		if !ok || len(av) != len(bv) {
			return false
		}
		for k, aVal := range av {
			found := false
			for bk, bVal := range bv {
				if deepEDNEqual(k, bk) {
					if !deepEDNEqual(aVal, bVal) {
						return false
					}
					found = true
					break
				}
			}
			if !found {
				return false
			}
		}
		return true
	case []interface{}:
		bv, ok := b.([]interface{})
		if !ok || len(av) != len(bv) {
			return false
		}
		for i := range av {
			if !deepEDNEqual(av[i], bv[i]) {
				return false
			}
		}
		return true
	default:
		return a == b
	}
}

// findAllMismatches checks all edge pairs and returns only the incompatible ones.
func findAllMismatches(pairs []EdgePair) []SchemaMismatch {
	var mismatches []SchemaMismatch
	for _, p := range pairs {
		if m := checkEdgeCompatibility(p); m != nil {
			mismatches = append(mismatches, *m)
		}
	}
	return mismatches
}

// validateEdgeSchemas checks schema compatibility edge-by-edge and asks the LLM
// to fix each incompatible edge individually. This gives the LLM a focused task
// instead of dumping all mismatches at once (which causes structural confusion).
// Returns an error if schemas cannot be reconciled — this is a hard gate.
func (o *Orchestrator) validateEdgeSchemas(ctx context.Context, spec string,
	steps []*DecompositionNode, walk *GraphWalkResult,
	agent *GraphAgent, onChunk func(string, string),
	onEvent func(OrchestratorEvent)) error {

	const maxRounds = 5 // outer rounds (full pass over all edges; pass-through propagation needs multiple rounds)

	pairs := deduplicateEdgePairs(extractEdgePairs(steps, walk))
	if len(pairs) == 0 {
		return nil
	}

	for round := 1; round <= maxRounds; round++ {
		// Recompute pairs from current step schemas
		pairs = deduplicateEdgePairs(extractEdgePairs(steps, walk))
		mismatches := findAllMismatches(pairs)
		if len(mismatches) == 0 {
			onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "passed",
				Message: fmt.Sprintf("All %d edge schemas are compatible (round %d)", len(pairs), round)})
			return nil
		}

		onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "mismatches_found",
			Message: fmt.Sprintf("Round %d: %d mismatched edges to reconcile", round, len(mismatches))})

		// Fix each mismatched edge individually
		for i, m := range mismatches {
			onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "fixing",
				Message: fmt.Sprintf("Fixing edge %d/%d: :%s → :%s", i+1, len(mismatches), m.SourceName, m.TargetName)})

			prompt := buildEdgeFixPrompt(spec, steps, m)

			response, err := agent.ChatStream(ctx, prompt,
				func(chunk string) { onChunk("schema-fix", chunk) })
			if err != nil {
				onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "warning",
					Message: fmt.Sprintf("LLM error fixing :%s → :%s: %v", m.SourceName, m.TargetName, err)})
				continue
			}

			corrections := parseSchemaCorrections(response)
			if len(corrections) == 0 {
				onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "warning",
					Message: fmt.Sprintf("No corrections returned for :%s → :%s", m.SourceName, m.TargetName)})
				continue
			}

			applied, skipped := applySchemaCorrections(steps, corrections, onEvent)
			if applied > 0 {
				onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "corrected",
					Message: fmt.Sprintf("Fixed :%s → :%s (%d applied, %d skipped)", m.SourceName, m.TargetName, applied, skipped)})
			}
		}
	}

	// Final check
	pairs = deduplicateEdgePairs(extractEdgePairs(steps, walk))
	mismatches := findAllMismatches(pairs)
	if len(mismatches) > 0 {
		var details []string
		for _, m := range mismatches {
			detail := fmt.Sprintf(":%s → :%s", m.SourceName, m.TargetName)
			if len(m.Missing) > 0 {
				detail += fmt.Sprintf(" missing=[%s]", strings.Join(m.Missing, ", "))
			}
			if len(m.TypeDiffs) > 0 {
				detail += fmt.Sprintf(" type_diffs=[%s]", strings.Join(m.TypeDiffs, "; "))
			}
			details = append(details, detail)
		}
		return fmt.Errorf("schema validation failed after %d rounds: %s",
			maxRounds, strings.Join(details, "; "))
	}
	return nil
}

// applySchemaCorrections applies valid EDN corrections to step nodes in place.
// Returns (applied, skipped) counts.
func applySchemaCorrections(steps []*DecompositionNode, corrections map[string]*schemaCorrection,
	onEvent func(OrchestratorEvent)) (applied, skipped int) {

	stepByName := make(map[string]*DecompositionNode)
	for _, s := range steps {
		stepByName[s.StepName] = s
	}

	for stepName, correction := range corrections {
		step := stepByName[stepName]
		if step == nil {
			continue
		}
		if correction.InputSchema != "" {
			if isValidEDN(correction.InputSchema) {
				step.InputSchema = correction.InputSchema
				applied++
			} else {
				onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "warning",
					Message: fmt.Sprintf("Invalid EDN in input-schema correction for %s", stepName)})
				skipped++
			}
		}
		if correction.OutputSchema != "" {
			if isValidEDN(correction.OutputSchema) {
				step.OutputSchema = correction.OutputSchema
				applied++
			} else {
				onEvent(OrchestratorEvent{Phase: "schema_validation", Status: "warning",
					Message: fmt.Sprintf("Invalid EDN in output-schema correction for %s", stepName)})
				skipped++
			}
		}
	}
	return
}

// isValidEDN checks whether a string is parseable as EDN.
func isValidEDN(s string) bool {
	var v interface{}
	return edn.Unmarshal([]byte(s), &v) == nil
}

// schemaCorrection holds corrected schemas for a single cell.
type schemaCorrection struct {
	InputSchema  string
	OutputSchema string
}

// parseSchemaCorrections extracts schema corrections from the LLM response.
// Expects a format like:
//
//	CORRECTED :step-name input-schema
//	[:map [:field :type] ...]
//
//	CORRECTED :step-name output-schema
//	[:map [:field :type] ...]
func parseSchemaCorrections(response string) map[string]*schemaCorrection {
	corrections := make(map[string]*schemaCorrection)

	// Look for correction blocks
	correctionRe := regexp.MustCompile(`(?m)^CORRECTED\s+:?([\w-]+)\s+(input-schema|output-schema)\s*$`)
	lines := strings.Split(response, "\n")

	for i, line := range lines {
		matches := correctionRe.FindStringSubmatch(strings.TrimSpace(line))
		if matches == nil {
			continue
		}

		stepName := matches[1]
		schemaType := matches[2]

		// Collect the schema from subsequent lines (until next CORRECTED or blank line)
		var schemaLines []string
		for j := i + 1; j < len(lines); j++ {
			trimmed := strings.TrimSpace(lines[j])
			if trimmed == "" {
				if len(schemaLines) > 0 {
					break // end of schema block
				}
				continue // skip leading blank lines
			}
			if strings.HasPrefix(trimmed, "CORRECTED") {
				break
			}
			// Strip markdown code fence markers
			if trimmed == "```" || trimmed == "```edn" || trimmed == "```clojure" {
				continue
			}
			schemaLines = append(schemaLines, trimmed)
		}

		if len(schemaLines) == 0 {
			continue
		}

		schema := strings.Join(schemaLines, " ")

		if corrections[stepName] == nil {
			corrections[stepName] = &schemaCorrection{}
		}
		if schemaType == "input-schema" {
			corrections[stepName].InputSchema = schema
		} else {
			corrections[stepName].OutputSchema = schema
		}
	}

	return corrections
}

// buildSchemaFixPrompt tells the LLM exactly which fields are missing/mismatched
// and asks it to produce corrected schemas.
func buildSchemaFixPrompt(spec string, steps []*DecompositionNode, mismatches []SchemaMismatch) string {
	var b strings.Builder

	b.WriteString("Schema compatibility check found the following mismatches between connected cells.\n")
	b.WriteString("Fix these by providing corrected schemas.\n\n")

	// Show all cells for context
	b.WriteString("Current cell schemas:\n\n")
	for _, step := range steps {
		fmt.Fprintf(&b, "  :%s\n    doc: %s\n    input:  %s\n    output: %s\n\n",
			step.StepName, step.Doc,
			defaultSchema(step.InputSchema), defaultSchema(step.OutputSchema))
	}

	b.WriteString("Mismatches found:\n\n")
	for i, m := range mismatches {
		fmt.Fprintf(&b, "  %d. :%s → :%s\n", i+1, m.SourceName, m.TargetName)
		if len(m.Missing) > 0 {
			fmt.Fprintf(&b, "     MISSING from :%s output: %s\n", m.SourceName, strings.Join(m.Missing, ", "))
		}
		if len(m.TypeDiffs) > 0 {
			fmt.Fprintf(&b, "     TYPE MISMATCH: %s\n", strings.Join(m.TypeDiffs, "; "))
		}
		b.WriteString("\n")
	}

	fmt.Fprintf(&b, `Reference specification:
<spec>
%s
</spec>

Fix the schemas so that every edge is compatible. For each cell that needs a change, output:

CORRECTED :<step-name> output-schema
<corrected Malli EDN schema>

CORRECTED :<step-name> input-schema
<corrected Malli EDN schema>

Rules:
- Prefer expanding the source's output schema to include missing fields.
- A cell's output MUST include pass-through fields from its input that downstream cells need.
  For example, if a cell receives {:state :string :items [:vector ...]} and the next cell
  needs :state, the output schema must include :state even if this cell doesn't modify it.
- Only adjust the target's input schema if the field genuinely shouldn't be there.
- Schemas MUST use lite Malli syntax: {:field-name :type} for maps.
- Use :string, :int, :double, :boolean, :keyword for primitives.
- Use [:vector <type>] for collections, {:field :type} for nested maps.
- Ensure the corrected schemas are consistent with what the cell's doc says it does.
`, spec)

	return b.String()
}

// buildEdgeFixPrompt creates a focused prompt for fixing a single mismatched edge.
// This gives the LLM a simpler task than fixing all mismatches at once.
// It also shows downstream successors so the LLM knows which fields need to be
// passed through the target cell's output.
func buildEdgeFixPrompt(spec string, steps []*DecompositionNode, m SchemaMismatch) string {
	var b strings.Builder

	// Build step lookup
	stepByName := make(map[string]*DecompositionNode)
	for _, s := range steps {
		stepByName[s.StepName] = s
	}
	source := stepByName[m.SourceName]
	target := stepByName[m.TargetName]

	b.WriteString("Fix ONE schema incompatibility between two connected cells.\n\n")

	fmt.Fprintf(&b, "SOURCE cell :%s\n", m.SourceName)
	if source != nil {
		fmt.Fprintf(&b, "  doc: %s\n", source.Doc)
		fmt.Fprintf(&b, "  input:  %s\n", defaultSchema(source.InputSchema))
		fmt.Fprintf(&b, "  output: %s\n", defaultSchema(source.OutputSchema))
	}

	fmt.Fprintf(&b, "\nTARGET cell :%s\n", m.TargetName)
	if target != nil {
		fmt.Fprintf(&b, "  doc: %s\n", target.Doc)
		fmt.Fprintf(&b, "  input:  %s\n", defaultSchema(target.InputSchema))
		fmt.Fprintf(&b, "  output: %s\n", defaultSchema(target.OutputSchema))
	}

	b.WriteString("\nPROBLEM:\n")
	if len(m.Missing) > 0 {
		fmt.Fprintf(&b, "  Target :%s needs these fields but source :%s output doesn't have them: %s\n",
			m.TargetName, m.SourceName, strings.Join(m.Missing, ", "))
	}
	if len(m.TypeDiffs) > 0 {
		fmt.Fprintf(&b, "  Type mismatches: %s\n", strings.Join(m.TypeDiffs, "; "))
	}

	// Show downstream context: what does the target's successors need?
	// This helps the LLM understand pass-through requirements.
	b.WriteString("\nPASS-THROUGH CONTEXT — all cells in this workflow:\n")
	for _, s := range steps {
		fmt.Fprintf(&b, "  :%s  input: %s  output: %s\n",
			s.StepName, defaultSchema(s.InputSchema), defaultSchema(s.OutputSchema))
	}

	fmt.Fprintf(&b, `
IMPORTANT: When you expand :%s output, you MUST also include those fields in :%s output
if ANY downstream cell needs them. Cells pass data forward — if a field enters a cell's input,
it must appear in the cell's output for subsequent cells to receive it.

Fix this by correcting one or both schemas. Output ONLY corrections in this format:

CORRECTED :<step-name> output-schema
<corrected lite Malli EDN schema>

CORRECTED :<step-name> input-schema
<corrected lite Malli EDN schema>

Rules:
- Prefer expanding :%s output to include missing fields (pass-through from input).
- When adding fields to a cell's input, ALSO add them to its output for downstream propagation.
- Only narrow :%s input if the field genuinely shouldn't be there.
- Use lite Malli syntax: {:field-name :type} for maps.
- The corrected schemas must be consistent with each cell's documented purpose.
`, m.SourceName, m.TargetName, m.SourceName, m.TargetName)

	return b.String()
}
