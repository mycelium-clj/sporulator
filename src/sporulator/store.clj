(ns sporulator.store
  "SQLite persistence for cells, manifests, test results, orchestration runs,
   and test contracts. Uses next.jdbc with immutable versioning."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; =============================================================
;; Connection and migration
;; =============================================================

(defn open
  "Opens or creates a SQLite database at the given path. Use \":memory:\" for testing."
  [path]
  (let [ds (if (= ":memory:" path)
             ;; For in-memory DBs, use a single persistent connection
             ;; (each new connection gets a different in-memory DB)
             (jdbc/get-connection (jdbc/get-datasource {:dbtype "sqlite" :dbname ":memory:"}))
             (jdbc/get-datasource {:dbtype "sqlite" :dbname path}))]
    (jdbc/execute! ds ["PRAGMA journal_mode=WAL"])
    (jdbc/execute! ds ["PRAGMA foreign_keys=ON"])
    (doseq [ddl ["CREATE TABLE IF NOT EXISTS cells (
                    id TEXT NOT NULL, version INTEGER NOT NULL,
                    schema TEXT NOT NULL DEFAULT '', handler TEXT NOT NULL,
                    doc TEXT NOT NULL DEFAULT '', requires TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    created_by TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (id, version))"
                 "CREATE TABLE IF NOT EXISTS manifests (
                    id TEXT NOT NULL, version INTEGER NOT NULL,
                    body TEXT NOT NULL,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    created_by TEXT NOT NULL DEFAULT '',
                    PRIMARY KEY (id, version))"
                 "CREATE TABLE IF NOT EXISTS manifest_cells (
                    manifest_id TEXT NOT NULL, manifest_version INTEGER NOT NULL,
                    cell_id TEXT NOT NULL, cell_version INTEGER NOT NULL,
                    PRIMARY KEY (manifest_id, manifest_version, cell_id))"
                 "CREATE TABLE IF NOT EXISTS test_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    cell_id TEXT NOT NULL, cell_version INTEGER NOT NULL,
                    input TEXT NOT NULL DEFAULT '', expected TEXT NOT NULL DEFAULT '',
                    actual TEXT NOT NULL DEFAULT '', passed INTEGER NOT NULL DEFAULT 0,
                    error TEXT NOT NULL DEFAULT '',
                    run_at TEXT NOT NULL DEFAULT (datetime('now')))"
                 "CREATE TABLE IF NOT EXISTS orchestration_runs (
                    id TEXT PRIMARY KEY, spec_hash TEXT NOT NULL,
                    manifest_id TEXT NOT NULL, config TEXT NOT NULL DEFAULT '{}',
                    status TEXT NOT NULL DEFAULT 'running',
                    tree_json TEXT NOT NULL DEFAULT '',
                    started_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')))"
                 "CREATE TABLE IF NOT EXISTS cell_attempts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL, cell_id TEXT NOT NULL,
                    attempt_type TEXT NOT NULL DEFAULT 'test',
                    attempt_number INTEGER NOT NULL,
                    code TEXT NOT NULL DEFAULT '', test_code TEXT NOT NULL DEFAULT '',
                    output TEXT NOT NULL DEFAULT '', passed INTEGER NOT NULL DEFAULT 0,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')))"
                 "CREATE INDEX IF NOT EXISTS idx_cell_attempts_run_cell
                    ON cell_attempts(run_id, cell_id)"
                 "CREATE TABLE IF NOT EXISTS test_contracts (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id TEXT NOT NULL, cell_id TEXT NOT NULL,
                    test_code TEXT NOT NULL DEFAULT '', test_body TEXT NOT NULL DEFAULT '',
                    review_notes TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'pending',
                    revision INTEGER NOT NULL DEFAULT 0,
                    feedback TEXT NOT NULL DEFAULT '', approved_at TEXT,
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')),
                    UNIQUE(run_id, cell_id))"
                 "CREATE TABLE IF NOT EXISTS chat_sessions (
                    id TEXT PRIMARY KEY,
                    agent_type TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    updated_at TEXT NOT NULL DEFAULT (datetime('now')))"
                 "CREATE TABLE IF NOT EXISTS chat_messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL DEFAULT '',
                    created_at TEXT NOT NULL DEFAULT (datetime('now')),
                    FOREIGN KEY (session_id) REFERENCES chat_sessions(id) ON DELETE CASCADE)"
                 "CREATE INDEX IF NOT EXISTS idx_chat_messages_session
                    ON chat_messages(session_id)"]]
      (jdbc/execute! ds [ddl]))
    {:datasource ds}))

