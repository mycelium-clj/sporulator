package store

import (
	"database/sql"
	"fmt"
	"time"
)

// SaveManifest saves a new manifest version. Returns the assigned version number.
func (s *Store) SaveManifest(manifest *Manifest) (int, error) {
	tx, err := s.db.Begin()
	if err != nil {
		return 0, fmt.Errorf("save manifest begin: %w", err)
	}
	defer tx.Rollback()

	var maxVersion sql.NullInt64
	err = tx.QueryRow("SELECT MAX(version) FROM manifests WHERE id = ?", manifest.ID).Scan(&maxVersion)
	if err != nil {
		return 0, fmt.Errorf("save manifest max version: %w", err)
	}
	version := 1
	if maxVersion.Valid {
		version = int(maxVersion.Int64) + 1
	}

	_, err = tx.Exec(`
		INSERT INTO manifests (id, version, body, created_by)
		VALUES (?, ?, ?, ?)`,
		manifest.ID, version, manifest.Body, manifest.CreatedBy,
	)
	if err != nil {
		return 0, fmt.Errorf("save manifest insert: %w", err)
	}

	if err := tx.Commit(); err != nil {
		return 0, fmt.Errorf("save manifest commit: %w", err)
	}
	return version, nil
}

// GetManifest retrieves a specific manifest version.
func (s *Store) GetManifest(id string, version int) (*Manifest, error) {
	m := &Manifest{}
	var createdAt string
	err := s.db.QueryRow(`
		SELECT id, version, body, created_at, created_by
		FROM manifests WHERE id = ? AND version = ?`, id, version,
	).Scan(&m.ID, &m.Version, &m.Body, &createdAt, &m.CreatedBy)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get manifest: %w", err)
	}
	m.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
	return m, nil
}

// GetLatestManifest retrieves the latest version of a manifest.
func (s *Store) GetLatestManifest(id string) (*Manifest, error) {
	m := &Manifest{}
	var createdAt string
	err := s.db.QueryRow(`
		SELECT id, version, body, created_at, created_by
		FROM manifests WHERE id = ? ORDER BY version DESC LIMIT 1`, id,
	).Scan(&m.ID, &m.Version, &m.Body, &createdAt, &m.CreatedBy)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get latest manifest: %w", err)
	}
	m.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
	return m, nil
}

// ListManifests returns a summary of all manifests with their latest version info.
func (s *Store) ListManifests() ([]ManifestSummary, error) {
	rows, err := s.db.Query(`
		SELECT m.id, m.version, m.created_at
		FROM manifests m
		INNER JOIN (SELECT id, MAX(version) AS version FROM manifests GROUP BY id) latest
			ON m.id = latest.id AND m.version = latest.version
		ORDER BY m.id`)
	if err != nil {
		return nil, fmt.Errorf("list manifests: %w", err)
	}
	defer rows.Close()

	var manifests []ManifestSummary
	for rows.Next() {
		var ms ManifestSummary
		var updatedAt string
		if err := rows.Scan(&ms.ID, &ms.LatestVersion, &updatedAt); err != nil {
			return nil, fmt.Errorf("list manifests scan: %w", err)
		}
		ms.UpdatedAt, _ = time.Parse("2006-01-02 15:04:05", updatedAt)
		manifests = append(manifests, ms)
	}
	return manifests, rows.Err()
}

// PinCellVersion associates a specific cell version with a manifest version.
func (s *Store) PinCellVersion(manifestID string, manifestVersion int, cellID string, cellVersion int) error {
	_, err := s.db.Exec(`
		INSERT OR REPLACE INTO manifest_cells (manifest_id, manifest_version, cell_id, cell_version)
		VALUES (?, ?, ?, ?)`,
		manifestID, manifestVersion, cellID, cellVersion,
	)
	if err != nil {
		return fmt.Errorf("pin cell version: %w", err)
	}
	return nil
}

// GetPinnedCells returns all cell versions pinned to a manifest version.
func (s *Store) GetPinnedCells(manifestID string, manifestVersion int) ([]CellPin, error) {
	rows, err := s.db.Query(`
		SELECT cell_id, cell_version FROM manifest_cells
		WHERE manifest_id = ? AND manifest_version = ?
		ORDER BY cell_id`, manifestID, manifestVersion)
	if err != nil {
		return nil, fmt.Errorf("get pinned cells: %w", err)
	}
	defer rows.Close()

	var pins []CellPin
	for rows.Next() {
		var p CellPin
		if err := rows.Scan(&p.CellID, &p.CellVersion); err != nil {
			return nil, fmt.Errorf("get pinned cells scan: %w", err)
		}
		pins = append(pins, p)
	}
	return pins, rows.Err()
}
