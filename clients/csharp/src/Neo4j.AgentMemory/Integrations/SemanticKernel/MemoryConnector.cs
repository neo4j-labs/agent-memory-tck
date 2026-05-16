using Neo4j.AgentMemory.Models;

namespace Neo4j.AgentMemory.Integrations.SemanticKernel;

/// <summary>
/// Lightweight Semantic-Kernel-friendly connector that wraps <see cref="MemoryClient"/>.
///
/// Implements operations equivalent to <c>IMemoryStore</c> without taking a hard
/// dependency on the SK package. SK users can adapt these methods into their
/// own <c>ISemanticTextMemory</c> implementation.
/// </summary>
public class MemoryConnector
{
    private readonly MemoryClient _client;

    public MemoryConnector(MemoryClient client) { _client = client; }

    /// <summary>Save text into the long-term graph as an Entity-typed memory.</summary>
    public async Task<string> SaveAsync(string collection, string id, string text, string? description = null, CancellationToken ct = default)
    {
        var entity = await _client.LongTerm.AddEntityAsync(id, collection, description ?? text, ct);
        return entity.Id;
    }

    /// <summary>Search the long-term graph by similarity.</summary>
    public async Task<IReadOnlyList<MemoryRecord>> SearchAsync(string query, string? type = null, int limit = 10, CancellationToken ct = default)
    {
        var entities = await _client.LongTerm.SearchEntitiesAsync(query, limit, type, ct);
        return entities.Select(e => new MemoryRecord
        {
            Id = e.Id,
            Text = e.Description ?? e.Name,
            Type = e.Type,
            Confidence = e.Confidence,
        }).ToList();
    }

    /// <summary>Inject conversational context as a system prompt prefix.</summary>
    public async Task<string> GetContextPrefixAsync(string conversationId, CancellationToken ct = default)
    {
        var ctx = await _client.ShortTerm.GetContextAsync(conversationId, ct);
        var lines = new List<string>();
        foreach (var r in ctx.Reflections) lines.Add($"[reflection] {r.Content}");
        foreach (var o in ctx.Observations) lines.Add($"[observation] {o.Content}");
        foreach (var m in ctx.RecentMessages) lines.Add($"{m.Role}: {m.Content}");
        return string.Join("\n", lines);
    }
}

/// <summary>Lightweight memory record returned by MemoryConnector.</summary>
public class MemoryRecord
{
    public string Id { get; set; } = "";
    public string Text { get; set; } = "";
    public string Type { get; set; } = "";
    public double? Confidence { get; set; }
}
