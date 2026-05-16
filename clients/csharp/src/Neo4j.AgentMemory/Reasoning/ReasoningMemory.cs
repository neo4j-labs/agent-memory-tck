using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.ShortTerm;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.Reasoning;

/// <summary>Reasoning (trace / step / tool call / provenance) memory operations.</summary>
public class ReasoningMemory
{
    private readonly ITransport _transport;

    internal ReasoningMemory(ITransport transport) { _transport = transport; }

    // ---- Silver tier (bridge) -------------------------------------------

    public async Task<ReasoningTrace> StartTraceAsync(string sessionId, string task, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ReasoningTrace>("start_trace", new()
        {
            ["session_id"] = sessionId,
            ["task"] = task
        }, ct);
        return result!;
    }

    public async Task<ReasoningStep> AddStepAsync(string traceId, string? thought = null, string? action = null, string? observation = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ReasoningStep>("add_step", new()
        {
            ["trace_id"] = traceId,
            ["thought"] = thought,
            ["action"] = action,
            ["observation"] = observation
        }, ct);
        return result!;
    }

    public async Task<ToolCall> RecordToolCallAsync(string stepId, string toolName, Dictionary<string, object?> arguments,
        object? result = null, ToolCallStatus status = ToolCallStatus.Success, int? durationMs = null,
        string? error = null, CancellationToken ct = default)
    {
        var response = await _transport.RequestAsync<ToolCall>("record_tool_call", new()
        {
            ["step_id"] = stepId,
            ["tool_name"] = toolName,
            ["arguments"] = arguments,
            ["result"] = result,
            ["status"] = status.ToWireString(),
            ["duration_ms"] = durationMs,
            ["error"] = error
        }, ct);
        return response!;
    }

    public async Task<ReasoningTrace> CompleteTraceAsync(string traceId, string? outcome = null, bool? success = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ReasoningTrace>("complete_trace", new()
        {
            ["trace_id"] = traceId,
            ["outcome"] = outcome,
            ["success"] = success
        }, ct);
        return result!;
    }

    public async Task<ReasoningTrace?> GetTraceWithStepsAsync(string traceId, CancellationToken ct = default)
    {
        return await _transport.RequestAsync<ReasoningTrace>("get_trace_with_steps", new() { ["trace_id"] = traceId }, ct);
    }

    public async Task<List<ReasoningTrace>> ListTracesAsync(string? sessionId = null, int limit = 100, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ReasoningTrace>>("list_traces", new()
        {
            ["session_id"] = sessionId,
            ["limit"] = limit
        }, ct);
        return result ?? new List<ReasoningTrace>();
    }

    public async Task<List<ToolStats>> GetToolStatsAsync(string? toolName = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ToolStats>>("get_tool_stats", new()
        {
            ["tool_name"] = toolName
        }, ct);
        return result ?? new List<ToolStats>();
    }

    public async Task<List<ReasoningTrace>> GetSimilarTracesAsync(string task, int limit = 5, bool successOnly = true, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ReasoningTrace>>("get_similar_traces", new()
        {
            ["task"] = task,
            ["limit"] = limit,
            ["success_only"] = successOnly
        }, ct);
        return result ?? new List<ReasoningTrace>();
    }

    // ---- Volume 5 / hosted-native ---------------------------------------

    /// <summary>Record one reasoning step under a conversation (hosted REACT model).</summary>
    public async Task<AgentStep> RecordStepAsync(string conversationId, string reasoning, string actionTaken, string? result = null, CancellationToken ct = default)
    {
        var step = await _transport.RequestAsync<AgentStep>("record_step", new()
        {
            ["conversation_id"] = conversationId,
            ["reasoning"] = reasoning,
            ["action_taken"] = actionTaken,
            ["result"] = result
        }, ct);
        return step!;
    }

    public async Task<List<AgentStep>> ListStepsAsync(string conversationId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<AgentStep>>("list_steps", new()
        {
            ["conversation_id"] = conversationId
        }, ct);
        return result ?? new List<AgentStep>();
    }

    public async Task<AgentStepExplanation> ExplainStepAsync(string stepId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<AgentStepExplanation>("explain_step", new() { ["step_id"] = stepId }, ct);
        return result!;
    }

    public async Task<ConversationTrace> GetTraceByConversationAsync(string conversationId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ConversationTrace>("get_trace_by_conversation", new()
        {
            ["conversation_id"] = conversationId
        }, ct);
        return result ?? new ConversationTrace { ConversationId = conversationId };
    }

    public async Task<EntityProvenance> GetEntityProvenanceAsync(string entityId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<EntityProvenance>("get_entity_provenance", new() { ["entity_id"] = entityId }, ct);
        return result ?? new EntityProvenance { EntityId = entityId };
    }
}
