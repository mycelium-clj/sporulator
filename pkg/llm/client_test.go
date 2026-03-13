package llm_test

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/llm"
)

// mockServer creates a test HTTP server that responds to /v1/chat/completions.
func mockServer(t *testing.T, handler http.HandlerFunc) (*httptest.Server, *llm.Client) {
	t.Helper()
	srv := httptest.NewServer(handler)
	t.Cleanup(srv.Close)
	client := llm.NewClient(srv.URL, "test-key", "test-model")
	return srv, client
}

func TestChatNonStreaming(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Verify request
		if r.Method != "POST" {
			t.Errorf("expected POST, got %s", r.Method)
		}
		if r.URL.Path != "/v1/chat/completions" {
			t.Errorf("unexpected path: %s", r.URL.Path)
		}
		if auth := r.Header.Get("Authorization"); auth != "Bearer test-key" {
			t.Errorf("auth header: %s", auth)
		}

		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)

		if reqBody["model"] != "test-model" {
			t.Errorf("model: %v", reqBody["model"])
		}
		if reqBody["stream"] != false {
			t.Errorf("stream should be false")
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": "Hello, world!"}},
			},
			"usage": map[string]any{
				"prompt_tokens":     10,
				"completion_tokens": 5,
			},
		})
	})

	resp, err := client.Chat(context.Background(), &llm.ChatRequest{
		Messages:    []llm.Message{{Role: "user", Content: "Hi"}},
		Temperature: 0.3,
		MaxTokens:   100,
	})
	if err != nil {
		t.Fatalf("chat error: %v", err)
	}
	if resp.Content != "Hello, world!" {
		t.Errorf("content: %q", resp.Content)
	}
	if resp.PromptTokens != 10 {
		t.Errorf("prompt tokens: %d", resp.PromptTokens)
	}
	if resp.CompletionTokens != 5 {
		t.Errorf("completion tokens: %d", resp.CompletionTokens)
	}
}

func TestChatStreaming(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)

		if reqBody["stream"] != true {
			t.Errorf("stream should be true")
		}

		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)

		chunks := []string{"Hello", ", ", "world", "!"}
		for _, chunk := range chunks {
			data := map[string]any{
				"choices": []map[string]any{
					{"delta": map[string]any{"content": chunk}},
				},
			}
			jsonBytes, _ := json.Marshal(data)
			fmt.Fprintf(w, "data: %s\n\n", jsonBytes)
			flusher.Flush()
		}

		// Final chunk with usage
		data := map[string]any{
			"choices": []map[string]any{
				{"delta": map[string]any{}, "finish_reason": "stop"},
			},
			"usage": map[string]any{
				"prompt_tokens":     10,
				"completion_tokens": 4,
			},
		}
		jsonBytes, _ := json.Marshal(data)
		fmt.Fprintf(w, "data: %s\n\n", jsonBytes)
		fmt.Fprintf(w, "data: [DONE]\n\n")
		flusher.Flush()
	})

	var chunks []string
	resp, err := client.ChatStream(context.Background(), &llm.ChatRequest{
		Messages:    []llm.Message{{Role: "user", Content: "Hi"}},
		Temperature: 0.3,
		MaxTokens:   100,
	}, func(chunk string) {
		chunks = append(chunks, chunk)
	})
	if err != nil {
		t.Fatalf("stream error: %v", err)
	}
	if resp.Content != "Hello, world!" {
		t.Errorf("full content: %q", resp.Content)
	}
	if len(chunks) != 4 {
		t.Errorf("expected 4 chunks, got %d: %v", len(chunks), chunks)
	}
	if strings.Join(chunks, "") != "Hello, world!" {
		t.Errorf("joined chunks: %q", strings.Join(chunks, ""))
	}
}

func TestChatAPIError(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusTooManyRequests)
		w.Write([]byte(`{"error": {"message": "rate limited"}}`))
	})

	_, err := client.Chat(context.Background(), &llm.ChatRequest{
		Messages: []llm.Message{{Role: "user", Content: "Hi"}},
	})
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "429") {
		t.Errorf("error should mention status code: %v", err)
	}
}

func TestChatEmptyChoices(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{},
		})
	})

	_, err := client.Chat(context.Background(), &llm.ChatRequest{
		Messages: []llm.Message{{Role: "user", Content: "Hi"}},
	})
	if err == nil {
		t.Fatal("expected error for empty choices")
	}
}

