package api

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"sync"

	"github.com/gorilla/websocket"
	"github.com/mycelium-clj/sporulator/pkg/agents"
)

var upgrader = websocket.Upgrader{
	CheckOrigin: func(r *http.Request) bool { return true },
}

// WSMessage is a message sent over the WebSocket.
type WSMessage struct {
	Type    string `json:"type"`              // "chat", "stream_chunk", "stream_end", "cell_result", "error", etc.
	ID      string `json:"id,omitempty"`      // request/session ID for correlation
	Payload any    `json:"payload,omitempty"` // type-specific data
}

// Hub manages all WebSocket connections and broadcasts.
type Hub struct {
	clients    map[*wsClient]bool
	broadcast  chan WSMessage
	register   chan *wsClient
	unregister chan *wsClient
	done       chan struct{}
	mu         sync.RWMutex
}

type wsClient struct {
	hub    *Hub
	conn   *websocket.Conn
	send   chan WSMessage
	ctx    context.Context
	cancel context.CancelFunc
}

// NewHub creates a new WebSocket hub.
func NewHub() *Hub {
	return &Hub{
		clients:    make(map[*wsClient]bool),
		broadcast:  make(chan WSMessage, 64),
		register:   make(chan *wsClient),
		unregister: make(chan *wsClient),
		done:       make(chan struct{}),
	}
}

// Run starts the hub's event loop. Should be run as a goroutine.
func (h *Hub) Run() {
	for {
		select {
		case <-h.done:
			h.mu.Lock()
			for client := range h.clients {
				client.cancel()
				close(client.send)
				delete(h.clients, client)
			}
			h.mu.Unlock()
			return

		case client := <-h.register:
			h.mu.Lock()
			h.clients[client] = true
			h.mu.Unlock()

		case client := <-h.unregister:
			h.mu.Lock()
			if _, ok := h.clients[client]; ok {
				client.cancel()
				delete(h.clients, client)
				close(client.send)
			}
			h.mu.Unlock()

		case msg := <-h.broadcast:
			h.mu.RLock()
			var dead []*wsClient
			for client := range h.clients {
				select {
				case client.send <- msg:
				default:
					dead = append(dead, client)
				}
			}
			h.mu.RUnlock()
			if len(dead) > 0 {
				h.mu.Lock()
				for _, client := range dead {
					if _, ok := h.clients[client]; ok {
						client.cancel()
						delete(h.clients, client)
						close(client.send)
					}
				}
				h.mu.Unlock()
			}
		}
	}
}

// Stop shuts down the hub, closing all client connections.
func (h *Hub) Stop() {
	close(h.done)
}

// Broadcast sends a message to all connected WebSocket clients.
func (h *Hub) Broadcast(msg WSMessage) {
	select {
	case h.broadcast <- msg:
	case <-h.done:
	}
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		log.Printf("ws upgrade error: %v", err)
		return
	}

	ctx, cancel := context.WithCancel(context.Background())
	client := &wsClient{
		hub:    s.hub,
		conn:   conn,
		send:   make(chan WSMessage, 64),
		ctx:    ctx,
		cancel: cancel,
	}
	s.hub.register <- client

	go client.writePump()
	go client.readPump(s)
}

func (c *wsClient) writePump() {
	defer c.conn.Close()
	for msg := range c.send {
		data, err := json.Marshal(msg)
		if err != nil {
			continue
		}
		if err := c.conn.WriteMessage(websocket.TextMessage, data); err != nil {
			return
		}
	}
}

func (c *wsClient) readPump(s *Server) {
	defer func() {
		c.hub.unregister <- c
		c.conn.Close()
	}()

	for {
		_, data, err := c.conn.ReadMessage()
		if err != nil {
			return
		}

		var msg WSMessage
		if err := json.Unmarshal(data, &msg); err != nil {
			c.sendError("invalid message format")
			continue
		}

		s.handleWSMessage(c, msg)
	}
}

