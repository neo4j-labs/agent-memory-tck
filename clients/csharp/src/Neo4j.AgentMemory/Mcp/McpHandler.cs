using System.Text.Json;
using Neo4j.AgentMemory.Models;

namespace Neo4j.AgentMemory.Mcp;

/// <summary>JSON Schema-compatible MCP tool descriptor.</summary>
public class McpToolDefinition
{
    public string Name { get; set; } = "";
    public string Description { get; set; } = "";
    public Dictionary<string, object?> InputSchema { get; set; } = new();
}

/// <summary>
/// 12-tool MCP surface matching the hosted server at memory.neo4jlabs.com/mcp.
/// </summary>
public static class McpHandler
{
    public static List<McpToolDefinition> Tools()
    {
        Dictionary<string, object?> Prop(string type, string desc) =>
            new() { ["type"] = type, ["description"] = desc };

        Dictionary<string, object?> ObjSchema(Dictionary<string, object?> props, params string[] required)
        {
            var schema = new Dictionary<string, object?>
            {
                ["type"] = "object",
                ["properties"] = props,
            };
            if (required.Length > 0) schema["required"] = required;
            return schema;
        }

        return new List<McpToolDefinition>
        {
            new() { Name = "memory_create_conversation", Description = "Create a new conversation session for a user.",
                InputSchema = ObjSchema(new() { ["user_id"] = Prop("string", "User identifier"), ["metadata"] = new Dictionary<string, object?> { ["type"] = "object" } }, "user_id") },
            new() { Name = "memory_add_messages", Description = "Append one or more messages to a conversation.",
                InputSchema = ObjSchema(new() { ["conversation_id"] = Prop("string", "Conversation id"), ["messages"] = new Dictionary<string, object?> { ["type"] = "array" } }, "conversation_id", "messages") },
            new() { Name = "memory_get_context", Description = "Three-tier context.",
                InputSchema = ObjSchema(new() { ["conversation_id"] = Prop("string", "Conversation id") }, "conversation_id") },
            new() { Name = "memory_search_messages", Description = "Search messages within a conversation.",
                InputSchema = ObjSchema(new() { ["conversation_id"] = Prop("string", "Conversation id"), ["query"] = Prop("string", "Search query"), ["limit"] = Prop("number", "Max results") }, "conversation_id", "query") },
            new() { Name = "memory_search_entities", Description = "Search the knowledge graph for entities.",
                InputSchema = ObjSchema(new() { ["query"] = Prop("string", "Query"), ["type"] = Prop("string", "Type filter"), ["limit"] = Prop("number", "Max") }, "query") },
            new() { Name = "memory_get_entity", Description = "Fetch one entity (with relationships) by id.",
                InputSchema = ObjSchema(new() { ["entity_id"] = Prop("string", "Entity id") }, "entity_id") },
            new() { Name = "memory_add_entity", Description = "Manually create an entity.",
                InputSchema = ObjSchema(new() { ["name"] = Prop("string", "Name"), ["type"] = Prop("string", "Type"), ["description"] = Prop("string", "Description") }, "name", "type") },
            new() { Name = "memory_get_entity_history", Description = "All conversations that mentioned this entity.",
                InputSchema = ObjSchema(new() { ["entity_id"] = Prop("string", "Entity id") }, "entity_id") },
            new() { Name = "memory_record_step", Description = "Log a reasoning step under a conversation.",
                InputSchema = ObjSchema(new() { ["conversation_id"] = Prop("string", "Conversation id"), ["reasoning"] = Prop("string", "Reasoning"), ["action_taken"] = Prop("string", "Action"), ["result"] = Prop("string", "Result") }, "conversation_id", "reasoning", "action_taken") },
            new() { Name = "memory_record_tool_call", Description = "Log a tool invocation tied to a reasoning step.",
                InputSchema = ObjSchema(new() { ["step_id"] = Prop("string", "Step id"), ["tool_name"] = Prop("string", "Tool name"), ["input"] = Prop("string", "Input"), ["output"] = Prop("string", "Output"), ["status"] = Prop("string", "Status"), ["duration_ms"] = Prop("number", "Duration") }, "tool_name", "status") },
            new() { Name = "memory_get_trace", Description = "Full reasoning trace for a conversation.",
                InputSchema = ObjSchema(new() { ["conversation_id"] = Prop("string", "Conversation id") }, "conversation_id") },
            new() { Name = "memory_explain_decision", Description = "Detailed explanation of one reasoning step.",
                InputSchema = ObjSchema(new() { ["step_id"] = Prop("string", "Step id") }, "step_id") },
        };
    }

