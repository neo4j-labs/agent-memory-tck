package memory

import "fmt"

// MemoryError is the base error type for all memory client errors.
type MemoryError struct {
	Message string
	Cause   error
}

func (e *MemoryError) Error() string {
	if e.Cause != nil {
		return fmt.Sprintf("%s: %v", e.Message, e.Cause)
	}
	return e.Message
}

func (e *MemoryError) Unwrap() error { return e.Cause }

// TransportError indicates an HTTP or network-level failure.
type TransportError struct {
	MemoryError
	StatusCode int
}

// ConnectionError indicates failure to connect to the backend.
type ConnectionError struct{ MemoryError }

// AuthenticationError indicates invalid credentials.
type AuthenticationError struct{ MemoryError }
