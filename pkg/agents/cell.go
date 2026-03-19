package agents

import (
	"context"
	"fmt"
	"strings"
	"sync"

	"github.com/mycelium-clj/sporulator/pkg/bridge"
	"github.com/mycelium-clj/sporulator/pkg/llm"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// CellBrief describes what a cell should do — enough for an LLM to implement it.
type CellBrief struct {
	ID       string   // e.g. ":order/compute-tax"
	Doc      string   // purpose description
	Schema   string   // EDN schema string, e.g. "{:input {...} :output {...}}"
	Requires []string // resource dependencies, e.g. ["db", "cache"]
	Context  string   // additional context (DB schema, helper functions, etc.)
}

// CellResult is the outcome of a cell implementation attempt.
type CellResult struct {
	CellID  string
	Code    string // extracted defcell form
	Raw     string // full LLM response
	Version int    // version number if saved to store
	Error   error
}

// FeedbackEvent is emitted during the REPL feedback loop to report progress.
type FeedbackEvent struct {
	Type    string // "eval", "error", "fix", "success"
	Attempt int    // 1-based attempt number
	Code    string // code that was evaluated
	Output  string // REPL output (value, out, or error)
	Message string // human-readable description
}

// CellAgent manages a short-lived conversation for implementing a single cell.
type CellAgent struct {
	session        *llm.Session
	client         *llm.Client
	store          *store.Store
	bridgeProvider BridgeProvider // returns current bridge, nil-safe
}

// Implement generates a cell implementation from a brief.
func (c *CellAgent) Implement(ctx context.Context, brief CellBrief) (*CellResult, error) {
	prompt := buildCellPrompt(brief)
	response, err := c.session.Send(ctx, c.client, prompt)
	if err != nil {
		return nil, fmt.Errorf("cell agent implement: %w", err)
	}
	return c.buildResult(brief.ID, response), nil
}

// ImplementStream generates a cell implementation, streaming tokens.
func (c *CellAgent) ImplementStream(ctx context.Context, brief CellBrief, onChunk func(string)) (*CellResult, error) {
	prompt := buildCellPrompt(brief)
	response, err := c.session.SendStream(ctx, c.client, prompt, onChunk)
	if err != nil {
		return nil, fmt.Errorf("cell agent implement stream: %w", err)
	}
	return c.buildResult(brief.ID, response), nil
}

// ImplementWithFeedback generates a cell, evaluates it in the REPL, and iterates
// on errors automatically. onChunk streams LLM tokens, onFeedback reports REPL results.
// Makes up to maxAttempts tries (1 initial + fixes). Returns the final result.
func (c *CellAgent) ImplementWithFeedback(ctx context.Context, brief CellBrief, maxAttempts int, onChunk func(string), onFeedback func(FeedbackEvent)) (*CellResult, error) {
	br := c.getBridge()
	if br == nil {
		return c.ImplementStream(ctx, brief, onChunk)
	}
	if maxAttempts < 1 {
		maxAttempts = 3
	}

	// Initial implementation
	result, err := c.ImplementStream(ctx, brief, onChunk)
	if err != nil {
		return nil, err
	}

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		code := result.Code
		if code == "" {
			onFeedback(FeedbackEvent{
				Type:    "error",
				Attempt: attempt,
				Message: "No defcell form found in LLM response",
			})
			break
		}

		// Eval in REPL
		onFeedback(FeedbackEvent{
			Type:    "eval",
			Attempt: attempt,
			Code:    code,
			Message: fmt.Sprintf("Evaluating cell (attempt %d/%d)", attempt, maxAttempts),
		})

		evalResult, evalErr := br.InstantiateCell(code)
		if evalErr != nil {
			onFeedback(FeedbackEvent{
				Type:    "error",
				Attempt: attempt,
				Output:  evalErr.Error(),
				Message: "REPL connection error",
			})
			break // can't fix connection errors
		}

		if !evalResult.IsError() {
			// Success — cell loaded cleanly
			onFeedback(FeedbackEvent{
				Type:    "success",
				Attempt: attempt,
				Code:    code,
				Output:  evalResult.Value,
				Message: "Cell loaded successfully",
			})
			return result, nil
		}

		// Error — feed it back to the LLM for a fix
		errMsg := evalResult.Ex
		if evalResult.Err != "" {
			errMsg += "\n" + evalResult.Err
		}
		onFeedback(FeedbackEvent{
			Type:    "error",
			Attempt: attempt,
			Code:    code,
			Output:  errMsg,
			Message: "REPL error, requesting fix from LLM",
		})

		if attempt >= maxAttempts {
			break // no more retries
		}

		// Ask the LLM to fix
		feedback := fmt.Sprintf("The code produced this error when evaluated in the REPL:\n\n```\n%s\n```\n\nPlease fix the issue and return the corrected `cell/defcell` form.", errMsg)
		onFeedback(FeedbackEvent{
			Type:    "fix",
			Attempt: attempt + 1,
			Message: "Requesting fix from LLM",
		})

		result, err = c.IterateStream(ctx, feedback, onChunk)
		if err != nil {
			return nil, fmt.Errorf("cell agent fix attempt %d: %w", attempt+1, err)
		}
	}

	return result, nil
}

