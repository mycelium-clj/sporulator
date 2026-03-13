package api_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"github.com/mycelium-clj/sporulator/pkg/agents"
	"github.com/mycelium-clj/sporulator/pkg/api"
	"github.com/mycelium-clj/sporulator/pkg/llm"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

func setupServer(t *testing.T) (*api.Server, *store.Store) {
	t.Helper()
	st, err := store.Open(":memory:")
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { st.Close() })

	// Mock LLM server — handles both streaming and non-streaming
	mockLLM := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)

		if reqBody["stream"] == true {
			w.Header().Set("Content-Type", "text/event-stream")
			flusher := w.(http.Flusher)
			chunk, _ := json.Marshal(map[string]any{
				"choices": []map[string]any{
					{"delta": map[string]any{"content": "mock response"}},
				},
			})
			fmt.Fprintf(w, "data: %s\n\n", chunk)
			fmt.Fprintf(w, "data: [DONE]\n\n")
			flusher.Flush()
			return
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": "mock response"}},
			},
		})
	}))
	t.Cleanup(mockLLM.Close)

	client := llm.NewClient(mockLLM.URL, "test", "test")
	mgr := agents.NewManager(agents.Config{
		GraphClient: client,
		CellClient:  client,
		Store:       st,
	})

	srv := api.NewServer(api.Config{
		Store:   st,
		Manager: mgr,
	})

	return srv, st
}

func doRequest(srv *api.Server, method, path string, body any) *httptest.ResponseRecorder {
	var reqBody *bytes.Buffer
	if body != nil {
		data, _ := json.Marshal(body)
		reqBody = bytes.NewBuffer(data)
	} else {
		reqBody = &bytes.Buffer{}
	}

	req := httptest.NewRequest(method, path, reqBody)
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	srv.ServeHTTP(w, req)
	return w
}

func parseJSON(w *httptest.ResponseRecorder) map[string]any {
	var result map[string]any
	json.Unmarshal(w.Body.Bytes(), &result)
	return result
}

// --- Cell API tests ---

func TestListCellsEmpty(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "GET", "/api/cells", nil)
	if w.Code != 200 {
		t.Fatalf("status: %d", w.Code)
	}

	var cells []any
	json.Unmarshal(w.Body.Bytes(), &cells)
	if len(cells) != 0 {
		t.Errorf("expected empty list, got %d", len(cells))
	}
}

func TestSaveAndGetCell(t *testing.T) {
	srv, _ := setupServer(t)

	// Save
	w := doRequest(srv, "POST", "/api/cell?id=:math/double", map[string]string{
		"handler":    "(fn [_ d] {:r (* 2 (:x d))})",
		"schema":     "{:input {:x :int} :output {:r :int}}",
		"doc":        "doubles input",
		"created_by": "test",
	})
	if w.Code != 201 {
		t.Fatalf("save status: %d body: %s", w.Code, w.Body.String())
	}
	result := parseJSON(w)
	if result["version"] != float64(1) {
		t.Errorf("version: %v", result["version"])
	}

	// Get
	w = doRequest(srv, "GET", "/api/cell?id=:math/double", nil)
	if w.Code != 200 {
		t.Fatalf("get status: %d", w.Code)
	}
	cell := parseJSON(w)
	if cell["Doc"] != "doubles input" {
		t.Errorf("doc: %v", cell["Doc"])
	}
}

func TestGetCellNotFound(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "GET", "/api/cell?id=:nope/missing", nil)
	if w.Code != 404 {
		t.Errorf("expected 404, got %d", w.Code)
	}
}

func TestCellHistory(t *testing.T) {
	srv, st := setupServer(t)

	st.SaveCell(&store.Cell{ID: ":app/x", Handler: "v1"})
	st.SaveCell(&store.Cell{ID: ":app/x", Handler: "v2"})

	w := doRequest(srv, "GET", "/api/cell/history?id=:app/x", nil)
	if w.Code != 200 {
		t.Fatalf("status: %d", w.Code)
	}

	var history []any
	json.Unmarshal(w.Body.Bytes(), &history)
	if len(history) != 2 {
		t.Errorf("expected 2 versions, got %d", len(history))
	}
}

func TestListCellsWithData(t *testing.T) {
	srv, st := setupServer(t)

	st.SaveCell(&store.Cell{ID: ":app/a", Handler: "h", Doc: "A"})
	st.SaveCell(&store.Cell{ID: ":app/b", Handler: "h", Doc: "B"})

	w := doRequest(srv, "GET", "/api/cells", nil)

	var cells []any
	json.Unmarshal(w.Body.Bytes(), &cells)
	if len(cells) != 2 {
		t.Errorf("expected 2 cells, got %d", len(cells))
	}
}

// --- Manifest API tests ---

