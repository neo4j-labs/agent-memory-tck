package memory

import (
	"context"
	"fmt"
)

// Client is the root entry point for all memory operations. It is goroutine-safe.
type Client struct {
	// ShortTerm provides conversational memory operations.
	ShortTerm *ShortTermMemory

	// LongTerm provides entity / preference / fact / graph operations.
	LongTerm *LongTermMemory

	// Reasoning provides trace / step / tool call / provenance operations.
	Reasoning *ReasoningMemory

	// Query is the read-only Cypher console (hosted service only).
	Query *QueryConsole

	// Auth manages API keys and OAuth refresh (hosted service only).
	Auth *AuthClient

	transport Transport
}

// New creates a new memory Client with the given options.
func New(opts ...Option) (*Client, error) {
	cfg := Config{}
	for _, o := range opts {
		o(&cfg)
	}

	if cfg.Endpoint == "" && cfg.CustomTransport == nil {
		return nil, &MemoryError{Message: "endpoint is required"}
	}

	var t Transport
	if cfg.CustomTransport != nil {
		t = cfg.CustomTransport
	} else {
		t = newTransport(cfg)
	}

	return &Client{
		ShortTerm: &ShortTermMemory{transport: t},
		LongTerm:  &LongTermMemory{transport: t},
		Reasoning: &ReasoningMemory{transport: t},
		Query:     &QueryConsole{transport: t},
		Auth:      &AuthClient{transport: t},
		transport: t,
	}, nil
}

// Connect validates the connection to the backend.
func (c *Client) Connect(ctx context.Context) error {
	if err := c.transport.Connect(ctx); err != nil {
		return fmt.Errorf("connect: %w", err)
	}
	return nil
}

// Close releases any resources held by the client.
func (c *Client) Close(ctx context.Context) error {
	return c.transport.Close(ctx)
}

// ClearAllData deletes all data. Used for test isolation.
func (c *Client) ClearAllData(ctx context.Context) error {
	return c.transport.Call(ctx, "clear_all_data", nil, nil)
}
