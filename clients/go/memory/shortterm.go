package memory

import "context"

// ShortTermMemory provides conversational memory operations.
type ShortTermMemory struct {
	transport Transport
}

// AddMessage adds a message to a conversation session.
func (s *ShortTermMemory) AddMessage(ctx context.Context, sessionID string, role MessageRole, content string, opts ...func(*AddMessageParams)) (*Message, error) {
	p := AddMessageParams{}
	for _, o := range opts {
		o(&p)
	}

	params := map[string]interface{}{
		"session_id": sessionID,
		"role":       string(role),
		"content":    content,
	}
	if p.metadata != nil {
		params["metadata"] = p.metadata
	}

	var result Message
	if err := s.transport.Call(ctx, "add_message", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type AddMessageParams struct {
	metadata map[string]interface{}
}

// WithMetadata attaches metadata to a message.
func WithMetadata(m map[string]interface{}) func(*AddMessageParams) {
	return func(p *AddMessageParams) { p.metadata = m }
}

// GetConversation retrieves a conversation by session ID.
func (s *ShortTermMemory) GetConversation(ctx context.Context, sessionID string, opts ...func(*GetConversationParams)) (*Conversation, error) {
	p := GetConversationParams{}
	for _, o := range opts {
		o(&p)
	}

	params := map[string]interface{}{
		"session_id": sessionID,
	}
	if p.limit != nil {
		params["limit"] = *p.limit
	}

	var result Conversation
	if err := s.transport.Call(ctx, "get_conversation", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type GetConversationParams struct {
	limit *int
}

// WithLimit sets the maximum number of messages to return.
func WithLimit(n int) func(*GetConversationParams) {
	return func(p *GetConversationParams) { p.limit = &n }
}

// SearchMessages searches messages by semantic similarity.
func (s *ShortTermMemory) SearchMessages(ctx context.Context, query string, opts ...func(*SearchMessagesParams)) ([]Message, error) {
	p := SearchMessagesParams{limit: 10, threshold: 0.7}
	for _, o := range opts {
		o(&p)
	}

	params := map[string]interface{}{
		"query":     query,
		"limit":     p.limit,
		"threshold": p.threshold,
	}
	if p.sessionID != "" {
		params["session_id"] = p.sessionID
	}

	var result []Message
	if err := s.transport.Call(ctx, "search_messages", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []Message{}, nil
	}
	return result, nil
}

type SearchMessagesParams struct {
	sessionID string
	limit     int
	threshold float64
}

// WithSessionID filters search results by session.
func WithSessionID(id string) func(*SearchMessagesParams) {
	return func(p *SearchMessagesParams) { p.sessionID = id }
}

// WithSearchLimit sets the maximum number of search results.
func WithSearchLimit(n int) func(*SearchMessagesParams) {
	return func(p *SearchMessagesParams) { p.limit = n }
}

// WithThreshold sets the similarity threshold for search.
func WithThreshold(t float64) func(*SearchMessagesParams) {
	return func(p *SearchMessagesParams) { p.threshold = t }
}

// ListSessions lists all active sessions.
func (s *ShortTermMemory) ListSessions(ctx context.Context, opts ...func(*ListSessionsParams)) ([]SessionInfo, error) {
	p := ListSessionsParams{limit: 100}
	for _, o := range opts {
		o(&p)
	}

	params := map[string]interface{}{
		"limit": p.limit,
	}

	var result []SessionInfo
	if err := s.transport.Call(ctx, "list_sessions", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []SessionInfo{}, nil
	}
	return result, nil
}

type ListSessionsParams struct {
	limit int
}

// WithListLimit sets the maximum number of sessions to return.
func WithListLimit(n int) func(*ListSessionsParams) {
	return func(p *ListSessionsParams) { p.limit = n }
}

// DeleteMessage deletes a message by ID. Returns true if deleted.
func (s *ShortTermMemory) DeleteMessage(ctx context.Context, messageID string) (bool, error) {
	var result struct {
		Deleted bool `json:"deleted"`
	}
	if err := s.transport.Call(ctx, "delete_message", map[string]interface{}{
		"message_id": messageID,
	}, &result); err != nil {
		return false, err
	}
	return result.Deleted, nil
}

// ClearSession removes all messages for a session.
func (s *ShortTermMemory) ClearSession(ctx context.Context, sessionID string) error {
	return s.transport.Call(ctx, "clear_session", map[string]interface{}{
		"session_id": sessionID,
	}, nil)
}

// ============================================================================
// Volume 5 / hosted-native methods
// ============================================================================

// CreateConversationParams configures CreateConversation.
type CreateConversationParams struct {
	UserID   string
	Metadata map[string]interface{}
}

// CreateConversation creates a new conversation (hosted service).
func (s *ShortTermMemory) CreateConversation(ctx context.Context, p CreateConversationParams) (*Conversation, error) {
	params := map[string]interface{}{
		"user_id": p.UserID,
	}
	if p.Metadata != nil {
		params["metadata"] = p.Metadata
	}
	var result Conversation
	if err := s.transport.Call(ctx, "create_conversation", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// ListConversations lists conversations the API key has access to.
func (s *ShortTermMemory) ListConversations(ctx context.Context, limit int) ([]Conversation, error) {
	params := map[string]interface{}{}
	if limit > 0 {
		params["limit"] = limit
	}
	var result []Conversation
	if err := s.transport.Call(ctx, "list_conversations", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []Conversation{}, nil
	}
	return result, nil
}

// GetConversationMetadata fetches conversation metadata (no messages).
func (s *ShortTermMemory) GetConversationMetadata(ctx context.Context, conversationID string) (*Conversation, error) {
	var result Conversation
	if err := s.transport.Call(ctx, "get_conversation_metadata", map[string]interface{}{
		"conversation_id": conversationID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// DeleteConversation deletes a conversation and all its messages.
func (s *ShortTermMemory) DeleteConversation(ctx context.Context, conversationID string) error {
	return s.transport.Call(ctx, "delete_conversation", map[string]interface{}{
		"conversation_id": conversationID,
	}, nil)
}

// GetContext returns the three-tier context (reflections + observations +
// recent messages) for a conversation.
func (s *ShortTermMemory) GetContext(ctx context.Context, conversationID string) (*ConversationContext, error) {
	var result ConversationContext
	if err := s.transport.Call(ctx, "get_context", map[string]interface{}{
		"conversation_id": conversationID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// BulkMessageInput is one message inside a bulk add.
type BulkMessageInput struct {
	Role     MessageRole            `json:"role"`
	Content  string                 `json:"content"`
	Metadata map[string]interface{} `json:"metadata,omitempty"`
}

// BulkAddMessages bulk-adds up to 100 messages in one request.
func (s *ShortTermMemory) BulkAddMessages(ctx context.Context, conversationID string, messages []BulkMessageInput) ([]Message, error) {
	if len(messages) > 100 {
		return nil, &MemoryError{Message: "BulkAddMessages accepts max 100 messages"}
	}
	var result []Message
	if err := s.transport.Call(ctx, "bulk_add_messages", map[string]interface{}{
		"conversation_id": conversationID,
		"messages":        messages,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// GetObservations returns the auto-generated observation summaries.
func (s *ShortTermMemory) GetObservations(ctx context.Context, conversationID string, limit int) ([]Observation, error) {
	params := map[string]interface{}{
		"conversation_id": conversationID,
	}
	if limit > 0 {
		params["limit"] = limit
	}
	var result []Observation
	if err := s.transport.Call(ctx, "get_observations", params, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// GetReflections returns the higher-level reflections for a conversation.
func (s *ShortTermMemory) GetReflections(ctx context.Context, conversationID string) ([]Reflection, error) {
	var result []Reflection
	if err := s.transport.Call(ctx, "get_reflections", map[string]interface{}{
		"conversation_id": conversationID,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}
