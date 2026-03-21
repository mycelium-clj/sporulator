package main

import (
	"bufio"
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
	Phase   string `json:"phase"`
	CellID  string `json:"cell_id"`
	Status  string `json:"status"`
	Message string `json:"message"`
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
	wsAddr           string
)

func main() {
	flag.StringVar(&manifestID, "manifest", ":order/placement", "Manifest ID")
	flag.IntVar(&maxDepth, "depth", 0, "Max decomposition depth")
	flag.IntVar(&maxSteps, "steps", 8, "Max steps per level")
	flag.BoolVar(&autoApprove, "auto-approve", false, "Auto-approve test contracts")
	flag.BoolVar(&autoApproveGraph, "auto-approve-graph", false, "Auto-approve graph decomposition")
	flag.StringVar(&specFile, "spec", "", "Spec file path (required)")
	flag.StringVar(&projectPath, "project", "", "Project path (required)")
	flag.StringVar(&baseNs, "ns", "", "Base namespace (required)")
	flag.StringVar(&wsAddr, "addr", "ws://localhost:8420/ws", "WebSocket address")
	flag.Parse()

	if specFile == "" || projectPath == "" || baseNs == "" {
		fmt.Println("Usage: orch_client -spec <path> -project <path> -ns <namespace> [flags]")
		flag.PrintDefaults()
		os.Exit(1)
	}

	spec, err := os.ReadFile(specFile)
	if err != nil {
		log.Fatal("Read spec:", err)
	}

	interrupt := make(chan os.Signal, 1)
	signal.Notify(interrupt, os.Interrupt)

	conn, _, err := websocket.DefaultDialer.Dial(wsAddr, nil)
	if err != nil {
		log.Fatal("Connect:", err)
	}
	defer conn.Close()

	// Send orchestrate message
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

	fmt.Printf("╔══════════════════════════════════════════════════╗\n")
	fmt.Printf("║  Sporulator — Interactive Orchestration Client   ║\n")
	fmt.Printf("╚══════════════════════════════════════════════════╝\n\n")
	fmt.Printf("  Project:  %s\n", projectPath)
	fmt.Printf("  Manifest: %s\n", manifestID)
	fmt.Printf("  Depth:    %d   Steps: %d\n\n", maxDepth, maxSteps)

	if err := conn.WriteJSON(msg); err != nil {
		log.Fatal("Send:", err)
	}

	reader := bufio.NewReader(os.Stdin)
	done := make(chan struct{})

	go func() {
		defer close(done)
		for {
			_, data, err := conn.ReadMessage()
			if err != nil {
				fmt.Printf("\n  Connection closed: %v\n", err)
				return
			}

			var resp WSMessage
			json.Unmarshal(data, &resp)

			switch resp.Type {
			case "stream_chunk":
				// skip

			case "orchestrator_event":
				handleEvent(resp.Payload)

			case "graph_review_data":
				handleGraphReview(conn, resp.Payload, reader)

			case "test_review_contracts":
				handleTestReview(conn, resp.Payload, reader)

			case "impl_review_data":
				handleImplReview(conn, resp.Payload, reader)

			case "orchestrator_complete":
				fmt.Printf("\n  ✓ ORCHESTRATION COMPLETE\n\n")
				return

			case "orchestrator_error":
				errStr, _ := json.Marshal(resp.Payload)
				fmt.Printf("\n  ✗ ERROR: %s\n\n", string(errStr))
				return

			case "error":
				errStr, _ := json.Marshal(resp.Payload)
				fmt.Printf("\n  ✗ ERROR: %s\n\n", string(errStr))
				return
			}
		}
	}()

	select {
	case <-done:
	case <-interrupt:
		fmt.Println("\n  Interrupted")
		conn.WriteMessage(websocket.CloseMessage,
			websocket.FormatCloseMessage(websocket.CloseNormalClosure, ""))
	}
}

// ─── Event Display ──────────────────────────────────────────

func handleEvent(payload interface{}) {
	data, _ := json.Marshal(payload)
	var event OrchestratorEvent
	json.Unmarshal(data, &event)

	// Filter to interesting events
	switch {
	case event.Phase == "schema_register" && event.Status == "registered":
		fmt.Printf("  ✓ Schema: %s\n", event.CellID)
	case event.Phase == "schema_register" && event.Status == "invalid":
		fmt.Printf("  ✗ Schema INVALID: %s — %s\n", event.CellID, trunc(event.Message, 80))
	case event.Phase == "cell_test" && event.Status == "passed":
		fmt.Printf("  ✓ Tests passed: %s\n", event.CellID)
	case event.Phase == "cell_test" && event.Status == "failed":
		fmt.Printf("  ✗ Tests FAILED: %s\n", event.CellID)
	case event.Phase == "cell_implement" && event.Status == "fixing":
		fmt.Printf("  … Fixing: %s\n", event.CellID)
	case event.Phase == "cell_implement" && event.Status == "error":
		fmt.Printf("  ✗ Compile error: %s\n", event.CellID)
	case event.Phase == "redecompose":
		fmt.Printf("  ↻ %s: %s\n", event.Status, trunc(event.Message, 80))
	case event.Phase == "register":
		fmt.Printf("  %s: %s — %s\n", icon(event.Status), event.CellID, event.Status)
	case event.Phase == "integration":
		fmt.Printf("  [integration] %s: %s\n", event.Status, trunc(event.Message, 100))
	case event.Status == "started" && event.Phase == "manifest":
		fmt.Printf("\n  ⏳ Decomposing specification...\n")
	case event.Status == "started" && event.Phase == "decompose":
		fmt.Printf("  ⏳ %s\n", event.Message)
	case event.Phase == "schema_validation" && event.Status == "tree_passed":
		fmt.Printf("  ✓ Schema tree validated\n")
	case event.Phase == "impl_review" && event.Status == "approved":
		fmt.Printf("  ✓ Impl approved: %s\n", event.CellID)
	}
}

