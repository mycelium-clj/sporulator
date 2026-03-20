package llm

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// Client talks to an OpenAI-compatible chat completions API.
// Works with DeepSeek, OpenAI, OpenRouter, and other compatible providers.
type Client struct {
	baseURL    string
	apiKey     string
	model      string
	httpClient *http.Client
}

// Message is a chat message with a role and content.
type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

// ChatRequest configures a chat completions call.
type ChatRequest struct {
	Messages    []Message
	Temperature float64
	MaxTokens   int
}

// ChatResponse is the result of a non-streaming chat call.
type ChatResponse struct {
	Content          string
	FinishReason     string // "stop", "length", etc.
	PromptTokens     int
	CompletionTokens int
}

// NewClient creates a client for an OpenAI-compatible API.
// baseURL should be the API base (e.g. "https://api.deepseek.com").
func NewClient(baseURL, apiKey, model string) *Client {
	return &Client{
		baseURL:    strings.TrimRight(baseURL, "/"),
		apiKey:     apiKey,
		model:      model,
		httpClient: &http.Client{Timeout: 10 * time.Minute},
	}
}

// apiRequest is the JSON body sent to the completions endpoint.
type apiRequest struct {
	Model       string    `json:"model"`
	Messages    []Message `json:"messages"`
	Temperature float64   `json:"temperature"`
	MaxTokens   int       `json:"max_tokens"`
	Stream      bool      `json:"stream"`
}

// apiResponse is the JSON body returned from a non-streaming call.
type apiResponse struct {
	Choices []struct {
		Message struct {
			Content string `json:"content"`
		} `json:"message"`
	} `json:"choices"`
	Usage struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage"`
}

// Chat sends a non-streaming chat completions request.
func (c *Client) Chat(ctx context.Context, req *ChatRequest) (*ChatResponse, error) {
	body := apiRequest{
		Model:       c.model,
		Messages:    req.Messages,
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
		Stream:      false,
	}

	respBody, err := c.doRequest(ctx, body)
	if err != nil {
		return nil, err
	}
	defer respBody.Close()

	data, err := io.ReadAll(respBody)
	if err != nil {
		return nil, fmt.Errorf("llm read response: %w", err)
	}

	var apiResp apiResponse
	if err := json.Unmarshal(data, &apiResp); err != nil {
		return nil, fmt.Errorf("llm parse response: %w (body: %s)", err, truncate(string(data), 200))
	}

	if len(apiResp.Choices) == 0 {
		return nil, fmt.Errorf("llm: no choices in response")
	}

	return &ChatResponse{
		Content:          apiResp.Choices[0].Message.Content,
		PromptTokens:     apiResp.Usage.PromptTokens,
		CompletionTokens: apiResp.Usage.CompletionTokens,
	}, nil
}

// streamDelta is a single SSE chunk from a streaming response.
// Supports both standard OpenAI format and DeepSeek reasoner
// (which uses reasoning_content for chain-of-thought, then content for final answer).
type streamDelta struct {
	Choices []struct {
		Delta struct {
			Content          *string `json:"content"`
			ReasoningContent *string `json:"reasoning_content"`
		} `json:"delta"`
		FinishReason *string `json:"finish_reason"`
	} `json:"choices"`
	Usage *struct {
		PromptTokens     int `json:"prompt_tokens"`
		CompletionTokens int `json:"completion_tokens"`
	} `json:"usage"`
}

