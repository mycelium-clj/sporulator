package store

import (
	"database/sql"
	"fmt"
	"time"

	_ "modernc.org/sqlite"
)

// Store manages the SQLite database for cells, manifests, and test results.
type Store struct {
	db *sql.DB
}

// Cell is a versioned cell implementation.
type Cell struct {
	ID        string
	Version   int
	Schema    string // EDN string
	Handler   string // Clojure source
	Doc       string
	Requires  string // EDN string, e.g. "[:db :cache]"
	CreatedAt time.Time
	CreatedBy string // "human", "cell-agent-deepseek", etc.
}

// CellSummary is a lightweight cell listing entry.
type CellSummary struct {
	ID            string
	LatestVersion int
	Doc           string
	UpdatedAt     time.Time
}

// Manifest is a versioned workflow manifest.
type Manifest struct {
	ID        string
	Version   int
	Body      string // Full manifest EDN
	CreatedAt time.Time
	CreatedBy string
}

// ManifestSummary is a lightweight manifest listing entry.
type ManifestSummary struct {
	ID            string
	LatestVersion int
	UpdatedAt     time.Time
}

// CellPin links a manifest version to a specific cell version.
type CellPin struct {
	CellID      string
	CellVersion int
}

// TestResult records the outcome of testing a cell version.
type TestResult struct {
	ID          int64
	CellID      string
	CellVersion int
	Input       string // EDN
	Expected    string // EDN
	Actual      string // EDN
	Passed      bool
	Error       string
	RunAt       time.Time
}

// Open opens or creates a SQLite database at the given path and runs migrations.
// Use ":memory:" for an in-memory database (useful for testing).
func Open(path string) (*Store, error) {
	db, err := sql.Open("sqlite", path)
	if err != nil {
		return nil, fmt.Errorf("store open: %w", err)
	}

	// Enable WAL mode for better concurrent read performance
	if _, err := db.Exec("PRAGMA journal_mode=WAL"); err != nil {
		db.Close()
		return nil, fmt.Errorf("store pragma: %w", err)
	}

	s := &Store{db: db}
	if err := s.migrate(); err != nil {
		db.Close()
		return nil, fmt.Errorf("store migrate: %w", err)
	}
	return s, nil
}

// Close closes the database connection.
func (s *Store) Close() error {
	return s.db.Close()
}

func (s *Store) migrate() error {
	_, err := s.db.Exec(`
		CREATE TABLE IF NOT EXISTS cells (
			id         TEXT    NOT NULL,
			version    INTEGER NOT NULL,
			schema     TEXT    NOT NULL DEFAULT '',
			handler    TEXT    NOT NULL,
			doc        TEXT    NOT NULL DEFAULT '',
			requires   TEXT    NOT NULL DEFAULT '',
			created_at TEXT    NOT NULL DEFAULT (datetime('now')),
			created_by TEXT    NOT NULL DEFAULT '',
			PRIMARY KEY (id, version)
		);

		CREATE TABLE IF NOT EXISTS manifests (
			id         TEXT    NOT NULL,
			version    INTEGER NOT NULL,
			body       TEXT    NOT NULL,
			created_at TEXT    NOT NULL DEFAULT (datetime('now')),
			created_by TEXT    NOT NULL DEFAULT '',
			PRIMARY KEY (id, version)
		);

		CREATE TABLE IF NOT EXISTS manifest_cells (
			manifest_id      TEXT    NOT NULL,
			manifest_version INTEGER NOT NULL,
			cell_id          TEXT    NOT NULL,
			cell_version     INTEGER NOT NULL,
			FOREIGN KEY (manifest_id, manifest_version) REFERENCES manifests(id, version),
			FOREIGN KEY (cell_id, cell_version) REFERENCES cells(id, version),
			PRIMARY KEY (manifest_id, manifest_version, cell_id)
		);

		CREATE TABLE IF NOT EXISTS test_results (
			id           INTEGER PRIMARY KEY AUTOINCREMENT,
			cell_id      TEXT    NOT NULL,
			cell_version INTEGER NOT NULL,
			input        TEXT    NOT NULL DEFAULT '',
			expected     TEXT    NOT NULL DEFAULT '',
			actual       TEXT    NOT NULL DEFAULT '',
			passed       INTEGER NOT NULL DEFAULT 0,
			error        TEXT    NOT NULL DEFAULT '',
			run_at       TEXT    NOT NULL DEFAULT (datetime('now')),
			FOREIGN KEY (cell_id, cell_version) REFERENCES cells(id, version)
		);
	`)
	return err
}
