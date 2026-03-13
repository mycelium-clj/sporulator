package bridge

import (
	"context"
	"fmt"
	"log"

	"github.com/mycelium-clj/sporulator/pkg/store"
)

// TestAndRecord runs test cases against a cell and saves results to the store.
// The cell must already be instantiated in the REPL.
func (b *Bridge) TestAndRecord(cellID string, cellVersion int, resources string, tests []TestCase) ([]TestOutcome, error) {
	outcomes, err := b.TestCell(cellID, resources, tests)
	if err != nil {
		return nil, err
	}

	for _, outcome := range outcomes {
		if err := b.store.SaveTestResult(&store.TestResult{
			CellID:      cellID,
			CellVersion: cellVersion,
			Input:       outcome.Input,
			Expected:    outcome.Expected,
			Actual:      outcome.Actual,
			Passed:      outcome.Passed,
			Error:       outcome.Error,
		}); err != nil {
			log.Printf("warning: failed to save test result for %s: %v", cellID, err)
		}
	}

	return outcomes, nil
}

// ImplementAndTest is a convenience that instantiates a cell from the store,
// runs tests, and records results. Returns the test outcomes.
func (b *Bridge) ImplementAndTest(cellID string, version int, resources string, tests []TestCase) ([]TestOutcome, error) {
	_, err := b.InstantiateCellVersion(cellID, version)
	if err != nil {
		return nil, fmt.Errorf("instantiate: %w", err)
	}

	return b.TestAndRecord(cellID, version, resources, tests)
}

// CellStatus summarizes a cell's implementation state in the REPL.
type CellStatus struct {
	CellID      string
	Version     int
	Instantiated bool
	TestsPassed  int
	TestsFailed  int
	TestsTotal   int
}

// GetCellStatus checks if a cell is registered in the REPL and summarizes
// its test results from the store.
func (b *Bridge) GetCellStatus(ctx context.Context, cellID string) (*CellStatus, error) {
	status := &CellStatus{CellID: cellID}

	// Get latest version from store
	cell, err := b.store.GetLatestCell(cellID)
	if err != nil {
		return nil, err
	}
	if cell != nil {
		status.Version = cell.Version
	}

	// Check if registered in REPL
	result, err := b.Eval(fmt.Sprintf(`(some? (cell/get-cell %s))`, cellID))
	if err == nil && result.Value == "true" {
		status.Instantiated = true
	}

	// Get test results
	if cell != nil {
		results, err := b.store.GetTestResults(cellID, cell.Version)
		if err == nil {
			status.TestsTotal = len(results)
			for _, r := range results {
				if r.Passed {
					status.TestsPassed++
				} else {
					status.TestsFailed++
				}
			}
		}
	}

	return status, nil
}
