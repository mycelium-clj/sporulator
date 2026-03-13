package agents_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/agents"
	"github.com/mycelium-clj/sporulator/pkg/llm"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// --- Extraction tests ---

func TestExtractCodeBlocks(t *testing.T) {
	response := "Here's the code:\n```clojure\n(+ 1 2)\n```\nAnd more:\n```edn\n{:a 1}\n```"
	blocks := agents.ExtractCodeBlocks(response)
	if len(blocks) != 2 {
		t.Fatalf("expected 2 blocks, got %d", len(blocks))
	}
	if blocks[0] != "(+ 1 2)" {
		t.Errorf("block 0: %q", blocks[0])
	}
	if blocks[1] != "{:a 1}" {
		t.Errorf("block 1: %q", blocks[1])
	}
}

func TestExtractCodeBlocksBare(t *testing.T) {
	response := "```\n(def x 1)\n```"
	blocks := agents.ExtractCodeBlocks(response)
	if len(blocks) != 1 || blocks[0] != "(def x 1)" {
		t.Errorf("blocks: %v", blocks)
	}
}

func TestExtractFirstCodeBlockFallback(t *testing.T) {
	response := "Just some text with no code blocks"
	result := agents.ExtractFirstCodeBlock(response)
	if result != "Just some text with no code blocks" {
		t.Errorf("expected trimmed response, got %q", result)
	}
}

func TestExtractDefcell(t *testing.T) {
	response := `Here's your cell:

` + "```clojure" + `
(cell/defcell :math/double
  {:input {:x :int} :output {:result :int}}
  (fn [_ data]
    {:result (* 2 (:x data))}))
` + "```" + `

This cell doubles the input.`

	code := agents.ExtractDefcell(response)
	if !strings.HasPrefix(code, "(cell/defcell :math/double") {
		t.Errorf("unexpected prefix: %q", code[:40])
	}
	if !strings.HasSuffix(code, "))") {
		t.Errorf("unexpected suffix: %q", code[len(code)-10:])
	}
}

func TestExtractDefcellWithStrings(t *testing.T) {
	// Ensure balanced paren extraction handles strings with parens
	response := "```clojure\n(cell/defcell :app/x\n  {:input {:s :string}}\n  (fn [_ data]\n    {:result (str \"hello (world)\")}))\n```"
	code := agents.ExtractDefcell(response)
	if !strings.Contains(code, "\"hello (world)\"") {
		t.Errorf("string with parens not preserved: %q", code)
	}
}

func TestExtractAllDefcells(t *testing.T) {
	response := "```clojure\n(cell/defcell :a/x\n  (fn [_ d] {:r 1}))\n\n(cell/defcell :b/y\n  (fn [_ d] {:r 2}))\n```"
	forms := agents.ExtractAllDefcells(response)
	if len(forms) != 2 {
		t.Fatalf("expected 2 forms, got %d", len(forms))
	}
	if !strings.Contains(forms[0], ":a/x") {
		t.Errorf("form 0: %q", forms[0])
	}
	if !strings.Contains(forms[1], ":b/y") {
		t.Errorf("form 1: %q", forms[1])
	}
}

// --- Mock helpers ---

func mockLLMServer(t *testing.T, handler http.HandlerFunc) *llm.Client {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	return llm.NewClient(srv.URL, "test-key", "test-model")
}

func fixedReply(content string) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": content}},
			},
		})
	}
}

func openTestStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(":memory:")
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

// --- Graph agent tests ---

func TestGraphAgentChat(t *testing.T) {
	client := mockLLMServer(t, fixedReply("Here's your workflow:\n```edn\n{:id :test}\n```"))
	st := openTestStore(t)

	mgr := agents.NewManager(agents.Config{
		GraphClient: client,
		CellClient:  client,
		Store:       st,
	})

	agent := mgr.GetGraphAgent("test-session")
	response, err := agent.Chat(context.Background(), "Design a simple workflow")
	if err != nil {
		t.Fatalf("chat error: %v", err)
	}
	if !strings.Contains(response, "{:id :test}") {
		t.Errorf("response: %q", response)
	}
}

func TestGraphAgentSessionPersistence(t *testing.T) {
	var callCount int32
	client := mockLLMServer(t, func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&callCount, 1)

		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)
		msgs := reqBody["messages"].([]any)

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": fmt.Sprintf("reply (msgs: %d)", len(msgs))}},
			},
		})
	})
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	agent := mgr.GetGraphAgent("persist-test")

	agent.Chat(context.Background(), "first")
	reply, _ := agent.Chat(context.Background(), "second")

	// Second call should include: system + user1 + assistant1 + user2 = 4 messages
	if !strings.Contains(reply, "msgs: 4") {
		t.Errorf("expected 4 messages in second call, got: %s", reply)
	}

	// Same session ID returns the same agent
	agent2 := mgr.GetGraphAgent("persist-test")
	if len(agent2.History()) != 4 {
		t.Errorf("expected 4 history entries, got %d", len(agent2.History()))
	}
}

func TestGraphAgentSaveManifest(t *testing.T) {
	client := mockLLMServer(t, fixedReply("```edn\n{:id :my-workflow :cells {}}\n```"))
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	agent := mgr.GetGraphAgent("save-test")
	response, _ := agent.Chat(context.Background(), "design something")

	version, err := agent.SaveManifest(":my-workflow", response, "graph-agent")
	if err != nil {
		t.Fatalf("save manifest: %v", err)
	}
	if version != 1 {
		t.Errorf("expected version 1, got %d", version)
	}

	// Verify it's in the store
	m, _ := st.GetLatestManifest(":my-workflow")
	if m == nil {
		t.Fatal("manifest not in store")
	}
	if !strings.Contains(m.Body, ":my-workflow") {
		t.Errorf("manifest body: %q", m.Body)
	}
}

