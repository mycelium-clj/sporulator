// Package bridge connects the sporulator to a live Clojure system via nREPL.
// It can instantiate cells, run tests, validate schemas, and compile workflows.
package bridge

import (
	"fmt"
	"strings"
	"time"

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

	// Load sporulator codegen helpers
	b.loadCodegenHelpers()

	return b, nil
}

// loadCodegenHelpers loads the sporulator.codegen namespace from the embedded resource.
// Falls back silently if the file is not available (e.g., during tests).
func (b *Bridge) loadCodegenHelpers() {
	code := `
(try
  (load-file "resources/clj/sporulator/codegen.clj")
  (require '[sporulator.codegen])
  "ok"
  (catch Exception e (str "codegen not loaded: " (.getMessage e))))
`
	b.Eval(code)
}

// AssembleCellSource generates a cell source file using the Clojure codegen helper.
// Returns the source string or an error.
func (b *Bridge) AssembleCellSource(cellNs, cellID, doc, schemaEDN string,
	requires []string, extraReqs []string, helpers, fnBody string) (string, error) {

	reqsVec := "[]"
	if len(requires) > 0 {
		parts := make([]string, len(requires))
		for i, r := range requires {
			parts[i] = `"` + r + `"`
		}
		reqsVec = "[" + strings.Join(parts, " ") + "]"
	}
	extraReqsVec := "[]"
	if len(extraReqs) > 0 {
		extraReqsVec = "[" + strings.Join(extraReqs, " ") + "]"
	}

	code := fmt.Sprintf(`
(sporulator.codegen/assemble-cell-source
  {:cell-ns %s
   :cell-id %s
   :doc %s
   :schema-map (sporulator.codegen/parse-schema %s)
   :requires %s
   :extra-requires %s
   :helpers %s
   :fn-body %s})`,
		quoteClojure(cellNs), cellID, quoteClojure(doc), quoteClojure(schemaEDN),
		reqsVec, extraReqsVec, quoteClojure(helpers), quoteClojure(fnBody))

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("assemble cell source: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("assemble cell source error: %s %s", result.Ex, result.Err)
	}
	return unquoteClojure(result.Value), nil
}

// AssembleStubCellSource generates a minimal stub cell source for schema validation.
func (b *Bridge) AssembleStubCellSource(cellNs, cellID, doc, schemaEDN string) (string, error) {
	code := fmt.Sprintf(`
(sporulator.codegen/assemble-stub-cell-source
  {:cell-ns %s
   :cell-id %s
   :doc %s
   :schema-map (sporulator.codegen/parse-schema %s)})`,
		quoteClojure(cellNs), cellID, quoteClojure(doc), quoteClojure(schemaEDN))

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("assemble stub source: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("assemble stub source error: %s %s", result.Ex, result.Err)
	}
	return unquoteClojure(result.Value), nil
}

// AssembleTestSource generates a test namespace source using codegen.
func (b *Bridge) AssembleTestSource(testNs, cellNs, cellID, testBody string) (string, error) {
	code := fmt.Sprintf(`
(sporulator.codegen/assemble-test-source
  {:test-ns %s :cell-ns %s :cell-id %s :test-body %s})`,
		quoteClojure(testNs), quoteClojure(cellNs), cellID, quoteClojure(testBody))

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("assemble test source: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("assemble test source error: %s %s", result.Ex, result.Err)
	}
	return unquoteClojure(result.Value), nil
}

// AssembleManifest generates a manifest EDN string using codegen.
// steps is serialized as a Clojure vector of maps via the bridge.
func (b *Bridge) AssembleManifest(id, nsPrefix string, stepsJSON string, edges, dispatches map[string]string) (string, error) {
	// Build edges and dispatches as Clojure map literals
	var edgesStr strings.Builder
	edgesStr.WriteString("{")
	for k, v := range edges {
		fmt.Fprintf(&edgesStr, "%s %s ", quoteClojure(k), quoteClojure(v))
	}
	edgesStr.WriteString("}")

	var dispStr strings.Builder
	dispStr.WriteString("{")
	for k, v := range dispatches {
		fmt.Fprintf(&dispStr, "%s %s ", quoteClojure(k), quoteClojure(v))
	}
	dispStr.WriteString("}")

	code := fmt.Sprintf(`
(let [steps-data (read-string %s)
      edges-raw (read-string %s)
      dispatches-raw (read-string %s)]
  (sporulator.codegen/assemble-manifest
    {:id %s :ns-prefix %s
     :steps (mapv (fn [s]
                    {:name (:name s)
                     :doc (:doc s)
                     :input-schema (mycelium.schema/normalize-schema (:input-schema s))
                     :output-schema (mycelium.schema/normalize-schema (:output-schema s))
                     :requires (:requires s)})
                  steps-data)
     :edges edges-raw
     :dispatches dispatches-raw}))`,
		quoteClojure(stepsJSON),
		quoteClojure(edgesStr.String()),
		quoteClojure(dispStr.String()),
		id, quoteClojure(nsPrefix))

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("assemble manifest: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("assemble manifest error: %s %s", result.Ex, result.Err)
	}
	return unquoteClojure(result.Value), nil
}