func icon(status string) string {
	switch status {
	case "success", "passed", "approved", "registered":
		return "✓"
	case "error", "failed":
		return "✗"
	case "started":
		return "⏳"
	default:
		return "·"
	}
}

// ─── Graph Review Gate ──────────────────────────────────────

func handleGraphReview(conn *websocket.Conn, payload interface{}, reader *bufio.Reader) {
	data, _ := json.Marshal(payload)
	var wrapper struct {
		RunID string `json:"run_id"`
		Graph struct {
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
		} `json:"graph"`
	}
	json.Unmarshal(data, &wrapper)

	fmt.Printf("\n┌─────────────────────────────────────────────────────┐\n")
	fmt.Printf("│  GRAPH REVIEW — %d steps (depth %d)                   \n", len(wrapper.Graph.Steps), wrapper.Graph.Depth)
	fmt.Printf("└─────────────────────────────────────────────────────┘\n\n")

	for i, s := range wrapper.Graph.Steps {
		leaf := ""
		if !s.IsLeaf {
			leaf = " [COMPLEX]"
		}
		fmt.Printf("  %d. %s%s\n", i+1, s.Name, leaf)
		fmt.Printf("     %s\n\n", trunc(s.Doc, 100))
	}

	fmt.Printf("  Edges:\n")
	for _, s := range wrapper.Graph.Steps {
		if e, ok := wrapper.Graph.Edges[s.Name]; ok {
			fmt.Printf("    %s → %s\n", s.Name, e)
		}
	}

	fmt.Printf("\n  [a]pprove  [r]evise  > ")
	input, _ := reader.ReadString('\n')
	input = strings.TrimSpace(strings.ToLower(input))

	decision := "approve"
	feedback := ""
	if strings.HasPrefix(input, "r") {
		decision = "revise"
		fmt.Printf("  Feedback: ")
		feedback, _ = reader.ReadString('\n')
		feedback = strings.TrimSpace(feedback)
	}

	resp := WSMessage{
		Type: "graph_review",
		ID:   "review",
		Payload: map[string]interface{}{
			"run_id": wrapper.RunID,
			"response": map[string]string{
				"decision": decision,
				"feedback": feedback,
			},
		},
	}
	conn.WriteJSON(resp)
	fmt.Printf("  → %s\n\n", decision)
}

// ─── Test Review Gate ───────────────────────────────────────

