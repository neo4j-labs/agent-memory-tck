using System.Text.Json;
using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;
using NSubstitute;
using Xunit;
using FactAttribute = Xunit.FactAttribute;

namespace Neo4j.AgentMemory.Tests;

public class ShortTermTests
{
    private readonly ITransport _transport = Substitute.For<ITransport>();
    private readonly MemoryClient _client;

    public ShortTermTests()
    {
        _client = new MemoryClient(_transport);
    }

    [Fact]
    public async Task AddMessage_SendsCorrectParameters()
    {
        var expected = new Message
        {
            Id = "msg-1",
            Role = "user",
            Content = "Hello",
            Timestamp = "2026-01-01T00:00:00Z"
        };

        _transport.RequestAsync<Message>("add_message", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.ShortTerm.AddMessageAsync("sess-1", MessageRole.User, "Hello");

        Assert.Equal("msg-1", result.Id);
        Assert.Equal("user", result.Role);
        Assert.Equal("Hello", result.Content);

        await _transport.Received(1).RequestAsync<Message>("add_message",
            Arg.Is<Dictionary<string, object?>?>(d =>
                d != null &&
                (string)d["session_id"]! == "sess-1" &&
                (string)d["role"]! == "user" &&
                (string)d["content"]! == "Hello"),
            Arg.Any<CancellationToken>());
    }

    [Fact]
    public async Task AddMessage_WithMetadata_IncludesMetadata()
    {
        var meta = new Dictionary<string, object?> { ["key"] = "value" };
        _transport.RequestAsync<Message>("add_message", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(new Message { Id = "msg-2", Role = "user", Content = "Hi" });

        await _client.ShortTerm.AddMessageAsync("sess-1", MessageRole.User, "Hi", meta);

        await _transport.Received(1).RequestAsync<Message>("add_message",
            Arg.Is<Dictionary<string, object?>?>(d =>
                d != null && d.ContainsKey("metadata") && d["metadata"] != null),
            Arg.Any<CancellationToken>());
    }

    [Fact]
    public async Task GetConversation_ReturnsConversation()
    {
        var expected = new Conversation
        {
            Id = "conv-1",
            SessionId = "sess-1",
            Messages = new List<Message>(),
            CreatedAt = "2026-01-01T00:00:00Z"
        };

        _transport.RequestAsync<Conversation>("get_conversation", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(expected);

        var result = await _client.ShortTerm.GetConversationAsync("sess-1");
        Assert.Equal("conv-1", result.Id);
        Assert.Equal("sess-1", result.SessionId);
    }

    [Fact]
    public async Task DeleteMessage_ReturnsBool()
    {
        var jsonElement = JsonSerializer.Deserialize<JsonElement>("{\"deleted\":true}");
        _transport.RequestAsync<JsonElement>("delete_message", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns(jsonElement);

        var result = await _client.ShortTerm.DeleteMessageAsync("msg-1");
        Assert.True(result);
    }

    [Fact]
    public async Task ListSessions_ReturnsEmptyListOnNull()
    {
        _transport.RequestAsync<List<SessionInfo>>("list_sessions", Arg.Any<Dictionary<string, object?>?>(), Arg.Any<CancellationToken>())
            .Returns((List<SessionInfo>?)null);

        var result = await _client.ShortTerm.ListSessionsAsync();
        Assert.Empty(result);
    }

    [Fact]
    public async Task ClearSession_CallsTransport()
    {
        await _client.ShortTerm.ClearSessionAsync("sess-1");

        await _transport.Received(1).RequestAsync("clear_session",
            Arg.Is<Dictionary<string, object?>?>(d =>
                d != null && (string)d["session_id"]! == "sess-1"),
            Arg.Any<CancellationToken>());
    }
}
