using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.Models;

/// <summary>Transport selection mode.</summary>
public enum TransportMode
{
    /// <summary>Pick automatically based on endpoint shape (REST when /v1).</summary>
    Auto,
    /// <summary>TCK bridge protocol — POST /{snake_method}.</summary>
    Bridge,
    /// <summary>Hosted REST API at memory.neo4jlabs.com/v1.</summary>
    Rest,
}

/// <summary>Configuration options for creating a MemoryClient.</summary>
public class MemoryClientOptions
{
    /// <summary>URL of the memory service HTTP endpoint.</summary>
    public string Endpoint { get; set; } = "";

    /// <summary>Optional Bearer API key (e.g. nams_*).</summary>
    public string? ApiKey { get; set; }

    /// <summary>Optional token provider for OAuth refresh flows. Overrides ApiKey when set.</summary>
    public TokenProvider? TokenProvider { get; set; }

    /// <summary>Transport selection. Default: Auto.</summary>
    public TransportMode Transport { get; set; } = TransportMode.Auto;

    /// <summary>Additional headers added to every request.</summary>
    public IDictionary<string, string>? Headers { get; set; }

    /// <summary>Request timeout. Default: 30 seconds.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);
}
