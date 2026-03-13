package store

import (
	"fmt"
	"time"
)

// SaveTestResult records a test run outcome for a cell version.
func (s *Store) SaveTestResult(result *TestResult) error {
	res, err := s.db.Exec(`
		INSERT INTO test_results (cell_id, cell_version, input, expected, actual, passed, error)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		result.CellID, result.CellVersion, result.Input, result.Expected,
		result.Actual, boolToInt(result.Passed), result.Error,
	)
	if err != nil {
		return fmt.Errorf("save test result: %w", err)
	}
	id, _ := res.LastInsertId()
	result.ID = id
	return nil
}

// GetTestResults returns all test results for a cell version, newest first.
func (s *Store) GetTestResults(cellID string, cellVersion int) ([]TestResult, error) {
	rows, err := s.db.Query(`
		SELECT id, cell_id, cell_version, input, expected, actual, passed, error, run_at
		FROM test_results
		WHERE cell_id = ? AND cell_version = ?
		ORDER BY id DESC`, cellID, cellVersion)
	if err != nil {
		return nil, fmt.Errorf("get test results: %w", err)
	}
	defer rows.Close()

	var results []TestResult
	for rows.Next() {
		var r TestResult
		var passed int
		var runAt string
		if err := rows.Scan(&r.ID, &r.CellID, &r.CellVersion, &r.Input,
			&r.Expected, &r.Actual, &passed, &r.Error, &runAt); err != nil {
			return nil, fmt.Errorf("get test results scan: %w", err)
		}
		r.Passed = passed != 0
		r.RunAt, _ = time.Parse("2006-01-02 15:04:05", runAt)
		results = append(results, r)
	}
	return results, rows.Err()
}

// GetLatestTestResults returns the most recent test results for a cell (any version).
func (s *Store) GetLatestTestResults(cellID string, limit int) ([]TestResult, error) {
	rows, err := s.db.Query(`
		SELECT id, cell_id, cell_version, input, expected, actual, passed, error, run_at
		FROM test_results
		WHERE cell_id = ?
		ORDER BY id DESC
		LIMIT ?`, cellID, limit)
	if err != nil {
		return nil, fmt.Errorf("get latest test results: %w", err)
	}
	defer rows.Close()

	var results []TestResult
	for rows.Next() {
		var r TestResult
		var passed int
		var runAt string
		if err := rows.Scan(&r.ID, &r.CellID, &r.CellVersion, &r.Input,
			&r.Expected, &r.Actual, &passed, &r.Error, &runAt); err != nil {
			return nil, fmt.Errorf("get latest test results scan: %w", err)
		}
		r.Passed = passed != 0
		r.RunAt, _ = time.Parse("2006-01-02 15:04:05", runAt)
		results = append(results, r)
	}
	return results, rows.Err()
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
