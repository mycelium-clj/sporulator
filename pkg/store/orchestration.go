package store

import "database/sql"

// OrchestrationRun represents a single orchestration run.
type OrchestrationRun struct {
	ID         string
	SpecHash   string
	ManifestID string
	Config     string
	Status     string // running, completed, failed, paused
	TreeJSON   string
	StartedAt  string
	UpdatedAt  string
}

// CellAttempt records a single attempt at implementing/testing a cell.
type CellAttempt struct {
	ID            int64
	RunID         string
	CellID        string
	AttemptType   string // lint, compile, test
	AttemptNumber int
	Code          string
	TestCode      string
	Output        string
	Passed        bool
	CreatedAt     string
}

// CreateOrchestrationRun inserts a new orchestration run record.
func (s *Store) CreateOrchestrationRun(run *OrchestrationRun) error {
	_, err := s.db.Exec(
		`INSERT INTO orchestration_runs (id, spec_hash, manifest_id, config, status, tree_json)
		 VALUES (?, ?, ?, ?, ?, ?)`,
		run.ID, run.SpecHash, run.ManifestID, run.Config, run.Status, run.TreeJSON)
	return err
}

// UpdateRunStatus updates only the status of an existing run.
func (s *Store) UpdateRunStatus(id, status string) error {
	_, err := s.db.Exec(
		`UPDATE orchestration_runs SET status = ?, updated_at = datetime('now') WHERE id = ?`,
		status, id)
	return err
}

// UpdateRunTree updates the tree_json (and optionally status) of an existing run.
func (s *Store) UpdateRunTree(id, status, treeJSON string) error {
	_, err := s.db.Exec(
		`UPDATE orchestration_runs SET status = ?, tree_json = ?, updated_at = datetime('now') WHERE id = ?`,
		status, treeJSON, id)
	return err
}

// GetOrchestrationRun retrieves a run by ID. Returns nil, nil if not found.
func (s *Store) GetOrchestrationRun(id string) (*OrchestrationRun, error) {
	row := s.db.QueryRow(
		`SELECT id, spec_hash, manifest_id, config, status, tree_json, started_at, updated_at
		 FROM orchestration_runs WHERE id = ?`, id)

	var run OrchestrationRun
	err := row.Scan(&run.ID, &run.SpecHash, &run.ManifestID, &run.Config, &run.Status,
		&run.TreeJSON, &run.StartedAt, &run.UpdatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &run, nil
}

// GetLatestRunForManifest returns the most recent run for a given manifest ID.
// Returns nil, nil if no runs exist for the manifest.
func (s *Store) GetLatestRunForManifest(manifestID string) (*OrchestrationRun, error) {
	row := s.db.QueryRow(
		`SELECT id, spec_hash, manifest_id, config, status, tree_json, started_at, updated_at
		 FROM orchestration_runs WHERE manifest_id = ? ORDER BY started_at DESC, rowid DESC LIMIT 1`, manifestID)

	var run OrchestrationRun
	err := row.Scan(&run.ID, &run.SpecHash, &run.ManifestID, &run.Config, &run.Status,
		&run.TreeJSON, &run.StartedAt, &run.UpdatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &run, nil
}

// SaveCellAttempt records a single cell implementation/test attempt.
func (s *Store) SaveCellAttempt(attempt *CellAttempt) error {
	_, err := s.db.Exec(
		`INSERT INTO cell_attempts (run_id, cell_id, attempt_type, attempt_number, code, test_code, output, passed)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		attempt.RunID, attempt.CellID, attempt.AttemptType, attempt.AttemptNumber,
		attempt.Code, attempt.TestCode, attempt.Output, attempt.Passed)
	return err
}

// GetCellAttempts returns all attempts for a given run and cell, ordered by attempt number.
func (s *Store) GetCellAttempts(runID, cellID string) ([]CellAttempt, error) {
	rows, err := s.db.Query(
		`SELECT id, run_id, cell_id, attempt_type, attempt_number, code, test_code, output, passed, created_at
		 FROM cell_attempts WHERE run_id = ? AND cell_id = ? ORDER BY attempt_number`, runID, cellID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var attempts []CellAttempt
	for rows.Next() {
		var a CellAttempt
		if err := rows.Scan(&a.ID, &a.RunID, &a.CellID, &a.AttemptType, &a.AttemptNumber,
			&a.Code, &a.TestCode, &a.Output, &a.Passed, &a.CreatedAt); err != nil {
			return nil, err
		}
		attempts = append(attempts, a)
	}
	return attempts, rows.Err()
}

// GetRunSummary returns a map of cell IDs to their best pass status for a given run.
// A cell is considered passing if any of its attempts passed (MAX(passed)).
func (s *Store) GetRunSummary(runID string) (map[string]bool, error) {
	rows, err := s.db.Query(
		`SELECT cell_id, MAX(passed) FROM cell_attempts WHERE run_id = ? GROUP BY cell_id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	summary := make(map[string]bool)
	for rows.Next() {
		var cellID string
		var passed bool
		if err := rows.Scan(&cellID, &passed); err != nil {
			return nil, err
		}
		summary[cellID] = passed
	}
	return summary, rows.Err()
}
