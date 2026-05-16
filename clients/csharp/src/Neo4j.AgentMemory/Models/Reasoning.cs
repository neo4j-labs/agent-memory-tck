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

/// <summary>Hosted-service flat reasoning step (per conversation).</summary>
public class AgentStep
{
    [JsonPropertyName("id")]
    public string Id { get; set; } = "";

    [JsonPropertyName("conversation_id")]
    public string ConversationId { get; set; } = "";

    [JsonPropertyName("reasoning")]
    public string Reasoning { get; set; } = "";

    [JsonPropertyName("action_taken")]
    public string ActionTaken { get; set; } = "";

    [JsonPropertyName("result")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Result { get; set; }

    [JsonPropertyName("created_at")]
    public string CreatedAt { get; set; } = "";
}

/// <summary>Detailed step explanation: tool calls + influenced entities.</summary>
public class AgentStepExplanation : AgentStep
{
    [JsonPropertyName("tool_calls")]
    public List<ToolCall> ToolCalls { get; set; } = new();

    [JsonPropertyName("influenced_entities")]
    public List<Entity> InfluencedEntities { get; set; } = new();
}

/// <summary>Hosted: flat reasoning trace for one conversation.</summary>
public class ConversationTrace
{
    [JsonPropertyName("conversation_id")]
    public string ConversationId { get; set; } = "";

    [JsonPropertyName("steps")]
    public List<AgentStep> Steps { get; set; } = new();

    [JsonPropertyName("tool_calls")]
    public List<ToolCall> ToolCalls { get; set; } = new();
}

/// <summary>Reasoning chain that influenced an entity.
///
/// Hosted REST returns the chain under <c>provenance</c>; bridge / older
/// responses use <c>steps</c>. We bind both and prefer whichever is
/// non-empty.
/// </summary>
public class EntityProvenance
{
    [JsonPropertyName("entity_id")]
    public string EntityId { get; set; } = "";

    [JsonPropertyName("steps")]
    public List<AgentStep> StepsField { get; set; } = new();

    [JsonPropertyName("provenance")]
    public List<AgentStep> ProvenanceField { get; set; } = new();

    [JsonIgnore]
    public List<AgentStep> Steps =>
        StepsField.Count > 0 ? StepsField : ProvenanceField;
}

/// <summary>Read-only Cypher result.</summary>
public class CypherResult
{
    [JsonPropertyName("columns")]
    public List<string> Columns { get; set; } = new();

    [JsonPropertyName("rows")]
    public List<List<object?>> Rows { get; set; } = new();

    [JsonPropertyName("stats")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public Dictionary<string, object?>? Stats { get; set; }
}

/// <summary>An API key descriptor.</summary>
public class ApiKey
{
    [JsonPropertyName("id")] public string Id { get; set; } = "";
    [JsonPropertyName("label")] public string Label { get; set; } = "";
    [JsonPropertyName("scopes")] public List<string> Scopes { get; set; } = new();
    [JsonPropertyName("workspace_id")] public string WorkspaceId { get; set; } = "";
    [JsonPropertyName("created_at")] public string CreatedAt { get; set; } = "";

    [JsonPropertyName("expires_at")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? ExpiresAt { get; set; }

    /// <summary>Plaintext key — only present at creation time.</summary>
    [JsonPropertyName("key")]
    [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    public string? Key { get; set; }
}

/// <summary>Returned by RefreshAccessTokenAsync.</summary>
public class AccessTokenPair
{
    [JsonPropertyName("access_token")] public string AccessToken { get; set; } = "";
    [JsonPropertyName("refresh_token")] public string RefreshToken { get; set; } = "";
    [JsonPropertyName("expires_in")] public int ExpiresIn { get; set; }
}
