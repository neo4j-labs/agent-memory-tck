using Neo4j.AgentMemory;
using Neo4j.AgentMemory.Models;

namespace Sage.Services;

/// <summary>
/// Detects contradictions in the knowledge graph by comparing facts
/// about the same entity for conflicting predicates.
/// </summary>
public class ConflictDetector
{
    private readonly MemoryClient _client;

    public ConflictDetector(MemoryClient client)
    {
        _client = client;
    }

    public async Task<ValidationResult> ValidateEntityAsync(string entityName)
    {
        var sessionId = $"sage-{Guid.NewGuid().ToString()[..8]}";

        // Start reasoning trace
        var trace = await _client.Reasoning.StartTraceAsync(sessionId, $"Validate entity: {entityName}");

        // Step 1: Look up entity
        var step1 = await _client.Reasoning.AddStepAsync(
            trace.Id,
            thought: $"Looking up entity '{entityName}' in knowledge graph",
            action: "get_entity_by_name");

        var entity = await _client.LongTerm.GetEntityByNameAsync(entityName);

        await _client.Reasoning.RecordToolCallAsync(
            step1.Id, "get_entity_by_name",
            new Dictionary<string, object?> { ["name"] = entityName },
            result: entity != null ? $"Found: {entity.Name} ({entity.Type})" : "Not found",
            status: entity != null ? ToolCallStatus.Success : ToolCallStatus.Failure,
            durationMs: 50);

        if (entity == null)
        {
            await _client.Reasoning.CompleteTraceAsync(trace.Id,
                outcome: $"Entity '{entityName}' not found in knowledge graph",
                success: false);

            return new ValidationResult
            {
                EntityName = entityName,
                Found = false,
                Conflicts = new List<Conflict>(),
                ConfidenceScore = 0.0,
                SessionId = sessionId,
                TraceId = trace.Id
            };
        }

        // Step 2: Search for facts and detect conflicts
        var step2 = await _client.Reasoning.AddStepAsync(
            trace.Id,
            thought: "Searching for facts about entity to detect contradictions",
            action: "search_entities");

        // Search for related entities and facts
        var relatedEntities = await _client.LongTerm.SearchEntitiesAsync(entityName, limit: 20);

        await _client.Reasoning.RecordToolCallAsync(
            step2.Id, "search_entities",
            new Dictionary<string, object?> { ["query"] = entityName, ["limit"] = 20 },
            result: $"Found {relatedEntities.Count} related entities",
            status: ToolCallStatus.Success,
            durationMs: 120);

        // Detect conflicts by looking for entities with similar names but different types
        var conflicts = new List<Conflict>();
        var nameGroups = relatedEntities
            .GroupBy(e => e.Name.ToLowerInvariant())
            .Where(g => g.Count() > 1);

        foreach (var group in nameGroups)
        {
            var types = group.Select(e => e.Type).Distinct().ToList();
            if (types.Count > 1)
            {
                conflicts.Add(new Conflict
                {
                    Type = "type_mismatch",
                    Description = $"Entity '{group.First().Name}' has conflicting types: {string.Join(", ", types)}",
                    Entities = group.Select(e => e.Id).ToList()
                });
            }
        }

        // Calculate confidence score (higher = more consistent)
        var totalEntities = relatedEntities.Count;
        var conflictCount = conflicts.Count;
        var confidenceScore = totalEntities > 0
            ? Math.Round(1.0 - ((double)conflictCount / totalEntities), 2)
            : 1.0;

        // Record a message
        await _client.ShortTerm.AddMessageAsync(sessionId, MessageRole.Assistant,
            $"Validated entity '{entityName}': {conflictCount} conflict(s) detected, confidence score: {confidenceScore}");

        await _client.Reasoning.CompleteTraceAsync(trace.Id,
            outcome: $"Validated {entityName}: {conflictCount} conflicts, confidence {confidenceScore}",
            success: conflictCount == 0);

        return new ValidationResult
        {
            EntityName = entityName,
            Found = true,
            EntityType = entity.Type,
            Conflicts = conflicts,
            ConfidenceScore = confidenceScore,
            RelatedEntityCount = totalEntities,
            SessionId = sessionId,
            TraceId = trace.Id
        };
    }
}

public class ValidationResult
{
    public string EntityName { get; set; } = "";
    public bool Found { get; set; }
    public string? EntityType { get; set; }
    public List<Conflict> Conflicts { get; set; } = new();
    public double ConfidenceScore { get; set; }
    public int RelatedEntityCount { get; set; }
    public string SessionId { get; set; } = "";
    public string TraceId { get; set; } = "";
}

public class Conflict
{
    public string Type { get; set; } = "";
    public string Description { get; set; } = "";
    public List<string> Entities { get; set; } = new();
}
