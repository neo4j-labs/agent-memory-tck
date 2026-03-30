using System.Text.Json;
using System.Text.Json.Serialization;

namespace Neo4j.AgentMemory.Models;

/// <summary>Message role in a conversation.</summary>
public enum MessageRole
{
    User,
    Assistant,
    System
}

/// <summary>Status of a tool call.</summary>
public enum ToolCallStatus
{
    Pending,
    Success,
    Failure,
    Error,
    Timeout,
    Cancelled
}

/// <summary>JSON converter that serializes enums as lowercase strings.</summary>
public class LowercaseEnumConverter<T> : JsonConverter<T> where T : struct, Enum
{
    public override T Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options)
    {
        var value = reader.GetString();
        if (value == null) return default;
        return Enum.TryParse<T>(value, ignoreCase: true, out var result) ? result : default;
    }

    public override void Write(Utf8JsonWriter writer, T value, JsonSerializerOptions options)
    {
        writer.WriteStringValue(value.ToString().ToLowerInvariant());
    }
}
