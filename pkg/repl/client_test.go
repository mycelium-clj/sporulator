package repl_test

import (
	"os"
	"strconv"
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/repl"
)

// nreplAddr returns host and port for the test nREPL server.
// Set NREPL_PORT (and optionally NREPL_HOST) to run integration tests.
// Tests are skipped if NREPL_PORT is not set.
func nreplAddr(t *testing.T) (string, int) {
	t.Helper()
	portStr := os.Getenv("NREPL_PORT")
	if portStr == "" {
		t.Skip("NREPL_PORT not set — skipping integration test (start an nREPL server to run)")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("invalid NREPL_PORT %q: %v", portStr, err)
	}
	host := os.Getenv("NREPL_HOST")
	if host == "" {
		host = "127.0.0.1"
	}
	return host, port
}

func connect(t *testing.T) *repl.Client {
	t.Helper()
	host, port := nreplAddr(t)
	client, err := repl.Connect(host, port)
	if err != nil {
		t.Fatalf("connect failed: %v", err)
	}
	t.Cleanup(func() { client.Close() })
	return client
}

func TestConnect(t *testing.T) {
	client := connect(t)
	_ = client // connection established successfully
}

func TestEvalSimple(t *testing.T) {
	client := connect(t)

	result, err := client.EvalCollect("(+ 3 4)")
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if result.Value != "7" {
		t.Errorf("expected value \"7\", got %q", result.Value)
	}
	if result.Ex != "" {
		t.Errorf("unexpected exception: %s", result.Ex)
	}
}

func TestEvalWithOutput(t *testing.T) {
	client := connect(t)

	result, err := client.EvalCollect(`(do (println "hello") 42)`)
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if result.Value != "42" {
		t.Errorf("expected value \"42\", got %q", result.Value)
	}
	if result.Out != "hello\n" {
		t.Errorf("expected out \"hello\\n\", got %q", result.Out)
	}
}

func TestEvalWithNamespace(t *testing.T) {
	client := connect(t)

	result, err := client.EvalCollect("(str *ns*)", repl.WithNs("user"))
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if result.Value != "\"user\"" {
		t.Errorf("expected namespace \"user\", got %q", result.Value)
	}
}

func TestEvalError(t *testing.T) {
	client := connect(t)

	result, err := client.EvalCollect("(throw (ex-info \"boom\" {}))")
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if result.Ex == "" {
		t.Error("expected exception, got none")
	}
}

func TestEvalMultipleSequential(t *testing.T) {
	client := connect(t)

	r1, err := client.EvalCollect("(+ 1 2)")
	if err != nil {
		t.Fatalf("first eval error: %v", err)
	}
	if r1.Value != "3" {
		t.Errorf("first: expected \"3\", got %q", r1.Value)
	}

	r2, err := client.EvalCollect("(* 3 4)")
	if err != nil {
		t.Fatalf("second eval error: %v", err)
	}
	if r2.Value != "12" {
		t.Errorf("second: expected \"12\", got %q", r2.Value)
	}
}

func TestCloneAndCloseSession(t *testing.T) {
	client := connect(t)

	session, err := client.Clone("")
	if err != nil {
		t.Fatalf("clone error: %v", err)
	}
	if session == "" {
		t.Fatal("clone returned empty session")
	}

	// Eval in the new session
	result, err := client.EvalCollect("(+ 10 20)", repl.WithSession(session))
	if err != nil {
		t.Fatalf("eval in session error: %v", err)
	}
	if result.Value != "30" {
		t.Errorf("expected \"30\", got %q", result.Value)
	}

	// Close the session
	err = client.CloseSession(session)
	if err != nil {
		t.Fatalf("close session error: %v", err)
	}

	// Verify session was removed from tracking
	for _, s := range client.Sessions {
		if s == session {
			t.Error("closed session still in Sessions list")
		}
	}
}

func TestDescribe(t *testing.T) {
	client := connect(t)

	msgs, err := client.Describe(false)
	if err != nil {
		t.Fatalf("describe error: %v", err)
	}
	if len(msgs) == 0 {
		t.Fatal("describe returned no messages")
	}
	// The describe response should contain an "ops" key
	found := false
	for _, m := range msgs {
		if _, ok := m["ops"]; ok {
			found = true
			break
		}
	}
	if !found {
		t.Error("describe response missing 'ops' key")
	}
}

func TestLsSessions(t *testing.T) {
	client := connect(t)

	// Create a session first so the list is non-empty
	session, err := client.Clone("")
	if err != nil {
		t.Fatalf("clone error: %v", err)
	}

	sessions, err := client.LsSessions()
	if err != nil {
		t.Fatalf("ls-sessions error: %v", err)
	}
	found := false
	for _, s := range sessions {
		if s == session {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("cloned session %s not found in ls-sessions result: %v", session, sessions)
	}
}

func TestEvalDefAndUse(t *testing.T) {
	client := connect(t)

	// Define something in one eval
	_, err := client.EvalCollect("(def test-sporulator-val 42)", repl.WithNs("user"))
	if err != nil {
		t.Fatalf("def error: %v", err)
	}

	// Use it in another eval
	result, err := client.EvalCollect("test-sporulator-val", repl.WithNs("user"))
	if err != nil {
		t.Fatalf("read error: %v", err)
	}
	if result.Value != "42" {
		t.Errorf("expected \"42\", got %q", result.Value)
	}
}

func TestEvalLargeOutput(t *testing.T) {
	client := connect(t)

	// Generate a large output to test chunked message handling
	result, err := client.EvalCollect(`(apply str (repeat 5000 "x"))`)
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	// The value is a quoted string, so it'll be "\"xxx...xxx\""
	// Just check it's reasonably long
	if len(result.Value) < 5000 {
		t.Errorf("expected large value, got length %d", len(result.Value))
	}
}
