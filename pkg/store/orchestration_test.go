package store_test

import (
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/store"
)

func TestOrchestrationRunCRUD(t *testing.T) {
	s := openTestStore(t)

	// Create
	err := s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID:         "run-1",
		SpecHash:   "abc123",
		ManifestID: ":order/placement",
		Status:     "running",
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}

	// Get
	run, err := s.GetOrchestrationRun("run-1")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if run == nil || run.Status != "running" {
		t.Errorf("unexpected: %+v", run)
	}

	// Update status only
	err = s.UpdateRunStatus("run-1", "completed")
	if err != nil {
		t.Fatalf("update status: %v", err)
	}

	run, _ = s.GetOrchestrationRun("run-1")
	if run.Status != "completed" {
		t.Errorf("status: %q", run.Status)
	}

	// Update tree
	err = s.UpdateRunTree("run-1", "running", `{"StepName":"root"}`)
	if err != nil {
		t.Fatalf("update tree: %v", err)
	}

	run, _ = s.GetOrchestrationRun("run-1")
	if run.Status != "running" {
		t.Errorf("status after tree update: %q", run.Status)
	}
	if run.TreeJSON != `{"StepName":"root"}` {
		t.Errorf("tree_json: %q", run.TreeJSON)
	}

	// Verify UpdateRunStatus doesn't clobber tree_json
	err = s.UpdateRunStatus("run-1", "completed")
	if err != nil {
		t.Fatalf("update status again: %v", err)
	}
	run, _ = s.GetOrchestrationRun("run-1")
	if run.TreeJSON != `{"StepName":"root"}` {
		t.Errorf("tree_json should be preserved after status update, got: %q", run.TreeJSON)
	}
}

func TestGetLatestRunForManifest(t *testing.T) {
	s := openTestStore(t)

	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-1", SpecHash: "abc", ManifestID: ":order/placement", Status: "failed",
	})
	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-2", SpecHash: "def", ManifestID: ":order/placement", Status: "running",
	})

	run, err := s.GetLatestRunForManifest(":order/placement")
	if err != nil {
		t.Fatalf("get latest: %v", err)
	}
	if run.ID != "run-2" {
		t.Errorf("expected run-2, got %s", run.ID)
	}
}

func TestGetLatestRunForManifestNotFound(t *testing.T) {
	s := openTestStore(t)
	run, err := s.GetLatestRunForManifest(":nope")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if run != nil {
		t.Error("expected nil")
	}
}

func TestCellAttempts(t *testing.T) {
	s := openTestStore(t)

	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-1", SpecHash: "abc", ManifestID: ":wf", Status: "running",
	})

	// Save attempts
	s.SaveCellAttempt(&store.CellAttempt{
		RunID: "run-1", CellID: ":order/validate", AttemptType: "test",
		AttemptNumber: 1, Code: "(defcell ...)", Output: "1 failure", Passed: false,
	})
	s.SaveCellAttempt(&store.CellAttempt{
		RunID: "run-1", CellID: ":order/validate", AttemptType: "test",
		AttemptNumber: 2, Code: "(defcell ... fixed)", Output: "0 failures", Passed: true,
	})

	// Get attempts
	attempts, err := s.GetCellAttempts("run-1", ":order/validate")
	if err != nil {
		t.Fatalf("get attempts: %v", err)
	}
	if len(attempts) != 2 {
		t.Fatalf("expected 2, got %d", len(attempts))
	}
	if attempts[0].AttemptNumber != 1 || attempts[0].Passed {
		t.Errorf("attempt 0: %+v", attempts[0])
	}
	if attempts[1].AttemptNumber != 2 || !attempts[1].Passed {
		t.Errorf("attempt 1: %+v", attempts[1])
	}
}

func TestGetRunSummary(t *testing.T) {
	s := openTestStore(t)

	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-1", SpecHash: "abc", ManifestID: ":wf", Status: "running",
	})

	s.SaveCellAttempt(&store.CellAttempt{
		RunID: "run-1", CellID: ":order/a", AttemptType: "test", AttemptNumber: 1, Passed: false,
	})
	s.SaveCellAttempt(&store.CellAttempt{
		RunID: "run-1", CellID: ":order/a", AttemptType: "test", AttemptNumber: 2, Passed: true,
	})
	s.SaveCellAttempt(&store.CellAttempt{
		RunID: "run-1", CellID: ":order/b", AttemptType: "test", AttemptNumber: 1, Passed: false,
	})

	summary, err := s.GetRunSummary("run-1")
	if err != nil {
		t.Fatalf("summary: %v", err)
	}
	if !summary[":order/a"] {
		t.Error(":order/a should be passing")
	}
	if summary[":order/b"] {
		t.Error(":order/b should be failing")
	}
}
