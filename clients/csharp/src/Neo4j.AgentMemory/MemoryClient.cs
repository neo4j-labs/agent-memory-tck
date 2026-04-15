using Neo4j.AgentMemory.LongTerm;
using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Reasoning;
using Neo4j.AgentMemory.ShortTerm;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory;

/// <summary>
/// Client for neo4j-agent-memory — provides short-term, long-term, and reasoning memory
/// for AI agents backed by Neo4j.
/// </summary>
public class MemoryClient : IAsyncDisposable, IDisposable
{
    private readonly ITransport _transport;
    private readonly bool _ownsTransport;

    /// <summary>Short-term (conversational) memory operations.</summary>
    public ShortTermMemory ShortTerm { get; }

    /// <summary>Long-term (entity/preference/fact) memory operations.</summary>
    public LongTermMemory LongTerm { get; }

    /// <summary>Reasoning (trace/step/tool call) memory operations.</summary>
    public ReasoningMemory Reasoning { get; }

    /// <summary>Create a MemoryClient from configuration options.</summary>
    public MemoryClient(MemoryClientOptions options)
    {
        _transport = new HttpTransport(options.Endpoint, options.ApiKey, options.Timeout);
        _ownsTransport = true;
        ShortTerm = new ShortTermMemory(_transport);
        LongTerm = new LongTermMemory(_transport);
        Reasoning = new ReasoningMemory(_transport);
    }

    /// <summary>Create a MemoryClient with a pre-configured transport.</summary>
    public MemoryClient(ITransport transport)
    {
        _transport = transport;
        _ownsTransport = false;
        ShortTerm = new ShortTermMemory(_transport);
        LongTerm = new LongTermMemory(_transport);
        Reasoning = new ReasoningMemory(_transport);
    }

    /// <summary>Connect to the memory service.</summary>
    public Task ConnectAsync(CancellationToken ct = default) => _transport.ConnectAsync(ct);

    /// <summary>Close the connection to the memory service.</summary>
    public Task CloseAsync(CancellationToken ct = default) => _transport.CloseAsync(ct);

    /// <summary>Delete all data (used for test isolation).</summary>
    public Task ClearAllDataAsync(CancellationToken ct = default) =>
        _transport.RequestAsync("clear_all_data", null, ct);

    public async ValueTask DisposeAsync()
    {
        await CloseAsync();
        if (_ownsTransport && _transport is IDisposable d)
            d.Dispose();
        GC.SuppressFinalize(this);
    }

    public void Dispose()
    {
        if (_ownsTransport && _transport is IDisposable d)
            d.Dispose();
        GC.SuppressFinalize(this);
    }
}
