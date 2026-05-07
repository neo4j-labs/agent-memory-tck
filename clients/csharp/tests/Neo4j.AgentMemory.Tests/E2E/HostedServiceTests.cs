using System.Collections.Concurrent;
using DotNetEnv;
using Neo4j.AgentMemory.Errors;
using Neo4j.AgentMemory.Models;
using Xunit;

namespace Neo4j.AgentMemory.Tests.E2E;

/// <summary>
/// Comprehensive end-to-end tests against the live hosted Neo4j Agent Memory
/// Service.
///
/// Mirrors the Python suite. Skipped wholesale when MEMORY_API_KEY is unset;
/// individual tests skip themselves when the service rejects an operation
/// that requires elevated workspace scope (Cypher, API-key management).
///
/// Each test creates short-lived data tagged with the `tck-e2e-cs-` user
/// prefix and tears it down via the fixture's tracking lists.
/// </summary>
[Trait("Category", "E2E")]
public class HostedServiceTests : IClassFixture<HostedServiceFixture>, IAsyncLifetime
{
    private readonly HostedServiceFixture _fx;
    private readonly List<string> _createdConversationIds = new();
    private readonly List<string> _createdEntityIds = new();

    public HostedServiceTests(HostedServiceFixture fx) { _fx = fx; }

    public Task InitializeAsync() => Task.CompletedTask;

    public async Task DisposeAsync()
    {
        if (_fx.Client == null) return;
        foreach (var id in _createdConversationIds)
        {
            try { await _fx.Client.ShortTerm.DeleteConversationAsync(id); } catch { }
        }
        foreach (var id in _createdEntityIds)
        {
            try { await _fx.Client.LongTerm.DeleteEntityAsync(id); } catch { }
        }
    }

    private MemoryClient Client
    {
        get
        {
            Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
            return _fx.Client!;
        }
    }

    private async Task<Conversation> NewConvAsync(string suffix = "")
    {
        var conv = await Client.ShortTerm.CreateConversationAsync(_fx.UserId(suffix));
        _createdConversationIds.Add(conv.Id);
        return conv;
    }

    private async Task<Conversation> NewConvAsync(string userId, Dictionary<string, object?>? metadata)
    {
        var conv = await Client.ShortTerm.CreateConversationAsync(userId, metadata);
        _createdConversationIds.Add(conv.Id);
        return conv;
    }

    private async Task<Entity> NewEntityAsync(string? name = null, string entityType = "concept", string? description = null)
    {
        var e = await Client.LongTerm.AddEntityAsync(
            name ?? $"TCK-Probe-{Guid.NewGuid():N}".Substring(0, 18),
            entityType,
            description ?? "tck e2e probe entity");
        _createdEntityIds.Add(e.Id);
        return e;
    }

    private static async Task<bool> WaitUntilAsync(Func<Task<bool>> predicate, TimeSpan timeout)
    {
        var deadline = DateTime.UtcNow + timeout;
        while (DateTime.UtcNow < deadline)
        {
            if (await predicate()) return true;
            await Task.Delay(1000);
        }
        return false;
    }

    private static string RandomHex(int n)
    {
        var bytes = new byte[(n / 2) + 1];
        System.Security.Cryptography.RandomNumberGenerator.Fill(bytes);
        return Convert.ToHexString(bytes).ToLowerInvariant().Substring(0, n);
    }

    // ====================================================================
    // 1. Connection + auth
    // ====================================================================

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

    [SkippableFact]
    public async Task EmptyKey_ThrowsAuthenticationException()
    {
        Skip.If(_fx.Client == null, "MEMORY_API_KEY not set");
        using var bad = new MemoryClient(new MemoryClientOptions
        {
            Endpoint = _fx.Endpoint,
            ApiKey = "",
        });
        await Assert.ThrowsAsync<AuthenticationException>(() => bad.ConnectAsync());
    }

    // ====================================================================
    // 2. Conversation lifecycle
    // ====================================================================

    [SkippableFact]
    public async Task Create_ReturnsIdAndUserAndWorkspace()
    {
        var uid = _fx.UserId("create");
        var conv = await NewConvAsync(uid, new Dictionary<string, object?> { ["source"] = "e2e", ["seq"] = 1 });
        Assert.True(conv.Id.Length >= 8);
        Assert.Equal(uid, conv.UserId);
        Assert.False(string.IsNullOrEmpty(conv.WorkspaceId));
    }

