using DotNetEnv;
using Neo4j.AgentMemory.Errors;
using Neo4j.AgentMemory.Models;
using Xunit;

namespace Neo4j.AgentMemory.Tests.E2E;

/// <summary>
/// End-to-end tests against the live hosted Neo4j Agent Memory Service.
///
/// Skipped when MEMORY_API_KEY is unset (or contains only whitespace).
/// `.env` at the repo root is loaded once by HostedServiceFixture; CI uses
/// the MEMORY_API_KEY repo secret as an environment variable.
/// </summary>
[Trait("Category", "E2E")]
public class HostedServiceTests : IClassFixture<HostedServiceFixture>, IAsyncLifetime
{
    private readonly HostedServiceFixture _fx;
    private readonly List<string> _createdConversationIds = new();

    public HostedServiceTests(HostedServiceFixture fx) { _fx = fx; }

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        if (_fx.Client == null) return;
        foreach (var id in _createdConversationIds)
        {
            try { await _fx.Client.ShortTerm.DeleteConversationAsync(id); }
            catch { /* best-effort cleanup */ }
        }
    }

    private async Task<string> NewConvAsync()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        var conv = await _fx.Client!.ShortTerm.CreateConversationAsync(_fx.UserId());
        _createdConversationIds.Add(conv.Id);
        return conv.Id;
    }

    // ----- Connection + auth -------------------------------------------

    [SkippableFact]
    public void Connect_SucceedsWithValidKey()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        Assert.NotNull(_fx.Client);
    }

    [SkippableFact]
    public async Task BadKey_ThrowsAuthenticationException()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        using var bad = new MemoryClient(new MemoryClientOptions
        {
            Endpoint = _fx.Endpoint,
            ApiKey = "nams_obviously_not_real_token",
        });
        await Assert.ThrowsAsync<AuthenticationException>(() => bad.ConnectAsync());
    }

    // ----- Short-Term --------------------------------------------------

    [SkippableFact]
    public async Task CreateAndListConversation()
    {
        var id = await NewConvAsync();
        Assert.NotEmpty(id);
        var list = await _fx.Client!.ShortTerm.ListConversationsAsync(limit: 50);
        Assert.NotNull(list);
    }

    [SkippableFact]
    public async Task AddMessage_ReadsBack()
    {
        var id = await NewConvAsync();
        var msg = await _fx.Client!.ShortTerm.AddMessageAsync(id, MessageRole.User, "Hello e2e.");
        Assert.NotEmpty(msg.Id);
        Assert.Equal("user", msg.Role);
    }

    [SkippableFact]
    public async Task BulkAddMessages_PreservesOrder()
    {
        var id = await NewConvAsync();
        var inputs = Enumerable.Range(0, 5)
            .Select(i => new BulkMessageInput { Role = "user", Content = $"bulk-{i}" })
            .ToList();
        var result = await _fx.Client!.ShortTerm.BulkAddMessagesAsync(id, inputs);
        Assert.Equal(5, result.Count);
    }

    [SkippableFact]
    public async Task GetContext_ReturnsThreeTierShape()
    {
        var id = await NewConvAsync();
        await _fx.Client!.ShortTerm.AddMessageAsync(id, MessageRole.User, "Hello.");
        var ctx = await _fx.Client.ShortTerm.GetContextAsync(id);
        Assert.NotNull(ctx.Reflections);
        Assert.NotNull(ctx.Observations);
        Assert.NotNull(ctx.RecentMessages);
    }

    // ----- Long-Term ---------------------------------------------------

    [SkippableFact]
    public async Task GetEntityGraph_ReturnsNodesAndEdges()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        var graph = await _fx.Client!.LongTerm.GetEntityGraphAsync();
        Assert.NotNull(graph.Nodes);
        Assert.NotNull(graph.Edges);
    }

    [SkippableFact]
    public async Task SearchEntities_ReturnsList()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        var entities = await _fx.Client!.LongTerm.SearchEntitiesAsync("anything", limit: 5);
        Assert.NotNull(entities);
    }

    [SkippableFact]
    public async Task ListEntities_ReturnsList()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        var entities = await _fx.Client!.LongTerm.ListEntitiesAsync(limit: 5);
        Assert.NotNull(entities);
    }

    // ----- Reasoning ---------------------------------------------------

    [SkippableFact]
    public async Task RecordStep_AndGetTraceByConversation()
    {
        var id = await NewConvAsync();
        var step = await _fx.Client!.Reasoning.RecordStepAsync(
            id, reasoning: "test", actionTaken: "ran", result: "passed");
        Assert.NotEmpty(step.Id);

        var trace = await _fx.Client.Reasoning.GetTraceByConversationAsync(id);
        Assert.Equal(id, trace.ConversationId);
    }

    // ----- Cypher ------------------------------------------------------

    [SkippableFact]
    public async Task CypherQuery_ReadOnlyRoundTrip()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        var result = await _fx.Client!.Query.CypherAsync(
            "MATCH (n) RETURN count(n) AS total");
        Assert.Contains("total", result.Columns);
        Assert.NotEmpty(result.Rows);
    }
}

public class HostedServiceFixture : IAsyncLifetime
{
    private static readonly object _envLock = new();
    public string Endpoint { get; private set; } = "https://memory.neo4jlabs.com/v1";
    public MemoryClient? Client { get; private set; }

    private string _userIdBase = "tck-e2e-cs";

    public Task InitializeAsync()
    {
        LoadDotEnv();

        var apiKey = Environment.GetEnvironmentVariable("MEMORY_API_KEY")?.Trim() ?? "";
        Endpoint = Environment.GetEnvironmentVariable("MEMORY_ENDPOINT") ?? Endpoint;
        _userIdBase = Environment.GetEnvironmentVariable("MEMORY_E2E_USER_ID") ?? "tck-e2e-cs";

        if (string.IsNullOrEmpty(apiKey)) return Task.CompletedTask;

        Client = new MemoryClient(new MemoryClientOptions
        {
            Endpoint = Endpoint,
            ApiKey = apiKey,
        });
        return Client.ConnectAsync();
    }

    public async Task DisposeAsync()
    {
        if (Client != null) await Client.DisposeAsync();
    }

    public string UserId()
    {
        var rand = Guid.NewGuid().ToString("N").Substring(0, 8);
        return $"{_userIdBase}-{rand}";
    }

    private static void LoadDotEnv()
    {
        lock (_envLock)
        {
            // Walk up from the current working directory to find a .env.
            var dir = new DirectoryInfo(Directory.GetCurrentDirectory());
            for (var i = 0; i < 8 && dir != null; i++, dir = dir.Parent)
            {
                var candidate = Path.Combine(dir.FullName, ".env");
                if (File.Exists(candidate))
                {
                    Env.Load(candidate, new LoadOptions(setEnvVars: true, clobberExistingVars: false, onlyExactPath: true));
                    return;
                }
            }
        }
    }
}
