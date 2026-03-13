package bridge_test

import (
	"os"
	"strconv"
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/bridge"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

func nreplPort(t *testing.T) int {
	t.Helper()
	portStr := os.Getenv("NREPL_PORT")
	if portStr == "" {
		t.Skip("NREPL_PORT not set — skipping integration test")
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatalf("invalid NREPL_PORT: %v", err)
	}
	return port
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

func connectBridge(t *testing.T) *bridge.Bridge {
	t.Helper()
	port := nreplPort(t)
	st := openTestStore(t)

	b, err := bridge.Connect(bridge.Config{
		Host:  "127.0.0.1",
		Port:  port,
		Store: st,
	})
	if err != nil {
		t.Fatalf("bridge connect: %v", err)
	}
	t.Cleanup(func() { b.Close() })
	return b
}

func TestBridgeConnect(t *testing.T) {
	b := connectBridge(t)
	if !b.Connected() {
		t.Error("bridge not connected")
	}
}

func TestBridgeEval(t *testing.T) {
	b := connectBridge(t)

	result, err := b.Eval("(+ 10 20)")
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if result.Value != "30" {
		t.Errorf("expected 30, got %q", result.Value)
	}
}

func TestBridgeEvalError(t *testing.T) {
	b := connectBridge(t)

	result, err := b.Eval(`(throw (ex-info "test error" {}))`)
	if err != nil {
		t.Fatalf("eval error: %v", err)
	}
	if !result.IsError() {
		t.Error("expected error result")
	}
}

func TestBridgeInstantiateCell(t *testing.T) {
	b := connectBridge(t)

	code := `(cell/defcell :test/bridge-double
  {:input {:x :int} :output {:result :int}}
  (fn [_ data]
    {:result (* 2 (:x data))}))`

	result, err := b.InstantiateCell(code)
	if err != nil {
		t.Fatalf("instantiate error: %v", err)
	}
	if result.IsError() {
		t.Fatalf("instantiate failed: %s %s", result.Ex, result.Err)
	}
}

func TestBridgeInstantiateCellFromStore(t *testing.T) {
	port := nreplPort(t)
	st := openTestStore(t)

	// Save a cell to the store
	st.SaveCell(&store.Cell{
		ID: ":test/store-cell",
		Handler: `(cell/defcell :test/store-cell
  {:input {:n :int} :output {:doubled :int}}
  (fn [_ data]
    {:doubled (* 2 (:n data))}))`,
	})

	b, err := bridge.Connect(bridge.Config{
		Host:  "127.0.0.1",
		Port:  port,
		Store: st,
	})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	defer b.Close()

	result, err := b.InstantiateCellFromStore(":test/store-cell")
	if err != nil {
		t.Fatalf("instantiate from store: %v", err)
	}
	if result.IsError() {
		t.Fatalf("failed: %s %s", result.Ex, result.Err)
	}
}

func TestBridgeValidateSchema(t *testing.T) {
	b := connectBridge(t)

	// Ensure malli is available
	_, err := b.Eval(`(require '[malli.core :as m])`)
	if err != nil {
		t.Skip("malli not available in REPL")
	}

	result, err := b.ValidateSchema(
		`[:map [:x :int] [:y :int]]`,
		`{:x 1 :y 2}`,
	)
	if err != nil {
		t.Fatalf("validate error: %v", err)
	}
	if result.IsError() {
		t.Fatalf("validate failed: %s", result.Ex)
	}
	if result.Value == "" {
		t.Error("expected validation result")
	}
}

func TestBridgeTestCell(t *testing.T) {
	b := connectBridge(t)

	// Register a simple cell
	code := `(cell/defcell :test/adder
  {:input {:a :int :b :int} :output {:sum :int}}
  (fn [_ data]
    {:sum (+ (:a data) (:b data))}))`

	result, err := b.InstantiateCell(code)
	if err != nil {
		t.Fatalf("instantiate: %v", err)
	}
	if result.IsError() {
		t.Fatalf("instantiate failed: %s %s", result.Ex, result.Err)
	}

	// Check if cell/get-cell! resolves
	check, err := b.Eval(`(some? (cell/get-cell! :test/adder))`)
	if err != nil || check.IsError() {
		t.Skip("cell/get-cell! not available — skipping test execution")
	}

	outcomes, err := b.TestCell(":test/adder", "{}", []bridge.TestCase{
		{Input: "{:a 1 :b 2}", Expected: "{:sum 3}"},
	})
	if err != nil {
		t.Fatalf("test cell: %v", err)
	}
	if len(outcomes) != 1 {
		t.Fatalf("expected 1 outcome, got %d", len(outcomes))
	}
}

func TestBridgeConnected(t *testing.T) {
	b := connectBridge(t)

	if !b.Connected() {
		t.Error("should be connected")
	}

	b.Close()
	if b.Connected() {
		t.Error("should not be connected after close")
	}
}