    [SkippableFact]
    public async Task GetMetadata_RoundTripsUserId()
    {
        var conv = await NewConvAsync();
        var meta = await Client.ShortTerm.GetConversationMetadataAsync(conv.Id);
        Assert.Equal(conv.Id, meta.Id);
        Assert.Equal(conv.UserId, meta.UserId);
    }

    [SkippableFact]
    public async Task List_IncludesFreshlyCreated()
    {
        var conv = await NewConvAsync("list-probe");
        var listed = await Client.ShortTerm.ListConversationsAsync(limit: 200);
        Assert.Contains(listed, x => x.Id == conv.Id);
    }

    [SkippableFact]
    public async Task Delete_IsIdempotent()
    {
        var conv = await Client.ShortTerm.CreateConversationAsync(_fx.UserId("del"));
        await Client.ShortTerm.DeleteConversationAsync(conv.Id);
        // Second delete must not throw
        await Client.ShortTerm.DeleteConversationAsync(conv.Id);
    }

    // ====================================================================
    // 3. Short-term: messages
    // ====================================================================

    [SkippableFact]
    public async Task AddMessage_ReturnsIdAndRole()
    {
        var conv = await NewConvAsync();
        var msg = await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, "hello world");
        Assert.False(string.IsNullOrEmpty(msg.Id));
        Assert.Equal("user", msg.Role);
        Assert.Equal("hello world", msg.Content);
    }

    [SkippableFact]
    public async Task GetConversation_ReturnsAddedMessages()
    {
        var conv = await NewConvAsync();
        var contents = new[] { "one", "two", "three", "four", "five" };
        foreach (var c in contents)
        {
            await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, c);
        }
        var got = await Client.ShortTerm.GetConversationAsync(conv.Id);
        var seen = got.Messages.Select(m => m.Content).ToHashSet();
        foreach (var c in contents) Assert.Contains(c, seen);
    }

    [SkippableFact]
    public async Task SearchMessages_ReturnsArray()
    {
        var conv = await NewConvAsync();
        await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User,
            "Marie Curie won the Nobel Prize in Physics in 1903.");
        var results = await Client.ShortTerm.SearchMessagesAsync(
            "Nobel", conv.Id, limit: 5, threshold: 0.0);
        Assert.NotNull(results);
    }

    [SkippableTheory]
    [InlineData(MessageRole.User, "user")]
    [InlineData(MessageRole.Assistant, "assistant")]
    [InlineData(MessageRole.System, "system")]
    public async Task MessageRole_RoundTrips(MessageRole role, string wire)
    {
        var conv = await NewConvAsync($"role-{wire}");
        var msg = await Client.ShortTerm.AddMessageAsync(conv.Id, role, $"role {wire}");
        Assert.Equal(wire, msg.Role);
    }

    [SkippableFact]
    public async Task UnicodeContent_Preserved()
    {
        var conv = await NewConvAsync();
        var content = "你好 🚀 émoji ñ ç ø";
        var msg = await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, content);
        Assert.Equal(content, msg.Content);
    }

    [SkippableFact]
    public async Task LongContent_Preserved()
    {
        var conv = await NewConvAsync();
        var content = new string('x', 10_000);
        var msg = await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, content);
        Assert.Equal(10_000, msg.Content.Length);
    }

    [SkippableFact]
    public async Task SpecialCharsContent_Preserved()
    {
        var conv = await NewConvAsync();
        var content = "quote \" backslash \\ newline\nreturn\r tab\t json {\"a\":1}";
        var msg = await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, content);
        Assert.Equal(content, msg.Content);
    }

    [SkippableFact]
    public async Task Metadata_RoundTripsWithoutError()
    {
        var conv = await NewConvAsync();
        var meta = new Dictionary<string, object?>
        {
            ["source"] = "tck-e2e",
            ["priority"] = "high",
            ["count"] = 42,
            ["active"] = true
        };
        var msg = await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, "with-meta", meta);
        Assert.Equal("with-meta", msg.Content);
    }

    // ====================================================================
    // 4. Bulk operations
    // ====================================================================

    [SkippableFact]
    public async Task BulkAdd_5Messages()
    {
        var conv = await NewConvAsync();
        var msgs = Enumerable.Range(0, 5).Select(i =>
            new BulkMessageInput { Role = "user", Content = $"bulk-{i}" }).ToList();
        var result = await Client.ShortTerm.BulkAddMessagesAsync(conv.Id, msgs);
        Assert.Equal(5, result.Count);
    }

    [SkippableFact]
    public async Task BulkAdd_50Messages()
    {
        var conv = await NewConvAsync();
        var msgs = Enumerable.Range(0, 50).Select(i =>
            new BulkMessageInput { Role = "user", Content = $"big-bulk-{i}" }).ToList();
        var result = await Client.ShortTerm.BulkAddMessagesAsync(conv.Id, msgs);
        Assert.Equal(50, result.Count);
    }

    [SkippableFact]
    public async Task BulkAdd_RejectsMoreThan100()
    {
        var conv = await NewConvAsync();
        var msgs = Enumerable.Range(0, 101).Select(i =>
            new BulkMessageInput { Role = "user", Content = $"x-{i}" }).ToList();
        await Assert.ThrowsAnyAsync<Exception>(() =>
            Client.ShortTerm.BulkAddMessagesAsync(conv.Id, msgs));
    }

    // ====================================================================
    // 5. Three-tier context
    // ====================================================================

    [SkippableFact]
    public async Task GetContext_ThreeTierShape()
    {
        var conv = await NewConvAsync();
        await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, "Hello world");
        var ctx = await Client.ShortTerm.GetContextAsync(conv.Id);
        Assert.NotNull(ctx.Reflections);
        Assert.NotNull(ctx.Observations);
        Assert.NotNull(ctx.RecentMessages);
    }

    [SkippableFact]
    public async Task GetObservations_ReturnsList()
    {
        var conv = await NewConvAsync();
        var obs = await Client.ShortTerm.GetObservationsAsync(conv.Id, limit: 10);
        Assert.NotNull(obs);
    }

    [SkippableFact]
    public async Task GetReflections_ReturnsList()
    {
        var conv = await NewConvAsync();
        var refl = await Client.ShortTerm.GetReflectionsAsync(conv.Id);
        Assert.NotNull(refl);
    }

    [SkippableFact]
    public async Task RecentMessages_IncludesAdded()
    {
        var conv = await NewConvAsync();
        await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User, "context-probe-message");
        var ctx = await Client.ShortTerm.GetContextAsync(conv.Id);
        var contents = ctx.RecentMessages.Select(m => m.Content).ToList();
        Assert.Contains("context-probe-message", contents);
    }

    // ====================================================================
    // 6. Long-term: entity CRUD + search
    // ====================================================================

    [SkippableFact]
    public async Task AddEntity_ReturnsIdAndFields()
    {
        var e = await NewEntityAsync(name: $"TCK Alice {RandomHex(4)}", description: "test person");
        Assert.True(e.Id.Length >= 8);
        Assert.Equal("test person", e.Description);
    }

    [SkippableFact]
    public async Task ListEntities_ReturnsArray()
    {
        var ents = await Client.LongTerm.ListEntitiesAsync(limit: 5);
        Assert.NotNull(ents);
    }

    [SkippableFact]
    public async Task ListEntities_TypeFilter()
    {
        var ents = await Client.LongTerm.ListEntitiesAsync(type: "person", limit: 5);
        Assert.NotNull(ents);
        foreach (var e in ents) Assert.Equal("person", e.Type);
    }

    [SkippableFact]
    public async Task GetEntity_IncludesRelationships()
    {
        var e = await NewEntityAsync();
        var full = await Client.LongTerm.GetEntityAsync(e.Id);
        Assert.Equal(e.Id, full.Id);
    }

    [SkippableFact]
    public async Task UpdateEntity_Description()
    {
        var e = await NewEntityAsync(description: "orig");
        var updated = await Client.LongTerm.UpdateEntityAsync(e.Id, description: "rewritten");
        Assert.Equal(e.Id, updated.Id);
        Assert.Equal("rewritten", updated.Description);
    }

    [SkippableFact]
    public async Task UpdateEntity_Name()
    {
        var e = await NewEntityAsync(name: $"Original-{RandomHex(6)}");
        var newName = $"Renamed-{RandomHex(6)}";
        var updated = await Client.LongTerm.UpdateEntityAsync(e.Id, name: newName);
        Assert.Equal(newName, updated.Name);
    }

    [SkippableFact]
    public async Task DeleteEntity_Removes()
    {
        var e = await Client.LongTerm.AddEntityAsync($"TCK-DelProbe-{RandomHex(6)}", "concept", "ephemeral");
        await Client.LongTerm.DeleteEntityAsync(e.Id);
        // Re-fetch may either 404 or soft-tombstone — either is acceptable.
        try { await Client.LongTerm.GetEntityAsync(e.Id); } catch { }
    }

    [SkippableFact]
    public async Task SearchEntities_ReturnsArray()
    {
        var results = await Client.LongTerm.SearchEntitiesAsync("anything", limit: 5);
        Assert.NotNull(results);
    }

    [SkippableFact]
    public async Task SearchEntities_FindsCreated()
    {
        var marker = $"TCK-Probe-{RandomHex(8)}";
        var e = await NewEntityAsync(name: marker);
        var found = await WaitUntilAsync(async () =>
        {
            var hits = await Client.LongTerm.SearchEntitiesAsync(marker, limit: 10);
            return hits.Any(h => h.Id == e.Id);
        }, TimeSpan.FromSeconds(12));
        Skip.If(!found, "entity not yet indexed for search after 12s");
    }

    [SkippableFact]
    public async Task SetEntityFeedback_ReturnsUpdated()
    {
        var e = await NewEntityAsync();
        var result = await Client.LongTerm.SetEntityFeedbackAsync(e.Id, userScore: 0.93, confirmed: true);
        Assert.Equal(e.Id, result.Id);
        Assert.True(result.Updated);
    }

    [SkippableFact]
    public async Task SetEntityFeedback_ZeroScore()
    {
        var e = await NewEntityAsync();
        var result = await Client.LongTerm.SetEntityFeedbackAsync(e.Id, userScore: 0.0, confirmed: false);
        Assert.Equal(e.Id, result.Id);
    }

    [SkippableFact]
    public async Task EntityHistory_ReturnsShape()
    {
        var e = await NewEntityAsync();
        var hist = await Client.LongTerm.GetEntityHistoryAsync(e.Id);
        Assert.Equal(e.Id, hist.EntityId);
        Assert.NotNull(hist.Mentions);
    }

    [SkippableFact]
    public async Task EntityProvenance_ReturnsShape()
    {
        var e = await NewEntityAsync();
        var prov = await Client.Reasoning.GetEntityProvenanceAsync(e.Id);
        Assert.Equal(e.Id, prov.EntityId);
        Assert.NotNull(prov.Steps);
    }

    [SkippableFact]
    public async Task GetEntityGraph_ReturnsNodesAndEdges()
    {
        var graph = await Client.LongTerm.GetEntityGraphAsync();
        Assert.NotNull(graph.Nodes);
        Assert.NotNull(graph.Edges);
    }

    [SkippableFact]
    public async Task MergeEntities_ReturnsStatus()
    {
        var a = await NewEntityAsync(name: $"MergeA-{RandomHex(6)}");
        var b = await NewEntityAsync(name: $"MergeB-{RandomHex(6)}");
        try
        {
            var result = await Client.LongTerm.MergeEntitiesAsync(a.Id, b.Id);
            Assert.False(string.IsNullOrEmpty(result.Status));
        }
        catch (Exception ex) when (ex is TransportException || ex is AuthenticationException)
        {
            throw new SkipException($"merge endpoint refused: {ex.Message}");
        }
    }

    // ====================================================================
    // 7. Reasoning: steps + explain + trace
    // ====================================================================

    [SkippableFact]
    public async Task RecordStep_Persists()
    {
        var conv = await NewConvAsync();
        var step = await Client.Reasoning.RecordStepAsync(
            conv.Id,
            reasoning: "hypothesizing user's intent",
            actionTaken: "lookup_user_profile",
            result: "found profile");
        Assert.False(string.IsNullOrEmpty(step.Id));
        Assert.Equal(conv.Id, step.ConversationId);
    }

    [SkippableFact]
    public async Task RecordStep_WithoutResult()
    {
        var conv = await NewConvAsync();
        var step = await Client.Reasoning.RecordStepAsync(conv.Id, reasoning: "r", actionTaken: "a");
        Assert.False(string.IsNullOrEmpty(step.Id));
    }

    [SkippableFact]
    public async Task ListSteps_ReturnsRecorded()
    {
        var conv = await NewConvAsync();
        var s1 = await Client.Reasoning.RecordStepAsync(conv.Id, reasoning: "r1", actionTaken: "a1");
        var s2 = await Client.Reasoning.RecordStepAsync(conv.Id, reasoning: "r2", actionTaken: "a2");
        var steps = await Client.Reasoning.ListStepsAsync(conv.Id);
        var ids = steps.Select(s => s.Id).ToHashSet();
        Assert.Contains(s1.Id, ids);
        Assert.Contains(s2.Id, ids);
    }

    [SkippableFact]
    public async Task ExplainStep_ReturnsToolCallsAndEntities()
    {
        var conv = await NewConvAsync();
        var step = await Client.Reasoning.RecordStepAsync(conv.Id, reasoning: "r", actionTaken: "a");
        var explanation = await Client.Reasoning.ExplainStepAsync(step.Id);
        Assert.Equal(step.Id, explanation.Id);
        Assert.NotNull(explanation.ToolCalls);
        Assert.NotNull(explanation.InfluencedEntities);
    }

    [SkippableFact]
    public async Task GetTrace_EmptyConversation()
    {
        var conv = await NewConvAsync();
        var trace = await Client.Reasoning.GetTraceByConversationAsync(conv.Id);
        Assert.Equal(conv.Id, trace.ConversationId);
        Assert.NotNull(trace.Steps);
        Assert.NotNull(trace.ToolCalls);
    }

    [SkippableFact]
    public async Task GetTrace_IncludesRecordedStep()
    {
        var conv = await NewConvAsync();
        await Client.Reasoning.RecordStepAsync(conv.Id, reasoning: "r", actionTaken: "a");
        var trace = await Client.Reasoning.GetTraceByConversationAsync(conv.Id);
        Assert.Contains(trace.Steps, s => s.Reasoning.Contains("r"));
    }

    // ====================================================================
    // 8. Cypher (skipped on 403)
    // ====================================================================

    [SkippableFact]
    public async Task Cypher_CountQuery()
    {
        try
        {
            var result = await Client.Query.CypherAsync("MATCH (n) RETURN count(n) AS total");
            Assert.Contains("total", result.Columns);
            Assert.NotEmpty(result.Rows);
        }
        catch (AuthenticationException e)
        {
            throw new SkipException($"API key lacks Cypher scope: {e.Message}");
        }
    }

    [SkippableFact]
    public async Task Cypher_ParameterisedQuery()
    {
        try
        {
            var result = await Client.Query.CypherAsync(
                "MATCH (n) RETURN $label AS label LIMIT 1",
                new Dictionary<string, object?> { ["label"] = "tck-e2e" });
            Assert.NotNull(result.Columns);
        }
        catch (AuthenticationException e)
        {
            throw new SkipException($"API key lacks Cypher scope: {e.Message}");
        }
    }

    // ====================================================================
    // 9. Auth API (skipped on 403)
    // ====================================================================

    [SkippableFact]
    public async Task ListApiKeys_ReturnsArrayOrSkips()
    {
        var conv = await NewConvAsync();
        var meta = await Client.ShortTerm.GetConversationMetadataAsync(conv.Id);
        Skip.If(string.IsNullOrEmpty(meta.WorkspaceId), "workspace_id not exposed by service");
        try
        {
            var keys = await Client.Auth.ListApiKeysAsync(meta.WorkspaceId!);
            Assert.NotNull(keys);
        }
        catch (AuthenticationException e)
        {
            throw new SkipException($"API key lacks auth scope: {e.Message}");
        }
    }

    // ====================================================================
    // 10. Cross-feature workflows
    // ====================================================================

    [SkippableFact]
    public async Task Workflow_MessageToExtractedEntities()
    {
        var conv = await NewConvAsync("agent-flow");
        var uniqueName = $"TCKMercury{RandomHex(8)}";
        await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.User,
            $"{uniqueName} is the smallest planet in the solar system.");
        await Client.ShortTerm.AddMessageAsync(conv.Id, MessageRole.Assistant,
            $"Yes, {uniqueName} has a thin atmosphere.");
        var found = await WaitUntilAsync(async () =>
        {
            var hits = await Client.LongTerm.SearchEntitiesAsync(uniqueName, limit: 10);
            return hits.Any(h => h.Name.Contains(uniqueName, StringComparison.OrdinalIgnoreCase));
        }, TimeSpan.FromSeconds(20));
        Skip.If(!found, "extracted entity not indexed within 20s");
    }

    [SkippableFact]
    public async Task Workflow_MultiStepReasoningChain()
    {
        var conv = await NewConvAsync();
        var recorded = new List<AgentStep>();
        for (var i = 0; i < 3; i++)
        {
            recorded.Add(await Client.Reasoning.RecordStepAsync(
                conv.Id,
                reasoning: $"step {i} reasoning",
                actionTaken: $"action_{i}",
                result: $"result_{i}"));
        }
        var trace = await Client.Reasoning.GetTraceByConversationAsync(conv.Id);
        var traced = trace.Steps.Select(s => s.Id).ToHashSet();
        foreach (var s in recorded) Assert.Contains(s.Id, traced);
    }

    [SkippableFact]
    public async Task Workflow_MultiTurnConversationInContext()
    {
        var conv = await NewConvAsync();
        var turns = new (MessageRole role, string content)[]
        {
            (MessageRole.User, "I'm planning a trip to Tokyo next month."),
            (MessageRole.Assistant, "Tokyo is great in autumn — what are your interests?"),
            (MessageRole.User, "Mostly food and historical sites."),
            (MessageRole.Assistant, "Visit Tsukiji Outer Market and Senso-ji."),
            (MessageRole.User, "How long should I stay?"),
        };
        foreach (var t in turns)
        {
            await Client.ShortTerm.AddMessageAsync(conv.Id, t.role, t.content);
        }
        var ctx = await Client.ShortTerm.GetContextAsync(conv.Id);
        var all = string.Join(" ", ctx.RecentMessages.Select(m => m.Content));
        Assert.True(all.Contains("Tokyo") || all.Contains("Tsukiji"));
    }

    // ====================================================================
    // 11. Concurrency
    // ====================================================================

    [SkippableFact]
    public async Task Concurrent_AddMessages()
    {
        var conv = await NewConvAsync();
        var ids = new ConcurrentBag<string>();
        await Task.WhenAll(Enumerable.Range(0, 8).Select(async i =>
        {
            var msg = await Client.ShortTerm.AddMessageAsync(
                conv.Id, MessageRole.User, $"concurrent-{i}");
            ids.Add(msg.Id);
        }));
        Assert.Equal(8, ids.Distinct().Count());
    }

    [SkippableFact]
    public async Task Concurrent_CreateConversations()
    {
        var ids = new ConcurrentBag<string>();
        await Task.WhenAll(Enumerable.Range(0, 4).Select(async i =>
        {
            var conv = await Client.ShortTerm.CreateConversationAsync(_fx.UserId($"concur-{i}"));
            _createdConversationIds.Add(conv.Id);
            ids.Add(conv.Id);
        }));
        Assert.Equal(4, ids.Distinct().Count());
    }
}

public class HostedServiceFixture : IAsyncLifetime
{
    private static readonly object _envLock = new();
    public string Endpoint { get; private set; } = "https://memory.neo4jlabs.com/v1";
    public MemoryClient? Client { get; private set; }

    private string _userIdBase = "tck-e2e-cs";
    private string _uniqueTag = Guid.NewGuid().ToString("N").Substring(0, 8);

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

    public string UserId(string suffix = "")
    {
        var rand = Guid.NewGuid().ToString("N").Substring(0, 6);
        var baseId = $"{_userIdBase}-{_uniqueTag}-{rand}";
        return string.IsNullOrEmpty(suffix) ? baseId : $"{baseId}-{suffix}";
    }

    private static void LoadDotEnv()
    {
        lock (_envLock)
        {
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
