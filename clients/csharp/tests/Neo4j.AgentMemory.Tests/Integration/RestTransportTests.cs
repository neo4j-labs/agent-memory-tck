using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using Neo4j.AgentMemory.Errors;
using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;
using Xunit;

namespace Neo4j.AgentMemory.Tests.Integration;

/// <summary>
/// Integration tests — RestTransport against a stub HttpMessageHandler.
/// Validates URL assembly, headers, body camelCasing, and response parsing.
/// </summary>
[Trait("Category", "Integration")]
public class RestTransportTests
{
    private const string Endpoint = "https://memory.test/v1";
    private const string ApiKey = "nams_test_key";

    private static (StubHandler handler, MemoryClient client) NewClient(StubResponse response)
    {
        var handler = new StubHandler(response);
        var http = new HttpClient(handler) { BaseAddress = new Uri(Endpoint) };
        // Build a RestTransport with the stub by routing through HttpClient via a derived class.
        var transport = new RestTransportWithHttpClient(Endpoint, ApiKey, http);
        var client = new MemoryClient(transport);
        return (handler, client);
    }

    [Fact]
    public async Task CreateConversation_SendsCamelCaseBodyAndParsesResponse()
    {
        var (handler, client) = NewClient(new StubResponse(
            HttpStatusCode.OK,
            "{\"id\":\"conv-1\",\"userId\":\"alice\",\"workspaceId\":\"ws\",\"createdAt\":\"2026-05-07T00:00:00Z\"}"));

        var conv = await client.ShortTerm.CreateConversationAsync("alice",
            metadata: new Dictionary<string, object?> { ["source"] = "test" });

        Assert.Equal(HttpMethod.Post, handler.LastRequest!.Method);
        Assert.EndsWith("/conversations", handler.LastRequest.RequestUri!.AbsolutePath);
        Assert.Equal("Bearer nams_test_key", handler.LastRequest.Headers.Authorization!.ToString());
        Assert.Contains("\"userId\":\"alice\"", handler.LastBody);
        Assert.Equal("conv-1", conv.Id);
        Assert.Equal("ws", conv.WorkspaceId);
    }

    [Fact]
    public async Task GetContext_SubstitutesPathParam_ReturnsThreeTier()
    {
        var (handler, client) = NewClient(new StubResponse(
            HttpStatusCode.OK,
            "{\"reflections\":[{\"id\":\"r1\",\"conversationId\":\"conv-42\",\"content\":\"clarity\",\"createdAt\":\"2026-05-07T00:00:00Z\"}],\"observations\":[],\"recentMessages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hi\"}]}"));

        var ctx = await client.ShortTerm.GetContextAsync("conv-42");

        Assert.EndsWith("/conversations/conv-42/context", handler.LastRequest!.RequestUri!.AbsolutePath);
        Assert.Equal(HttpMethod.Get, handler.LastRequest.Method);
        Assert.Single(ctx.Reflections);
        Assert.Single(ctx.RecentMessages);
    }

    [Fact]
    public async Task ListConversations_UnwrapsEnvelope()
    {
        var (_, client) = NewClient(new StubResponse(
            HttpStatusCode.OK,
            "{\"conversations\":[{\"id\":\"c1\",\"userId\":\"a\",\"createdAt\":\"2026-05-07T00:00:00Z\"},{\"id\":\"c2\",\"userId\":\"b\",\"createdAt\":\"2026-05-07T00:00:00Z\"}]}"));

        var convs = await client.ShortTerm.ListConversationsAsync(limit: 10);

        Assert.Equal(2, convs.Count);
        Assert.Equal("c1", convs[0].Id);
        Assert.Equal("c2", convs[1].Id);
    }

    [Fact]
    public async Task SetEntityFeedback_PutsCamelCaseBody()
    {
        var (handler, client) = NewClient(new StubResponse(
            HttpStatusCode.OK, "{\"id\":\"e1\",\"updated\":true}"));

        var result = await client.LongTerm.SetEntityFeedbackAsync("e1", userScore: 0.95, confirmed: true);

        Assert.Equal(HttpMethod.Put, handler.LastRequest!.Method);
        Assert.EndsWith("/entities/e1/feedback", handler.LastRequest.RequestUri!.AbsolutePath);
        Assert.Contains("\"userScore\":0.95", handler.LastBody);
        Assert.Contains("\"confirmed\":true", handler.LastBody);
        Assert.True(result.Updated);
    }

