using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.Query;

/// <summary>Read-only Cypher query console (hosted service only).</summary>
public class QueryConsole
{
    private readonly ITransport _transport;

    internal QueryConsole(ITransport transport) { _transport = transport; }

    /// <summary>Execute a read-only Cypher query.</summary>
    public async Task<CypherResult> CypherAsync(string cypher, Dictionary<string, object?>? parameters = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<CypherResult>("cypher_query", new()
        {
            ["cypher"] = cypher,
            ["params"] = parameters ?? new Dictionary<string, object?>()
        }, ct);
        return result ?? new CypherResult();
    }
}
