// Package bridge connects the sporulator to a live Clojure system via nREPL.
// It can instantiate cells, run tests, validate schemas, and compile workflows.
package bridge

import (
	"fmt"
	"strings"

	"github.com/mycelium-clj/sporulator/pkg/repl"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// Bridge connects to an nREPL server and provides high-level operations
// for working with Mycelium cells and workflows.
type Bridge struct {
	client  *repl.Client
	session string
	store   *store.Store
}

// Config configures the bridge connection.
type Config struct {
	Host  string
	Port  int
	Store *store.Store
}

// Connect establishes a connection to an nREPL server and sets up
// a dedicated session with Mycelium requires loaded.
func Connect(cfg Config) (*Bridge, error) {
	client, err := repl.Connect(cfg.Host, cfg.Port)
	if err != nil {
		return nil, fmt.Errorf("bridge connect: %w", err)
	}

	// Create a dedicated session for sporulator work
	session, err := client.Clone("")
	if err != nil {
		client.Close()
		return nil, fmt.Errorf("bridge clone session: %w", err)
	}

	b := &Bridge{
		client:  client,
		session: session,
		store:   cfg.Store,
	}

	// Require mycelium.cell in our session
	_, err = b.Eval(`(require '[mycelium.cell :as cell])`)
	if err != nil {
		client.Close()
		return nil, fmt.Errorf("bridge require mycelium.cell: %w", err)
	}

	return b, nil
}

// Close shuts down the bridge session and connection.
func (b *Bridge) Close() error {
	b.client.CloseSession(b.session)
	return b.client.Close()
}

// EvalResult holds the outcome of evaluating Clojure code.
type EvalResult struct {
	Value string
	Out   string
	Err   string
	Ex    string
}

// IsError returns true if the evaluation produced an exception.
func (r *EvalResult) IsError() bool {
	return r.Ex != ""
}

// Eval evaluates Clojure code in the bridge's session.
func (b *Bridge) Eval(code string) (*EvalResult, error) {
	result, err := b.client.EvalCollect(code, repl.WithSession(b.session))
	if err != nil {
		return nil, fmt.Errorf("bridge eval: %w", err)
	}
	return &EvalResult{
		Value: result.Value,
		Out:   result.Out,
		Err:   result.Err,
		Ex:    result.Ex,
	}, nil
}

// EvalInNs evaluates code in a specific namespace.
func (b *Bridge) EvalInNs(ns, code string) (*EvalResult, error) {
	result, err := b.client.EvalCollect(code,
		repl.WithSession(b.session),
		repl.WithNs(ns))
	if err != nil {
		return nil, fmt.Errorf("bridge eval in ns: %w", err)
	}
	return &EvalResult{
		Value: result.Value,
		Out:   result.Out,
		Err:   result.Err,
		Ex:    result.Ex,
	}, nil
}

// InstantiateCell evaluates a defcell form in the REPL, registering the cell.
func (b *Bridge) InstantiateCell(defcellCode string) (*EvalResult, error) {
	return b.Eval(defcellCode)
}

// InstantiateCellFromStore loads the latest version of a cell from the store
// and evaluates it in the REPL.
func (b *Bridge) InstantiateCellFromStore(cellID string) (*EvalResult, error) {
	cell, err := b.store.GetLatestCell(cellID)
	if err != nil {
		return nil, fmt.Errorf("load cell %s: %w", cellID, err)
	}
	if cell == nil {
		return nil, fmt.Errorf("cell %s not found in store", cellID)
	}

	return b.InstantiateCell(cell.Handler)
}

// InstantiateCellVersion loads a specific version of a cell and evaluates it.
func (b *Bridge) InstantiateCellVersion(cellID string, version int) (*EvalResult, error) {
	cell, err := b.store.GetCell(cellID, version)
	if err != nil {
		return nil, fmt.Errorf("load cell %s v%d: %w", cellID, version, err)
	}
	if cell == nil {
		return nil, fmt.Errorf("cell %s v%d not found in store", cellID, version)
	}

	return b.InstantiateCell(cell.Handler)
}

// TestCase defines a test to run against a cell.
type TestCase struct {
	Input    string // EDN map, e.g. "{:x 1 :y 2}"
	Expected string // EDN map, e.g. "{:sum 3}"
}

// TestOutcome is the result of running a single test case.
type TestOutcome struct {
	Passed   bool
	Actual   string // EDN of actual output
	Error    string // exception message if any
	Input    string
	Expected string
}

// TestCell runs test cases against a registered cell by invoking its handler.
// The cell must already be instantiated in the REPL via InstantiateCell.
func (b *Bridge) TestCell(cellID string, resources string, tests []TestCase) ([]TestOutcome, error) {
	if resources == "" {
		resources = "{}"
	}

	outcomes := make([]TestOutcome, len(tests))
	for i, tc := range tests {
		// Invoke the cell's handler directly via its spec
		code := fmt.Sprintf(
			`(try
			   (let [handler (:handler (cell/get-cell! %s))
			         result  (handler %s %s)]
			     {:passed true :actual (pr-str result)})
			   (catch Exception e
			     {:passed false :error (.getMessage e)}))`,
			cellID, resources, tc.Input)

		result, err := b.Eval(code)
		if err != nil {
			outcomes[i] = TestOutcome{
				Input:    tc.Input,
				Expected: tc.Expected,
				Error:    fmt.Sprintf("eval error: %v", err),
			}
			continue
		}

		if result.IsError() {
			outcomes[i] = TestOutcome{
				Input:    tc.Input,
				Expected: tc.Expected,
				Error:    result.Ex + ": " + result.Err,
			}
			continue
		}

		// Parse the result map to check passed/actual/error
		outcomes[i] = parseTestOutcome(result.Value, tc)
	}

	return outcomes, nil
}

// parseTestOutcome extracts test results from the Clojure map returned by TestCell.
func parseTestOutcome(value string, tc TestCase) TestOutcome {
	outcome := TestOutcome{
		Input:    tc.Input,
		Expected: tc.Expected,
	}

	if strings.Contains(value, ":passed true") {
		outcome.Passed = true
		// Extract :actual value
		if idx := strings.Index(value, ":actual"); idx >= 0 {
			rest := value[idx+len(":actual"):]
			// The actual value is a pr-str'd string inside the map
			outcome.Actual = strings.TrimSpace(rest)
			outcome.Actual = strings.TrimSuffix(outcome.Actual, "}")
			outcome.Actual = strings.TrimSpace(outcome.Actual)
			// Remove surrounding quotes from pr-str
			outcome.Actual = unquoteClojure(outcome.Actual)
		}
	} else {
		outcome.Passed = false
		if idx := strings.Index(value, ":error"); idx >= 0 {
			rest := value[idx+len(":error"):]
			outcome.Error = strings.TrimSpace(rest)
			outcome.Error = strings.TrimSuffix(outcome.Error, "}")
			outcome.Error = strings.TrimSpace(outcome.Error)
			outcome.Error = unquoteClojure(outcome.Error)
		}
	}

	return outcome
}

// ValidateSchema validates data against a Malli schema in the REPL.
func (b *Bridge) ValidateSchema(schema, data string) (*EvalResult, error) {
	code := fmt.Sprintf(
		`(require '[malli.core :as m])
		 (let [s %s
		       d %s]
		   (if (m/validate s d)
		     {:valid true}
		     {:valid false
		      :errors (m/explain s d)}))`,
		schema, data)
	return b.Eval(code)
}

// CompileWorkflow compiles a workflow manifest in the REPL.
func (b *Bridge) CompileWorkflow(manifestEDN string, opts string) (*EvalResult, error) {
	if opts == "" {
		opts = "{}"
	}
	code := fmt.Sprintf(
		`(require '[mycelium.core :as myc])
		 (myc/compile-workflow %s %s)`,
		manifestEDN, opts)
	return b.Eval(code)
}

// RunWorkflow compiles and runs a workflow with the given resources and input.
func (b *Bridge) RunWorkflow(manifestEDN, resources, input, opts string) (*EvalResult, error) {
	if resources == "" {
		resources = "{}"
	}
	if opts == "" {
		opts = "{}"
	}
	code := fmt.Sprintf(
		`(require '[mycelium.core :as myc])
		 (let [wf (myc/compile-workflow %s %s)]
		   (pr-str (myc/run-workflow wf %s %s)))`,
		manifestEDN, opts, resources, input)
	return b.Eval(code)
}

// ListRegisteredCells returns cell IDs currently registered in the REPL.
func (b *Bridge) ListRegisteredCells() (*EvalResult, error) {
	return b.Eval(`(pr-str (cell/list-cells))`)
}

// unquoteClojure removes surrounding quotes and unescapes a Clojure pr-str'd string.
func unquoteClojure(s string) string {
	if len(s) >= 2 && s[0] == '"' && s[len(s)-1] == '"' {
		s = s[1 : len(s)-1]
		s = strings.ReplaceAll(s, `\"`, `"`)
		s = strings.ReplaceAll(s, `\\`, `\`)
	}
	return s
}

// Connected returns true if the bridge has an active connection.
func (b *Bridge) Connected() bool {
	_, err := b.client.EvalCollect("1", repl.WithSession(b.session))
	return err == nil
}