    [Fact]
    public async Task CypherQuery_SendsParams()
    {
        var (handler, client) = NewClient(new StubResponse(
            HttpStatusCode.OK,
            "{\"columns\":[\"name\"],\"rows\":[[\"Alice\"]]}"));

        var result = await client.Query.CypherAsync(
            "MATCH (n) RETURN n.name AS name LIMIT $n",
            new Dictionary<string, object?> { ["n"] = 1 });

        Assert.EndsWith("/query", handler.LastRequest!.RequestUri!.AbsolutePath);
        Assert.Contains("MATCH", handler.LastBody);
        Assert.Equal(new[] { "name" }, result.Columns.ToArray());
    }

    [Fact]
    public async Task Status401_RaisesAuthenticationError()
    {
        var (_, client) = NewClient(new StubResponse(
            HttpStatusCode.Unauthorized, "{\"error\":\"bad token\"}"));

        await Assert.ThrowsAsync<AuthenticationException>(() =>
            client.ShortTerm.ListConversationsAsync());
    }

    [Fact]
    public async Task Status500_RaisesTransportError()
    {
        var (_, client) = NewClient(new StubResponse(
            HttpStatusCode.InternalServerError, "{\"error\":\"boom\"}"));

        var ex = await Assert.ThrowsAsync<TransportException>(() =>
            client.ShortTerm.CreateConversationAsync("alice"));
        Assert.Equal(500, ex.StatusCode);
    }

    [Fact]
    public async Task LegacyMethod_ThrowsNotSupported()
    {
        var (_, client) = NewClient(new StubResponse(HttpStatusCode.OK, "{}"));
        await Assert.ThrowsAsync<NotSupportedException>(() =>
            client.LongTerm.AddPreferenceAsync("style", "concise"));
    }

    [Fact]
    public async Task TokenProvider_CalledPerRequest()
    {
        var calls = 0;
        Task<string> Provider(CancellationToken ct)
        {
            calls++;
            return Task.FromResult($"token-{calls}");
        }

        var observed = new List<string?>();
        var handler = new RecordingHandler(req =>
        {
            observed.Add(req.Headers.Authorization?.ToString());
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent("{\"conversations\":[]}", Encoding.UTF8, "application/json"),
            };
        });
        var http = new HttpClient(handler) { BaseAddress = new Uri(Endpoint) };
        var transport = new RestTransportWithHttpClient(Endpoint, apiKey: null, http: http, tokenProvider: Provider);
        var client = new MemoryClient(transport);

        await client.ShortTerm.ListConversationsAsync();
        await client.ShortTerm.ListConversationsAsync();

        Assert.Equal(2, calls);
        Assert.Equal("Bearer token-1", observed[0]);
        Assert.Equal("Bearer token-2", observed[1]);
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    private record StubResponse(HttpStatusCode Status, string Body);

    private class StubHandler : HttpMessageHandler
    {
        public HttpRequestMessage? LastRequest;
        public string LastBody = "";
        private readonly StubResponse _resp;

        public StubHandler(StubResponse resp) { _resp = resp; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            LastRequest = request;
            if (request.Content != null)
            {
                LastBody = await request.Content.ReadAsStringAsync(cancellationToken);
            }
            return new HttpResponseMessage(_resp.Status)
            {
                Content = new StringContent(_resp.Body, Encoding.UTF8, "application/json"),
            };
        }
    }

    private class RecordingHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _fn;
        public RecordingHandler(Func<HttpRequestMessage, HttpResponseMessage> fn) { _fn = fn; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            return Task.FromResult(_fn(request));
        }
    }

    /// <summary>RestTransport variant that injects a pre-built HttpClient (for testing).</summary>
    private class RestTransportWithHttpClient : ITransport
    {
        private readonly RestTransport _inner;

        public RestTransportWithHttpClient(string endpoint, string? apiKey, HttpClient http,
            TokenProvider? tokenProvider = null)
        {
            // We can't replace HttpClient on the existing RestTransport, so
            // we build a new RestTransport pointing at the BaseAddress of
            // the supplied HttpClient. Tests rely on the BaseAddress being
            // the same Endpoint string.
            _inner = new RestTransport(endpoint, apiKey, TimeSpan.FromSeconds(30), tokenProvider);
            // Replace the private _httpClient field.
            var field = typeof(RestTransport).GetField("_httpClient",
                System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!;
            field.SetValue(_inner, http);
        }

        public Task<T?> RequestAsync<T>(string method, Dictionary<string, object?>? parameters = null,
            CancellationToken ct = default) => _inner.RequestAsync<T>(method, parameters, ct);

        public Task RequestAsync(string method, Dictionary<string, object?>? parameters = null,
            CancellationToken ct = default) => _inner.RequestAsync(method, parameters, ct);

        public Task ConnectAsync(CancellationToken ct = default) => _inner.ConnectAsync(ct);

        public Task CloseAsync(CancellationToken ct = default) => _inner.CloseAsync(ct);
    }
}