func (c *wsClient) sendMsg(msg WSMessage) {
	// recover protects against sending on a closed channel
	// (client disconnected while a handler goroutine is still running)
	defer func() { recover() }()
	select {
	case c.send <- msg:
	default:
	}
}

func (c *wsClient) sendError(msg string) {
	c.sendMsg(WSMessage{Type: "error", Payload: msg})
}

// --- WebSocket message handlers ---

func (s *Server) handleWSMessage(c *wsClient, msg WSMessage) {
	switch msg.Type {
	case "graph_chat":
		go s.handleGraphChat(c, msg)
	case "graph_chat_feedback":
		go s.handleGraphChatFeedback(c, msg)
	case "cell_implement":
		go s.handleCellImplement(c, msg)
	case "cell_implement_feedback":
		go s.handleCellImplementFeedback(c, msg)
	case "cell_iterate":
		go s.handleCellIterate(c, msg)
	case "orchestrate":
		go s.handleOrchestrate(c, msg)
	case "orchestrate_resume":
		go s.handleOrchestrateResume(c, msg)
	case "test_review":
		s.handleTestReview(c, msg)
	case "graph_review":
		s.handleGraphReview(c, msg)
	default:
		c.sendError("unknown message type: " + msg.Type)
	}
}

type graphChatPayload struct {
	SessionID string `json:"session_id"`
	Message   string `json:"message"`
}

func (s *Server) handleGraphChat(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload graphChatPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid graph_chat payload")
		return
	}

	agent := s.manager.GetGraphAgent(payload.SessionID)
	response, err := agent.ChatStream(c.ctx, payload.Message, func(chunk string) {
		c.sendMsg(WSMessage{
			Type: "stream_chunk",
			ID:   payload.SessionID,
			Payload: map[string]string{
				"chunk": chunk,
			},
		})
	})

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "stream_error",
			ID:      payload.SessionID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "stream_end",
		ID:   payload.SessionID,
		Payload: map[string]string{
			"content": response,
		},
	})
}

type cellImplementPayload struct {
	Brief agents.CellBrief `json:"brief"`
}

func (s *Server) handleCellImplement(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload cellImplementPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid cell_implement payload")
		return
	}

	cellID := payload.Brief.ID
	agent := s.manager.NewCellAgent(cellID)
	result, err := agent.ImplementStream(c.ctx, payload.Brief, func(chunk string) {
		c.sendMsg(WSMessage{
			Type: "stream_chunk",
			ID:   cellID,
			Payload: map[string]string{
				"chunk": chunk,
			},
		})
	})

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "stream_error",
			ID:      cellID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "cell_result",
		ID:   cellID,
		Payload: map[string]string{
			"cell_id": result.CellID,
			"code":    result.Code,
			"raw":     result.Raw,
		},
	})
}

type cellIteratePayload struct {
	SessionID string `json:"session_id"`
	Feedback  string `json:"feedback"`
}

func (s *Server) handleCellIterate(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload cellIteratePayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid cell_iterate payload")
		return
	}

	agent := s.manager.GetCellAgent(payload.SessionID)
	result, err := agent.IterateStream(c.ctx, payload.Feedback, func(chunk string) {
		c.sendMsg(WSMessage{
			Type: "stream_chunk",
			ID:   payload.SessionID,
			Payload: map[string]string{
				"chunk": chunk,
			},
		})
	})

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "stream_error",
			ID:      payload.SessionID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "cell_result",
		ID:   payload.SessionID,
		Payload: map[string]string{
			"cell_id": result.CellID,
			"code":    result.Code,
			"raw":     result.Raw,
		},
	})
}

// --- Feedback loop handlers ---

type graphChatFeedbackPayload struct {
	SessionID   string `json:"session_id"`
	Message     string `json:"message"`
	MaxAttempts int    `json:"max_attempts"`
}

