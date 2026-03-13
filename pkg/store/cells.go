package store

import (
	"database/sql"
	"fmt"
	"time"
)

// SaveCell saves a new cell version. The version is auto-incremented from the
// latest existing version. Returns the assigned version number.
func (s *Store) SaveCell(cell *Cell) (int, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, fmt.Errorf("save cell begin: %w", err)
	}
	defer tx.Rollback()

	// Get next version
	var maxVersion sql.NullInt64
	err = tx.QueryRow("SELECT MAX(version) FROM cells WHERE id = ?", cell.ID).Scan(&maxVersion)
	if err != nil {
		return 0, fmt.Errorf("save cell max version: %w", err)
	}
	version := 1
	if maxVersion.Valid {
		version = int(maxVersion.Int64) + 1
	}

	_, err = tx.Exec(`
		INSERT INTO cells (id, version, schema, handler, doc, requires, created_by)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		cell.ID, version, cell.Schema, cell.Handler, cell.Doc, cell.Requires, cell.CreatedBy,
	)
	if err != nil {
		return 0, fmt.Errorf("save cell insert: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return 0, fmt.Errorf("save cell commit: %w", err)
	}
	return version, nil
}

// GetCell retrieves a specific cell version.
func (s *Store) GetCell(id string, version int) (*Cell, error) {
	cell := &Cell{}
	var createdAt string
	err := s.db.QueryRow(`
		SELECT id, version, schema, handler, doc, requires, created_at, created_by
		FROM cells WHERE id = ? AND version = ?`, id, version,
	).Scan(&cell.ID, &cell.Version, &cell.Schema, &cell.Handler,
		&cell.Doc, &cell.Requires, &createdAt, &cell.CreatedBy)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get cell: %w", err)
	}
	cell.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
	return cell, nil
}

// GetLatestCell retrieves the latest version of a cell.
func (s *Store) GetLatestCell(id string) (*Cell, error) {
	cell := &Cell{}
	var createdAt string
	err := s.db.QueryRow(`
		SELECT id, version, schema, handler, doc, requires, created_at, created_by
		FROM cells WHERE id = ? ORDER BY version DESC LIMIT 1`, id,
	).Scan(&cell.ID, &cell.Version, &cell.Schema, &cell.Handler,
		&cell.Doc, &cell.Requires, &createdAt, &cell.CreatedBy)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get latest cell: %w", err)
	}
	cell.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
	return cell, nil
}

// ListCells returns a summary of all cells with their latest version info.
func (s *Store) ListCells() ([]CellSummary, error) {
	rows, err := s.db.Query(`
		SELECT c.id, c.version, c.doc, c.created_at
		FROM cells c
		INNER JOIN (SELECT id, MAX(version) AS version FROM cells GROUP BY id) latest
			ON c.id = latest.id AND c.version = latest.version
		ORDER BY c.id`)
	if err != nil {
		return nil, fmt.Errorf("list cells: %w", err)
	}
	defer rows.Close()

	var cells []CellSummary
	for rows.Next() {
		var cs CellSummary
		var updatedAt string
		if err := rows.Scan(&cs.ID, &cs.LatestVersion, &cs.Doc, &updatedAt); err != nil {
			return nil, fmt.Errorf("list cells scan: %w", err)
		}
		cs.UpdatedAt, _ = time.Parse("2006-01-02 15:04:05", updatedAt)
		cells = append(cells, cs)
	}
	return cells, rows.Err()
}

// GetCellHistory returns all versions of a cell, newest first.
func (s *Store) GetCellHistory(id string) ([]Cell, error) {
	rows, err := s.db.Query(`
		SELECT id, version, schema, handler, doc, requires, created_at, created_by
		FROM cells WHERE id = ? ORDER BY version DESC`, id)
	if err != nil {
		return nil, fmt.Errorf("cell history: %w", err)
	}
	defer rows.Close()

	var cells []Cell
	for rows.Next() {
		var c Cell
		var createdAt string
		if err := rows.Scan(&c.ID, &c.Version, &c.Schema, &c.Handler,
			&c.Doc, &c.Requires, &createdAt, &c.CreatedBy); err != nil {
			return nil, fmt.Errorf("cell history scan: %w", err)
		}
		c.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
		cells = append(cells, c)
	}
	return cells, rows.Err()
}
