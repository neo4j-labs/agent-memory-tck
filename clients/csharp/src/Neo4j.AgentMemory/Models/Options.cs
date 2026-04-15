namespace Neo4j.AgentMemory.Models;

/// <summary>Configuration options for creating a MemoryClient.</summary>
public class MemoryClientOptions
{
    /// <summary>URL of the memory service HTTP endpoint.</summary>
    public string Endpoint { get; set; } = "";

    /// <summary>Optional API key for authentication.</summary>
    public string? ApiKey { get; set; }

    /// <summary>Request timeout. Default: 30 seconds.</summary>
    public TimeSpan Timeout { get; set; } = TimeSpan.FromSeconds(30);
}
