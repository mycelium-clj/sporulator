package llm

import (
	"context"
	"sync"
)

// Session maintains conversation history for multi-turn interactions.
type Session struct {
	ID       string
	system   string
	messages []Message
	mu       sync.Mutex
	sendMu   sync.Mutex // serializes Send/SendStream to prevent interleaving
}

// NewSession creates a session with a system prompt.
func NewSession(id, systemPrompt string) *Session {
	return &Session{
		ID:     id,
		system: systemPrompt,
	}
}

// Messages returns the full message list including the system prompt,
// suitable for passing to a chat completions call.
func (s *Session) Messages() []Message {
	s.mu.Lock()
	defer s.mu.Unlock()

	msgs := make([]Message, 0, len(s.messages)+1)
	if s.system != "" {
		msgs = append(msgs, Message{Role: "system", Content: s.system})
	}
	msgs = append(msgs, s.messages...)
	return msgs
}

// Send sends a user message through the client and appends both the user
// message and the assistant response to the session history.
func (s *Session) Send(ctx context.Context, client *Client, userMessage string, opts ...RequestOption) (string, error) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()

	s.addMessage("user", userMessage)

	reqOpts := defaultOpts()
	for _, opt := range opts {
		opt(&reqOpts)
	}

	resp, err := client.Chat(ctx, &ChatRequest{
		Messages:    s.Messages(),
		Temperature: reqOpts.temperature,
		MaxTokens:   reqOpts.maxTokens,
	})
	if err != nil {
		// Remove the user message on failure so the session stays consistent
		s.popLast()
		return "", err
	}

	s.addMessage("assistant", resp.Content)
	return resp.Content, nil
}

// SendStream sends a user message and streams the response token by token.
// onChunk is called with each content fragment as it arrives.
func (s *Session) SendStream(ctx context.Context, client *Client, userMessage string, onChunk func(string), opts ...RequestOption) (string, error) {
	s.sendMu.Lock()
	defer s.sendMu.Unlock()

	s.addMessage("user", userMessage)

	reqOpts := defaultOpts()
	for _, opt := range opts {
		opt(&reqOpts)
	}

	resp, err := client.ChatStream(ctx, &ChatRequest{
		Messages:    s.Messages(),
		Temperature: reqOpts.temperature,
		MaxTokens:   reqOpts.maxTokens,
	}, onChunk)
	if err != nil {
		s.popLast()
		return "", err
	}

	s.addMessage("assistant", resp.Content)
	return resp.Content, nil
}

// Reset clears the conversation history, keeping the system prompt.
func (s *Session) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.messages = nil
}

// History returns a copy of the conversation messages (excluding system prompt).
func (s *Session) History() []Message {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Message, len(s.messages))
	copy(out, s.messages)
	return out
}

func (s *Session) addMessage(role, content string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.messages = append(s.messages, Message{Role: role, Content: content})
}

func (s *Session) popLast() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.messages) > 0 {
		s.messages = s.messages[:len(s.messages)-1]
	}
}

// RequestOption configures a Send/SendStream call.
type RequestOption func(*requestOpts)

type requestOpts struct {
	temperature float64
	maxTokens   int
}

func defaultOpts() requestOpts {
	return requestOpts{
		temperature: 0.3,
		maxTokens:   4096,
	}
}

// WithTemperature sets the sampling temperature.
func WithTemperature(t float64) RequestOption {
	return func(o *requestOpts) { o.temperature = t }
}

// WithMaxTokens sets the maximum tokens in the response.
func WithMaxTokens(n int) RequestOption {
	return func(o *requestOpts) { o.maxTokens = n }
}
