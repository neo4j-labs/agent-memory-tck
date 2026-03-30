using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.ShortTerm;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.Reasoning;

/// <summary>Reasoning (trace/step/tool call) memory operations — Silver + Gold tier.</summary>
public class ReasoningMemory
{
    private readonly ITransport _transport;

    internal ReasoningMemory(ITransport transport)
    {
        _transport = transport;
    }

    /// <summary>Start a new reasoning trace for a task.</summary>
    public async Task<ReasoningTrace> StartTraceAsync(
        string sessionId,
        string task,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ReasoningTrace>("start_trace", new Dictionary<string, object?>
        {
            ["session_id"] = sessionId,
            ["task"] = task
        }, ct);
        return result!;
    }

    /// <summary>Add a reasoning step to a trace.</summary>
    public async Task<ReasoningStep> AddStepAsync(
        string traceId,
        string? thought = null,
        string? action = null,
        string? observation = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ReasoningStep>("add_step", new Dictionary<string, object?>
        {
            ["trace_id"] = traceId,
            ["thought"] = thought,
            ["action"] = action,
            ["observation"] = observation
        }, ct);
        return result!;
    }

    /// <summary>Record a tool call within a reasoning step.</summary>
    public async Task<ToolCall> RecordToolCallAsync(
        string stepId,
        string toolName,
        Dictionary<string, object?> arguments,
        object? result = null,
        ToolCallStatus status = ToolCallStatus.Success,
        int? durationMs = null,
        string? error = null,
        CancellationToken ct = default)
    {
        var response = await _transport.RequestAsync<ToolCall>("record_tool_call", new Dictionary<string, object?>
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

    /// <summary>Complete a reasoning trace with outcome.</summary>
    public async Task<ReasoningTrace> CompleteTraceAsync(
        string traceId,
        string? outcome = null,
        bool? success = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ReasoningTrace>("complete_trace", new Dictionary<string, object?>
        {
            ["trace_id"] = traceId,
            ["outcome"] = outcome,
            ["success"] = success
        }, ct);
        return result!;
    }

    /// <summary>Get a full reasoning trace including steps and tool calls.</summary>
    public async Task<ReasoningTrace?> GetTraceWithStepsAsync(
        string traceId,
        CancellationToken ct = default)
    {
        return await _transport.RequestAsync<ReasoningTrace>("get_trace_with_steps", new Dictionary<string, object?>
        {
            ["trace_id"] = traceId
        }, ct);
    }

    /// <summary>List reasoning traces, optionally filtered by session.</summary>
    public async Task<List<ReasoningTrace>> ListTracesAsync(
        string? sessionId = null,
        int limit = 100,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ReasoningTrace>>("list_traces", new Dictionary<string, object?>
        {
            ["session_id"] = sessionId,
            ["limit"] = limit
        }, ct);
        return result ?? new List<ReasoningTrace>();
    }

    /// <summary>Get aggregated tool usage statistics.</summary>
    public async Task<List<ToolStats>> GetToolStatsAsync(
        string? toolName = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ToolStats>>("get_tool_stats", new Dictionary<string, object?>
        {
            ["tool_name"] = toolName
        }, ct);
        return result ?? new List<ToolStats>();
    }

    // --- Gold Tier ---

    /// <summary>Find reasoning traces similar to a given task description.</summary>
    public async Task<List<ReasoningTrace>> GetSimilarTracesAsync(
        string task,
        int limit = 5,
        bool successOnly = true,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<ReasoningTrace>>("get_similar_traces", new Dictionary<string, object?>
        {
            ["task"] = task,
            ["limit"] = limit,
            ["success_only"] = successOnly
        }, ct);
        return result ?? new List<ReasoningTrace>();
    }
}
