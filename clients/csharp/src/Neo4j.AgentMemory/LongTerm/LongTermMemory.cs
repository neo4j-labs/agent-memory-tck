using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.LongTerm;

/// <summary>Long-term (entity / preference / fact / graph) memory operations.</summary>
public class LongTermMemory
{
    private readonly ITransport _transport;

    internal LongTermMemory(ITransport transport) { _transport = transport; }

    // ---- Silver tier (bridge) -------------------------------------------

    public async Task<Entity> AddEntityAsync(string name, string entityType, string? description = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Entity>("add_entity", new()
        {
            ["name"] = name,
            ["entity_type"] = entityType,
            ["type"] = entityType,
            ["description"] = description
        }, ct);
        return result!;
    }

    public async Task<Preference> AddPreferenceAsync(string category, string preference, string? context = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Preference>("add_preference", new()
        {
            ["category"] = category,
            ["preference"] = preference,
            ["context"] = context
        }, ct);
        return result!;
    }

    public async Task<Fact> AddFactAsync(string subject, string predicate, string obj, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Fact>("add_fact", new()
        {
            ["subject"] = subject,
            ["predicate"] = predicate,
            ["obj"] = obj
        }, ct);
        return result!;
    }

    public async Task<List<Entity>> SearchEntitiesAsync(string query, int limit = 10, string? type = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Entity>>("search_entities", new()
        {
            ["query"] = query,
            ["limit"] = limit,
            ["type"] = type
        }, ct);
        return result ?? new List<Entity>();
    }

    public async Task<List<Preference>> SearchPreferencesAsync(string query, string? category = null, int limit = 10, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Preference>>("search_preferences", new()
        {
            ["query"] = query,
            ["category"] = category,
            ["limit"] = limit
        }, ct);
        return result ?? new List<Preference>();
    }

    public async Task<Entity?> GetEntityByNameAsync(string name, CancellationToken ct = default)
    {
        return await _transport.RequestAsync<Entity>("get_entity_by_name", new() { ["name"] = name }, ct);
    }

    public async Task<List<Entity>> GetRelatedEntitiesAsync(string entityId, string? relationshipType = null, int depth = 1, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Entity>>("get_related_entities", new()
        {
            ["entity_id"] = entityId,
            ["relationship_type"] = relationshipType,
            ["depth"] = depth
        }, ct);
        return result ?? new List<Entity>();
    }

    public async Task<Relationship> AddRelationshipAsync(string sourceId, string targetId, string relationshipType, Dictionary<string, object?>? properties = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Relationship>("add_relationship", new()
        {
            ["source_id"] = sourceId,
            ["target_id"] = targetId,
            ["relationship_type"] = relationshipType,
            ["properties"] = properties
        }, ct);
        return result!;
    }

    public async Task<Entity> MergeDuplicateEntitiesAsync(string sourceId, string targetId, string? canonicalName = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Entity>("merge_duplicate_entities", new()
        {
            ["source_id"] = sourceId,
            ["target_id"] = targetId,
            ["canonical_name"] = canonicalName
        }, ct);
        return result!;
    }

    // ---- Volume 5 / hosted-native ---------------------------------------

    public async Task<List<Entity>> ListEntitiesAsync(string? type = null, int? limit = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Entity>>("list_entities", new()
        {
            ["type"] = type,
            ["limit"] = limit
        }, ct);
        return result ?? new List<Entity>();
    }

    public async Task<Entity> GetEntityAsync(string entityId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Entity>("get_entity", new() { ["entity_id"] = entityId }, ct);
        return result!;
    }

    public async Task<Entity> UpdateEntityAsync(string entityId, string? name = null, string? description = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Entity>("update_entity", new()
        {
            ["entity_id"] = entityId,
            ["name"] = name,
            ["description"] = description
        }, ct);
        return result!;
    }

    public async Task DeleteEntityAsync(string entityId, CancellationToken ct = default)
    {
        await _transport.RequestAsync("delete_entity", new() { ["entity_id"] = entityId }, ct);
    }

    public async Task<EntityFeedbackResult> SetEntityFeedbackAsync(string entityId, double userScore, bool confirmed, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<EntityFeedbackResult>("set_entity_feedback", new()
        {
            ["entity_id"] = entityId,
            ["user_score"] = userScore,
            ["confirmed"] = confirmed
        }, ct);
        return result!;
    }

    public async Task<EntityHistory> GetEntityHistoryAsync(string entityId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<EntityHistory>("get_entity_history", new() { ["entity_id"] = entityId }, ct);
        return result ?? new EntityHistory { EntityId = entityId };
    }

    public async Task<EntityMergeResult> MergeEntitiesAsync(string sourceId, string targetId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<EntityMergeResult>("merge_entities", new()
        {
            ["source_id"] = sourceId,
            ["target_id"] = targetId
        }, ct);
        return result!;
    }

    public async Task<EntityGraph> GetEntityGraphAsync(CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<EntityGraph>("get_entity_graph", null, ct);
        return result ?? new EntityGraph();
    }
}
