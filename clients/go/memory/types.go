// Package memory provides a Go client for neo4j-agent-memory.
//
// Two transports ship in-box:
//
//   - Bridge — the TCK bridge protocol used by conformance servers
//     (POST {endpoint}/{snake_case_method}).
//   - REST — the hosted service at https://memory.neo4jlabs.com/v1.
//
// All types use snake_case JSON tags so they round-trip through both
// transports unchanged (the REST transport translates camelCase ↔ snake_case
// at the wire layer).
package memory

import (
	"strings"
	"time"
)

// FlexTime is a time.Time that parses ISO 8601 timestamps with or without
// timezone, and tolerates JSON null.
type FlexTime struct {
	time.Time
}

func (ft *FlexTime) UnmarshalJSON(data []byte) error {
	s := strings.Trim(string(data), `"`)
	if s == "null" || s == "" {
		return nil
	}
	t, err := time.Parse(time.RFC3339, s)
	if err == nil {
		ft.Time = t
		return nil
	}
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
	ID             string                 `json:"id"`
	Role           MessageRole            `json:"role"`
	Content        string                 `json:"content"`
	Timestamp      FlexTime               `json:"timestamp"`
	Embedding      []float64              `json:"embedding,omitempty"`
	Metadata       map[string]interface{} `json:"metadata,omitempty"`
	ConversationID string                 `json:"conversation_id,omitempty"`
}

// Conversation represents a conversation session containing messages.
type Conversation struct {
	ID          string                 `json:"id"`
	SessionID   string                 `json:"session_id"`
	Messages    []Message              `json:"messages"`
	Title       string                 `json:"title,omitempty"`
	CreatedAt   FlexTime               `json:"created_at"`
	UpdatedAt   string                 `json:"updated_at,omitempty"`
	WorkspaceID string                 `json:"workspace_id,omitempty"`
	UserID      string                 `json:"user_id,omitempty"`
	Metadata    map[string]interface{} `json:"metadata,omitempty"`
}

// SessionInfo provides summary information about a session.
type SessionInfo struct {
	SessionID    string   `json:"session_id"`
	MessageCount int      `json:"message_count"`
	CreatedAt    FlexTime `json:"created_at"`
	UpdatedAt    string   `json:"updated_at,omitempty"`
}

// Observation is an auto-generated message-window summary.
type Observation struct {
	ID             string   `json:"id"`
	ConversationID string   `json:"conversation_id"`
	Content        string   `json:"content"`
	WindowStart    string   `json:"window_start,omitempty"`
	WindowEnd      string   `json:"window_end,omitempty"`
	CreatedAt      FlexTime `json:"created_at"`
}

// Reflection is a higher-level insight derived from observations.
type Reflection struct {
	ID             string   `json:"id"`
	ConversationID string   `json:"conversation_id"`
	Content        string   `json:"content"`
	CreatedAt      FlexTime `json:"created_at"`
}

// ConversationContext is the three-tier context window the hosted service
// returns from GET /v1/conversations/{id}/context.
type ConversationContext struct {
	Reflections    []Reflection  `json:"reflections"`
	Observations   []Observation `json:"observations"`
	RecentMessages []Message     `json:"recent_messages"`
}

// EntityRelationshipRef is a relationship returned alongside an entity.
type EntityRelationshipRef struct {
	ID         string                 `json:"id"`
	Type       string                 `json:"type"`
	TargetID   string                 `json:"target_id"`
	TargetName string                 `json:"target_name,omitempty"`
	Properties map[string]interface{} `json:"properties,omitempty"`
}

