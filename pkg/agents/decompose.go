package agents

import (
	"bytes"
	"context"
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
	Depth        int                  // recursion depth (0 = root)
}

// DecompositionConfig controls decomposition behavior.
type DecompositionConfig struct {
	MaxStepsPerLevel int // max steps per decomposition level (default 5)
	MaxDepth         int // max recursion depth (default 3)
}

// GraphWalkResult holds the edges and dispatches decided during BFS graph walk.
type GraphWalkResult struct {
	Edges      map[string]string // stepName → EDN edges entry e.g. "{:done :process}"
	Dispatches map[string]string // stepName → EDN dispatches entry e.g. "[[:done (constantly true)]]"
}

// DefaultDecompositionConfig returns sensible defaults.
func DefaultDecompositionConfig() DecompositionConfig {
	return DecompositionConfig{
		MaxStepsPerLevel: 5,
		MaxDepth:         3,
	}
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
			b.WriteString("[:map]")
		}
		b.WriteString(" :output ")
		if step.OutputSchema != "" {
			b.WriteString(step.OutputSchema)
		} else {
			b.WriteString("[:map]")
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
// Returns "[:map]" if the value is nil or marshaling fails.
func ednToString(v interface{}) string {
	if v == nil {
		return "[:map]"
	}
	var buf bytes.Buffer
	if err := edn.NewEncoder(&buf).Encode(v); err != nil {
		return "[:map]"
	}
	result := strings.TrimSpace(buf.String())
	if result == "" {
		return "[:map]"
	}
	return result
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
// Expects two EDN forms: edges map and dispatches vector.
func parseEdgeResponse(response string) (edges, dispatches string, targets []string, err error) {
	block := ExtractFirstCodeBlock(response)
	if block == "" {
		block = response
	}

	// Try to find edges pattern: {:<keyword> :<keyword>} or {:<keyword> {:<keyword> :<keyword>}}
	edgeRe := regexp.MustCompile(`(?s)(\{[^{}]*(?:\{[^{}]*\}[^{}]*)?\})`)
	edgeMatches := edgeRe.FindAllString(block, -1)

	if len(edgeMatches) == 0 {
		return "", "", nil, fmt.Errorf("no edges found in response")
	}

	// First match is the edges map
	edges = edgeMatches[0]

	// Extract targets from edges (keywords that are values, not keys)
	targetRe := regexp.MustCompile(`:(\w[\w-]*)`)
	targetMatches := targetRe.FindAllStringSubmatch(edges, -1)
	seen := map[string]bool{}
	for i, tm := range targetMatches {
		// Skip every other keyword (keys vs values) - simplistic approach
		// Better: find all value-position keywords
		if i > 0 && !seen[tm[1]] {
			targets = append(targets, tm[1])
			seen[tm[1]] = true
		}
	}

	// Find dispatches: [[:keyword (fn ...)]] or [[:keyword (constantly true)]]
	dispatchRe := regexp.MustCompile(`(?s)(\[\[.*\]\])`)
	dispatchMatch := dispatchRe.FindString(block)
	if dispatchMatch != "" {
		dispatches = dispatchMatch
	} else {
		// Try to find it in subsequent code blocks
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

	// Recurse on complex steps if within depth limit
	if depth < cfg.MaxDepth {
		for _, step := range steps {
			if !step.IsLeaf {
				onEvent(OrchestratorEvent{Phase: "decompose", Status: "recursing",
					Message: fmt.Sprintf("Recursing into complex step: %s", step.StepName)})

				subPrefix := nsPrefix + "." + step.StepName
				subSpec := fmt.Sprintf("Sub-task of the parent workflow.\n\nParent step: %s\nDescription: %s\n\nFull spec:\n%s",
					step.StepName, step.Doc, spec)

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
			}
		}
	} else {
		// At max depth, force all steps to be leaves
		for _, step := range steps {
			step.IsLeaf = true
		}
	}

	// Walk the graph to determine edges and dispatches
	walk, err := o.walkGraph(ctx, steps, agent, onChunk, onEvent)
	if err != nil {
		return nil, fmt.Errorf("graph walk at depth %d: %w", depth, err)
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
		StepName: nsPrefix,
		CellID:   ":" + nsPrefix + "/workflow",
		Doc:      "Root workflow for " + nsPrefix,
		IsLeaf:   false,
		Children: steps,
		Manifest: manifest,
		Depth:    depth,
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

			prompt := buildGraphWalkPrompt(step, availableTargets, edgeSummary.String())

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
- :input-schema — EDN schema for input data (e.g. [:map [:x :int]])
- :output-schema — EDN schema for output data
- :requires — vector of resource keywords (e.g. [:db :cache])
- :simple? — true if implementable as one cell (~80 lines of Clojure), false if complex

Return the steps as an EDN vector in a code block. Example:

`)
	b.WriteString("```edn\n")
	b.WriteString(`[{:name "validate-input" :doc "Validate and normalize input data" :input-schema [:map] :output-schema [:map] :requires [] :simple? true}
 {:name "process-data" :doc "Transform and enrich the data" :input-schema [:map] :output-schema [:map] :requires [:db] :simple? true}
 {:name "finalize" :doc "Produce final output" :input-schema [:map] :output-schema [:map] :requires [] :simple? true}]`)
	b.WriteString("\n```\n")

	fmt.Fprintf(&b, "\nCell IDs will be namespaced as :%s/<step-name>.", nsPrefix)

	return b.String()
}

func buildGraphWalkPrompt(step *DecompositionNode, availableTargets []string, edgeSummary string) string {
	var b strings.Builder

	fmt.Fprintf(&b, `Cell :%s does: "%s"
Schema: input %s, output %s

Available targets: %s
`,
		step.StepName, step.Doc,
		step.InputSchema, step.OutputSchema,
		strings.Join(availableTargets, " "))

	if edgeSummary != "" {
		fmt.Fprintf(&b, "\nAlready connected:\n%s\n", edgeSummary)
	}

	b.WriteString(`
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
