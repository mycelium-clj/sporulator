package agents

import (
	"context"
	"fmt"

	"github.com/mycelium-clj/sporulator/pkg/llm"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// GraphAgent manages a long-lived conversation about workflow design.
// It produces and modifies manifests but does not implement cells.
type GraphAgent struct {
	session *llm.Session
	client  *llm.Client
	store   *store.Store
}

// Chat sends a message to the graph agent and returns the response.
func (g *GraphAgent) Chat(ctx context.Context, message string) (string, error) {
	return g.session.Send(ctx, g.client, message)
}

// ChatStream sends a message and streams the response token by token.
func (g *GraphAgent) ChatStream(ctx context.Context, message string, onChunk func(string)) (string, error) {
	return g.session.SendStream(ctx, g.client, message, onChunk)
}

// ChatWithManifest sends a message with the current manifest as context.
// Useful when resuming a session or when the manifest has been modified externally.
func (g *GraphAgent) ChatWithManifest(ctx context.Context, manifestID, message string) (string, error) {
	manifest, err := g.store.GetLatestManifest(manifestID)
	if err != nil {
		return "", fmt.Errorf("load manifest: %w", err)
	}

	prompt := message
	if manifest != nil {
		prompt = fmt.Sprintf("Current manifest:\n```edn\n%s\n```\n\n%s", manifest.Body, message)
	}

	return g.session.Send(ctx, g.client, prompt)
}

// SaveManifest extracts a manifest from the agent's last response and saves it.
// Returns the version number, or 0 if no manifest was found in the response.
func (g *GraphAgent) SaveManifest(manifestID, response, createdBy string) (int, error) {
	body := ExtractFirstCodeBlock(response)
	if body == "" {
		return 0, nil
	}

	version, err := g.store.SaveManifest(&store.Manifest{
		ID:        manifestID,
		Body:      body,
		CreatedBy: createdBy,
	})
	if err != nil {
		return 0, fmt.Errorf("save manifest: %w", err)
	}
	return version, nil
}

// History returns the conversation history.
func (g *GraphAgent) History() []llm.Message {
	return g.session.History()
}

// Reset clears the conversation history.
func (g *GraphAgent) Reset() {
	g.session.Reset()
}
