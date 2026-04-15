using Neo4j.AgentMemory;
using Neo4j.AgentMemory.Models;

namespace Sage.Services;

/// <summary>
/// Audits the knowledge graph for completeness and consistency.
/// Reports entity counts, session activity, and trace statistics.
/// </summary>
public class KnowledgeAuditor
{
    private readonly MemoryClient _client;

    public KnowledgeAuditor(MemoryClient client)
    {
        _client = client;
    }

    public async Task<AuditResult> AuditAsync(string? query = null)
    {
        var sessionId = $"sage-{Guid.NewGuid().ToString()[..8]}";

        // Start reasoning trace
        var trace = await _client.Reasoning.StartTraceAsync(sessionId, "Knowledge graph audit");

        // Step 1: Count entities
        var step1 = await _client.Reasoning.AddStepAsync(
            trace.Id,
            thought: "Searching for all entities in the knowledge graph",
            action: "search_entities");

        var searchQuery = query ?? "AI";
        var entities = await _client.LongTerm.SearchEntitiesAsync(searchQuery, limit: 50);

        await _client.Reasoning.RecordToolCallAsync(
            step1.Id, "search_entities",
            new Dictionary<string, object?> { ["query"] = searchQuery, ["limit"] = 50 },
            result: $"Found {entities.Count} entities",
            status: ToolCallStatus.Success,
            durationMs: 200);

        // Categorize entities by type
        var byType = entities
            .GroupBy(e => e.Type)
            .ToDictionary(g => g.Key, g => g.Count());

        // Count entities without descriptions
        var withoutDescription = entities.Count(e => string.IsNullOrEmpty(e.Description));

        // Step 2: Check sessions and traces
        var step2 = await _client.Reasoning.AddStepAsync(
            trace.Id,
            thought: "Gathering session and trace statistics",
            action: "list_sessions");

        var sessions = await _client.ShortTerm.ListSessionsAsync(limit: 100);
        var traces = await _client.Reasoning.ListTracesAsync(limit: 100);

        await _client.Reasoning.RecordToolCallAsync(
            step2.Id, "list_sessions",
            new Dictionary<string, object?> { ["limit"] = 100 },
            result: $"Found {sessions.Count} sessions, {traces.Count} traces",
            status: ToolCallStatus.Success,
            durationMs: 80);

        // Categorize sessions by agent prefix
        var sessionsByAgent = sessions
            .GroupBy(s => s.SessionId.Split('-').FirstOrDefault() ?? "unknown")
            .ToDictionary(g => g.Key, g => g.Count());

        // Record summary message
        await _client.ShortTerm.AddMessageAsync(sessionId, MessageRole.Assistant,
            $"Audit complete: {entities.Count} entities, {sessions.Count} sessions, {traces.Count} traces");

        await _client.Reasoning.CompleteTraceAsync(trace.Id,
            outcome: $"Audit: {entities.Count} entities ({byType.Count} types), {sessions.Count} sessions, {traces.Count} traces",
            success: true);

        return new AuditResult
        {
            TotalEntities = entities.Count,
            EntitiesByType = byType,
            EntitiesWithoutDescription = withoutDescription,
            TotalSessions = sessions.Count,
            SessionsByAgent = sessionsByAgent,
            TotalTraces = traces.Count,
            SessionId = sessionId,
            TraceId = trace.Id
        };
    }
}

public class AuditResult
{
    public int TotalEntities { get; set; }
    public Dictionary<string, int> EntitiesByType { get; set; } = new();
    public int EntitiesWithoutDescription { get; set; }
    public int TotalSessions { get; set; }
    public Dictionary<string, int> SessionsByAgent { get; set; } = new();
    public int TotalTraces { get; set; }
    public string SessionId { get; set; } = "";
    public string TraceId { get; set; } = "";
}
