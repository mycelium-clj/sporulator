package store

import "database/sql"

// TestContractRecord persists a test contract for human review across sessions.
type TestContractRecord struct {
	ID          int64
	RunID       string
	CellID      string
	TestCode    string // assembled full test namespace
	TestBody    string // raw deftest forms from LLM
	ReviewNotes string // LLM self-review output
	Status      string // pending, approved, skipped
	Revision    int
	Feedback    string // user feedback from most recent review round
	ApprovedAt  string
	CreatedAt   string
	UpdatedAt   string
}

// SaveTestContract inserts or updates (upsert) a test contract for a run+cell.
func (s *Store) SaveTestContract(tc *TestContractRecord) error {
	_, err := s.db.Exec(
		`INSERT INTO test_contracts (run_id, cell_id, test_code, test_body, review_notes, status, revision, feedback)
		 VALUES (?, ?, ?, ?, ?, ?, ?, ?)
		 ON CONFLICT(run_id, cell_id) DO UPDATE SET
		   test_code    = excluded.test_code,
		   test_body    = excluded.test_body,
		   review_notes = excluded.review_notes,
		   status       = excluded.status,
		   revision     = excluded.revision,
		   feedback     = excluded.feedback,
		   updated_at   = datetime('now')`,
		tc.RunID, tc.CellID, tc.TestCode, tc.TestBody, tc.ReviewNotes,
		tc.Status, tc.Revision, tc.Feedback)
	return err
}

// UpdateTestContractStatus updates the status (and optionally approved_at) of a contract.
func (s *Store) UpdateTestContractStatus(runID, cellID, status string) error {
	var err error
	if status == "approved" {
		_, err = s.db.Exec(
			`UPDATE test_contracts SET status = ?, approved_at = datetime('now'), updated_at = datetime('now')
			 WHERE run_id = ? AND cell_id = ?`,
			status, runID, cellID)
	} else {
		_, err = s.db.Exec(
			`UPDATE test_contracts SET status = ?, updated_at = datetime('now')
			 WHERE run_id = ? AND cell_id = ?`,
			status, runID, cellID)
	}
	return err
}

// GetTestContract retrieves a single test contract by run+cell. Returns nil, nil if not found.
func (s *Store) GetTestContract(runID, cellID string) (*TestContractRecord, error) {
	row := s.db.QueryRow(
		`SELECT id, run_id, cell_id, test_code, test_body, review_notes, status, revision,
		        feedback, COALESCE(approved_at, ''), created_at, updated_at
		 FROM test_contracts WHERE run_id = ? AND cell_id = ?`, runID, cellID)

	var tc TestContractRecord
	err := row.Scan(&tc.ID, &tc.RunID, &tc.CellID, &tc.TestCode, &tc.TestBody,
		&tc.ReviewNotes, &tc.Status, &tc.Revision, &tc.Feedback,
		&tc.ApprovedAt, &tc.CreatedAt, &tc.UpdatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return &tc, nil
}

// GetApprovedTestContracts returns all approved contracts for a given run.
func (s *Store) GetApprovedTestContracts(runID string) ([]TestContractRecord, error) {
	rows, err := s.db.Query(
		`SELECT id, run_id, cell_id, test_code, test_body, review_notes, status, revision,
		        feedback, COALESCE(approved_at, ''), created_at, updated_at
		 FROM test_contracts WHERE run_id = ? AND status = 'approved' ORDER BY cell_id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var contracts []TestContractRecord
	for rows.Next() {
		var tc TestContractRecord
		if err := rows.Scan(&tc.ID, &tc.RunID, &tc.CellID, &tc.TestCode, &tc.TestBody,
			&tc.ReviewNotes, &tc.Status, &tc.Revision, &tc.Feedback,
			&tc.ApprovedAt, &tc.CreatedAt, &tc.UpdatedAt); err != nil {
			return nil, err
		}
		contracts = append(contracts, tc)
	}
	return contracts, rows.Err()
}

// GetTestContracts returns all contracts for a given run (any status).
func (s *Store) GetTestContracts(runID string) ([]TestContractRecord, error) {
	rows, err := s.db.Query(
		`SELECT id, run_id, cell_id, test_code, test_body, review_notes, status, revision,
		        feedback, COALESCE(approved_at, ''), created_at, updated_at
		 FROM test_contracts WHERE run_id = ? ORDER BY cell_id`, runID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var contracts []TestContractRecord
	for rows.Next() {
		var tc TestContractRecord
		if err := rows.Scan(&tc.ID, &tc.RunID, &tc.CellID, &tc.TestCode, &tc.TestBody,
			&tc.ReviewNotes, &tc.Status, &tc.Revision, &tc.Feedback,
			&tc.ApprovedAt, &tc.CreatedAt, &tc.UpdatedAt); err != nil {
			return nil, err
		}
		contracts = append(contracts, tc)
	}
	return contracts, rows.Err()
}
