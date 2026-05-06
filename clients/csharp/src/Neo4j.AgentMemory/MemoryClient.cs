using Neo4j.AgentMemory.Auth;
using Neo4j.AgentMemory.LongTerm;
using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Query;
using Neo4j.AgentMemory.Reasoning;
using Neo4j.AgentMemory.ShortTerm;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory;

/// <summary>
/// Client for neo4j-agent-memory. Picks the transport automatically:
///
///   • REST when the endpoint contains a /v1 segment (hosted service).
///   • Bridge otherwise (TCK conformance servers, local reference).
///
/// Override the choice with <see cref="MemoryClientOptions.Transport"/>.
/// </summary>
public class MemoryClient : IAsyncDisposable, IDisposable
{
    private readonly ITransport _transport;
    private readonly bool _ownsTransport;

    public ShortTermMemory ShortTerm { get; }
    public LongTermMemory LongTerm { get; }
    public ReasoningMemory Reasoning { get; }
    public QueryConsole Query { get; }
    public AuthClient Auth { get; }

    public MemoryClient(MemoryClientOptions options)
    {
        _transport = BuildTransport(options);
        _ownsTransport = true;
        ShortTerm = new ShortTermMemory(_transport);
        LongTerm = new LongTermMemory(_transport);
        Reasoning = new ReasoningMemory(_transport);
        Query = new QueryConsole(_transport);
        Auth = new AuthClient(_transport);
    }

    public MemoryClient(ITransport transport)
    {
        _transport = transport;
        _ownsTransport = false;
        ShortTerm = new ShortTermMemory(_transport);
        LongTerm = new LongTermMemory(_transport);
        Reasoning = new ReasoningMemory(_transport);
        Query = new QueryConsole(_transport);
        Auth = new AuthClient(_transport);
    }

    private static ITransport BuildTransport(MemoryClientOptions options)
    {
        var mode = options.Transport;
        if (mode == TransportMode.Auto)
            mode = LooksLikeRest(options.Endpoint) ? TransportMode.Rest : TransportMode.Bridge;

        return mode switch
        {
            TransportMode.Rest => new RestTransport(options.Endpoint, options.ApiKey, options.Timeout, options.TokenProvider, options.Headers),
            _ => new BridgeTransport(options.Endpoint, options.ApiKey, options.Timeout, options.Headers),
        };
    }

    private static bool LooksLikeRest(string endpoint)
    {
        if (string.IsNullOrEmpty(endpoint)) return false;
        foreach (var segment in endpoint.Split('/'))
        {
            if (segment.Length >= 2 && segment[0] == 'v' &&
                int.TryParse(segment.AsSpan(1), out _))
                return true;
        }
        return false;
    }

    public Task ConnectAsync(CancellationToken ct = default) => _transport.ConnectAsync(ct);
    public Task CloseAsync(CancellationToken ct = default) => _transport.CloseAsync(ct);

    public Task ClearAllDataAsync(CancellationToken ct = default) =>
        _transport.RequestAsync("clear_all_data", null, ct);

    public async ValueTask DisposeAsync()
    {
        await CloseAsync();
        if (_ownsTransport && _transport is IDisposable d) d.Dispose();
        GC.SuppressFinalize(this);
    }

    public void Dispose()
    {
        if (_ownsTransport && _transport is IDisposable d) d.Dispose();
        GC.SuppressFinalize(this);
    }
}
