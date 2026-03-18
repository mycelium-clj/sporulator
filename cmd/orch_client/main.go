package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"time"

	"github.com/gorilla/websocket"
)

type WSMessage struct {
	Type    string      `json:"type"`
	ID      string      `json:"id,omitempty"`
	Payload interface{} `json:"payload,omitempty"`
}

type OrchestratorEvent struct {
	Phase   string `json:"Phase"`
	CellID  string `json:"CellID"`
	Status  string `json:"Status"`
	Message string `json:"Message"`
}

type TestContract struct {
	CellID      string    `json:"cell_id"`
	TestCode    string    `json:"test_code"`
	ReviewNotes string    `json:"review_notes"`
	CellBrief   CellBrief `json:"cell_brief"`
	Revision    int       `json:"revision"`
}

type CellBrief struct {
	ID     string `json:"id"`
	Doc    string `json:"doc"`
	Schema string `json:"schema"`
}

var (
	manifestID       string
	maxDepth         int
	maxSteps         int
	autoApprove      bool
	autoApproveGraph bool
	specFile         string
	projectPath      string
	baseNs           string
)

func main() {
	startCmd := flag.NewFlagSet("start", flag.ExitOnError)
	startCmd.StringVar(&manifestID, "manifest", ":order/placement", "Manifest ID")
	startCmd.IntVar(&maxDepth, "depth", 0, "Max decomposition depth")
	startCmd.IntVar(&maxSteps, "steps", 8, "Max steps per level")
	startCmd.BoolVar(&autoApprove, "auto-approve", false, "Auto-approve test contracts")
	startCmd.BoolVar(&autoApproveGraph, "auto-approve-graph", false, "Auto-approve graph decomposition")
	startCmd.StringVar(&specFile, "spec", "/Users/yogthos/src/mycelium-clj/order-lifecycle/SPEC.md", "Spec file path")
	startCmd.StringVar(&projectPath, "project", "/Users/yogthos/src/mycelium-clj/order-lifecycle", "Project path")
	startCmd.StringVar(&baseNs, "ns", "example.order-lifecycle", "Base namespace")

	if len(os.Args) < 2 {
		fmt.Println("Usage: orch_client <command> [flags]")
		fmt.Println("  start         - start a new orchestration run")
		fmt.Println("  review        - send test review responses from /tmp/review_responses.json")
		fmt.Println("  graph-review  - send graph review response from /tmp/graph_review.json")
		fmt.Println("\nstart flags:")
		startCmd.PrintDefaults()
		os.Exit(1)
	}

	switch os.Args[1] {
	case "start":
		startCmd.Parse(os.Args[2:])
		startOrchestration()
	case "review":
		sendReview()
	case "graph-review":
		sendGraphReview()
	default:
		log.Fatalf("Unknown command: %s", os.Args[1])
	}
}

func startOrchestration() {
	spec, err := os.ReadFile(specFile)
	if err != nil {
		log.Fatal(err)
	}

	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)

	conn, _, err := websocket.DefaultDialer.Dial("ws://localhost:8420/ws", nil)
	if err != nil {
		log.Fatal("dial:", err)
	}
	defer conn.Close()

	msg := WSMessage{
		Type: "orchestrate",
		ID:   "orch-run",
		Payload: map[string]interface{}{
			"project_path":        projectPath,
			"base_namespace":      baseNs,
			"source_dir":          "src/clj",
			"test_dir":            "test/clj",
			"spec":                string(spec),
			"manifest_id":         manifestID,
			"max_steps_per_level": maxSteps,
			"max_depth":           maxDepth,
			"auto_approve_tests":  autoApprove,
			"auto_approve_graph":  autoApproveGraph,
		},
	}

	fmt.Printf("[%s] Config: manifest=%s depth=%d steps=%d auto_approve=%v\n", ts(), manifestID, maxDepth, maxSteps, autoApprove)

	if err := conn.WriteJSON(msg); err != nil {
		log.Fatal("send:", err)
	}
	fmt.Printf("[%s] Orchestrate sent, waiting for events...\n", ts())

	// Log file for full output
	logFile, _ := os.Create("/tmp/orch_run.log")
	defer logFile.Close()

	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			_, message, err := conn.ReadMessage()
			if err != nil {
				fmt.Printf("[%s] Connection closed: %v\n", ts(), err)
				return
			}

			// Log raw message
			logFile.Write(message)
			logFile.WriteString("\n")

			var resp WSMessage
			json.Unmarshal(message, &resp)

			switch resp.Type {
			case "stream_chunk":
				// skip

			case "orchestrator_event":
				eventBytes, _ := json.Marshal(resp.Payload)
				var event OrchestratorEvent
				json.Unmarshal(eventBytes, &event)

				// Only print interesting events
				if event.Status == "started" || event.Status == "success" || event.Status == "error" ||
					event.Status == "fixing" || event.Status == "retry" || event.Status == "failed" ||
					event.Status == "awaiting_review" || event.Status == "approved" || event.Status == "revising" ||
					event.Status == "info" || event.Status == "warning" ||
					event.Status == "extracted" || event.Status == "mismatch" || event.Status == "tree_passed" ||
					event.Phase == "test_review" || event.Phase == "graph_review" {
					fmt.Printf("[%s] [%s] %s %s: %s\n", ts(), event.Phase, event.CellID, event.Status, trunc(event.Message, 150))
				}

			case "test_review_contracts":
				// Save contracts for review
				handleContracts(resp.Payload)

			case "graph_review_data":
				// Save graph for review
				handleGraphReviewData(resp.Payload)

			case "orchestrator_complete":
				fmt.Printf("\n[%s] === ORCHESTRATION COMPLETE ===\n", ts())
				return

			case "orchestrator_error":
				errStr, _ := json.Marshal(resp.Payload)
				fmt.Printf("\n[%s] === ERROR: %s ===\n", ts(), string(errStr))
				return

			case "error":
				errStr, _ := json.Marshal(resp.Payload)
				fmt.Printf("[%s] ERROR: %s\n", ts(), string(errStr))
				return

			default:
				// Print unknown message types
				fmt.Printf("[%s] [%s] (see log)\n", ts(), resp.Type)
			}
		}
	}()

	select {
	case <-done:
	case <-interrupt:
		fmt.Println("Interrupted")
		conn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
	}
}

