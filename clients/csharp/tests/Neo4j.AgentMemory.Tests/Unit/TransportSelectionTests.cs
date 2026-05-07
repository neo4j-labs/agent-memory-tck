using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;
using Xunit;

namespace Neo4j.AgentMemory.Tests.Unit;

[Trait("Category", "Unit")]
public class TransportSelectionTests
{
    [Fact]
    public void AutoSelectsRest_ForV1Endpoint()
    {
        using var client = new MemoryClient(new MemoryClientOptions
        {
            Endpoint = "https://memory.neo4jlabs.com/v1",
            ApiKey = "k",
        });
        Assert.IsType<RestTransport>(GetTransport(client));
    }

    [Fact]
    public void AutoSelectsBridge_ForLocalhost()
    {
        using var client = new MemoryClient(new MemoryClientOptions
        {
            Endpoint = "http://localhost:3001",
        });
        Assert.IsType<BridgeTransport>(GetTransport(client));
    }

    [Fact]
    public void ExplicitMode_OverridesAuto()
    {
        using var client = new MemoryClient(new MemoryClientOptions
        {
            Endpoint = "https://memory.neo4jlabs.com/v1",
            Transport = TransportMode.Bridge,
        });
        Assert.IsType<BridgeTransport>(GetTransport(client));
    }

    [Fact]
    public void AcceptsCustomTransport()
    {
        var fake = new FakeTransport();
        using var client = new MemoryClient(fake);
        Assert.Same(fake, GetTransport(client));
    }

    private static ITransport GetTransport(MemoryClient client)
    {
        var field = typeof(MemoryClient).GetField(
            "_transport",
            System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
        return (ITransport)field.GetValue(client)!;
    }

    private class FakeTransport : ITransport
    {
        public Task<T?> RequestAsync<T>(string method, Dictionary<string, object?>? parameters = null,
            CancellationToken ct = default) => Task.FromResult<T?>(default);
        public Task RequestAsync(string method, Dictionary<string, object?>? parameters = null,
            CancellationToken ct = default) => Task.CompletedTask;
        public Task ConnectAsync(CancellationToken ct = default) => Task.CompletedTask;
        public Task CloseAsync(CancellationToken ct = default) => Task.CompletedTask;
    }
}
