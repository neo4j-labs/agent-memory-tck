package memory

import "context"

// ReasoningMemory provides trace, step, and tool call operations.
type ReasoningMemory struct {
	transport Transport
}

// StartTrace begins a new reasoning trace for a task.
func (r *ReasoningMemory) StartTrace(ctx context.Context, sessionID, task string) (*ReasoningTrace, error) {
	var result ReasoningTrace
	if err := r.transport.Call(ctx, "start_trace", map[string]interface{}{
		"session_id": sessionID,
		"task":       task,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// AddStep adds a reasoning step to a trace.
func (r *ReasoningMemory) AddStep(ctx context.Context, traceID string, opts ...func(*AddStepParams)) (*ReasoningStep, error) {
	p := AddStepParams{}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"trace_id": traceID,
	}
	if p.thought != "" {
		params["thought"] = p.thought
	}
	if p.action != "" {
		params["action"] = p.action
	}
	if p.observation != "" {
		params["observation"] = p.observation
	}
	var result ReasoningStep
	if err := r.transport.Call(ctx, "add_step", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type AddStepParams struct {
	thought     string
	action      string
	observation string
}

// WithThought sets the reasoning step thought.
func WithThought(t string) func(*AddStepParams) {
	return func(p *AddStepParams) { p.thought = t }
}

// WithAction sets the reasoning step action.
func WithAction(a string) func(*AddStepParams) {
	return func(p *AddStepParams) { p.action = a }
}

// WithObservation sets the reasoning step observation.
func WithObservation(o string) func(*AddStepParams) {
	return func(p *AddStepParams) { p.observation = o }
}

// RecordToolCall records a tool call within a reasoning step.
func (r *ReasoningMemory) RecordToolCall(ctx context.Context, stepID, toolName string, arguments map[string]interface{}, opts ...func(*RecordToolCallParams)) (*ToolCall, error) {
	p := RecordToolCallParams{status: StatusSuccess}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"step_id":   stepID,
		"tool_name": toolName,
		"arguments": arguments,
		"status":    string(p.status),
	}
	if p.result != nil {
		params["result"] = p.result
	}
	if p.durationMs != nil {
		params["duration_ms"] = *p.durationMs
	}
	if p.errMsg != "" {
		params["error"] = p.errMsg
	}
	var result ToolCall
	if err := r.transport.Call(ctx, "record_tool_call", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type RecordToolCallParams struct {
	result     interface{}
	status     ToolCallStatus
	durationMs *int
	errMsg     string
}

// WithResult sets the tool call result.
func WithResult(r interface{}) func(*RecordToolCallParams) {
	return func(p *RecordToolCallParams) { p.result = r }
}

// WithStatus sets the tool call status.
func WithStatus(s ToolCallStatus) func(*RecordToolCallParams) {
	return func(p *RecordToolCallParams) { p.status = s }
}

// WithDurationMs sets the tool call duration.
func WithDurationMs(ms int) func(*RecordToolCallParams) {
	return func(p *RecordToolCallParams) { p.durationMs = &ms }
}

// WithError sets the tool call error message.
func WithError(e string) func(*RecordToolCallParams) {
	return func(p *RecordToolCallParams) { p.errMsg = e }
}

// CompleteTrace completes a reasoning trace with outcome.
func (r *ReasoningMemory) CompleteTrace(ctx context.Context, traceID string, opts ...func(*CompleteTraceParams)) (*ReasoningTrace, error) {
	p := CompleteTraceParams{}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"trace_id": traceID,
	}
	if p.outcome != "" {
		params["outcome"] = p.outcome
	}
	if p.success != nil {
		params["success"] = *p.success
	}
	var result ReasoningTrace
	if err := r.transport.Call(ctx, "complete_trace", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

type CompleteTraceParams struct {
	outcome string
	success *bool
}

// WithOutcome sets the trace outcome.
func WithOutcome(o string) func(*CompleteTraceParams) {
	return func(p *CompleteTraceParams) { p.outcome = o }
}

// WithSuccess sets whether the trace succeeded.
func WithSuccess(s bool) func(*CompleteTraceParams) {
	return func(p *CompleteTraceParams) { p.success = &s }
}

// GetTraceWithSteps retrieves a full trace including steps and tool calls.
func (r *ReasoningMemory) GetTraceWithSteps(ctx context.Context, traceID string) (*ReasoningTrace, error) {
	var result *ReasoningTrace
	if err := r.transport.Call(ctx, "get_trace_with_steps", map[string]interface{}{
		"trace_id": traceID,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// ListTraces lists reasoning traces.
func (r *ReasoningMemory) ListTraces(ctx context.Context, opts ...func(*ListTracesParams)) ([]ReasoningTrace, error) {
	p := ListTracesParams{limit: 100}
	for _, o := range opts {
		o(&p)
	}
	params := map[string]interface{}{
		"limit": p.limit,
	}
	if p.sessionID != "" {
		params["session_id"] = p.sessionID
	}
	var result []ReasoningTrace
	if err := r.transport.Call(ctx, "list_traces", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []ReasoningTrace{}, nil
	}
	return result, nil
}

type ListTracesParams struct {
	sessionID string
	limit     int
}

// WithTraceSessionID filters traces by session.
func WithTraceSessionID(id string) func(*ListTracesParams) {
	return func(p *ListTracesParams) { p.sessionID = id }
}

// WithTracesLimit sets the maximum number of traces to return.
func WithTracesLimit(n int) func(*ListTracesParams) {
	return func(p *ListTracesParams) { p.limit = n }
}

// GetToolStats returns aggregated tool usage statistics.
func (r *ReasoningMemory) GetToolStats(ctx context.Context, toolName string) ([]ToolStats, error) {
	params := map[string]interface{}{}
	if toolName != "" {
		params["tool_name"] = toolName
	}
	var result []ToolStats
	if err := r.transport.Call(ctx, "get_tool_stats", params, &result); err != nil {
		return nil, err
	}
	if result == nil {
		return []ToolStats{}, nil
	}
	return result, nil
}

// GetSimilarTraces returns reasoning traces similar to the given task.
func (r *ReasoningMemory) GetSimilarTraces(ctx context.Context, task string, limit int, successOnly bool) ([]ReasoningTrace, error) {
	if limit <= 0 {
		limit = 5
	}
	var result []ReasoningTrace
	if err := r.transport.Call(ctx, "get_similar_traces", map[string]interface{}{
		"task":         task,
		"limit":        limit,
		"success_only": successOnly,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// ============================================================================
// Volume 5 / hosted-native methods
// ============================================================================

// RecordStepInput is the input to RecordStep.
type RecordStepInput struct {
	ConversationID string `json:"conversation_id"`
	Reasoning      string `json:"reasoning"`
	ActionTaken    string `json:"action_taken"`
	Result         string `json:"result,omitempty"`
}

// RecordStep records one reasoning step under a conversation (hosted REACT model).
func (r *ReasoningMemory) RecordStep(ctx context.Context, in RecordStepInput) (*AgentStep, error) {
	params := map[string]interface{}{
		"conversation_id": in.ConversationID,
		"reasoning":       in.Reasoning,
		"action_taken":    in.ActionTaken,
	}
	if in.Result != "" {
		params["result"] = in.Result
	}
	var result AgentStep
	if err := r.transport.Call(ctx, "record_step", params, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// ListSteps lists all reasoning steps for one conversation.
func (r *ReasoningMemory) ListSteps(ctx context.Context, conversationID string) ([]AgentStep, error) {
	var result []AgentStep
	if err := r.transport.Call(ctx, "list_steps", map[string]interface{}{
		"conversation_id": conversationID,
	}, &result); err != nil {
		return nil, err
	}
	return result, nil
}

// ExplainStep returns a detailed explanation of one reasoning step.
func (r *ReasoningMemory) ExplainStep(ctx context.Context, stepID string) (*AgentStepExplanation, error) {
	var result AgentStepExplanation
	if err := r.transport.Call(ctx, "explain_step", map[string]interface{}{
		"step_id": stepID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// GetTraceByConversation returns the full reasoning trace for a conversation.
func (r *ReasoningMemory) GetTraceByConversation(ctx context.Context, conversationID string) (*ConversationTrace, error) {
	var result ConversationTrace
	if err := r.transport.Call(ctx, "get_trace_by_conversation", map[string]interface{}{
		"conversation_id": conversationID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}

// GetEntityProvenance returns the reasoning chain that influenced an entity.
func (r *ReasoningMemory) GetEntityProvenance(ctx context.Context, entityID string) (*EntityProvenance, error) {
	var result EntityProvenance
	if err := r.transport.Call(ctx, "get_entity_provenance", map[string]interface{}{
		"entity_id": entityID,
	}, &result); err != nil {
		return nil, err
	}
	return &result, nil
}