func TestSaveAndGetManifest(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "POST", "/api/manifest?id=:todo-app", map[string]string{
		"body":       "{:id :todo-app}",
		"created_by": "test",
	})
	if w.Code != 201 {
		t.Fatalf("save status: %d body: %s", w.Code, w.Body.String())
	}

	w = doRequest(srv, "GET", "/api/manifest?id=:todo-app", nil)
	if w.Code != 200 {
		t.Fatalf("get status: %d", w.Code)
	}
	m := parseJSON(w)
	if m["Body"] != "{:id :todo-app}" {
		t.Errorf("body: %v", m["Body"])
	}
}

func TestListManifests(t *testing.T) {
	srv, st := setupServer(t)

	st.SaveManifest(&store.Manifest{ID: ":wf-a", Body: "a"})
	st.SaveManifest(&store.Manifest{ID: ":wf-b", Body: "b"})

	w := doRequest(srv, "GET", "/api/manifests", nil)

	var manifests []any
	json.Unmarshal(w.Body.Bytes(), &manifests)
	if len(manifests) != 2 {
		t.Errorf("expected 2, got %d", len(manifests))
	}
}

// --- REPL API tests ---

func TestReplStatusNoConnection(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "GET", "/api/repl/status", nil)
	if w.Code != 200 {
		t.Fatalf("status: %d", w.Code)
	}
	result := parseJSON(w)
	if result["connected"] != false {
		t.Errorf("expected not connected")
	}
}

func TestReplEvalNoConnection(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "POST", "/api/repl/eval", map[string]string{"code": "(+ 1 2)"})
	if w.Code != 503 {
		t.Errorf("expected 503, got %d", w.Code)
	}
}

// --- WebSocket tests ---

func TestWebSocketConnect(t *testing.T) {
	srv, _ := setupServer(t)

	ts := httptest.NewServer(srv)
	defer ts.Close()

	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws connect: %v", err)
	}
	defer conn.Close()

	// Send an unknown message type
	msg := api.WSMessage{Type: "unknown_type"}
	data, _ := json.Marshal(msg)
	conn.WriteMessage(websocket.TextMessage, data)

	// Should get an error response
	conn.SetReadDeadline(time.Now().Add(2 * time.Second))
	_, respData, err := conn.ReadMessage()
	if err != nil {
		t.Fatalf("read error: %v", err)
	}

	var resp api.WSMessage
	json.Unmarshal(respData, &resp)
	if resp.Type != "error" {
		t.Errorf("expected error type, got %q", resp.Type)
	}
}

func TestWebSocketGraphChat(t *testing.T) {
	srv, _ := setupServer(t)

	ts := httptest.NewServer(srv)
	defer ts.Close()

	wsURL := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws connect: %v", err)
	}
	defer conn.Close()

	// Send graph_chat message
	msg := api.WSMessage{
		Type: "graph_chat",
		ID:   "test-session",
		Payload: map[string]string{
			"session_id": "test-session",
			"message":    "Design a workflow",
		},
	}
	data, _ := json.Marshal(msg)
	conn.WriteMessage(websocket.TextMessage, data)

	// Read responses until stream_end
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	gotEnd := false
	for i := 0; i < 10; i++ {
		_, respData, err := conn.ReadMessage()
		if err != nil {
			t.Fatalf("read %d: %v", i, err)
		}
		var resp api.WSMessage
		json.Unmarshal(respData, &resp)

		if resp.Type == "stream_end" {
			gotEnd = true
			payload := resp.Payload.(map[string]any)
			if content, ok := payload["content"].(string); ok {
				if content != "mock response" {
					t.Errorf("content: %q", content)
				}
			}
			break
		}
	}
	if !gotEnd {
		t.Error("never received stream_end")
	}
}

func TestSaveCellMissingHandler(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "POST", "/api/cell?id=:app/x", map[string]string{
		"schema": "{:input {:x :int}}",
	})
	if w.Code != 400 {
		t.Errorf("expected 400, got %d: %s", w.Code, w.Body.String())
	}
}

func TestSaveManifestMissingBody(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "POST", "/api/manifest?id=:app", map[string]string{
		"created_by": "test",
	})
	if w.Code != 400 {
		t.Errorf("expected 400, got %d: %s", w.Code, w.Body.String())
	}
}

func TestCellTestsEmpty(t *testing.T) {
	srv, _ := setupServer(t)

	w := doRequest(srv, "GET", "/api/cell/tests?id=:app/x", nil)
	if w.Code != 200 {
		t.Fatalf("status: %d", w.Code)
	}
	var results []any
	json.Unmarshal(w.Body.Bytes(), &results)
	if len(results) != 0 {
		t.Errorf("expected empty, got %d", len(results))
	}
}
