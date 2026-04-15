// Package memory provides a Go client for neo4j-agent-memory.
//
// It supports connecting to the hosted NAMS service via HTTP
// or directly to a Neo4j instance. All types mirror the TCK data models
// defined in tck/adapters/base_adapter.py.
package memory

import (
	"strings"
	"time"
)

// FlexTime is a time.Time that can parse ISO 8601 timestamps with or without timezone.
type FlexTime struct {
	time.Time
}

func (ft *FlexTime) UnmarshalJSON(data []byte) error {
	s := strings.Trim(string(data), `"`)
	if s == "null" || s == "" {
		return nil
	}
	// Try RFC3339 first (with timezone)
	t, err := time.Parse(time.RFC3339, s)
	if err == nil {
		ft.Time = t
		return nil
	}
	// Try without timezone
	t, err = time.Parse("2006-01-02T15:04:05.999999", s)
	if err == nil {
		ft.Time = t
		return nil
	}
	t, err = time.Parse("2006-01-02T15:04:05", s)
	if err == nil {
		ft.Time = t
		return nil
	}
	return err
}

// MessageRole represents the role of a message sender.
type MessageRole string

const (
	RoleUser      MessageRole = "user"
	RoleAssistant MessageRole = "assistant"
	RoleSystem    MessageRole = "system"
)

// ToolCallStatus represents the status of a tool invocation.
type ToolCallStatus string

const (
	StatusPending   ToolCallStatus = "pending"
	StatusSuccess   ToolCallStatus = "success"
	StatusFailure   ToolCallStatus = "failure"
	StatusError     ToolCallStatus = "error"
	StatusTimeout   ToolCallStatus = "timeout"
	StatusCancelled ToolCallStatus = "cancelled"
)

// Message represents a single message in a conversation.
type Message struct {
	ID        string                 `json:"id"`
	Role      MessageRole            `json:"role"`
	Content   string                 `json:"content"`
	Timestamp FlexTime               `json:"timestamp"`
	Embedding []float64              `json:"embedding,omitempty"`
	Metadata  map[string]interface{} `json:"metadata,omitempty"`
}

// Conversation represents a conversation session containing messages.
type Conversation struct {
	ID        string    `json:"id"`
	SessionID string    `json:"session_id"`
	Messages  []Message `json:"messages"`
	Title     string    `json:"title,omitempty"`
	CreatedAt FlexTime  `json:"created_at"`
	UpdatedAt string    `json:"updated_at,omitempty"`
}

// SessionInfo provides summary information about a session.
type SessionInfo struct {
	SessionID    string   `json:"session_id"`
	MessageCount int      `json:"message_count"`
	CreatedAt    FlexTime `json:"created_at"`
	UpdatedAt    string   `json:"updated_at,omitempty"`
}

// Entity represents a named entity in the knowledge graph.
// The type parameter T allows attaching custom metadata.
type Entity[T any] struct {
	ID            string    `json:"id"`
	Name          string    `json:"name"`
	Type          string    `json:"type"`
	Subtype       string    `json:"subtype,omitempty"`
	Description   string    `json:"description,omitempty"`
	Embedding     []float64 `json:"embedding,omitempty"`
	CanonicalName string    `json:"canonical_name,omitempty"`
	CreatedAt     FlexTime  `json:"created_at"`
	Extra         T         `json:"extra,omitempty"`
}

// BaseEntity is the common entity type without extra metadata.
type BaseEntity = Entity[struct{}]

// Preference represents a user preference.
type Preference struct {
	ID         string    `json:"id"`
	Category   string    `json:"category"`
	Preference string    `json:"preference"`
	Context    string    `json:"context,omitempty"`
	Embedding  []float64 `json:"embedding,omitempty"`
}

// Fact represents a subject-predicate-object fact triple.
type Fact struct {
	ID        string    `json:"id"`
	Subject   string    `json:"subject"`
	Predicate string    `json:"predicate"`
	Object    string    `json:"object"`
	Embedding []float64 `json:"embedding,omitempty"`
}

// Relationship represents a typed relationship between entities.
type Relationship struct {
	ID               string                 `json:"id"`
	SourceID         string                 `json:"source_id"`
	TargetID         string                 `json:"target_id"`
	RelationshipType string                 `json:"relationship_type"`
	Properties       map[string]interface{} `json:"properties,omitempty"`
}

// ReasoningTrace represents a complete reasoning trace for a task.
type ReasoningTrace struct {
	ID          string          `json:"id"`
	SessionID   string          `json:"session_id"`
	Task        string          `json:"task"`
	Steps       []ReasoningStep `json:"steps,omitempty"`
	Outcome     string          `json:"outcome,omitempty"`
	Success     *bool           `json:"success,omitempty"`
	StartedAt   FlexTime        `json:"started_at"`
	CompletedAt *FlexTime       `json:"completed_at,omitempty"`
}

// ReasoningStep represents a step in the agent's reasoning process.
type ReasoningStep struct {
	ID          string     `json:"id"`
	TraceID     string     `json:"trace_id"`
	StepNumber  int        `json:"step_number"`
	Thought     string     `json:"thought,omitempty"`
	Action      string     `json:"action,omitempty"`
	Observation string     `json:"observation,omitempty"`
	ToolCalls   []ToolCall `json:"tool_calls,omitempty"`
}

// ToolCall represents a single tool invocation within a reasoning step.
type ToolCall struct {
	ID         string                 `json:"id"`
	ToolName   string                 `json:"tool_name"`
	Arguments  map[string]interface{} `json:"arguments,omitempty"`
	Result     interface{}            `json:"result,omitempty"`
	Status     ToolCallStatus         `json:"status"`
	DurationMs *int                   `json:"duration_ms,omitempty"`
	Error      string                 `json:"error,omitempty"`
}

// ToolStats provides aggregated statistics for a tool.
type ToolStats struct {
	Name            string   `json:"name"`
	TotalCalls      int      `json:"total_calls"`
	SuccessfulCalls int      `json:"successful_calls"`
	FailedCalls     int      `json:"failed_calls"`
	SuccessRate     float64  `json:"success_rate"`
	AvgDurationMs   *float64 `json:"avg_duration_ms,omitempty"`
}