func TestChatContextCancellation(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		// Block forever — context should cancel
		<-r.Context().Done()
	})

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // cancel immediately

	_, err := client.Chat(ctx, &llm.ChatRequest{
		Messages: []llm.Message{{Role: "user", Content: "Hi"}},
	})
	if err == nil {
		t.Fatal("expected error from cancelled context")
	}
}

// --- Session tests ---

func TestSessionSend(t *testing.T) {
	callCount := 0
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		callCount++
		var reqBody map[string]any
		json.NewDecoder(r.Body).Decode(&reqBody)

		msgs := reqBody["messages"].([]any)

		// First call: system + user
		// Second call: system + user + assistant + user
		if callCount == 1 {
			if len(msgs) != 2 {
				t.Errorf("call 1: expected 2 messages, got %d", len(msgs))
			}
		} else {
			if len(msgs) != 4 {
				t.Errorf("call 2: expected 4 messages, got %d", len(msgs))
			}
		}

		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": fmt.Sprintf("reply-%d", callCount)}},
			},
		})
	})

	session := llm.NewSession("test", "You are a helper.")
	ctx := context.Background()

	reply1, err := session.Send(ctx, client, "Hello")
	if err != nil {
		t.Fatalf("send 1: %v", err)
	}
	if reply1 != "reply-1" {
		t.Errorf("reply 1: %q", reply1)
	}

	reply2, err := session.Send(ctx, client, "Follow up")
	if err != nil {
		t.Fatalf("send 2: %v", err)
	}
	if reply2 != "reply-2" {
		t.Errorf("reply 2: %q", reply2)
	}

	// Verify history
	history := session.History()
	if len(history) != 4 {
		t.Fatalf("expected 4 history entries, got %d", len(history))
	}
	if history[0].Role != "user" || history[0].Content != "Hello" {
		t.Errorf("history[0]: %+v", history[0])
	}
	if history[1].Role != "assistant" || history[1].Content != "reply-1" {
		t.Errorf("history[1]: %+v", history[1])
	}
}

func TestSessionReset(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]any{
			"choices": []map[string]any{
				{"message": map[string]any{"content": "ok"}},
			},
		})
	})

	session := llm.NewSession("test", "system")
	session.Send(context.Background(), client, "msg1")
	session.Send(context.Background(), client, "msg2")

	if len(session.History()) != 4 {
		t.Fatalf("expected 4 messages before reset")
	}

	session.Reset()
	if len(session.History()) != 0 {
		t.Errorf("expected 0 messages after reset, got %d", len(session.History()))
	}

	// System prompt still works after reset
	msgs := session.Messages()
	if len(msgs) != 1 || msgs[0].Role != "system" {
		t.Errorf("system prompt lost after reset: %+v", msgs)
	}
}

func TestSessionSendErrorRollback(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		w.Write([]byte("error"))
	})

	session := llm.NewSession("test", "system")
	_, err := session.Send(context.Background(), client, "will fail")
	if err == nil {
		t.Fatal("expected error")
	}

	// User message should have been rolled back
	if len(session.History()) != 0 {
		t.Errorf("history should be empty after error, got %d", len(session.History()))
	}
}

func TestSessionSendStream(t *testing.T) {
	_, client := mockServer(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		flusher := w.(http.Flusher)

		for _, chunk := range []string{"streamed", " ", "reply"} {
			data, _ := json.Marshal(map[string]any{
				"choices": []map[string]any{
					{"delta": map[string]any{"content": chunk}},
				},
			})
			fmt.Fprintf(w, "data: %s\n\n", data)
			flusher.Flush()
		}
		fmt.Fprintf(w, "data: [DONE]\n\n")
		flusher.Flush()
	})

	session := llm.NewSession("test", "system")
	var chunks []string
	reply, err := session.SendStream(context.Background(), client, "stream me", func(chunk string) {
		chunks = append(chunks, chunk)
	})
	if err != nil {
		t.Fatalf("stream error: %v", err)
	}
	if reply != "streamed reply" {
		t.Errorf("reply: %q", reply)
	}
	if len(chunks) != 3 {
		t.Errorf("expected 3 chunks, got %d", len(chunks))
	}

	// History should contain the full reply
	history := session.History()
	if len(history) != 2 {
		t.Fatalf("expected 2 history entries, got %d", len(history))
	}
	if history[1].Content != "streamed reply" {
		t.Errorf("history reply: %q", history[1].Content)
	}
}