func (s *Server) handleGraphChatFeedback(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload graphChatFeedbackPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid graph_chat_feedback payload")
		return
	}

	agent := s.manager.GetGraphAgent(payload.SessionID)
	maxAttempts := payload.MaxAttempts
	if maxAttempts == 0 {
		maxAttempts = 3
	}

	response, err := agent.ChatStreamWithFeedback(c.ctx, payload.Message, maxAttempts,
		func(chunk string) {
			c.sendMsg(WSMessage{
				Type: "stream_chunk",
				ID:   payload.SessionID,
				Payload: map[string]string{
					"chunk": chunk,
				},
			})
		},
		func(event agents.FeedbackEvent) {
			c.sendMsg(WSMessage{
				Type: "feedback_event",
				ID:   payload.SessionID,
				Payload: map[string]any{
					"event_type": event.Type,
					"attempt":    event.Attempt,
					"code":       event.Code,
					"output":     event.Output,
					"message":    event.Message,
				},
			})
		},
	)

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "stream_error",
			ID:      payload.SessionID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "stream_end",
		ID:   payload.SessionID,
		Payload: map[string]string{
			"content": response,
		},
	})
}

type cellImplementFeedbackPayload struct {
	Brief       agents.CellBrief `json:"brief"`
	MaxAttempts int              `json:"max_attempts"`
}

func (s *Server) handleCellImplementFeedback(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload cellImplementFeedbackPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid cell_implement_feedback payload")
		return
	}

	cellID := payload.Brief.ID
	agent := s.manager.NewCellAgent(cellID)
	maxAttempts := payload.MaxAttempts
	if maxAttempts == 0 {
		maxAttempts = 3
	}

	result, err := agent.ImplementWithFeedback(c.ctx, payload.Brief, maxAttempts,
		func(chunk string) {
			c.sendMsg(WSMessage{
				Type: "stream_chunk",
				ID:   cellID,
				Payload: map[string]string{
					"chunk": chunk,
				},
			})
		},
		func(event agents.FeedbackEvent) {
			c.sendMsg(WSMessage{
				Type: "feedback_event",
				ID:   cellID,
				Payload: map[string]any{
					"event_type": event.Type,
					"attempt":    event.Attempt,
					"code":       event.Code,
					"output":     event.Output,
					"message":    event.Message,
				},
			})
		},
	)

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "stream_error",
			ID:      cellID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "cell_result",
		ID:   cellID,
		Payload: map[string]string{
			"cell_id": result.CellID,
			"code":    result.Code,
			"raw":     result.Raw,
		},
	})
}

// --- Orchestrator handler ---

type orchestratePayload struct {
	ProjectPath      string `json:"project_path"`
	BaseNamespace    string `json:"base_namespace"`
	SourceDir        string `json:"source_dir"`
	TestDir          string `json:"test_dir"`
	Spec             string `json:"spec"`
	ManifestID       string `json:"manifest_id"`
	MaxStepsPerLevel int    `json:"max_steps_per_level"`
	MaxDepth         int    `json:"max_depth"`
	AutoApproveTests bool   `json:"auto_approve_tests"`
	AutoApproveGraph bool   `json:"auto_approve_graph"`
}

func (s *Server) handleOrchestrate(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload orchestratePayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid orchestrate payload")
		return
	}

	if payload.SourceDir == "" {
		payload.SourceDir = "src/clj"
	}
	if payload.TestDir == "" {
		payload.TestDir = "test/clj"
	}

	orch := agents.NewOrchestrator(s.manager, s.store)

	cfg := agents.ProjectConfig{
		ProjectPath:      payload.ProjectPath,
		BaseNamespace:    payload.BaseNamespace,
		SourceDir:        payload.SourceDir,
		TestDir:          payload.TestDir,
		Spec:             payload.Spec,
		ManifestID:       payload.ManifestID,
		MaxStepsPerLevel: payload.MaxStepsPerLevel,
		MaxDepth:         payload.MaxDepth,
		AutoApproveTests: payload.AutoApproveTests,
		AutoApproveGraph: payload.AutoApproveGraph,
	}

	onChunk := func(source string, chunk string) {
		c.sendMsg(WSMessage{
			Type: "stream_chunk",
			ID:   source,
			Payload: map[string]string{
				"chunk": chunk,
			},
		})
	}

	onEvent := func(event agents.OrchestratorEvent) {
		c.sendMsg(WSMessage{
			Type:    "orchestrator_event",
			ID:      msg.ID,
			Payload: event,
		})
	}

	cbs := agents.ReviewCallbacks{
		OnTestReview:  s.makeReviewCallback(c, msg.ID),
		OnGraphReview: s.makeGraphReviewCallback(c, msg.ID),
	}

	err = orch.Run(c.ctx, cfg, onChunk, onEvent, cbs)

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "orchestrator_error",
			ID:      msg.ID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "orchestrator_complete",
		ID:   msg.ID,
	})
}

