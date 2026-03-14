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

	var fullContent strings.Builder
	var fullReasoning strings.Builder
	var promptTokens, completionTokens int

	scanner := bufio.NewScanner(respBody)
	scanner.Buffer(make([]byte, 0, 256*1024), 1024*1024) // 1MB line buffer for long SSE events
	for scanner.Scan() {
		line := scanner.Text()

		// SSE format: "data: {...}" or "data: [DONE]"
		if !strings.HasPrefix(line, "data: ") {
			continue
		}
		payload := strings.TrimPrefix(line, "data: ")
		if payload == "[DONE]" {
			break
		}

		var delta streamDelta
		if err := json.Unmarshal([]byte(payload), &delta); err != nil {
			continue // skip malformed chunks
		}

		if len(delta.Choices) > 0 {
			d := delta.Choices[0].Delta
			// Capture content (the final answer)
			if d.Content != nil && *d.Content != "" {
				fullContent.WriteString(*d.Content)
				if onChunk != nil {
					onChunk(*d.Content)
				}
			}
			// Also capture reasoning_content (DeepSeek reasoner's chain-of-thought)
			if d.ReasoningContent != nil && *d.ReasoningContent != "" {
				fullReasoning.WriteString(*d.ReasoningContent)
			}
		}

		if delta.Usage != nil {
			promptTokens = delta.Usage.PromptTokens
			completionTokens = delta.Usage.CompletionTokens
		}
	}

	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("llm stream read: %w", err)
	}

	// If content is empty but reasoning has data, use reasoning as fallback
	// (DeepSeek reasoner sometimes puts the answer in reasoning_content)
	content := fullContent.String()
	if content == "" && fullReasoning.Len() > 0 {
		content = fullReasoning.String()
	}

	return &ChatResponse{
		Content:          content,
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