(defn close
  "Closes the database connection."
  [{:keys [datasource]}]
  (when (instance? java.io.Closeable datasource)
    (.close ^java.io.Closeable datasource)))

(defn- ds [{:keys [datasource]}] datasource)

;; =============================================================
;; Cells
;; =============================================================

(defn save-cell!
  "Saves a new cell version. Returns the assigned version number."
  [store {:keys [id handler schema doc requires created-by]
          :or {schema "" doc "" requires "" created-by ""}}]
  (jdbc/with-transaction [tx (ds store)]
    (let [max-v (:max (jdbc/execute-one! tx
                        ["SELECT MAX(version) AS max FROM cells WHERE id = ?" id]
                        {:builder-fn rs/as-unqualified-kebab-maps}))
          version (if max-v (inc max-v) 1)]
      (jdbc/execute! tx
        ["INSERT INTO cells (id, version, schema, handler, doc, requires, created_by) VALUES (?,?,?,?,?,?,?)"
         id version (or schema "") handler (or doc "") (or requires "") (or created-by "")])
      version)))

(defn get-cell
  "Retrieves a specific cell version. Returns nil if not found."
  [store id version]
  (jdbc/execute-one! (ds store)
    ["SELECT id, version, schema, handler, doc, requires, created_at, created_by FROM cells WHERE id = ? AND version = ?" id version]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-latest-cell
  "Retrieves the latest version of a cell."
  [store id]
  (jdbc/execute-one! (ds store)
    ["SELECT id, version, schema, handler, doc, requires, created_at, created_by FROM cells WHERE id = ? ORDER BY version DESC LIMIT 1" id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-cells
  "Returns a summary of all cells with their latest version info."
  [store]
  (jdbc/execute! (ds store)
    ["SELECT c.id, c.version AS latest_version, c.doc, c.created_at
      FROM cells c
      INNER JOIN (SELECT id, MAX(version) AS version FROM cells GROUP BY id) latest
        ON c.id = latest.id AND c.version = latest.version
      ORDER BY c.id"]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-latest-cells
  "Returns all cells with their latest version's full data."
  [store]
  (jdbc/execute! (ds store)
    ["SELECT c.id, c.version, c.schema, c.handler, c.doc, c.requires, c.created_at, c.created_by
      FROM cells c
      INNER JOIN (SELECT id, MAX(version) AS version FROM cells GROUP BY id) latest
        ON c.id = latest.id AND c.version = latest.version
      ORDER BY c.id"]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-cell-history
  "Returns all versions of a cell, newest first."
  [store id]
  (jdbc/execute! (ds store)
    ["SELECT id, version, schema, handler, doc, requires, created_at, created_by FROM cells WHERE id = ? ORDER BY version DESC" id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

;; =============================================================
;; Manifests
;; =============================================================

(defn save-manifest!
  "Saves a new manifest version. Returns the assigned version number."
  [store {:keys [id body created-by] :or {created-by ""}}]
  (jdbc/with-transaction [tx (ds store)]
    (let [max-v (:max (jdbc/execute-one! tx
                        ["SELECT MAX(version) AS max FROM manifests WHERE id = ?" id]
                        {:builder-fn rs/as-unqualified-kebab-maps}))
          version (if max-v (inc max-v) 1)]
      (jdbc/execute! tx
        ["INSERT INTO manifests (id, version, body, created_by) VALUES (?,?,?,?)"
         id version body (or created-by "")])
      version)))

(defn get-manifest
  "Retrieves a specific manifest version."
  [store id version]
  (jdbc/execute-one! (ds store)
    ["SELECT id, version, body, created_at, created_by FROM manifests WHERE id = ? AND version = ?" id version]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-latest-manifest
  "Retrieves the latest version of a manifest."
  [store id]
  (jdbc/execute-one! (ds store)
    ["SELECT id, version, body, created_at, created_by FROM manifests WHERE id = ? ORDER BY version DESC LIMIT 1" id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-manifests
  "Returns a summary of all manifests with their latest version info."
  [store]
  (jdbc/execute! (ds store)
    ["SELECT m.id, m.version AS latest_version, m.created_at
      FROM manifests m
      INNER JOIN (SELECT id, MAX(version) AS version FROM manifests GROUP BY id) latest
        ON m.id = latest.id AND m.version = latest.version
      ORDER BY m.id"]
    {:builder-fn rs/as-unqualified-kebab-maps}))

;; =============================================================
;; Cell pinning
;; =============================================================

(defn pin-cell-version!
  "Associates a specific cell version with a manifest version."
  [store manifest-id manifest-version cell-id cell-version]
  (jdbc/execute! (ds store)
    ["INSERT OR REPLACE INTO manifest_cells (manifest_id, manifest_version, cell_id, cell_version) VALUES (?,?,?,?)"
     manifest-id manifest-version cell-id cell-version]))

(defn get-pinned-cells
  "Returns all cell versions pinned to a manifest version."
  [store manifest-id manifest-version]
  (jdbc/execute! (ds store)
    ["SELECT cell_id, cell_version FROM manifest_cells WHERE manifest_id = ? AND manifest_version = ? ORDER BY cell_id"
     manifest-id manifest-version]
    {:builder-fn rs/as-unqualified-kebab-maps}))

;; =============================================================
;; Test results
;; =============================================================

(defn save-test-result!
  "Records a test run outcome for a cell version."
  [store {:keys [cell-id cell-version input expected actual passed? error]
          :or {input "" expected "" actual "" error ""}}]
  (jdbc/execute! (ds store)
    ["INSERT INTO test_results (cell_id, cell_version, input, expected, actual, passed, error) VALUES (?,?,?,?,?,?,?)"
     cell-id cell-version (or input "") (or expected "") (or actual "")
     (if passed? 1 0) (or error "")]))

(defn get-test-results
  "Returns all test results for a cell version, newest first."
  [store cell-id cell-version]
  (->> (jdbc/execute! (ds store)
         ["SELECT id, cell_id, cell_version, input, expected, actual, passed, error, run_at
           FROM test_results WHERE cell_id = ? AND cell_version = ? ORDER BY id DESC"
          cell-id cell-version]
         {:builder-fn rs/as-unqualified-kebab-maps})
       (mapv #(assoc % :passed? (not= 0 (:passed %))))))

(defn get-latest-test-results
  "Returns the most recent test results for a cell (any version)."
  [store cell-id limit]
  (->> (jdbc/execute! (ds store)
         ["SELECT id, cell_id, cell_version, input, expected, actual, passed, error, run_at
           FROM test_results WHERE cell_id = ? ORDER BY id DESC LIMIT ?"
          cell-id limit]
         {:builder-fn rs/as-unqualified-kebab-maps})
       (mapv #(assoc % :passed? (not= 0 (:passed %))))))

;; =============================================================
;; Orchestration runs
;; =============================================================

(defn create-run!
  "Inserts a new orchestration run record."
  [store {:keys [id spec-hash manifest-id config status tree-json]
          :or {config "{}" status "running" tree-json ""}}]
  (jdbc/execute! (ds store)
    ["INSERT INTO orchestration_runs (id, spec_hash, manifest_id, config, status, tree_json) VALUES (?,?,?,?,?,?)"
     id spec-hash manifest-id (or config "{}") (or status "running") (or tree-json "")]))

(defn get-run
  "Retrieves a run by ID. Returns nil if not found."
  [store id]
  (jdbc/execute-one! (ds store)
    ["SELECT id, spec_hash, manifest_id, config, status, tree_json, started_at, updated_at
      FROM orchestration_runs WHERE id = ?" id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn update-run-status!
  "Updates only the status of an existing run."
  [store id status]
  (jdbc/execute! (ds store)
    ["UPDATE orchestration_runs SET status = ?, updated_at = datetime('now') WHERE id = ?" status id]))

(defn update-run-tree!
  "Updates the tree JSON and status of an existing run."
  [store id status tree-json]
  (jdbc/execute! (ds store)
    ["UPDATE orchestration_runs SET status = ?, tree_json = ?, updated_at = datetime('now') WHERE id = ?"
     status tree-json id]))

(defn get-latest-run-for-manifest
  "Returns the most recent run for a given manifest ID."
  [store manifest-id]
  (jdbc/execute-one! (ds store)
    ["SELECT id, spec_hash, manifest_id, config, status, tree_json, started_at, updated_at
      FROM orchestration_runs WHERE manifest_id = ? ORDER BY started_at DESC, rowid DESC LIMIT 1"
     manifest-id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

;; =============================================================
;; Cell attempts
;; =============================================================

(defn save-cell-attempt!
  "Records a single cell implementation/test attempt."
  [store {:keys [run-id cell-id attempt-type attempt-number code test-code output passed?]
          :or {attempt-type "test" code "" test-code "" output ""}}]
  (jdbc/execute! (ds store)
    ["INSERT INTO cell_attempts (run_id, cell_id, attempt_type, attempt_number, code, test_code, output, passed) VALUES (?,?,?,?,?,?,?,?)"
     run-id cell-id (or attempt-type "test") attempt-number
     (or code "") (or test-code "") (or output "") (if passed? 1 0)]))

(defn get-cell-attempts
  "Returns all attempts for a given run and cell, ordered by attempt number."
  [store run-id cell-id]
  (->> (jdbc/execute! (ds store)
         ["SELECT id, run_id, cell_id, attempt_type, attempt_number, code, test_code, output, passed, created_at
           FROM cell_attempts WHERE run_id = ? AND cell_id = ? ORDER BY attempt_number"
          run-id cell-id]
         {:builder-fn rs/as-unqualified-kebab-maps})
       (mapv #(assoc % :passed? (not= 0 (:passed %))))))

(defn get-run-summary
  "Returns a map of cell IDs to their best pass status for a given run."
  [store run-id]
  (->> (jdbc/execute! (ds store)
         ["SELECT cell_id, MAX(passed) AS passed FROM cell_attempts WHERE run_id = ? GROUP BY cell_id"
          run-id]
         {:builder-fn rs/as-unqualified-kebab-maps})
       (into {} (map (fn [{:keys [cell-id passed]}] [cell-id (not= 0 passed)])))))

;; =============================================================
;; Test contracts
;; =============================================================

(defn save-test-contract!
  "Inserts or upserts a test contract for a run+cell."
  [store {:keys [run-id cell-id test-code test-body review-notes status revision feedback]
          :or {test-code "" test-body "" review-notes "" status "pending" revision 0 feedback ""}}]
  (jdbc/execute! (ds store)
    ["INSERT INTO test_contracts (run_id, cell_id, test_code, test_body, review_notes, status, revision, feedback)
      VALUES (?,?,?,?,?,?,?,?)
      ON CONFLICT(run_id, cell_id) DO UPDATE SET
        test_code = excluded.test_code,
        test_body = excluded.test_body,
        review_notes = excluded.review_notes,
        status = excluded.status,
        revision = excluded.revision,
        feedback = excluded.feedback,
        updated_at = datetime('now')"
     run-id cell-id (or test-code "") (or test-body "") (or review-notes "")
     (or status "pending") (or revision 0) (or feedback "")]))

(defn update-test-contract-status!
  "Updates the status of a contract. Sets approved_at when status is 'approved'."
  [store run-id cell-id status]
  (if (= "approved" status)
    (jdbc/execute! (ds store)
      ["UPDATE test_contracts SET status = ?, approved_at = datetime('now'), updated_at = datetime('now')
        WHERE run_id = ? AND cell_id = ?" status run-id cell-id])
    (jdbc/execute! (ds store)
      ["UPDATE test_contracts SET status = ?, updated_at = datetime('now')
        WHERE run_id = ? AND cell_id = ?" status run-id cell-id])))

(defn- scan-contract [row]
  (when row
    (update row :approved-at #(or % ""))))

(defn get-test-contract
  "Retrieves a single test contract by run+cell. Returns nil if not found."
  [store run-id cell-id]
  (scan-contract
    (jdbc/execute-one! (ds store)
      ["SELECT id, run_id, cell_id, test_code, test_body, review_notes, status, revision,
              feedback, COALESCE(approved_at, '') AS approved_at, created_at, updated_at
        FROM test_contracts WHERE run_id = ? AND cell_id = ?" run-id cell-id]
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-approved-test-contracts
  "Returns all approved contracts for a given run."
  [store run-id]
  (mapv scan-contract
    (jdbc/execute! (ds store)
      ["SELECT id, run_id, cell_id, test_code, test_body, review_notes, status, revision,
              feedback, COALESCE(approved_at, '') AS approved_at, created_at, updated_at
        FROM test_contracts WHERE run_id = ? AND status = 'approved' ORDER BY cell_id" run-id]
      {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-test-contracts
  "Returns all contracts for a given run (any status)."
  [store run-id]
  (mapv scan-contract
    (jdbc/execute! (ds store)
      ["SELECT id, run_id, cell_id, test_code, test_body, review_notes, status, revision,
              feedback, COALESCE(approved_at, '') AS approved_at, created_at, updated_at
        FROM test_contracts WHERE run_id = ? ORDER BY cell_id" run-id]
      {:builder-fn rs/as-unqualified-kebab-maps})))

;; =============================================================
;; Chat sessions
;; =============================================================

(defn create-chat-session!
  "Creates a new chat session. Returns nil."
  [store id agent-type]
  (jdbc/execute! (ds store)
    ["INSERT INTO chat_sessions (id, agent_type) VALUES (?, ?)"
     id (or agent-type "")])
  nil)

(defn get-chat-session
  "Retrieves a chat session by ID. Returns nil if not found."
  [store id]
  (jdbc/execute-one! (ds store)
    ["SELECT id, agent_type, created_at, updated_at FROM chat_sessions WHERE id = ?" id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn list-chat-sessions
  "Returns all chat sessions with message counts, newest first."
  [store]
  (jdbc/execute! (ds store)
    ["SELECT s.id, s.agent_type, s.created_at, s.updated_at,
             COUNT(m.id) AS message_count
      FROM chat_sessions s
      LEFT JOIN chat_messages m ON m.session_id = s.id
      GROUP BY s.id
      ORDER BY s.updated_at DESC"]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn delete-chat-session!
  "Deletes a chat session and all its messages."
  [store id]
  (jdbc/execute! (ds store)
    ["DELETE FROM chat_messages WHERE session_id = ?" id])
  (jdbc/execute! (ds store)
    ["DELETE FROM chat_sessions WHERE id = ?" id])
  nil)

;; =============================================================
;; Chat messages
;; =============================================================

(defn save-chat-message!
  "Appends a message to a chat session. Updates the session's updated_at."
  [store session-id role content]
  (jdbc/execute! (ds store)
    ["INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)"
     session-id role (or content "")])
  (jdbc/execute! (ds store)
    ["UPDATE chat_sessions SET updated_at = datetime('now') WHERE id = ?" session-id])
  nil)

(defn load-chat-messages
  "Returns all messages for a session, oldest first."
  [store session-id]
  (jdbc/execute! (ds store)
    ["SELECT id, session_id, role, content, created_at
      FROM chat_messages WHERE session_id = ? ORDER BY id ASC" session-id]
    {:builder-fn rs/as-unqualified-kebab-maps}))

(defn clear-chat-messages!
  "Deletes all messages for a session but keeps the session itself."
  [store session-id]
  (jdbc/execute! (ds store)
    ["DELETE FROM chat_messages WHERE session_id = ?" session-id])
  nil)
