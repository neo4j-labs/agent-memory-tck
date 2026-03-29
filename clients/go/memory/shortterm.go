package memory

import "context"

// ShortTermMemory provides conversational memory operations.
type ShortTermMemory struct {
	transport *transport
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
	if err := s.transport.call(ctx, "add_message", params, &result); err != nil {
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
	if err := s.transport.call(ctx, "get_conversation", params, &result); err != nil {
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
	if err := s.transport.call(ctx, "search_messages", params, &result); err != nil {
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
	if err := s.transport.call(ctx, "list_sessions", params, &result); err != nil {
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
	if err := s.transport.call(ctx, "delete_message", map[string]interface{}{
		"message_id": messageID,
	}, &result); err != nil {
		return false, err
	}
	return result.Deleted, nil
}

// ClearSession removes all messages for a session.
func (s *ShortTermMemory) ClearSession(ctx context.Context, sessionID string) error {
	return s.transport.call(ctx, "clear_session", map[string]interface{}{
		"session_id": sessionID,
	}, nil)
}