    /// <summary>Dispatch one of the 12 standard MCP tool calls to a MemoryClient.</summary>
    public static async Task<object?> DispatchAsync(MemoryClient client, string toolName, Dictionary<string, object?> args, CancellationToken ct = default)
    {
        string GetStr(string key) => args.TryGetValue(key, out var v) && v != null ? v.ToString()! : "";
        int GetInt(string key, int def)
        {
            if (!args.TryGetValue(key, out var v) || v == null) return def;
            if (v is int i) return i;
            if (v is long l) return (int)l;
            if (v is double d) return (int)d;
            if (int.TryParse(v.ToString(), out var parsed)) return parsed;
            return def;
        }
        Dictionary<string, object?>? GetMap(string key)
        {
            if (!args.TryGetValue(key, out var v)) return null;
            return v as Dictionary<string, object?>;
        }

        return toolName switch
        {
            "memory_create_conversation" =>
                await client.ShortTerm.CreateConversationAsync(GetStr("user_id"), GetMap("metadata"), ct),
            "memory_add_messages" => await DispatchAddMessagesAsync(client, GetStr("conversation_id"), args, ct),
            "memory_get_context" =>
                await client.ShortTerm.GetContextAsync(GetStr("conversation_id"), ct),
            "memory_search_messages" =>
                await client.ShortTerm.SearchMessagesAsync(GetStr("query"), GetStr("conversation_id"), GetInt("limit", 10), 0.0, ct),
            "memory_search_entities" =>
                await client.LongTerm.SearchEntitiesAsync(GetStr("query"), GetInt("limit", 10), GetStr("type") is var t && t.Length > 0 ? t : null, ct),
            "memory_get_entity" =>
                await client.LongTerm.GetEntityAsync(GetStr("entity_id"), ct),
            "memory_add_entity" =>
                await client.LongTerm.AddEntityAsync(GetStr("name"), GetStr("type"), GetStr("description") is var d && d.Length > 0 ? d : null, ct),
            "memory_get_entity_history" =>
                await client.LongTerm.GetEntityHistoryAsync(GetStr("entity_id"), ct),
            "memory_record_step" =>
                await client.Reasoning.RecordStepAsync(GetStr("conversation_id"), GetStr("reasoning"), GetStr("action_taken"), GetStr("result") is var r && r.Length > 0 ? r : null, ct),
            "memory_record_tool_call" =>
                await client.Reasoning.RecordToolCallAsync(
                    GetStr("step_id"),
                    GetStr("tool_name"),
                    new Dictionary<string, object?> { ["input"] = GetStr("input") },
                    GetStr("output"),
                    GetStr("status") switch { "error" => ToolCallStatus.Error, "timeout" => ToolCallStatus.Timeout, _ => ToolCallStatus.Success },
                    GetInt("duration_ms", 0) is var ms && ms > 0 ? ms : null,
                    null, ct),
            "memory_get_trace" =>
                await client.Reasoning.GetTraceByConversationAsync(GetStr("conversation_id"), ct),
            "memory_explain_decision" =>
                await client.Reasoning.ExplainStepAsync(GetStr("step_id"), ct),
            _ => throw new ArgumentException($"Unknown memory tool: {toolName}"),
        };
    }

    private static async Task<List<Message>> DispatchAddMessagesAsync(MemoryClient client, string conversationId, Dictionary<string, object?> args, CancellationToken ct)
    {
        if (!args.TryGetValue("messages", out var raw) || raw is not List<object?> rawList || rawList.Count == 0)
            return new List<Message>();
        var messages = new List<BulkMessageInput>();
        foreach (var item in rawList)
        {
            if (item is not Dictionary<string, object?> m) continue;
            messages.Add(new BulkMessageInput
            {
                Role = m.GetValueOrDefault("role")?.ToString() ?? "user",
                Content = m.GetValueOrDefault("content")?.ToString() ?? "",
                Metadata = m.GetValueOrDefault("metadata") as Dictionary<string, object?>,
            });
        }
        if (messages.Count == 1)
        {
            var only = messages[0];
            var role = only.Role switch { "assistant" => MessageRole.Assistant, "system" => MessageRole.System, _ => MessageRole.User };
            return new List<Message> { await client.ShortTerm.AddMessageAsync(conversationId, role, only.Content, only.Metadata, ct) };
        }
        return await client.ShortTerm.BulkAddMessagesAsync(conversationId, messages, ct);
    }

    /// <summary>Convenience — serialize a tool list as MCP-compatible JSON.</summary>
    public static string ToolsJson() => JsonSerializer.Serialize(new { tools = Tools() });
}