// Entity represents a named entity in the knowledge graph.
type Entity[T any] struct {
	ID            string                  `json:"id"`
	Name          string                  `json:"name"`
	Type          string                  `json:"type"`
	Subtype       string                  `json:"subtype,omitempty"`
	Description   string                  `json:"description,omitempty"`
	Embedding     []float64               `json:"embedding,omitempty"`
	CanonicalName string                  `json:"canonical_name,omitempty"`
	CreatedAt     FlexTime                `json:"created_at"`
	UpdatedAt     string                  `json:"updated_at,omitempty"`
	Confidence    *float64                `json:"confidence,omitempty"`
	SourceStage   string                  `json:"source_stage,omitempty"`
	Relationships []EntityRelationshipRef `json:"relationships,omitempty"`
	Extra         T                       `json:"extra,omitempty"`
}

// BaseEntity is the common entity type without extra metadata.
type BaseEntity = Entity[struct{}]

// EntityHistory groups all conversation mentions of one entity.
type EntityHistory struct {
	EntityID string          `json:"entity_id"`
	Mentions []EntityMention `json:"mentions"`
}

// EntityMention links a conversation/message to an entity.
type EntityMention struct {
	ConversationID string `json:"conversation_id"`
	MessageID      string `json:"message_id,omitempty"`
	Content        string `json:"content"`
	Timestamp      string `json:"timestamp"`
}

// EntityGraphNode / EntityGraphEdge / EntityGraph represent the full graph view.
type EntityGraphNode struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	Type string `json:"type"`
}

type EntityGraphEdge struct {
	ID     string `json:"id"`
	Source string `json:"source"`
	Target string `json:"target"`
	Type   string `json:"type"`
}

type EntityGraph struct {
	Nodes []EntityGraphNode `json:"nodes"`
	Edges []EntityGraphEdge `json:"edges"`
}

// EntityFeedbackResult is returned by SetEntityFeedback.
type EntityFeedbackResult struct {
	ID      string `json:"id"`
	Updated bool   `json:"updated"`
}

// EntityMergeResult is returned by MergeEntities.
type EntityMergeResult struct {
	SourceID string `json:"source_id"`
	TargetID string `json:"target_id"`
	Status   string `json:"status"`
}

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

// AgentStep is the hosted-service flat reasoning step (per conversation, no
// trace wrapper).
type AgentStep struct {
	ID             string   `json:"id"`
	ConversationID string   `json:"conversation_id"`
	Reasoning      string   `json:"reasoning"`
	ActionTaken    string   `json:"action_taken"`
	Result         string   `json:"result,omitempty"`
	CreatedAt      FlexTime `json:"created_at"`
}

// AgentStepExplanation is a detailed step view: tool calls + influenced entities.
type AgentStepExplanation struct {
	AgentStep
	ToolCalls          []ToolCall   `json:"tool_calls"`
	InfluencedEntities []BaseEntity `json:"influenced_entities"`
}

// ConversationTrace is the hosted flat trace for one conversation.
type ConversationTrace struct {
	ConversationID string      `json:"conversation_id"`
	Steps          []AgentStep `json:"steps"`
	ToolCalls      []ToolCall  `json:"tool_calls"`
}

// EntityProvenance is the reasoning chain that influenced an entity.
type EntityProvenance struct {
	EntityID string      `json:"entity_id"`
	Steps    []AgentStep `json:"steps"`
}

// CypherResult is the response from POST /v1/query.
type CypherResult struct {
	Columns []string                 `json:"columns"`
	Rows    [][]interface{}          `json:"rows"`
	Stats   map[string]interface{}   `json:"stats,omitempty"`
}

// APIKey represents an API key (with optional plaintext at creation time).
type APIKey struct {
	ID          string   `json:"id"`
	Label       string   `json:"label"`
	Scopes      []string `json:"scopes,omitempty"`
	WorkspaceID string   `json:"workspace_id"`
	CreatedAt   string   `json:"created_at"`
	ExpiresAt   string   `json:"expires_at,omitempty"`
	Key         string   `json:"key,omitempty"`
}

// AccessTokenPair is returned by RefreshAccessToken.
type AccessTokenPair struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	ExpiresIn    int    `json:"expires_in"`
}
