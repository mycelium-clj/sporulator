package store_test

import (
	"testing"

	"github.com/mycelium-clj/sporulator/pkg/store"
)

func openTestStore(t *testing.T) *store.Store {
	t.Helper()
	s, err := store.Open(":memory:")
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { s.Close() })
	return s
}

// --- Cell tests ---

func TestSaveCellAutoVersions(t *testing.T) {
	s := openTestStore(t)

	v1, err := s.SaveCell(&store.Cell{
		ID:      ":order/compute-tax",
		Handler: `(fn [_ data] {:tax (* (:subtotal data) 0.1)})`,
		Schema:  `{:input {:subtotal :double} :output {:tax :double}}`,
	})
	if err != nil {
		t.Fatalf("save v1: %v", err)
	}
	if v1 != 1 {
		t.Errorf("expected version 1, got %d", v1)
	}

	v2, err := s.SaveCell(&store.Cell{
		ID:      ":order/compute-tax",
		Handler: `(fn [_ data] {:tax (* (:subtotal data) (:tax-rate data))})`,
		Schema:  `{:input {:subtotal :double :tax-rate :double} :output {:tax :double}}`,
	})
	if err != nil {
		t.Fatalf("save v2: %v", err)
	}
	if v2 != 2 {
		t.Errorf("expected version 2, got %d", v2)
	}
}

func TestGetCell(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{
		ID:        ":math/double",
		Handler:   `(fn [_ data] {:result (* 2 (:x data))})`,
		Schema:    `{:input {:x :int} :output {:result :int}}`,
		Doc:       "Doubles the input",
		CreatedBy: "human",
	})

	cell, err := s.GetCell(":math/double", 1)
	if err != nil {
		t.Fatalf("get cell: %v", err)
	}
	if cell == nil {
		t.Fatal("cell not found")
	}
	if cell.Doc != "Doubles the input" {
		t.Errorf("doc: got %q", cell.Doc)
	}
	if cell.CreatedBy != "human" {
		t.Errorf("created_by: got %q", cell.CreatedBy)
	}
}

func TestGetCellNotFound(t *testing.T) {
	s := openTestStore(t)

	cell, err := s.GetCell(":nope/missing", 1)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cell != nil {
		t.Error("expected nil for missing cell")
	}
}

func TestGetLatestCell(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":app/step", Handler: "v1"})
	s.SaveCell(&store.Cell{ID: ":app/step", Handler: "v2"})
	s.SaveCell(&store.Cell{ID: ":app/step", Handler: "v3"})

	cell, err := s.GetLatestCell(":app/step")
	if err != nil {
		t.Fatalf("get latest: %v", err)
	}
	if cell.Version != 3 {
		t.Errorf("expected version 3, got %d", cell.Version)
	}
	if cell.Handler != "v3" {
		t.Errorf("expected handler v3, got %q", cell.Handler)
	}
}

func TestListCells(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":app/a", Handler: "a1", Doc: "Cell A"})
	s.SaveCell(&store.Cell{ID: ":app/a", Handler: "a2", Doc: "Cell A v2"})
	s.SaveCell(&store.Cell{ID: ":app/b", Handler: "b1", Doc: "Cell B"})

	cells, err := s.ListCells()
	if err != nil {
		t.Fatalf("list cells: %v", err)
	}
	if len(cells) != 2 {
		t.Fatalf("expected 2 cells, got %d", len(cells))
	}

	// Ordered by id
	if cells[0].ID != ":app/a" || cells[0].LatestVersion != 2 {
		t.Errorf("cell 0: got %s v%d", cells[0].ID, cells[0].LatestVersion)
	}
	if cells[0].Doc != "Cell A v2" {
		t.Errorf("cell 0 doc: got %q, want %q", cells[0].Doc, "Cell A v2")
	}
	if cells[1].ID != ":app/b" || cells[1].LatestVersion != 1 {
		t.Errorf("cell 1: got %s v%d", cells[1].ID, cells[1].LatestVersion)
	}
}

