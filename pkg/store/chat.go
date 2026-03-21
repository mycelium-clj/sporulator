package store

import (
	"database/sql"
	"fmt"
	"time"
)

// ChatSession represents a persistent chat session.
type ChatSession struct {
	ID        string
	AgentType string // "graph" or "cell"
	CreatedAt time.Time
	UpdatedAt time.Time
}

// ChatMessage is a single message in a chat session.
type ChatMessage struct {
	ID        int64
	SessionID string
	Role      string // "user" or "assistant"
	Content   string
	CreatedAt time.Time
}

// ChatSessionSummary is a lightweight listing entry.
type ChatSessionSummary struct {
	ID           string
	AgentType    string
	MessageCount int
	CreatedAt    time.Time
	UpdatedAt    time.Time
}

// SaveChatMessage appends a message to a chat session, creating the session if needed.
func (s *Store) SaveChatMessage(sessionID, agentType, role, content string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return fmt.Errorf("save chat message begin: %w", err)
	}
	defer tx.Rollback()

	// Upsert session
	_, err = tx.Exec(`
		INSERT INTO chat_sessions (id, agent_type) VALUES (?, ?)
		ON CONFLICT(id) DO UPDATE SET updated_at = datetime('now')`,
		sessionID, agentType,
	)
	if err != nil {
		return fmt.Errorf("save chat session upsert: %w", err)
	}

	_, err = tx.Exec(`
		INSERT INTO chat_messages (session_id, role, content) VALUES (?, ?, ?)`,
		sessionID, role, content,
	)
	if err != nil {
		return fmt.Errorf("save chat message insert: %w", err)
	}

	return tx.Commit()
}

// LoadChatMessages returns all messages for a session in chronological order.
func (s *Store) LoadChatMessages(sessionID string) ([]ChatMessage, error) {
	rows, err := s.db.Query(`
		SELECT id, session_id, role, content, created_at
		FROM chat_messages WHERE session_id = ?
		ORDER BY created_at, id`, sessionID,
	)
	if err != nil {
		return nil, fmt.Errorf("load chat messages: %w", err)
	}
	defer rows.Close()

	var msgs []ChatMessage
	for rows.Next() {
		var m ChatMessage
		var createdAt string
		if err := rows.Scan(&m.ID, &m.SessionID, &m.Role, &m.Content, &createdAt); err != nil {
			return nil, fmt.Errorf("load chat messages scan: %w", err)
		}
		m.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
		msgs = append(msgs, m)
	}
	return msgs, rows.Err()
}

// ListChatSessions returns summaries of all chat sessions.
func (s *Store) ListChatSessions() ([]ChatSessionSummary, error) {
	rows, err := s.db.Query(`
		SELECT s.id, s.agent_type, COUNT(m.id) as msg_count, s.created_at, s.updated_at
		FROM chat_sessions s
		LEFT JOIN chat_messages m ON s.id = m.session_id
		GROUP BY s.id
		ORDER BY s.updated_at DESC`)
	if err != nil {
		return nil, fmt.Errorf("list chat sessions: %w", err)
	}
	defer rows.Close()

	var sessions []ChatSessionSummary
	for rows.Next() {
		var cs ChatSessionSummary
		var createdAt, updatedAt string
		if err := rows.Scan(&cs.ID, &cs.AgentType, &cs.MessageCount, &createdAt, &updatedAt); err != nil {
			return nil, fmt.Errorf("list chat sessions scan: %w", err)
		}
		cs.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
		cs.UpdatedAt, _ = time.Parse("2006-01-02 15:04:05", updatedAt)
		sessions = append(sessions, cs)
	}
	return sessions, rows.Err()
}

// GetChatSession retrieves a single chat session by ID.
func (s *Store) GetChatSession(id string) (*ChatSession, error) {
	cs := &ChatSession{}
	var createdAt, updatedAt string
	err := s.db.QueryRow(`
		SELECT id, agent_type, created_at, updated_at
		FROM chat_sessions WHERE id = ?`, id,
	).Scan(&cs.ID, &cs.AgentType, &createdAt, &updatedAt)
	if err == sql.ErrNoRows {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("get chat session: %w", err)
	}
	cs.CreatedAt, _ = time.Parse("2006-01-02 15:04:05", createdAt)
	cs.UpdatedAt, _ = time.Parse("2006-01-02 15:04:05", updatedAt)
	return cs, nil
}

// ClearChatMessages removes all messages for a session but keeps the session itself.
func (s *Store) ClearChatMessages(sessionID string) error {
	_, err := s.db.Exec("DELETE FROM chat_messages WHERE session_id = ?", sessionID)
	if err != nil {
		return fmt.Errorf("clear chat messages: %w", err)
	}
	return nil
}

// DeleteChatSession removes a session and all its messages.
func (s *Store) DeleteChatSession(id string) error {
	tx, err := s.db.Begin()
	if err != nil {
		return fmt.Errorf("delete chat session begin: %w", err)
	}
	defer tx.Rollback()

	if _, err := tx.Exec("DELETE FROM chat_messages WHERE session_id = ?", id); err != nil {
		return fmt.Errorf("delete chat messages: %w", err)
	}
	if _, err := tx.Exec("DELETE FROM chat_sessions WHERE id = ?", id); err != nil {
		return fmt.Errorf("delete chat session: %w", err)
	}
	return tx.Commit()
}
