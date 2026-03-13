package agents

import (
	"context"
	"fmt"
	"strings"
	"sync"

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

// CellAgent manages a short-lived conversation for implementing a single cell.
type CellAgent struct {
	session *llm.Session
	client  *llm.Client
	store   *store.Store
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
		fmt.Fprintf(&b, "**Purpose:** %s\n", brief.Doc)
	}
	if brief.Schema != "" {
		fmt.Fprintf(&b, "\n**Contract:**\n")
		fmt.Fprintf(&b, "- Schema: `%s`\n", brief.Schema)
	}
	if len(brief.Requires) > 0 {
		resources := make([]string, len(brief.Requires))
		for i, r := range brief.Requires {
			resources[i] = "`" + r + "`"
		}
		fmt.Fprintf(&b, "**Required resources:** %s\n", strings.Join(resources, ", "))
	} else {
		b.WriteString("**Required resources:** none\n")
	}
	if brief.Context != "" {
		fmt.Fprintf(&b, "\n**Additional context:**\n%s\n", brief.Context)
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