func TestGetCellHistory(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":app/x", Handler: "h1", CreatedBy: "human"})
	s.SaveCell(&store.Cell{ID: ":app/x", Handler: "h2", CreatedBy: "deepseek"})
	s.SaveCell(&store.Cell{ID: ":app/x", Handler: "h3", CreatedBy: "human"})

	history, err := s.GetCellHistory(":app/x")
	if err != nil {
		t.Fatalf("history: %v", err)
	}
	if len(history) != 3 {
		t.Fatalf("expected 3 versions, got %d", len(history))
	}
	// Newest first
	if history[0].Version != 3 || history[0].CreatedBy != "human" {
		t.Errorf("history[0]: v%d by %s", history[0].Version, history[0].CreatedBy)
	}
	if history[2].Version != 1 || history[2].CreatedBy != "human" {
		t.Errorf("history[2]: v%d by %s", history[2].Version, history[2].CreatedBy)
	}
}

// --- Manifest tests ---

func TestSaveAndGetManifest(t *testing.T) {
	s := openTestStore(t)

	body := `{:id :todo-app :cells {:start :todo/parse} :pipeline [:start]}`
	v, err := s.SaveManifest(&store.Manifest{
		ID:        ":todo-app",
		Body:      body,
		CreatedBy: "graph-agent",
	})
	if err != nil {
		t.Fatalf("save manifest: %v", err)
	}
	if v != 1 {
		t.Errorf("expected version 1, got %d", v)
	}

	m, err := s.GetManifest(":todo-app", 1)
	if err != nil {
		t.Fatalf("get manifest: %v", err)
	}
	if m.Body != body {
		t.Errorf("body mismatch")
	}
	if m.CreatedBy != "graph-agent" {
		t.Errorf("created_by: got %q", m.CreatedBy)
	}
}

func TestGetLatestManifest(t *testing.T) {
	s := openTestStore(t)

	s.SaveManifest(&store.Manifest{ID: ":app", Body: "v1"})
	s.SaveManifest(&store.Manifest{ID: ":app", Body: "v2"})

	m, err := s.GetLatestManifest(":app")
	if err != nil {
		t.Fatalf("get latest: %v", err)
	}
	if m.Version != 2 || m.Body != "v2" {
		t.Errorf("got v%d body=%q", m.Version, m.Body)
	}
}

func TestGetManifestNotFound(t *testing.T) {
	s := openTestStore(t)

	m, err := s.GetLatestManifest(":nope")
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if m != nil {
		t.Error("expected nil")
	}
}

func TestListManifests(t *testing.T) {
	s := openTestStore(t)

	s.SaveManifest(&store.Manifest{ID: ":app-a", Body: "a"})
	s.SaveManifest(&store.Manifest{ID: ":app-b", Body: "b1"})
	s.SaveManifest(&store.Manifest{ID: ":app-b", Body: "b2"})

	list, err := s.ListManifests()
	if err != nil {
		t.Fatalf("list: %v", err)
	}
	if len(list) != 2 {
		t.Fatalf("expected 2, got %d", len(list))
	}
	if list[0].ID != ":app-a" || list[0].LatestVersion != 1 {
		t.Errorf("manifest 0: %s v%d", list[0].ID, list[0].LatestVersion)
	}
	if list[1].ID != ":app-b" || list[1].LatestVersion != 2 {
		t.Errorf("manifest 1: %s v%d", list[1].ID, list[1].LatestVersion)
	}
}

// --- Manifest-Cell pinning tests ---