// AssembleCellFromLLMOutput takes raw LLM output (fn body + helpers + require comments),
// extracts the parts using Clojure, assembles a complete cell source, and returns it.
// The entire pipeline runs in the REPL — no Go-side code parsing.
func (b *Bridge) AssembleCellFromLLMOutput(cellNs, cellID, doc, schemaEDN string,
	requires []string, rawLLMCode string) (string, error) {

	code := fmt.Sprintf(`
(sporulator.codegen/llm-response->cell-source
  {:cell-ns %s :cell-id %s :doc %s
   :schema-edn %s :requires %s :raw-code %s})`,
		quoteClojure(cellNs), cellID, quoteClojure(doc),
		quoteClojure(schemaEDN), clojureVec(requires), quoteClojure(rawLLMCode))

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("assemble cell from LLM: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("assemble cell error: %s %s", result.Ex, result.Err)
	}
	return unquoteClojure(result.Value), nil
}

// AssembleTestFromLLMOutput takes raw LLM test response and assembles a test namespace.
func (b *Bridge) AssembleTestFromLLMOutput(testNs, cellNs, cellID, rawResponse string) (string, error) {
	code := fmt.Sprintf(`
(sporulator.codegen/llm-response->test-source
  {:test-ns %s :cell-ns %s :cell-id %s :raw-response %s})`,
		quoteClojure(testNs), quoteClojure(cellNs), cellID, quoteClojure(rawResponse))

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("assemble test from LLM: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("assemble test error: %s %s", result.Ex, result.Err)
	}
	return unquoteClojure(result.Value), nil
}

// LoadAndCheckCell loads a cell source file and verifies it registered correctly.
// Returns the pr-str'd result map: {:ok true :input-keys #{} :output-keys #{}} or {:error "..."}.
func (b *Bridge) LoadAndCheckCell(cellNs, cellID, sourceFile string) (bool, string, error) {
	code := fmt.Sprintf(`
(pr-str (sporulator.codegen/load-and-check-cell %s %s %s))`,
		quoteClojure(cellNs), quoteClojure(cellID), quoteClojure(sourceFile))

	result, err := b.Eval(code)
	if err != nil {
		return false, "", err
	}
	val := unquoteClojure(result.Value)
	ok := strings.Contains(val, ":ok true")
	return ok, val, nil
}

// RunCellTests loads source + test files, runs tests, returns structured results.
// Returns ok=true if all tests pass, plus the full output string.
func (b *Bridge) RunCellTests(cellNs, testNs, sourceFile, testFile string) (bool, string, error) {
	code := fmt.Sprintf(`
(pr-str (sporulator.codegen/run-cell-tests %s %s %s %s))`,
		quoteClojure(cellNs), quoteClojure(testNs),
		quoteClojure(sourceFile), quoteClojure(testFile))

	result, err := b.Eval(code)
	if err != nil {
		return false, "", err
	}
	val := unquoteClojure(result.Value)
	passed := strings.Contains(val, ":passed? true")
	// Extract :output from the result for display
	output := val
	if result.Out != "" {
		output = result.Out
	}
	return passed, output, nil
}

// RunWorkflowWithTrace compiles and runs a workflow, returning the result with trace.
// Returns the pr-str'd result map from codegen/run-workflow-with-trace.
func (b *Bridge) RunWorkflowWithTrace(manifestEDN, resourcesEDN, inputEDN string) (string, error) {
	code := fmt.Sprintf(`
(pr-str (sporulator.codegen/run-workflow-with-trace
  %s %s %s))`,
		manifestEDN, resourcesEDN, inputEDN)

	result, err := b.Eval(code)
	if err != nil {
		return "", fmt.Errorf("run workflow with trace: %w", err)
	}
	if result.IsError() {
		return "", fmt.Errorf("run workflow error: %s %s", result.Ex, result.Err)
	}
	val := result.Value
	if result.Out != "" {
		val = result.Out + "\n" + val
	}
	return unquoteClojure(val), nil
}

