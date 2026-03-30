using System.Text.Json.Serialization;

namespace Neo4j.AgentMemory.Models;

/// <summary>A complete reasoning trace for a task.</summary>
public class ReasoningTrace
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("session_id")]
    public string SessionId { get; set; } = "";

    [JsonPropertyName("task")]
    public string Task { get; set; } = "";

    [JsonPropertyName("steps")]
    public List<ReasoningStep> Steps { get; set; } = new();

    [JsonPropertyName("outcome")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Outcome { get; set; }

    [JsonPropertyName("success")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public bool? Success { get; set; }

    [JsonPropertyName("started_at")]
    public string StartedAt { get; set; } = "";

    [JsonPropertyName("completed_at")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? CompletedAt { get; set; }
}

/// <summary>A step in the agent's reasoning process.</summary>
public class ReasoningStep
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("trace_id")]
    public string TraceId { get; set; } = "";

    [JsonPropertyName("step_number")]
    public int StepNumber { get; set; }

    [JsonPropertyName("thought")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Thought { get; set; }

    [JsonPropertyName("action")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Action { get; set; }

    [JsonPropertyName("observation")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Observation { get; set; }

    [JsonPropertyName("tool_calls")]
    public List<ToolCall> ToolCalls { get; set; } = new();
}

/// <summary>A single tool invocation within a reasoning step.</summary>
public class ToolCall
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("tool_name")]
    public string ToolName { get; set; } = "";

    [JsonPropertyName("arguments")]
    public Dictionary<string, object?>? Arguments { get; set; }

    [JsonPropertyName("result")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public object? Result { get; set; }

    [JsonPropertyName("status")]
    [JsonConverter(typeof(LowercaseEnumConverter<ToolCallStatus>))]
    public ToolCallStatus Status { get; set; } = ToolCallStatus.Pending;

    [JsonPropertyName("duration_ms")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public int? DurationMs { get; set; }

    [JsonPropertyName("error")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Error { get; set; }
}

/// <summary>Aggregated statistics for a tool.</summary>
public class ToolStats
{
    [JsonPropertyName("name")]
    public string Name { get; set; } = "";

    [JsonPropertyName("total_calls")]
    public int TotalCalls { get; set; }

    [JsonPropertyName("successful_calls")]
    public int SuccessfulCalls { get; set; }

    [JsonPropertyName("failed_calls")]
    public int FailedCalls { get; set; }

    [JsonPropertyName("success_rate")]
    public double SuccessRate { get; set; }

    [JsonPropertyName("avg_duration_ms")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public double? AvgDurationMs { get; set; }
}
