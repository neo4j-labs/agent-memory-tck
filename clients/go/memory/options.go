package memory

import "time"

// Config holds the configuration for a memory Client.
type Config struct {
	// Endpoint is the base URL — bridge endpoint or `https://.../v1` for REST.
	Endpoint string

	// APIKey for Bearer authentication.
	APIKey string

	// TokenProvider returns a fresh access token. Overrides APIKey when set.
	TokenProvider TokenProvider

	// TransportMode picks between bridge / rest. Default is auto.
	TransportMode TransportMode

	// Headers added to every outgoing request.
	Headers map[string]string

	// Timeout for HTTP requests. Default: 30s.
	Timeout time.Duration

	// Namespace for multi-agent shared entity namespacing.
	Namespace string

	// CustomTransport overrides the auto-selected transport entirely.
	CustomTransport Transport
}

// Option configures a Client.
type Option func(*Config)

// WithEndpoint sets the HTTP endpoint URL.
func WithEndpoint(u string) Option {
	return func(c *Config) { c.Endpoint = u }
}

// WithAPIKey sets the authentication API key.
func WithAPIKey(key string) Option {
	return func(c *Config) { c.APIKey = key }
}

// WithTokenProvider supplies a callback that returns a fresh access token.
// Useful when running OAuth refresh-token flows.
func WithTokenProvider(p TokenProvider) Option {
	return func(c *Config) { c.TokenProvider = p }
}

// WithTimeout sets the HTTP request timeout.
func WithTimeout(d time.Duration) Option {
	return func(c *Config) { c.Timeout = d }
}

// WithNamespace sets the shared entity namespace.
func WithNamespace(ns string) Option {
	return func(c *Config) { c.Namespace = ns }
}

// WithTransportMode forces a specific transport.
func WithTransportMode(m TransportMode) Option {
	return func(c *Config) { c.TransportMode = m }
}

// WithHeaders adds custom HTTP headers.
func WithHeaders(h map[string]string) Option {
	return func(c *Config) { c.Headers = h }
}

// WithTransport supplies a custom Transport implementation.
func WithTransport(t Transport) Option {
	return func(c *Config) { c.CustomTransport = t }
}
