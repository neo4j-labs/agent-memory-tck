using System.Text.Json;
using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.ShortTerm;

/// <summary>Short-term (conversational) memory operations — Bronze tier.</summary>
public class ShortTermMemory
{
    private readonly ITransport _transport;

    internal ShortTermMemory(ITransport transport)
    {
        _transport = transport;
    }

    /// <summary>Add a message to a session. Creates the session if it doesn't exist.</summary>
    public async Task<Message> AddMessageAsync(
        string sessionId,
        MessageRole role,
        string content,
        Dictionary<string, object?>? metadata = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Message>("add_message", new Dictionary<string, object?>
        {
            ["session_id"] = sessionId,
            ["role"] = role.ToWireString(),
            ["content"] = content,
            ["metadata"] = metadata
        }, ct);
        return result!;
    }

    /// <summary>Retrieve a conversation by session ID with its messages in order.</summary>
    public async Task<Conversation> GetConversationAsync(
        string sessionId,
        int? limit = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Conversation>("get_conversation", new Dictionary<string, object?>
        {
            ["session_id"] = sessionId,
            ["limit"] = limit
        }, ct);
        return result!;
    }

    /// <summary>Search messages by semantic similarity.</summary>
    public async Task<List<Message>> SearchMessagesAsync(
        string query,
        string? sessionId = null,
        int limit = 10,
        double threshold = 0.7,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Message>>("search_messages", new Dictionary<string, object?>
        {
            ["query"] = query,
            ["session_id"] = sessionId,
            ["limit"] = limit,
            ["threshold"] = threshold
        }, ct);
        return result ?? new List<Message>();
    }

    /// <summary>List all sessions.</summary>
    public async Task<List<SessionInfo>> ListSessionsAsync(
        int limit = 100,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<SessionInfo>>("list_sessions", new Dictionary<string, object?>
        {
            ["limit"] = limit
        }, ct);
        return result ?? new List<SessionInfo>();
    }

    /// <summary>Delete a specific message. Returns true if deleted, false if not found.</summary>
    public async Task<bool> DeleteMessageAsync(
        string messageId,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<JsonElement>("delete_message", new Dictionary<string, object?>
        {
            ["message_id"] = messageId
        }, ct);
        if (result.ValueKind == JsonValueKind.Object && result.TryGetProperty("deleted", out var deleted))
            return deleted.GetBoolean();
        return false;
    }

    /// <summary>Delete all data for a specific session.</summary>
    public async Task ClearSessionAsync(
        string sessionId,
        CancellationToken ct = default)
    {
        await _transport.RequestAsync("clear_session", new Dictionary<string, object?>
        {
            ["session_id"] = sessionId
        }, ct);
    }
}

internal static class EnumExtensions
{
    public static string ToWireString(this MessageRole role) => role switch
    {
        MessageRole.User => "user",
        MessageRole.Assistant => "assistant",
        MessageRole.System => "system",
        _ => throw new ArgumentOutOfRangeException(nameof(role))
    };

    public static string ToWireString(this ToolCallStatus status) => status switch
    {
        ToolCallStatus.Pending => "pending",
        ToolCallStatus.Success => "success",
        ToolCallStatus.Failure => "failure",
        ToolCallStatus.Error => "error",
        ToolCallStatus.Timeout => "timeout",
        ToolCallStatus.Cancelled => "cancelled",
        _ => throw new ArgumentOutOfRangeException(nameof(status))
    };
}
