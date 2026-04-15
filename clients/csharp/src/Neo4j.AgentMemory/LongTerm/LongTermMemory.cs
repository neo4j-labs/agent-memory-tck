using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.LongTerm;

/// <summary>Long-term (entity/preference/fact) memory operations — Silver + Gold tier.</summary>
public class LongTermMemory
{
    private readonly ITransport _transport;

    internal LongTermMemory(ITransport transport)
    {
        _transport = transport;
    }

    /// <summary>Create or update an entity in the knowledge graph.</summary>
    public async Task<Entity> AddEntityAsync(
        string name,
        string entityType,
        string? description = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Entity>("add_entity", new Dictionary<string, object?>
        {
            ["name"] = name,
            ["entity_type"] = entityType,
            ["description"] = description
        }, ct);
        return result!;
    }

    /// <summary>Store a user preference.</summary>
    public async Task<Preference> AddPreferenceAsync(
        string category,
        string preference,
        string? context = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Preference>("add_preference", new Dictionary<string, object?>
        {
            ["category"] = category,
            ["preference"] = preference,
            ["context"] = context
        }, ct);
        return result!;
    }

    /// <summary>Store a subject-predicate-object fact triple.</summary>
    public async Task<Fact> AddFactAsync(
        string subject,
        string predicate,
        string obj,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Fact>("add_fact", new Dictionary<string, object?>
        {
            ["subject"] = subject,
            ["predicate"] = predicate,
            ["obj"] = obj
        }, ct);
        return result!;
    }

    /// <summary>Search entities by semantic similarity.</summary>
    public async Task<List<Entity>> SearchEntitiesAsync(
        string query,
        int limit = 10,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Entity>>("search_entities", new Dictionary<string, object?>
        {
            ["query"] = query,
            ["limit"] = limit
        }, ct);
        return result ?? new List<Entity>();
    }

    /// <summary>Search preferences by semantic similarity.</summary>
    public async Task<List<Preference>> SearchPreferencesAsync(
        string query,
        string? category = null,
        int limit = 10,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Preference>>("search_preferences", new Dictionary<string, object?>
        {
            ["query"] = query,
            ["category"] = category,
            ["limit"] = limit
        }, ct);
        return result ?? new List<Preference>();
    }

    /// <summary>Look up an entity by exact name. Returns null if not found.</summary>
    public async Task<Entity?> GetEntityByNameAsync(
        string name,
        CancellationToken ct = default)
    {
        return await _transport.RequestAsync<Entity>("get_entity_by_name", new Dictionary<string, object?>
        {
            ["name"] = name
        }, ct);
    }

    /// <summary>Get entities related to the given entity.</summary>
    public async Task<List<Entity>> GetRelatedEntitiesAsync(
        string entityId,
        string? relationshipType = null,
        int depth = 1,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Entity>>("get_related_entities", new Dictionary<string, object?>
        {
            ["entity_id"] = entityId,
            ["relationship_type"] = relationshipType,
            ["depth"] = depth
        }, ct);
        return result ?? new List<Entity>();
    }

    // --- Gold Tier ---

    /// <summary>Create a typed relationship between two entities.</summary>
    public async Task<Relationship> AddRelationshipAsync(
        string sourceId,
        string targetId,
        string relationshipType,
        Dictionary<string, object?>? properties = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Relationship>("add_relationship", new Dictionary<string, object?>
        {
            ["source_id"] = sourceId,
            ["target_id"] = targetId,
            ["relationship_type"] = relationshipType,
            ["properties"] = properties
        }, ct);
        return result!;
    }

    /// <summary>Merge two duplicate entities into one.</summary>
    public async Task<Entity> MergeDuplicateEntitiesAsync(
        string sourceId,
        string targetId,
        string? canonicalName = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Entity>("merge_duplicate_entities", new Dictionary<string, object?>
        {
            ["source_id"] = sourceId,
            ["target_id"] = targetId,
            ["canonical_name"] = canonicalName
        }, ct);
        return result!;
    }
}