// FormatTraceForLLM formats a workflow trace for inclusion in an LLM prompt.
func (b *Bridge) FormatTraceForLLM(traceEDN string) (string, error) {
	code := fmt.Sprintf(`
(sporulator.codegen/format-trace-for-llm (read-string %s))`,
		quoteClojure(traceEDN))

	result, err := b.Eval(code)
	if err != nil {
		return "", err
	}
	return unquoteClojure(result.Value), nil
}

// clojureVec converts a Go string slice to a Clojure vector string: ["a" "b" "c"]
func clojureVec(items []string) string {
	if len(items) == 0 {
		return "[]"
	}
	parts := make([]string, len(items))
	for i, s := range items {
		parts[i] = `"` + s + `"`
	}
	return "[" + strings.Join(parts, " ") + "]"
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

// CloneSession creates a new nREPL session cloned from the bridge's main session.
// The new session inherits loaded requires (e.g. mycelium.cell).
// Caller must call CloseSession when done.
func (b *Bridge) CloneSession() (string, error) {
	session, err := b.client.Clone(b.session)
	if err != nil {
		return "", fmt.Errorf("bridge clone session: %w", err)
	}
	return session, nil
}

// CloseSession closes a previously cloned session.
func (b *Bridge) CloseSession(session string) {
	b.client.CloseSession(session)
}

// EvalInSession evaluates code in a specific nREPL session.
func (b *Bridge) EvalInSession(session, code string) (*EvalResult, error) {
	result, err := b.client.EvalCollect(code, repl.WithSession(session))
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

// ValidateManifestEDN checks that a manifest is valid EDN and has the expected structure.
// Does NOT require cells to be registered — use this during manifest design.
func (b *Bridge) ValidateManifestEDN(manifestEDN string) (*EvalResult, error) {
	code := fmt.Sprintf(
		`(let [m %s]
		   (cond
		     (not (map? m))
		       (throw (ex-info "Manifest must be a map" {}))
		     (not (:id m))
		       (throw (ex-info "Manifest must have :id" {}))
		     (not (:cells m))
		       (throw (ex-info "Manifest must have :cells" {}))
		     (not (or (:pipeline m) (:edges m)))
		       (throw (ex-info "Manifest must have :pipeline or :edges" {}))
		     (not (:start (:cells m)))
		       (throw (ex-info "First cell must have key :start" {}))
		     (and (:edges m) (not (:dispatches m)))
		       (throw (ex-info "Manifest with :edges must also have :dispatches" {}))
		     :else
		       (pr-str {:id (:id m) :cell-count (count (:cells m))})))`,
		manifestEDN)
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

// CellContract describes the expected contract for a cell after implementation.
type CellContract struct {
	CellID       string // expected cell ID, e.g. ":order/validate"
	InputSchema  string // expected input schema EDN (optional, "" to skip check)
	OutputSchema string // expected output schema EDN (optional, "" to skip check)
	Doc          string // expected doc string (optional, "" to skip check)
}

// VerifyCellContract checks that a cell registered in the REPL matches the expected contract.
// Returns nil if the cell is valid, or a descriptive EvalResult with error details.
func (b *Bridge) VerifyCellContract(contract CellContract) (*EvalResult, error) {
	// Step 1: Check the cell exists with the expected ID
	code := fmt.Sprintf(
		`(let [cell-spec (cell/get-cell! %s)]
		   (pr-str {:id %s
		            :has-handler (some? (:handler cell-spec))
		            :doc (get cell-spec :doc "")
		            :schema (get cell-spec :schema {})}))`,
		contract.CellID, contract.CellID)

	result, err := b.Eval(code)
	if err != nil {
		return nil, fmt.Errorf("verify cell contract: %w", err)
	}
	if result.IsError() {
		return &EvalResult{
			Ex: fmt.Sprintf("Cell %s not found in registry", contract.CellID),
			Err: fmt.Sprintf("Expected cell %s to be registered after load-file. "+
				"Ensure your (cell/defcell ...) form uses exactly %s as the cell ID.\n"+
				"REPL error: %s", contract.CellID, contract.CellID, result.Ex),
		}, nil
	}

	return result, nil
}

// RegisterWorkflowCell registers a sub-workflow as a cell via mycelium.compose.
func (b *Bridge) RegisterWorkflowCell(cellID, manifestEDN, schemaEDN string) (*EvalResult, error) {
	code := fmt.Sprintf(
		`(require '[mycelium.compose :as compose])
		 (compose/register-workflow-cell! %s %s %s)`,
		cellID, manifestEDN, schemaEDN)
	return b.Eval(code)
}

// ListRegisteredCells returns cell IDs currently registered in the REPL.
func (b *Bridge) ListRegisteredCells() (*EvalResult, error) {
	return b.Eval(`(pr-str (cell/list-cells))`)
}

// ValidateMalliSchema checks a single schema string is valid Malli via the REPL.
// Uses mycelium.schema/normalize-schema to handle lite map syntax before validation.
// Returns nil on success, or a descriptive error string.
func (b *Bridge) ValidateMalliSchema(schemaEDN string) (string, error) {
	code := fmt.Sprintf(`
(require '[malli.core :as m] '[mycelium.schema :as schema])
(try
  (let [s (schema/normalize-schema (read-string %s))]
    (m/schema s)
    "ok")
  (catch Exception e
    (str "INVALID: " (.getMessage e))))`, quoteClojure(schemaEDN))
	result, err := b.Eval(code)
	if err != nil {
		return "", err
	}
	val := unquoteClojure(result.Value)
	if val == "ok" {
		return "", nil
	}
	return val, nil
}

// ValidateCellSchemas checks that every cell referenced in the manifest has a valid
// Malli schema. Returns a pr-str'd vector of error maps, or "[]" if all schemas are valid.
// Handles per-transition output schemas (map of transition → schema) from composed cells.
func (b *Bridge) ValidateCellSchemas(manifestEDN string) (*EvalResult, error) {
	code := fmt.Sprintf(`
(require '[malli.core :as m] '[mycelium.cell :as cell])
(letfn [(validate-schema [s]
          (try (m/schema s) nil
            (catch Exception e (.getMessage e))))]
  (let [manifest (read-string %s)
        cells    (:cells manifest)
        errors   (atom [])]
    (doseq [[cell-name cell-def] cells]
      (let [cell-id  (if (map? cell-def) (:id cell-def) cell-def)
            cell-spec (try (cell/get-cell! cell-id) (catch Exception e nil))]
        (when cell-spec
          (let [schema (:schema cell-spec)
                input  (:input schema)
                output (:output schema)]
            ;; Validate input schema
            (when input
              (when-let [err (validate-schema input)]
                (swap! errors conj
                       {:cell-name (str cell-name) :cell-id (str cell-id)
                        :phase ":input" :error err :schema (pr-str input)})))
            ;; Validate output schema — may be per-transition map {transition → schema}
            (when output
              (if (and (map? output)
                       (some vector? (vals output)))
                ;; Per-transition output: validate each transition's schema separately
                (doseq [[transition s] output]
                  (when-let [err (validate-schema s)]
                    (swap! errors conj
                           {:cell-name (str cell-name) :cell-id (str cell-id)
                            :phase (str ":output/" transition) :error err :schema (pr-str s)})))
                ;; Single output schema
                (when-let [err (validate-schema output)]
                  (swap! errors conj
                         {:cell-name (str cell-name) :cell-id (str cell-id)
                          :phase ":output" :error err :schema (pr-str output)}))))))))
    (pr-str @errors)))`, quoteClojure(manifestEDN))
	return b.Eval(code)
}

// SchemaChainCheck attempts to compile the workflow and returns structured error info
// on schema chain failures. Returns the compilation result — check IsError().
// On schema chain error, Err contains the detailed message with cell names, missing keys,
// and available keys.
func (b *Bridge) SchemaChainCheck(manifestEDN string) (*EvalResult, error) {
	code := fmt.Sprintf(`
(require '[mycelium.core :as myc])
(try
  (myc/compile-workflow %s {:coerce? true})
  (pr-str {:ok true})
  (catch Exception e
    (pr-str {:ok false
             :error (.getMessage e)
             :data  (ex-data e)})))`, manifestEDN)
	return b.Eval(code)
}

// quoteClojure wraps a string for embedding inside a Clojure (read-string ...) call.
func quoteClojure(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	return `"` + s + `"`
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
// Times out after 5 seconds to avoid blocking if the nREPL is hung.
func (b *Bridge) Connected() bool {
	done := make(chan bool, 1)
	go func() {
		_, err := b.client.EvalCollect("1", repl.WithSession(b.session))
		done <- (err == nil)
	}()
	select {
	case ok := <-done:
		return ok
	case <-time.After(5 * time.Second):
		return false
	}
}
