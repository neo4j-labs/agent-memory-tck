using System.Text.Json;
using Neo4j.AgentMemory.Models;
using Neo4j.AgentMemory.Transport;

namespace Neo4j.AgentMemory.ShortTerm;

/// <summary>Short-term (conversational) memory operations.</summary>
public class ShortTermMemory
{
    private readonly ITransport _transport;

    internal ShortTermMemory(ITransport transport) { _transport = transport; }

    // ---- Bronze tier (bridge) -------------------------------------------

    public async Task<Message> AddMessageAsync(
        string sessionId, MessageRole role, string content,
        Dictionary<string, object?>? metadata = null,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Message>("add_message", new()
        {
            ["session_id"] = sessionId,
            ["role"] = role.ToWireString(),
            ["content"] = content,
            ["metadata"] = metadata,
        }, ct);
        return result!;
    }

    public async Task<Conversation> GetConversationAsync(
        string sessionId, int? limit = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Conversation>("get_conversation", new()
        {
            ["session_id"] = sessionId,
            ["limit"] = limit,
        }, ct);
        return result!;
    }

    public async Task<List<Message>> SearchMessagesAsync(
        string query, string? sessionId = null, int limit = 10, double threshold = 0.7,
        CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Message>>("search_messages", new()
        {
            ["query"] = query,
            ["session_id"] = sessionId,
            ["limit"] = limit,
            ["threshold"] = threshold,
        }, ct);
        return result ?? new List<Message>();
    }

    public async Task<List<SessionInfo>> ListSessionsAsync(int limit = 100, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<SessionInfo>>("list_sessions", new()
        {
            ["limit"] = limit,
        }, ct);
        return result ?? new List<SessionInfo>();
    }

    public async Task<bool> DeleteMessageAsync(string messageId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<JsonElement>("delete_message", new()
        {
            ["message_id"] = messageId,
        }, ct);
        if (result.ValueKind == JsonValueKind.Object && result.TryGetProperty("deleted", out var deleted))
            return deleted.GetBoolean();
        return false;
    }

    public async Task ClearSessionAsync(string sessionId, CancellationToken ct = default)
    {
        await _transport.RequestAsync("clear_session", new() { ["session_id"] = sessionId }, ct);
    }

    // ---- Volume 5 / hosted-native ----------------------------------------

    /// <summary>Create a new conversation (hosted service).</summary>
    public async Task<Conversation> CreateConversationAsync(
        string userId, Dictionary<string, object?>? metadata = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Conversation>("create_conversation", new()
        {
            ["user_id"] = userId,
            ["metadata"] = metadata,
        }, ct);
        return result!;
    }

    public async Task<List<Conversation>> ListConversationsAsync(int? limit = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Conversation>>("list_conversations", new()
        {
            ["limit"] = limit,
        }, ct);
        return result ?? new List<Conversation>();
    }

    public async Task<Conversation> GetConversationMetadataAsync(string conversationId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<Conversation>("get_conversation_metadata", new()
        {
            ["conversation_id"] = conversationId,
        }, ct);
        return result!;
    }

    public async Task DeleteConversationAsync(string conversationId, CancellationToken ct = default)
    {
        await _transport.RequestAsync("delete_conversation", new()
        {
            ["conversation_id"] = conversationId,
        }, ct);
    }

    /// <summary>Three-tier context (reflections + observations + recent messages).</summary>
    public async Task<ConversationContext> GetContextAsync(string conversationId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<ConversationContext>("get_context", new()
        {
            ["conversation_id"] = conversationId,
        }, ct);
        return result ?? new ConversationContext();
    }

    /// <summary>Bulk-add up to 100 messages to a conversation.</summary>
    public async Task<List<Message>> BulkAddMessagesAsync(
        string conversationId, IEnumerable<BulkMessageInput> messages, CancellationToken ct = default)
    {
        var list = messages.ToList();
        if (list.Count > 100)
            throw new ArgumentException("BulkAddMessages accepts max 100 messages.", nameof(messages));
        var result = await _transport.RequestAsync<List<Message>>("bulk_add_messages", new()
        {
            ["conversation_id"] = conversationId,
            ["messages"] = list,
        }, ct);
        return result ?? new List<Message>();
    }

    public async Task<List<Observation>> GetObservationsAsync(string conversationId, int? limit = null, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Observation>>("get_observations", new()
        {
            ["conversation_id"] = conversationId,
            ["limit"] = limit,
        }, ct);
        return result ?? new List<Observation>();
    }

    public async Task<List<Reflection>> GetReflectionsAsync(string conversationId, CancellationToken ct = default)
    {
        var result = await _transport.RequestAsync<List<Reflection>>("get_reflections", new()
        {
            ["conversation_id"] = conversationId,
        }, ct);
        return result ?? new List<Reflection>();
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
