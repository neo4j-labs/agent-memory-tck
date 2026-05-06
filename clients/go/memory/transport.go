package memory

import (
	"context"
	"strings"
	"time"
)

// Transport is the wire-protocol abstraction used by the Client.
//
// Two implementations ship in-box:
//
//   - bridgeTransport speaks the TCK bridge protocol
//     (POST {endpoint}/{snake_case_method}, snake_case JSON).
//   - restTransport speaks the hosted REST API at
//     https://memory.neo4jlabs.com/v1 (camelCase JSON, REST topology).
//
// Users may also supply a custom Transport via WithTransport for testing or
// for adapting to a different backend.
type Transport interface {
	Call(ctx context.Context, method string, params map[string]interface{}, result interface{}) error
	Connect(ctx context.Context) error
	Close(ctx context.Context) error
}

// TokenProvider returns a fresh bearer token. Used for OAuth refresh flows.
type TokenProvider func(ctx context.Context) (string, error)

// TransportMode picks between bridge / rest. Default is "auto", which picks
// REST when the endpoint contains a /v1 path component.
type TransportMode string

const (
	TransportAuto   TransportMode = "auto"
	TransportBridge TransportMode = "bridge"
	TransportREST   TransportMode = "rest"
)

func newTransport(cfg Config) Transport {
	timeout := cfg.Timeout
	if timeout == 0 {
		timeout = 30 * time.Second
	}
	mode := cfg.TransportMode
	if mode == "" || mode == TransportAuto {
		if isRestEndpoint(cfg.Endpoint) {
			mode = TransportREST
		} else {
			mode = TransportBridge
		}
	}
	switch mode {
	case TransportREST:
		return newRestTransport(cfg, timeout)
	default:
		return newBridgeTransport(cfg, timeout)
	}
}

func isRestEndpoint(endpoint string) bool {
	for _, segment := range strings.Split(endpoint, "/") {
		if len(segment) >= 2 && segment[0] == 'v' {
			rest := segment[1:]
			allDigits := true
			for _, r := range rest {
				if r < '0' || r > '9' {
					allDigits = false
					break
				}
			}
			if allDigits && len(rest) > 0 {
				return true
			}
		}
	}
	return false
}
