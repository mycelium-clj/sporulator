package api

import (
	"context"
	"encoding/json"
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
	case "cell_implement":
		go s.handleCellImplement(c, msg)
	case "cell_iterate":
		go s.handleCellIterate(c, msg)
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