// Iterate sends feedback and gets an updated implementation.
func (c *CellAgent) Iterate(ctx context.Context, feedback string) (*CellResult, error) {
	response, err := c.session.Send(ctx, c.client, feedback)
	if err != nil {
		return nil, fmt.Errorf("cell agent iterate: %w", err)
	}
	return c.buildResult(c.session.ID, response), nil
}

// IterateStream sends feedback and streams the updated implementation.
func (c *CellAgent) IterateStream(ctx context.Context, feedback string, onChunk func(string)) (*CellResult, error) {
	response, err := c.session.SendStream(ctx, c.client, feedback, onChunk)
	if err != nil {
		return nil, fmt.Errorf("cell agent iterate stream: %w", err)
	}
	return c.buildResult(c.session.ID, response), nil
}

// Save persists the cell result to the store. Returns the version number.
func (c *CellAgent) Save(result *CellResult, schema, requires, doc, createdBy string) (int, error) {
	version, err := c.store.SaveCell(&store.Cell{
		ID:        result.CellID,
		Handler:   result.Code,
		Schema:    schema,
		Requires:  requires,
		Doc:       doc,
		CreatedBy: createdBy,
	})
	if err != nil {
		return 0, fmt.Errorf("save cell: %w", err)
	}
	result.Version = version
	return version, nil
}

// History returns the conversation history.
func (c *CellAgent) History() []llm.Message {
	return c.session.History()
}

// getBridge returns the current bridge, or nil if no provider or no bridge.
func (c *CellAgent) getBridge() *bridge.Bridge {
	if c.bridgeProvider == nil {
		return nil
	}
	return c.bridgeProvider()
}

func (c *CellAgent) buildResult(cellID, response string) *CellResult {
	return &CellResult{
		CellID: cellID,
		Code:   ExtractDefcell(response),
		Raw:    response,
	}
}

func buildCellPrompt(brief CellBrief) string {
	var b strings.Builder
	b.WriteString("Implement the following Mycelium cell.\n\n")

	if brief.ID != "" {
		fmt.Fprintf(&b, "**Cell ID:** `%s`\n", brief.ID)
	}
	if brief.Doc != "" {
		fmt.Fprintf(&b, "\n**Implementation Requirements:**\n%s\n", brief.Doc)
	}
	if brief.Schema != "" {
		fmt.Fprintf(&b, "\n**Contract (input/output schema):**\n```\n%s\n```\n", brief.Schema)
	}
	if len(brief.Requires) > 0 {
		resources := make([]string, len(brief.Requires))
		for i, r := range brief.Requires {
			resources[i] = "`" + r + "`"
		}
		fmt.Fprintf(&b, "\n**Required resources:** %s\n", strings.Join(resources, ", "))
	} else {
		b.WriteString("\n**Required resources:** none\n")
	}
	if brief.Context != "" {
		fmt.Fprintf(&b, "\n**Workflow position (predecessors/successors):**\n%s\n", brief.Context)
	}

	b.WriteString("\nReturn the complete `cell/defcell` form.")
	return b.String()
}

// ImplementCells runs multiple cell agents in parallel.
func ImplementCells(ctx context.Context, mgr *Manager, briefs []CellBrief) []CellResult {
	results := make([]CellResult, len(briefs))
	var wg sync.WaitGroup

	for i, brief := range briefs {
		wg.Add(1)
		go func(idx int, b CellBrief) {
			defer wg.Done()
			agent := mgr.NewCellAgent(b.ID)
			result, err := agent.Implement(ctx, b)
			if err != nil {
				results[idx] = CellResult{CellID: b.ID, Error: err}
				return
			}
			results[idx] = *result
		}(i, brief)
	}

	wg.Wait()
	return results
}
