using System.Text.Json.Serialization;

namespace Neo4j.AgentMemory.Models;

/// <summary>Message role in a conversation.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<MessageRole>))]
public enum MessageRole
{
    [JsonStringEnumMemberName("user")]
    User,

    [JsonStringEnumMemberName("assistant")]
    Assistant,

    [JsonStringEnumMemberName("system")]
    System
}

/// <summary>Status of a tool call.</summary>
[JsonConverter(typeof(JsonStringEnumConverter<ToolCallStatus>))]
public enum ToolCallStatus
{
    [JsonStringEnumMemberName("pending")]
    Pending,

    [JsonStringEnumMemberName("success")]
    Success,

    [JsonStringEnumMemberName("failure")]
    Failure,

    [JsonStringEnumMemberName("error")]
    Error,

    [JsonStringEnumMemberName("timeout")]
    Timeout,

    [JsonStringEnumMemberName("cancelled")]
    Cancelled
}
