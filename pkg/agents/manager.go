package agents

import (
	"sync"

	"github.com/mycelium-clj/sporulator/pkg/llm"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// Config configures the agent manager.
type Config struct {
	// GraphClient is the LLM client for the graph agent (e.g. Claude).
	GraphClient *llm.Client
	// CellClient is the LLM client for cell agents (e.g. DeepSeek).
	CellClient *llm.Client
	// Store is the database for persisting cells and manifests.
	Store *store.Store
	// GraphPrompt overrides the default graph agent system prompt.
	GraphPrompt string
	// CellPrompt overrides the default cell agent system prompt.
	CellPrompt string
}

// Manager orchestrates graph agents and cell agents.
type Manager struct {
	graphClient *llm.Client
	cellClient  *llm.Client
	store       *store.Store
	graphPrompt string
	cellPrompt  string

	mu            sync.Mutex
	graphSessions map[string]*GraphAgent
	cellSessions  map[string]*CellAgent
}

// NewManager creates an agent manager with the given configuration.
func NewManager(cfg Config) *Manager {
	graphPrompt := cfg.GraphPrompt
	if graphPrompt == "" {
		graphPrompt = DefaultGraphPrompt
	}
	cellPrompt := cfg.CellPrompt
	if cellPrompt == "" {
		cellPrompt = DefaultCellPrompt
	}

	return &Manager{
		graphClient:   cfg.GraphClient,
		cellClient:    cfg.CellClient,
		store:         cfg.Store,
		graphPrompt:   graphPrompt,
		cellPrompt:    cellPrompt,
		graphSessions: make(map[string]*GraphAgent),
		cellSessions:  make(map[string]*CellAgent),
	}
}

// GetGraphAgent returns (or creates) a persistent graph agent session.
func (m *Manager) GetGraphAgent(sessionID string) *GraphAgent {
	m.mu.Lock()
	defer m.mu.Unlock()

	if agent, ok := m.graphSessions[sessionID]; ok {
		return agent
	}

	agent := &GraphAgent{
		session: llm.NewSession(sessionID, m.graphPrompt),
		client:  m.graphClient,
		store:   m.store,
	}
	m.graphSessions[sessionID] = agent
	return agent
}

// ResetGraphAgent clears the conversation history for a graph agent session.
func (m *Manager) ResetGraphAgent(sessionID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.graphSessions, sessionID)
}

// ListGraphSessions returns the IDs of all active graph agent sessions.
func (m *Manager) ListGraphSessions() []string {
	m.mu.Lock()
	defer m.mu.Unlock()

	ids := make([]string, 0, len(m.graphSessions))
	for id := range m.graphSessions {
		ids = append(ids, id)
	}
	return ids
}

// NewCellAgent creates a new cell agent for implementing a specific cell.
// Use GetCellAgent to reuse an existing session for iteration.
func (m *Manager) NewCellAgent(cellID string) *CellAgent {
	m.mu.Lock()
	defer m.mu.Unlock()

	agent := &CellAgent{
		session: llm.NewSession(cellID, m.cellPrompt),
		client:  m.cellClient,
		store:   m.store,
	}
	m.cellSessions[cellID] = agent
	return agent
}

// GetCellAgent returns an existing cell agent session or creates a new one.
// This preserves conversation history for iteration workflows.
func (m *Manager) GetCellAgent(cellID string) *CellAgent {
	m.mu.Lock()
	defer m.mu.Unlock()

	if agent, ok := m.cellSessions[cellID]; ok {
		return agent
	}

	agent := &CellAgent{
		session: llm.NewSession(cellID, m.cellPrompt),
		client:  m.cellClient,
		store:   m.store,
	}
	m.cellSessions[cellID] = agent
	return agent
}
