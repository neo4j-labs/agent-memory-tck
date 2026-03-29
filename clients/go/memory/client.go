package memory

import (
	"context"
	"fmt"
)

// Client is the root entry point for all memory operations.
// It is goroutine-safe.
type Client struct {
	// ShortTerm provides conversational memory operations (Bronze tier).
	ShortTerm *ShortTermMemory

	// LongTerm provides entity, preference, and fact operations (Silver tier).
	LongTerm *LongTermMemory

	// Reasoning provides trace, step, and tool call operations (Silver tier).
	Reasoning *ReasoningMemory

	transport *transport
}

// New creates a new memory Client with the given options.
func New(opts ...Option) (*Client, error) {
	cfg := Config{}
	for _, o := range opts {
		o(&cfg)
	}

	if cfg.Endpoint == "" {
		return nil, &MemoryError{Message: "endpoint is required"}
	}

	t := newTransport(cfg)

	return &Client{
		ShortTerm: &ShortTermMemory{transport: t},
		LongTerm:  &LongTermMemory{transport: t},
		Reasoning: &ReasoningMemory{transport: t},
		transport: t,
	}, nil
}

// Connect validates the connection to the backend.
func (c *Client) Connect(ctx context.Context) error {
	var result map[string]interface{}
	if err := c.transport.call(ctx, "setup", nil, &result); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	return nil
}

// Close releases any resources held by the client.
func (c *Client) Close(_ context.Context) error {
	// HTTP is stateless; nothing to close.
	return nil
}

// ClearAllData deletes all data. Used for test isolation.
func (c *Client) ClearAllData(ctx context.Context) error {
	return c.transport.call(ctx, "clear_all_data", nil, nil)
}