// ChatStream sends a streaming chat completions request.
// onChunk is called with each content fragment as it arrives.
// Returns the complete response after the stream ends.
// Includes a 2-minute idle timeout — if no data arrives for 2 minutes, the stream is cancelled.
func (c *Client) ChatStream(ctx context.Context, req *ChatRequest, onChunk func(string)) (*ChatResponse, error) {
	body := apiRequest{
		Model:       c.model,
		Messages:    req.Messages,
		Temperature: req.Temperature,
		MaxTokens:   req.MaxTokens,
		Stream:      true,
	}

	respBody, err := c.doRequest(ctx, body)
	if err != nil {
		return nil, err
	}
	defer respBody.Close()

	// Create a context with idle timeout — cancels if no chunk arrives for 2 minutes
	idleTimeout := 2 * time.Minute
	idleCtx, idleCancel := context.WithCancel(ctx)
	defer idleCancel()
	idleTimer := time.AfterFunc(idleTimeout, idleCancel)
	defer idleTimer.Stop()

	var fullContent strings.Builder
	var fullReasoning strings.Builder
	var promptTokens, completionTokens int
	var finishReason string

	scanner := bufio.NewScanner(respBody)
	scanner.Buffer(make([]byte, 0, 256*1024), 1024*1024) // 1MB line buffer for long SSE events

	// Wrap scanner in a channel so we can select on idle timeout
	lines := make(chan string)
	scanErr := make(chan error, 1)
	go func() {
		defer close(lines)
		for scanner.Scan() {
			lines <- scanner.Text()
		}
		if err := scanner.Err(); err != nil {
			scanErr <- err
		}
	}()

loop:
	for {
		select {
		case line, ok := <-lines:
			if !ok {
				break loop
			}
			// Reset idle timer on each received line
			idleTimer.Reset(idleTimeout)

			// SSE format: "data: {...}" or "data: [DONE]"
			if !strings.HasPrefix(line, "data: ") {
				continue
			}
			payload := strings.TrimPrefix(line, "data: ")
			if payload == "[DONE]" {
				break loop
			}

			var delta streamDelta
			if err := json.Unmarshal([]byte(payload), &delta); err != nil {
				continue // skip malformed chunks
			}

			if len(delta.Choices) > 0 {
				d := delta.Choices[0].Delta
				if d.Content != nil && *d.Content != "" {
					fullContent.WriteString(*d.Content)
					if onChunk != nil {
						onChunk(*d.Content)
					}
				}
				if d.ReasoningContent != nil && *d.ReasoningContent != "" {
					fullReasoning.WriteString(*d.ReasoningContent)
				}
				if delta.Choices[0].FinishReason != nil {
					finishReason = *delta.Choices[0].FinishReason
				}
			}

			if delta.Usage != nil {
				promptTokens = delta.Usage.PromptTokens
				completionTokens = delta.Usage.CompletionTokens
			}

		case <-idleCtx.Done():
			if ctx.Err() != nil {
				return nil, fmt.Errorf("llm stream cancelled: %w", ctx.Err())
			}
			return nil, fmt.Errorf("llm stream idle timeout: no data received for %v", idleTimeout)
		}
	}

	// Check for scanner errors
	select {
	case err := <-scanErr:
		if err != nil {
			return nil, fmt.Errorf("llm stream read: %w", err)
		}
	default:
	}

	// If content is empty but reasoning has data, use reasoning as fallback
	// (DeepSeek reasoner sometimes puts the answer in reasoning_content)
	content := fullContent.String()
	if content == "" && fullReasoning.Len() > 0 {
		content = fullReasoning.String()
	}

	return &ChatResponse{
		Content:          content,
		FinishReason:     finishReason,
		PromptTokens:     promptTokens,
		CompletionTokens: completionTokens,
	}, nil
}

func (c *Client) doRequest(ctx context.Context, body apiRequest) (io.ReadCloser, error) {
	jsonBody, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("llm marshal: %w", err)
	}

	url := c.baseURL + "/v1/chat/completions"
	httpReq, err := http.NewRequestWithContext(ctx, "POST", url, bytes.NewReader(jsonBody))
	if err != nil {
		return nil, fmt.Errorf("llm request: %w", err)
	}

	httpReq.Header.Set("Content-Type", "application/json")
	httpReq.Header.Set("Authorization", "Bearer "+c.apiKey)

	resp, err := c.httpClient.Do(httpReq)
	if err != nil {
		return nil, fmt.Errorf("llm http: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		return nil, fmt.Errorf("llm api error %d: %s", resp.StatusCode, truncate(string(data), 300))
	}

	return resp.Body, nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "..."
}
