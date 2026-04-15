package memory

import "time"

// Config holds the configuration for a memory Client.
type Config struct {
	// Endpoint is the URL of the NAMS or compatible HTTP service.
	Endpoint string

	// APIKey for authentication with the hosted service.
	APIKey string

	// Timeout for HTTP requests. Default: 30s.
	Timeout time.Duration

	// Namespace for multi-agent shared entity namespace.
	Namespace string
}

// Option configures a Client.
type Option func(*Config)

// WithEndpoint sets the HTTP endpoint URL.
func WithEndpoint(url string) Option {
	return func(c *Config) { c.Endpoint = url }
}

// WithAPIKey sets the authentication API key.
func WithAPIKey(key string) Option {
	return func(c *Config) { c.APIKey = key }
}

// WithTimeout sets the HTTP request timeout.
func WithTimeout(d time.Duration) Option {
	return func(c *Config) { c.Timeout = d }
}

// WithNamespace sets the shared entity namespace.
func WithNamespace(ns string) Option {
	return func(c *Config) { c.Namespace = ns }
}