func TestGraphAgentReset(t *testing.T) {
	client := mockLLMServer(t, fixedReply("ok"))
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	agent := mgr.GetGraphAgent("reset-test")
	agent.Chat(context.Background(), "hello")

	mgr.ResetGraphAgent("reset-test")
	agent2 := mgr.GetGraphAgent("reset-test")
	if len(agent2.History()) != 0 {
		t.Errorf("expected fresh session after reset, got %d messages", len(agent2.History()))
	}
}

// --- Cell agent tests ---

func TestCellAgentImplement(t *testing.T) {
	cellCode := "(cell/defcell :math/double\n  {:input {:x :int} :output {:result :int}}\n  (fn [_ data]\n    {:result (* 2 (:x data))}))"
	client := mockLLMServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify the prompt includes the brief info
		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)
		msgs := reqBody["messages"].([]any)
		lastMsg := msgs[len(msgs)-1].(map[string]any)["content"].(string)

		if !strings.Contains(lastMsg, ":math/double") {
			t.Errorf("prompt should contain cell ID")
		}
		if !strings.Contains(lastMsg, "Double a number") {
			t.Errorf("prompt should contain doc")
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": "```clojure\n" + cellCode + "\n```"}},
			},
		})
	})
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	agent := mgr.NewCellAgent(":math/double")
	result, err := agent.Implement(context.Background(), agents.CellBrief{
		ID:     ":math/double",
		Doc:    "Double a number",
		Schema: "{:input {:x :int} :output {:result :int}}",
	})
	if err != nil {
		t.Fatalf("implement error: %v", err)
	}
	if !strings.Contains(result.Code, "cell/defcell") {
		t.Errorf("code doesn't contain defcell: %q", result.Code)
	}
	if result.CellID != ":math/double" {
		t.Errorf("cell ID: %s", result.CellID)
	}
}

func TestCellAgentIterate(t *testing.T) {
	callCount := 0
	client := mockLLMServer(t, func(w http.ResponseWriter, r *http.Request) {
		callCount++
		code := "(cell/defcell :app/x (fn [_ d] {:r 1}))"
		if callCount == 2 {
			code = "(cell/defcell :app/x (fn [_ d] {:r (* (:a d) (:b d))}))"
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": "```clojure\n" + code + "\n```"}},
			},
		})
	})
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	agent := mgr.NewCellAgent(":app/x")

	// Initial implementation
	agent.Implement(context.Background(), agents.CellBrief{ID: ":app/x", Doc: "test"})

	// Iterate with feedback
	result, err := agent.Iterate(context.Background(), "The result should multiply :a and :b")
	if err != nil {
		t.Fatalf("iterate error: %v", err)
	}
	if !strings.Contains(result.Code, "* (:a d) (:b d)") {
		t.Errorf("iterated code: %q", result.Code)
	}
}

func TestCellAgentSave(t *testing.T) {
	client := mockLLMServer(t, fixedReply("```clojure\n(cell/defcell :app/y (fn [_ d] {:v 1}))\n```"))
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	agent := mgr.NewCellAgent(":app/y")
	result, _ := agent.Implement(context.Background(), agents.CellBrief{ID: ":app/y"})

	version, err := agent.Save(result, "{:input {} :output {:v :int}}", "[]", "test cell", "cell-agent")
	if err != nil {
		t.Fatalf("save error: %v", err)
	}
	if version != 1 {
		t.Errorf("expected version 1, got %d", version)
	}

	// Verify in store
	cell, _ := st.GetLatestCell(":app/y")
	if cell == nil {
		t.Fatal("cell not in store")
	}
	if cell.CreatedBy != "cell-agent" {
		t.Errorf("created_by: %q", cell.CreatedBy)
	}
}

func TestImplementCellsParallel(t *testing.T) {
	var callCount int32
	client := mockLLMServer(t, func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&callCount, 1)

		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)
		msgs := reqBody["messages"].([]any)
		lastMsg := msgs[len(msgs)-1].(map[string]any)["content"].(string)

		// Echo back the cell ID in the response
		var cellID string
		if strings.Contains(lastMsg, ":cell/a") {
			cellID = ":cell/a"
		} else if strings.Contains(lastMsg, ":cell/b") {
			cellID = ":cell/b"
		} else {
			cellID = ":cell/unknown"
		}

		code := fmt.Sprintf("(cell/defcell %s (fn [_ d] {:r 1}))", cellID)
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": "```clojure\n" + code + "\n```"}},
			},
		})
	})
	st := openTestStore(t)
	mgr := agents.NewManager(agents.Config{GraphClient: client, CellClient: client, Store: st})

	briefs := []agents.CellBrief{
		{ID: ":cell/a", Doc: "Cell A"},
		{ID: ":cell/b", Doc: "Cell B"},
	}

	results := agents.ImplementCells(context.Background(), mgr, briefs)
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}

	for _, r := range results {
		if r.Error != nil {
			t.Errorf("cell %s error: %v", r.CellID, r.Error)
		}
		if !strings.Contains(r.Code, r.CellID) {
			t.Errorf("cell %s code doesn't contain ID: %q", r.CellID, r.Code)
		}
	}

	if atomic.LoadInt32(&callCount) != 2 {
		t.Errorf("expected 2 LLM calls, got %d", callCount)
	}
}