func TestPinCellVersion(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":app/a", Handler: "a1"})
	s.SaveCell(&store.Cell{ID: ":app/a", Handler: "a2"})
	s.SaveCell(&store.Cell{ID: ":app/b", Handler: "b1"})
	s.SaveManifest(&store.Manifest{ID: ":workflow", Body: "{}"})

	// Pin specific versions
	s.PinCellVersion(":workflow", 1, ":app/a", 2)
	s.PinCellVersion(":workflow", 1, ":app/b", 1)

	pins, err := s.GetPinnedCells(":workflow", 1)
	if err != nil {
		t.Fatalf("get pins: %v", err)
	}
	if len(pins) != 2 {
		t.Fatalf("expected 2 pins, got %d", len(pins))
	}
	if pins[0].CellID != ":app/a" || pins[0].CellVersion != 2 {
		t.Errorf("pin 0: %s v%d", pins[0].CellID, pins[0].CellVersion)
	}
	if pins[1].CellID != ":app/b" || pins[1].CellVersion != 1 {
		t.Errorf("pin 1: %s v%d", pins[1].CellID, pins[1].CellVersion)
	}
}

func TestPinCellVersionUpdate(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":app/a", Handler: "a1"})
	s.SaveCell(&store.Cell{ID: ":app/a", Handler: "a2"})
	s.SaveManifest(&store.Manifest{ID: ":wf", Body: "{}"})

	// Pin to v1, then update to v2
	s.PinCellVersion(":wf", 1, ":app/a", 1)
	s.PinCellVersion(":wf", 1, ":app/a", 2)

	pins, err := s.GetPinnedCells(":wf", 1)
	if err != nil {
		t.Fatalf("get pins: %v", err)
	}
	if len(pins) != 1 {
		t.Fatalf("expected 1 pin, got %d", len(pins))
	}
	if pins[0].CellVersion != 2 {
		t.Errorf("expected v2, got v%d", pins[0].CellVersion)
	}
}

// --- Test result tests ---

func TestSaveAndGetTestResults(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":math/add", Handler: "h"})

	err := s.SaveTestResult(&store.TestResult{
		CellID:      ":math/add",
		CellVersion: 1,
		Input:       "{:x 1 :y 2}",
		Expected:    "{:sum 3}",
		Actual:      "{:sum 3}",
		Passed:      true,
	})
	if err != nil {
		t.Fatalf("save result: %v", err)
	}

	err = s.SaveTestResult(&store.TestResult{
		CellID:      ":math/add",
		CellVersion: 1,
		Input:       "{:x 1 :y 2}",
		Expected:    "{:sum 3}",
		Actual:      "{:sum 4}",
		Passed:      false,
		Error:       "mismatch",
	})
	if err != nil {
		t.Fatalf("save result 2: %v", err)
	}

	results, err := s.GetTestResults(":math/add", 1)
	if err != nil {
		t.Fatalf("get results: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2 results, got %d", len(results))
	}
	// Newest first
	if results[0].Passed {
		t.Error("expected latest result to be failed")
	}
	if results[0].Error != "mismatch" {
		t.Errorf("error: got %q", results[0].Error)
	}
	if !results[1].Passed {
		t.Error("expected first result to be passed")
	}
}

func TestGetLatestTestResults(t *testing.T) {
	s := openTestStore(t)

	s.SaveCell(&store.Cell{ID: ":app/x", Handler: "h1"})
	s.SaveCell(&store.Cell{ID: ":app/x", Handler: "h2"})

	s.SaveTestResult(&store.TestResult{CellID: ":app/x", CellVersion: 1, Passed: true})
	s.SaveTestResult(&store.TestResult{CellID: ":app/x", CellVersion: 2, Passed: false, Error: "schema"})

	results, err := s.GetLatestTestResults(":app/x", 10)
	if err != nil {
		t.Fatalf("get latest results: %v", err)
	}
	if len(results) != 2 {
		t.Fatalf("expected 2, got %d", len(results))
	}
	// Newest first — v2's result should be first
	if results[0].CellVersion != 2 {
		t.Errorf("expected v2 first, got v%d", results[0].CellVersion)
	}
}