func (s *Server) handleOrchestrateResume(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload orchestratePayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid orchestrate_resume payload")
		return
	}

	if payload.SourceDir == "" {
		payload.SourceDir = "src/clj"
	}
	if payload.TestDir == "" {
		payload.TestDir = "test/clj"
	}

	orch := agents.NewOrchestrator(s.manager, s.store)

	cfg := agents.ProjectConfig{
		ProjectPath:      payload.ProjectPath,
		BaseNamespace:    payload.BaseNamespace,
		SourceDir:        payload.SourceDir,
		TestDir:          payload.TestDir,
		Spec:             payload.Spec,
		ManifestID:       payload.ManifestID,
		MaxStepsPerLevel: payload.MaxStepsPerLevel,
		MaxDepth:         payload.MaxDepth,
		AutoApproveTests: payload.AutoApproveTests,
		AutoApproveGraph: payload.AutoApproveGraph,
	}

	onChunk := func(source string, chunk string) {
		c.sendMsg(WSMessage{
			Type: "stream_chunk",
			ID:   source,
			Payload: map[string]string{
				"chunk": chunk,
			},
		})
	}

	onEvent := func(event agents.OrchestratorEvent) {
		c.sendMsg(WSMessage{
			Type:    "orchestrator_event",
			ID:      msg.ID,
			Payload: event,
		})
	}

	cbs := agents.ReviewCallbacks{
		OnTestReview:  s.makeReviewCallback(c, msg.ID),
		OnGraphReview: s.makeGraphReviewCallback(c, msg.ID),
	}

	err = orch.RunResumable(c.ctx, cfg, onChunk, onEvent, cbs)

	if err != nil {
		c.sendMsg(WSMessage{
			Type:    "orchestrator_error",
			ID:      msg.ID,
			Payload: err.Error(),
		})
		return
	}

	c.sendMsg(WSMessage{
		Type: "orchestrator_complete",
		ID:   msg.ID,
	})
}

// makeReviewCallback creates an OnReviewFunc that sends contracts to the client via WebSocket
// and blocks until the client sends a test_review response.
func (s *Server) makeReviewCallback(c *wsClient, msgID string) agents.OnReviewFunc {
	return func(contracts []agents.TestContract) ([]agents.ReviewResponse, error) {
		// Use the WS message ID as the gate key
		runID := msgID

		// Create a gate for this review round
		gate := &reviewGate{ch: make(chan []agents.ReviewResponse, 1)}
		s.reviewGatesMu.Lock()
		s.reviewGates[runID] = gate
		s.reviewGatesMu.Unlock()

		defer func() {
			s.reviewGatesMu.Lock()
			delete(s.reviewGates, runID)
			s.reviewGatesMu.Unlock()
		}()

		// Build contract data for the client
		contractData := make([]map[string]any, len(contracts))
		for i, tc := range contracts {
			contractData[i] = map[string]any{
				"cell_id":      tc.CellID,
				"test_code":    tc.TestCode,
				"review_notes": tc.ReviewNotes,
				"cell_brief": map[string]any{
					"id":     tc.Brief.ID,
					"doc":    tc.Brief.Doc,
					"schema": tc.Brief.Schema,
				},
				"revision": tc.Revision,
			}
		}

		// Send review event + contract data to client
		c.sendMsg(WSMessage{
			Type: "orchestrator_event",
			ID:   msgID,
			Payload: agents.OrchestratorEvent{
				Phase:   "test_review",
				Status:  "awaiting_review",
				Message: "Review test contracts",
			},
		})
		c.sendMsg(WSMessage{
			Type: "test_review_contracts",
			ID:   msgID,
			Payload: map[string]any{
				"run_id":    runID,
				"contracts": contractData,
			},
		})

		// Block until response arrives via handleTestReview
		select {
		case responses := <-gate.ch:
			return responses, nil
		case <-c.ctx.Done():
			return nil, c.ctx.Err()
		}
	}
}

