package repl

import "fmt"

// Eval evaluates Clojure code on the nREPL server.
// Options can specify namespace, session, and eval function.
func (c *Client) Eval(code string, opts ...EvalOption) ([]Message, error) {
	msg := Message{"op": "eval", "code": code}
	for _, opt := range opts {
		opt(msg)
	}
	return c.Send(msg)
}

// EvalOption configures an Eval call.
type EvalOption func(Message)

// WithNs sets the namespace for evaluation.
func WithNs(ns string) EvalOption {
	return func(m Message) { m["ns"] = ns }
}

// WithSession sets the session for evaluation.
func WithSession(session string) EvalOption {
	return func(m Message) { m["session"] = session }
}

// Clone creates a new nREPL session. If session is empty, clones the default session.
// Returns the new session ID.
func (c *Client) Clone(session string) (string, error) {
	msg := Message{"op": "clone"}
	if session != "" {
		msg["session"] = session
	}
	msgs, err := c.Send(msg)
	if err != nil {
		return "", err
	}
	for _, m := range msgs {
		if newSess := m.GetString("new-session"); newSess != "" {
			c.Sessions = append(c.Sessions, newSess)
			return newSess, nil
		}
	}
	return "", fmt.Errorf("nrepl clone: no new-session in response")
}

// CloseSession closes an nREPL session.
func (c *Client) CloseSession(session string) error {
	msg := Message{"op": "close"}
	if session != "" {
		msg["session"] = session
	}
	msgs, err := c.Send(msg)
	if err != nil {
		return err
	}
	for _, m := range msgs {
		if m.HasStatus("session-closed") {
			filtered := c.Sessions[:0]
			for _, s := range c.Sessions {
				if s != session {
					filtered = append(filtered, s)
				}
			}
			c.Sessions = filtered
			return nil
		}
	}
	return fmt.Errorf("nrepl close: session %s not confirmed closed (response: %v)", session, msgs)
}

// Describe returns information about the nREPL server's capabilities.
func (c *Client) Describe(verbose bool) ([]Message, error) {
	msg := Message{"op": "describe"}
	if verbose {
		msg["verbose?"] = "true"
	}
	return c.Send(msg)
}

// LoadFile loads Clojure source code as if it were a file.
func (c *Client) LoadFile(content, fileName, filePath string) ([]Message, error) {
	msg := Message{
		"op":        "load-file",
		"file":      content,
		"file-name": fileName,
		"file-path": filePath,
	}
	return c.Send(msg)
}

// LsSessions lists all active sessions on the nREPL server.
// Also updates the client's Sessions field.
func (c *Client) LsSessions() ([]string, error) {
	msgs, err := c.Send(Message{"op": "ls-sessions"})
	if err != nil {
		return nil, err
	}
	for _, m := range msgs {
		if sessions := m.GetStringList("sessions"); sessions != nil {
			c.Sessions = sessions
			return sessions, nil
		}
	}
	return nil, fmt.Errorf("nrepl ls-sessions: no sessions in response")
}

// Interrupt attempts to interrupt a running evaluation.
func (c *Client) Interrupt(session, id string) ([]Message, error) {
	msg := Message{"op": "interrupt"}
	if session != "" {
		msg["session"] = session
	}
	if id != "" {
		msg["interrupt-id"] = id
	}
	return c.Send(msg)
}

// Stdin sends input to the nREPL server's stdin.
func (c *Client) Stdin(text string) ([]Message, error) {
	return c.Send(Message{"op": "stdin", "stdin": text})
}

// EvalResult extracts the evaluated value and any stdout/stderr from response messages.
type EvalResult struct {
	Value string   // The return value (printed representation)
	Out   string   // Accumulated stdout
	Err   string   // Accumulated stderr
	Ex    string   // Exception class, if any
	Msgs  []Message // All raw messages
}

// EvalCollect evaluates code and collects results into a structured EvalResult.
func (c *Client) EvalCollect(code string, opts ...EvalOption) (*EvalResult, error) {
	msgs, err := c.Eval(code, opts...)
	if err != nil {
		return nil, err
	}

	result := &EvalResult{Msgs: msgs}
	for _, m := range msgs {
		if v := m.GetString("value"); v != "" {
			result.Value = v
		}
		if v := m.GetString("out"); v != "" {
			result.Out += v
		}
		if v := m.GetString("err"); v != "" {
			result.Err += v
		}
		if v := m.GetString("ex"); v != "" {
			result.Ex = v
		}
	}
	return result, nil
}