func handleContracts(payload interface{}) {
	data, _ := json.Marshal(payload)

	// Parse the contracts
	var wrapper struct {
		RunID     string         `json:"run_id"`
		Contracts []TestContract `json:"contracts"`
	}
	json.Unmarshal(data, &wrapper)

	fmt.Printf("\n[%s] ========================================\n", ts())
	fmt.Printf("[%s] TEST CONTRACTS READY FOR REVIEW\n", ts())
	fmt.Printf("[%s] Run ID: %s\n", ts(), wrapper.RunID)
	fmt.Printf("[%s] %d contracts to review\n", ts(), len(wrapper.Contracts))
	fmt.Printf("[%s] ========================================\n\n", ts())

	// Save run_id
	os.WriteFile("/tmp/orch_run_id.txt", []byte(wrapper.RunID), 0644)

	// Save each contract
	for i, c := range wrapper.Contracts {
		filename := fmt.Sprintf("/tmp/contract_%d_%s.txt", i, sanitize(c.CellID))
		var sb strings.Builder
		sb.WriteString(fmt.Sprintf("CELL ID: %s\n", c.CellID))
		sb.WriteString(fmt.Sprintf("REVISION: %d\n", c.Revision))
		sb.WriteString(fmt.Sprintf("DOC: %s\n", c.CellBrief.Doc))
		sb.WriteString(fmt.Sprintf("SCHEMA: %s\n", c.CellBrief.Schema))
		sb.WriteString("\n--- REVIEW NOTES ---\n")
		sb.WriteString(c.ReviewNotes)
		sb.WriteString("\n\n--- TEST CODE ---\n")
		sb.WriteString(c.TestCode)
		os.WriteFile(filename, []byte(sb.String()), 0644)

		fmt.Printf("  [%d] %s (rev %d) — %s\n", i, c.CellID, c.Revision, trunc(c.CellBrief.Doc, 80))
		fmt.Printf("      Saved to %s\n", filename)
	}

	// Save full contracts as JSON for programmatic access
	contractsJSON, _ := json.MarshalIndent(wrapper, "", "  ")
	os.WriteFile("/tmp/orch_contracts.json", contractsJSON, 0644)

	// Generate a template review response (all approve)
	var responses []map[string]string
	for _, c := range wrapper.Contracts {
		responses = append(responses, map[string]string{
			"cell_id":  c.CellID,
			"decision": "approve",
		})
	}
	template := map[string]interface{}{
		"run_id":    wrapper.RunID,
		"responses": responses,
	}
	templateJSON, _ := json.MarshalIndent(template, "", "  ")
	os.WriteFile("/tmp/review_responses.json", templateJSON, 0644)

	fmt.Printf("\n  Review template saved to /tmp/review_responses.json\n")
	fmt.Printf("  Edit decisions (approve/revise/edit/skip) and run: orch_client review\n\n")
}