// testReviewPayload is the client's response to a test_review_contracts message.
type testReviewPayload struct {
	RunID     string                  `json:"run_id"`
	Responses []agents.ReviewResponse `json:"responses"`
}

// handleTestReview processes a test_review message from the client (user review responses).
// It unblocks the waiting review gate for the corresponding run.
func (s *Server) handleTestReview(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload testReviewPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid test_review payload")
		return
	}

	s.reviewGatesMu.Lock()
	gate, ok := s.reviewGates[payload.RunID]
	s.reviewGatesMu.Unlock()

	if !ok {
		c.sendError("no pending review for run_id: " + payload.RunID)
		return
	}

	// Non-blocking send — if the channel already has a value this is a duplicate
	select {
	case gate.ch <- payload.Responses:
	default:
	}
}

// makeGraphReviewCallback creates an OnGraphReviewFunc that sends the graph to the client
// via WebSocket and blocks until the client sends a graph_review response.
func (s *Server) makeGraphReviewCallback(c *wsClient, msgID string) agents.OnGraphReviewFunc {
	return func(review agents.GraphReview) (*agents.GraphReviewResponse, error) {
		runID := msgID

		gate := &graphReviewGate{ch: make(chan *agents.GraphReviewResponse, 1)}
		s.graphReviewMu.Lock()
		s.graphReviewGates[runID] = gate
		s.graphReviewMu.Unlock()

		defer func() {
			s.graphReviewMu.Lock()
			delete(s.graphReviewGates, runID)
			s.graphReviewMu.Unlock()
		}()

		// Send graph review data to client
		c.sendMsg(WSMessage{
			Type: "orchestrator_event",
			ID:   msgID,
			Payload: agents.OrchestratorEvent{
				Phase:   "graph_review",
				Status:  "awaiting_review",
				Message: fmt.Sprintf("Review graph at depth %d", review.Depth),
			},
		})
		c.sendMsg(WSMessage{
			Type: "graph_review_data",
			ID:   msgID,
			Payload: map[string]any{
				"run_id": runID,
				"graph":  review,
			},
		})

		// Block until response arrives via handleGraphReview
		select {
		case resp := <-gate.ch:
			return resp, nil
		case <-c.ctx.Done():
			return nil, c.ctx.Err()
		}
	}
}

// graphReviewPayload is the client's response to a graph_review_data message.
type graphReviewPayload struct {
	RunID    string                       `json:"run_id"`
	Response agents.GraphReviewResponse   `json:"response"`
}

// handleGraphReview processes a graph_review message from the client.
func (s *Server) handleGraphReview(c *wsClient, msg WSMessage) {
	payloadBytes, err := json.Marshal(msg.Payload)
	if err != nil {
		c.sendError("invalid payload")
		return
	}
	var payload graphReviewPayload
	if err := json.Unmarshal(payloadBytes, &payload); err != nil {
		c.sendError("invalid graph_review payload")
		return
	}

	s.graphReviewMu.Lock()
	gate, ok := s.graphReviewGates[payload.RunID]
	s.graphReviewMu.Unlock()

	if !ok {
		c.sendError("no pending graph review for run_id: " + payload.RunID)
		return
	}

	select {
	case gate.ch <- &payload.Response:
	default:
	}
}
