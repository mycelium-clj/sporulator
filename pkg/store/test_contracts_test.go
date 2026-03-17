package store_test

import (
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/store"
)

func TestTestContractCRUD(t *testing.T) {
	s := openTestStore(t)

	// Need a run first (foreign key)
	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-tc1", SpecHash: "abc", ManifestID: ":order/placement", Status: "running",
	})

	// Save a contract
	err := s.SaveTestContract(&store.TestContractRecord{
		RunID:       "run-tc1",
		CellID:      ":order/compute-tax",
		TestCode:    "(ns test-ns ...)",
		TestBody:    "(deftest test-tax ...)",
		ReviewNotes: "ALL TESTS VERIFIED",
		Status:      "pending",
		Revision:    0,
	})
	if err != nil {
		t.Fatalf("save: %v", err)
	}

	// Get it back
	tc, err := s.GetTestContract("run-tc1", ":order/compute-tax")
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if tc == nil {
		t.Fatal("expected non-nil contract")
	}
	if tc.Status != "pending" {
		t.Errorf("status: got %q, want pending", tc.Status)
	}
	if tc.TestBody != "(deftest test-tax ...)" {
		t.Errorf("test_body: got %q", tc.TestBody)
	}
	if tc.ReviewNotes != "ALL TESTS VERIFIED" {
		t.Errorf("review_notes: got %q", tc.ReviewNotes)
	}
	if tc.Revision != 0 {
		t.Errorf("revision: got %d, want 0", tc.Revision)
	}

	// Upsert with updated content
	err = s.SaveTestContract(&store.TestContractRecord{
		RunID:       "run-tc1",
		CellID:      ":order/compute-tax",
		TestCode:    "(ns test-ns-v2 ...)",
		TestBody:    "(deftest test-tax-v2 ...)",
		ReviewNotes: "Fixed arithmetic",
		Status:      "pending",
		Revision:    1,
		Feedback:    "tax rate was wrong",
	})
	if err != nil {
		t.Fatalf("upsert: %v", err)
	}

	tc, _ = s.GetTestContract("run-tc1", ":order/compute-tax")
	if tc.Revision != 1 {
		t.Errorf("revision after upsert: got %d, want 1", tc.Revision)
	}
	if tc.TestBody != "(deftest test-tax-v2 ...)" {
		t.Errorf("test_body after upsert: got %q", tc.TestBody)
	}
	if tc.Feedback != "tax rate was wrong" {
		t.Errorf("feedback: got %q", tc.Feedback)
	}
}

func TestUpdateTestContractStatus(t *testing.T) {
	s := openTestStore(t)

	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-tc2", SpecHash: "abc", ManifestID: ":wf", Status: "running",
	})
	s.SaveTestContract(&store.TestContractRecord{
		RunID:  "run-tc2",
		CellID: ":order/validate",
		Status: "pending",
	})

	// Approve
	err := s.UpdateTestContractStatus("run-tc2", ":order/validate", "approved")
	if err != nil {
		t.Fatalf("update status: %v", err)
	}

	tc, _ := s.GetTestContract("run-tc2", ":order/validate")
	if tc.Status != "approved" {
		t.Errorf("status: got %q, want approved", tc.Status)
	}
	if tc.ApprovedAt == "" {
		t.Error("approved_at should be set")
	}
}

func TestGetApprovedTestContracts(t *testing.T) {
	s := openTestStore(t)

	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-tc3", SpecHash: "abc", ManifestID: ":wf", Status: "running",
	})

	s.SaveTestContract(&store.TestContractRecord{
		RunID: "run-tc3", CellID: ":order/a", Status: "approved",
		TestCode: "test-a", TestBody: "body-a",
	})
	s.SaveTestContract(&store.TestContractRecord{
		RunID: "run-tc3", CellID: ":order/b", Status: "pending",
		TestCode: "test-b", TestBody: "body-b",
	})
	s.SaveTestContract(&store.TestContractRecord{
		RunID: "run-tc3", CellID: ":order/c", Status: "approved",
		TestCode: "test-c", TestBody: "body-c",
	})
	s.SaveTestContract(&store.TestContractRecord{
		RunID: "run-tc3", CellID: ":order/d", Status: "skipped",
	})

	approved, err := s.GetApprovedTestContracts("run-tc3")
	if err != nil {
		t.Fatalf("get approved: %v", err)
	}
	if len(approved) != 2 {
		t.Fatalf("expected 2 approved, got %d", len(approved))
	}
	if approved[0].CellID != ":order/a" {
		t.Errorf("first: got %s", approved[0].CellID)
	}
	if approved[1].CellID != ":order/c" {
		t.Errorf("second: got %s", approved[1].CellID)
	}
}

func TestGetTestContractNotFound(t *testing.T) {
	s := openTestStore(t)

	tc, err := s.GetTestContract("no-run", ":no/cell")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if tc != nil {
		t.Error("expected nil")
	}
}

func TestGetTestContracts(t *testing.T) {
	s := openTestStore(t)

	s.CreateOrchestrationRun(&store.OrchestrationRun{
		ID: "run-tc4", SpecHash: "abc", ManifestID: ":wf", Status: "running",
	})

	s.SaveTestContract(&store.TestContractRecord{
		RunID: "run-tc4", CellID: ":order/x", Status: "pending",
	})
	s.SaveTestContract(&store.TestContractRecord{
		RunID: "run-tc4", CellID: ":order/y", Status: "approved",
	})

	all, err := s.GetTestContracts("run-tc4")
	if err != nil {
		t.Fatalf("get all: %v", err)
	}
	if len(all) != 2 {
		t.Fatalf("expected 2, got %d", len(all))
	}
}