func sendReview() {
	data, err := os.ReadFile("/tmp/review_responses.json")
	if err != nil {
		log.Fatal("Read review responses:", err)
	}

	conn, _, err := websocket.DefaultDialer.Dial("ws://localhost:8420/ws", nil)
	if err != nil {
		log.Fatal("dial:", err)
	}
	defer conn.Close()

	var payload interface{}
	json.Unmarshal(data, &payload)

	msg := WSMessage{
		Type:    "test_review",
		ID:      "review",
		Payload: payload,
	}

	if err := conn.WriteJSON(msg); err != nil {
		log.Fatal("send:", err)
	}
	fmt.Printf("[%s] Review responses sent\n", ts())

	// Read a response to confirm
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, message, err := conn.ReadMessage()
	if err == nil {
		fmt.Printf("[%s] Response: %s\n", ts(), trunc(string(message), 200))
	}
}

func handleGraphReviewData(payload interface{}) {
	data, _ := json.Marshal(payload)

	var wrapper struct {
		RunID string          `json:"run_id"`
		Graph json.RawMessage `json:"graph"`
	}
	json.Unmarshal(data, &wrapper)

	// Pretty-print the graph
	var graph struct {
		Depth      int    `json:"depth"`
		NsPrefix   string `json:"ns_prefix"`
		Steps      []struct {
			Name         string `json:"name"`
			Doc          string `json:"doc"`
			InputSchema  string `json:"input_schema"`
			OutputSchema string `json:"output_schema"`
			IsLeaf       bool   `json:"is_leaf"`
		} `json:"steps"`
		Edges      map[string]string `json:"edges"`
		Dispatches map[string]string `json:"dispatches"`
		Manifest   string            `json:"manifest"`
	}
	json.Unmarshal(wrapper.Graph, &graph)

	fmt.Printf("\n[%s] ========================================\n", ts())
	fmt.Printf("[%s] GRAPH READY FOR REVIEW (depth %d, ns: %s)\n", ts(), graph.Depth, graph.NsPrefix)
	fmt.Printf("[%s] ========================================\n\n", ts())

	for i, s := range graph.Steps {
		leaf := ""
		if s.IsLeaf {
			leaf = " [LEAF]"
		}
		fmt.Printf("  [%d] %s%s\n", i, s.Name, leaf)
		fmt.Printf("      %s\n", s.Doc)
		fmt.Printf("      in:  %s\n", trunc(s.InputSchema, 100))
		fmt.Printf("      out: %s\n", trunc(s.OutputSchema, 100))
	}

	fmt.Printf("\n  Edges:\n")
	for name, edges := range graph.Edges {
		fmt.Printf("    %s: %s\n", name, edges)
	}

	// Save run_id
	os.WriteFile("/tmp/orch_run_id.txt", []byte(wrapper.RunID), 0644)

	// Save full graph as JSON
	graphJSON, _ := json.MarshalIndent(wrapper, "", "  ")
	os.WriteFile("/tmp/orch_graph.json", graphJSON, 0644)

	// Save manifest for easy reading
	os.WriteFile("/tmp/orch_graph_manifest.edn", []byte(graph.Manifest), 0644)

	// Generate template response (approve)
	template := map[string]interface{}{
		"run_id": wrapper.RunID,
		"response": map[string]string{
			"decision": "approve",
		},
	}
	templateJSON, _ := json.MarshalIndent(template, "", "  ")
	os.WriteFile("/tmp/graph_review.json", templateJSON, 0644)

	fmt.Printf("\n  Graph saved to /tmp/orch_graph.json\n")
	fmt.Printf("  Manifest saved to /tmp/orch_graph_manifest.edn\n")
	fmt.Printf("  Review template saved to /tmp/graph_review.json\n")
	fmt.Printf("  Edit decision (approve/revise) and run: orch_client graph-review\n\n")
}

func sendGraphReview() {
	data, err := os.ReadFile("/tmp/graph_review.json")
	if err != nil {
		log.Fatal("Read graph review:", err)
	}

	conn, _, err := websocket.DefaultDialer.Dial("ws://localhost:8420/ws", nil)
	if err != nil {
		log.Fatal("dial:", err)
	}
	defer conn.Close()

	var payload interface{}
	json.Unmarshal(data, &payload)

	msg := WSMessage{
		Type:    "graph_review",
		ID:      "review",
		Payload: payload,
	}

	if err := conn.WriteJSON(msg); err != nil {
		log.Fatal("send:", err)
	}
	fmt.Printf("[%s] Graph review response sent\n", ts())

	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	_, message, err := conn.ReadMessage()
	if err == nil {
		fmt.Printf("[%s] Response: %s\n", ts(), trunc(string(message), 200))
	}
}

func ts() string {
	return time.Now().Format("15:04:05")
}

func trunc(s string, max int) string {
	s = strings.ReplaceAll(s, "\n", " ")
	if len(s) <= max {
		return s
	}
	return s[:max] + "..."
}

func sanitize(s string) string {
	s = strings.ReplaceAll(s, ":", "")
	s = strings.ReplaceAll(s, "/", "_")
	return s
}