func handleTestReview(conn *websocket.Conn, payload interface{}, reader *bufio.Reader) {
	data, _ := json.Marshal(payload)
	var wrapper struct {
		RunID     string `json:"run_id"`
		Contracts []struct {
			CellID      string `json:"cell_id"`
			TestCode    string `json:"test_code"`
			ReviewNotes string `json:"review_notes"`
			CellBrief   struct {
				Doc    string `json:"doc"`
				Schema string `json:"schema"`
			} `json:"cell_brief"`
			Revision int `json:"revision"`
		} `json:"contracts"`
	}
	json.Unmarshal(data, &wrapper)

	fmt.Printf("\n┌─────────────────────────────────────────────────────┐\n")
	fmt.Printf("│  TEST REVIEW — %d contracts                          \n", len(wrapper.Contracts))
	fmt.Printf("└─────────────────────────────────────────────────────┘\n\n")

	for i, c := range wrapper.Contracts {
		testCount := strings.Count(c.TestCode, "deftest")
		fmt.Printf("  %d. %s — %d tests (rev %d)\n", i+1, c.CellID, testCount, c.Revision)
	}

	fmt.Printf("\n  [a]pprove all  [v]iew <n>  [r]evise <n>  [s]kip <n>  > ")
	input, _ := reader.ReadString('\n')
	input = strings.TrimSpace(strings.ToLower(input))

	var responses []map[string]string

	if strings.HasPrefix(input, "v") {
		// View a specific contract
		// For now, just show it and re-prompt
		idx := 0
		fmt.Sscanf(input[1:], "%d", &idx)
		if idx > 0 && idx <= len(wrapper.Contracts) {
			c := wrapper.Contracts[idx-1]
			fmt.Printf("\n  === %s ===\n", c.CellID)
			fmt.Printf("  Doc: %s\n\n", trunc(c.CellBrief.Doc, 120))
			fmt.Printf("  Test code:\n%s\n\n", c.TestCode)
			fmt.Printf("  Review notes:\n%s\n\n", trunc(c.ReviewNotes, 500))
		}
		// Re-prompt
		fmt.Printf("  [a]pprove all  [r]evise <n>  > ")
		input, _ = reader.ReadString('\n')
		input = strings.TrimSpace(strings.ToLower(input))
	}

	if strings.HasPrefix(input, "r") {
		// Revise specific contracts
		idx := 0
		fmt.Sscanf(input[1:], "%d", &idx)
		fmt.Printf("  Feedback for %s: ", wrapper.Contracts[idx-1].CellID)
		feedback, _ := reader.ReadString('\n')
		feedback = strings.TrimSpace(feedback)

		for i, c := range wrapper.Contracts {
			if i == idx-1 {
				responses = append(responses, map[string]string{
					"cell_id": c.CellID, "decision": "revise", "feedback": feedback,
				})
			} else {
				responses = append(responses, map[string]string{
					"cell_id": c.CellID, "decision": "approve",
				})
			}
		}
	} else {
		// Approve all
		for _, c := range wrapper.Contracts {
			responses = append(responses, map[string]string{
				"cell_id": c.CellID, "decision": "approve",
			})
		}
	}

	resp := WSMessage{
		Type: "test_review",
		ID:   "review",
		Payload: map[string]interface{}{
			"run_id":    wrapper.RunID,
			"responses": responses,
		},
	}
	conn.WriteJSON(resp)
	fmt.Printf("  → sent %d responses\n\n", len(responses))
}

// ─── Implementation Review Gate ─────────────────────────────

func handleImplReview(conn *websocket.Conn, payload interface{}, reader *bufio.Reader) {
	data, _ := json.Marshal(payload)
	var wrapper struct {
		RunID   string `json:"run_id"`
		Reviews []struct {
			CellID   string `json:"cell_id"`
			ImplCode string `json:"impl_code"`
			TestCode string `json:"test_code"`
			Brief    struct {
				Doc    string `json:"doc"`
				Schema string `json:"schema"`
			} `json:"brief"`
		} `json:"reviews"`
	}
	json.Unmarshal(data, &wrapper)

	fmt.Printf("\n┌─────────────────────────────────────────────────────┐\n")
	fmt.Printf("│  IMPL REVIEW — %d cells                              \n", len(wrapper.Reviews))
	fmt.Printf("└─────────────────────────────────────────────────────┘\n\n")

	for i, r := range wrapper.Reviews {
		lines := strings.Count(r.ImplCode, "\n") + 1
		fmt.Printf("  %d. %s — %d lines\n", i+1, r.CellID, lines)
	}

	fmt.Printf("\n  [a]pprove all  [v]iew <n>  [r]evise <n>  > ")
	input, _ := reader.ReadString('\n')
	input = strings.TrimSpace(strings.ToLower(input))

	if strings.HasPrefix(input, "v") {
		idx := 0
		fmt.Sscanf(input[1:], "%d", &idx)
		if idx > 0 && idx <= len(wrapper.Reviews) {
			r := wrapper.Reviews[idx-1]
			fmt.Printf("\n  === %s ===\n%s\n", r.CellID, r.ImplCode)
		}
		fmt.Printf("  [a]pprove all  [r]evise <n>  > ")
		input, _ = reader.ReadString('\n')
		input = strings.TrimSpace(strings.ToLower(input))
	}

	var responses []map[string]string
	if strings.HasPrefix(input, "r") {
		idx := 0
		fmt.Sscanf(input[1:], "%d", &idx)
		fmt.Printf("  Feedback for %s: ", wrapper.Reviews[idx-1].CellID)
		feedback, _ := reader.ReadString('\n')
		feedback = strings.TrimSpace(feedback)

		for i, r := range wrapper.Reviews {
			if i == idx-1 {
				responses = append(responses, map[string]string{
					"cell_id": r.CellID, "decision": "revise", "feedback": feedback,
				})
			} else {
				responses = append(responses, map[string]string{
					"cell_id": r.CellID, "decision": "approve",
				})
			}
		}
	} else {
		for _, r := range wrapper.Reviews {
			responses = append(responses, map[string]string{
				"cell_id": r.CellID, "decision": "approve",
			})
		}
	}

	resp := WSMessage{
		Type: "impl_review",
		ID:   "review",
		Payload: map[string]interface{}{
			"run_id":    wrapper.RunID,
			"responses": responses,
		},
	}
	conn.WriteJSON(resp)
	fmt.Printf("  → sent %d responses\n\n", len(responses))
}

// ─── Helpers ────────────────────────────────────────────────

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
