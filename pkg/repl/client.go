package repl

import (
	"bufio"
	"bytes"
	"fmt"
	"net"
	"sync"

	bencode "github.com/jackpal/bencode-go"
	"github.com/google/uuid"
)

// Message represents an nREPL message (request or response).
type Message map[string]any

// GetString returns the string value for a key, or empty string if absent/wrong type.
func (m Message) GetString(key string) string {
	if v, ok := m[key]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}

// GetStringList returns a string slice for a key whose value is a bencode list of strings.
func (m Message) GetStringList(key string) []string {
	v, ok := m[key]
	if !ok {
		return nil
	}
	switch list := v.(type) {
	case []any:
		out := make([]string, 0, len(list))
		for _, item := range list {
			if s, ok := item.(string); ok {
				out = append(out, s)
			}
		}
		return out
	case []string:
		return list
	default:
		return nil
	}
}

// HasStatus checks if the message status list contains a specific value.
func (m Message) HasStatus(status string) bool {
	for _, s := range m.GetStringList("status") {
		if s == status {
			return true
		}
	}
	return false
}

// pendingOp tracks a request awaiting responses.
type pendingOp struct {
	ch chan Message
}

// Client is an nREPL client connected to a running nREPL server.
type Client struct {
	conn    net.Conn
	reader  *bufio.Reader
	writeMu sync.Mutex

	mu      sync.Mutex
	pending map[string]*pendingOp
	readErr error

	Sessions []string
}

// Connect establishes a TCP connection to an nREPL server at host:port.
func Connect(host string, port int) (*Client, error) {
	addr := net.JoinHostPort(host, fmt.Sprintf("%d", port))
	conn, err := net.Dial("tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("nrepl connect %s: %w", addr, err)
	}

	c := &Client{
		conn:    conn,
		reader:  bufio.NewReaderSize(conn, 64*1024),
		pending: make(map[string]*pendingOp),
	}

	go c.readLoop()
	return c, nil
}

// Close shuts down the connection. Any blocked Send calls will return an error.
func (c *Client) Close() error {
	return c.conn.Close()
}

// readLoop reads bencode messages from the connection and dispatches them
// to pending request channels by message ID. Runs until the connection closes.
func (c *Client) readLoop() {
	for {
		decoded, err := bencode.Decode(c.reader)
		if err != nil {
			c.mu.Lock()
			c.readErr = err
			for _, op := range c.pending {
				close(op.ch)
			}
			c.pending = make(map[string]*pendingOp)
			c.mu.Unlock()
			return
		}

		msg := toMessage(decoded)
		id := msg.GetString("id")
		if id == "" {
			continue
		}

		c.mu.Lock()
		op, ok := c.pending[id]
		c.mu.Unlock()

		if ok {
			op.ch <- msg
		}
	}
}

// toMessage converts a bencode-decoded value to a Message.
func toMessage(v any) Message {
	if m, ok := v.(map[string]any); ok {
		return Message(m)
	}
	return Message{}
}

// Send sends an nREPL message and collects all response messages until
// the server signals completion (status contains "done"). Blocks until complete.
func (c *Client) Send(msg Message) ([]Message, error) {
	// Assign an ID if missing
	id := msg.GetString("id")
	if id == "" {
		id = uuid.New().String()
		msg["id"] = id
	}

	// Strip nil values (bencode can't encode nil)
	cleaned := make(map[string]any, len(msg))
	for k, v := range msg {
		if v != nil {
			cleaned[k] = v
		}
	}

	// Register response channel before sending to avoid races
	op := &pendingOp{ch: make(chan Message, 32)}
	c.mu.Lock()
	if c.readErr != nil {
		err := c.readErr
		c.mu.Unlock()
		return nil, fmt.Errorf("nrepl connection closed: %w", err)
	}
	c.pending[id] = op
	c.mu.Unlock()

	defer func() {
		c.mu.Lock()
		delete(c.pending, id)
		c.mu.Unlock()
	}()

	// Encode and write
	var buf bytes.Buffer
	if err := bencode.Marshal(&buf, cleaned); err != nil {
		return nil, fmt.Errorf("nrepl encode: %w", err)
	}

	c.writeMu.Lock()
	_, writeErr := c.conn.Write(buf.Bytes())
	c.writeMu.Unlock()
	if writeErr != nil {
		return nil, fmt.Errorf("nrepl write: %w", writeErr)
	}

	// Collect responses until "done" status
	var messages []Message
	for m := range op.ch {
		messages = append(messages, m)
		if m.HasStatus("done") {
			return messages, nil
		}
	}

	// Channel was closed — connection died
	c.mu.Lock()
	err := c.readErr
	c.mu.Unlock()
	if err != nil {
		return messages, fmt.Errorf("nrepl connection lost: %w", err)
	}
	return messages, fmt.Errorf("nrepl: response stream ended unexpectedly")
}
