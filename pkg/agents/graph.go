package agents

import (
	"context"
	"fmt"
	"strings"

	"github.com/mycelium-clj/sporulator/pkg/bridge"
	"github.com/mycelium-clj/sporulator/pkg/llm"
	"github.com/mycelium-clj/sporulator/pkg/store"
)

// GraphAgent manages a long-lived conversation about workflow design.
// It produces and modifies manifests but does not implement cells.
type GraphAgent struct {
	session        *llm.Session
	client         *llm.Client
	store          *store.Store
	bridgeProvider BridgeProvider // returns current bridge, nil-safe
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

// ChatStreamWithFeedback sends a message, streams the response, and if the response
// contains a manifest, attempts to compile it in the REPL. On compilation error,
// feeds the error back to the LLM for a fix. maxAttempts controls retries (default 3).
func (g *GraphAgent) ChatStreamWithFeedback(ctx context.Context, message string, maxAttempts int, onChunk func(string), onFeedback func(FeedbackEvent)) (string, error) {
	br := g.getBridge()
	if br == nil {
		return g.ChatStream(ctx, message, onChunk)
	}
	if maxAttempts < 1 {
		maxAttempts = 3
	}

	response, err := g.ChatStream(ctx, message, onChunk)
	if err != nil {
		return "", err
	}

	for attempt := 1; attempt <= maxAttempts; attempt++ {
		manifest := ExtractFirstCodeBlock(response)
		if manifest == "" || !looksLikeManifest(manifest) {
			// No manifest in response, just return the text
			return response, nil
		}

		onFeedback(FeedbackEvent{
			Type:    "eval",
			Attempt: attempt,
			Code:    manifest,
			Message: fmt.Sprintf("Compiling manifest in REPL (attempt %d/%d)", attempt, maxAttempts),
		})

		evalResult, evalErr := br.CompileWorkflow(manifest, "")
		if evalErr != nil {
			onFeedback(FeedbackEvent{
				Type:    "error",
				Attempt: attempt,
				Output:  evalErr.Error(),
				Message: "REPL connection error",
			})
			return response, nil // can't fix connection errors
		}

		if !evalResult.IsError() {
			onFeedback(FeedbackEvent{
				Type:    "success",
				Attempt: attempt,
				Code:    manifest,
				Output:  evalResult.Value,
				Message: "Manifest compiled successfully",
			})
			return response, nil
		}

		// Compilation error — feed back to LLM
		errMsg := evalResult.Ex
		if evalResult.Err != "" {
			errMsg += "\n" + evalResult.Err
		}
		onFeedback(FeedbackEvent{
			Type:    "error",
			Attempt: attempt,
			Code:    manifest,
			Output:  errMsg,
			Message: "Manifest compilation error, requesting fix",
		})

		if attempt >= maxAttempts {
			break
		}

		feedback := fmt.Sprintf("The manifest failed to compile with this error:\n\n```\n%s\n```\n\nPlease fix the manifest and return the corrected EDN.", errMsg)
		onFeedback(FeedbackEvent{
			Type:    "fix",
			Attempt: attempt + 1,
			Message: "Requesting fix from LLM",
		})

		response, err = g.ChatStream(ctx, feedback, onChunk)
		if err != nil {
			return "", fmt.Errorf("graph agent fix attempt %d: %w", attempt+1, err)
		}
	}

	return response, nil
}

// looksLikeManifest does a quick check to see if text looks like a Mycelium manifest.
// Requires :id AND at least one of :cells or :pipeline to avoid false positives.
func looksLikeManifest(s string) bool {
	return len(s) > 10 && strings.Contains(s, ":id") &&
		(strings.Contains(s, ":cells") || strings.Contains(s, ":pipeline"))
}

// getBridge returns the current bridge, or nil if no provider or no bridge.
func (g *GraphAgent) getBridge() *bridge.Bridge {
	if g.bridgeProvider == nil {
		return nil
	}
	return g.bridgeProvider()
}

// History returns the conversation history.
func (g *GraphAgent) History() []llm.Message {
	return g.session.History()
}

// Reset clears the conversation history.
func (g *GraphAgent) Reset() {
	g.session.Reset()
}
