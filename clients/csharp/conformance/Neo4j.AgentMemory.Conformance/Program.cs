// HTTP bridge conformance server for the C# client.
//
// This server enables the Python TCK test suite to validate the C# client
// by proxying BaseAdapter method calls through the MemoryClient.
//
// Usage:
//   MEMORY_ENDPOINT=https://... dotnet run
//   # Then from the TCK repo:
//   pytest -m bronze --bridge-url http://localhost:3001

using System.Text.Json;
using Neo4j.AgentMemory;
using Neo4j.AgentMemory.Models;

var endpoint = Environment.GetEnvironmentVariable("MEMORY_ENDPOINT");
if (string.IsNullOrEmpty(endpoint))
{
    Console.Error.WriteLine("Set MEMORY_ENDPOINT env var to the upstream service URL");
    return 1;
}

var port = Environment.GetEnvironmentVariable("TCK_BRIDGE_PORT") ?? "3001";

var client = new MemoryClient(new MemoryClientOptions { Endpoint = endpoint });
await client.ConnectAsync();

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
var app = builder.Build();

var jsonOptions = new JsonSerializerOptions
{
    PropertyNamingPolicy = null,
    DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
};

// Helper to read JSON body as a dictionary
async Task<Dictionary<string, JsonElement>> ReadBody(HttpRequest req)
{
    if (req.ContentLength == null || req.ContentLength == 0)
        return new Dictionary<string, JsonElement>();
    var doc = await JsonDocument.ParseAsync(req.Body);
    var dict = new Dictionary<string, JsonElement>();
    foreach (var prop in doc.RootElement.EnumerateObject())
        dict[prop.Name] = prop.Value.Clone();
    return dict;
}

string S(Dictionary<string, JsonElement> body, string key)
{
    if (body.TryGetValue(key, out var val) && val.ValueKind == JsonValueKind.String)
        return val.GetString()!;
    return "";
}

int I(Dictionary<string, JsonElement> body, string key, int def)
{
    if (body.TryGetValue(key, out var val) && val.ValueKind == JsonValueKind.Number)
        return val.GetInt32();
    return def;
}

double D(Dictionary<string, JsonElement> body, string key, double def)
{
    if (body.TryGetValue(key, out var val) && val.ValueKind == JsonValueKind.Number)
        return val.GetDouble();
    return def;
}

bool? B(Dictionary<string, JsonElement> body, string key)
{
    if (body.TryGetValue(key, out var val))
    {
        if (val.ValueKind == JsonValueKind.True) return true;
        if (val.ValueKind == JsonValueKind.False) return false;
    }
    return null;
}

Dictionary<string, object?>? M(Dictionary<string, JsonElement> body, string key)
{
    if (body.TryGetValue(key, out var val) && val.ValueKind == JsonValueKind.Object)
        return JsonSerializer.Deserialize<Dictionary<string, object?>>(val.GetRawText());
    return null;
}

object? V(Dictionary<string, JsonElement> body, string key)
{
    if (!body.TryGetValue(key, out var val)) return null;
    return val.ValueKind switch
    {
        JsonValueKind.String => val.GetString(),
        JsonValueKind.Number => val.GetDouble(),
        JsonValueKind.True => true,
        JsonValueKind.False => false,
        JsonValueKind.Null => null,
        _ => JsonSerializer.Deserialize<object>(val.GetRawText())
    };
}

// --- Lifecycle ---

app.MapPost("/setup", () => Results.Ok(new { ok = true }));

app.MapPost("/teardown", () => Results.NoContent());

app.MapPost("/clear_all_data", async () =>
{
    await client.ClearAllDataAsync();
    return Results.NoContent();
});

// --- Short-Term Memory ---

app.MapPost("/add_message", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var msg = await client.ShortTerm.AddMessageAsync(
        S(body, "session_id"),
        Enum.Parse<MessageRole>(S(body, "role"), ignoreCase: true),
        S(body, "content"),
        M(body, "metadata"));
    return Results.Ok(msg);
});

app.MapPost("/get_conversation", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    int? limit = body.ContainsKey("limit") ? I(body, "limit", 0) : null;
    var conv = await client.ShortTerm.GetConversationAsync(S(body, "session_id"), limit);
    return Results.Ok(conv);
});

app.MapPost("/search_messages", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var sid = S(body, "session_id");
    var msgs = await client.ShortTerm.SearchMessagesAsync(
        S(body, "query"),
        string.IsNullOrEmpty(sid) ? null : sid,
        I(body, "limit", 10),
        D(body, "threshold", 0.7));
    return Results.Ok(msgs);
});

app.MapPost("/list_sessions", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var sessions = await client.ShortTerm.ListSessionsAsync(I(body, "limit", 100));
    return Results.Ok(sessions);
});

app.MapPost("/delete_message", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var deleted = await client.ShortTerm.DeleteMessageAsync(S(body, "message_id"));
    return Results.Ok(new { deleted });
});

app.MapPost("/clear_session", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    await client.ShortTerm.ClearSessionAsync(S(body, "session_id"));
    return Results.NoContent();
});

// --- Long-Term Memory ---

app.MapPost("/add_entity", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var desc = S(body, "description");
    var entity = await client.LongTerm.AddEntityAsync(
        S(body, "name"),
        S(body, "entity_type"),
        string.IsNullOrEmpty(desc) ? null : desc);
    return Results.Ok(entity);
});

app.MapPost("/add_preference", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var ctx = S(body, "context");
    var pref = await client.LongTerm.AddPreferenceAsync(
        S(body, "category"),
        S(body, "preference"),
        string.IsNullOrEmpty(ctx) ? null : ctx);
    return Results.Ok(pref);
});

app.MapPost("/add_fact", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var fact = await client.LongTerm.AddFactAsync(
        S(body, "subject"),
        S(body, "predicate"),
        S(body, "obj"));
    return Results.Ok(fact);
});

app.MapPost("/search_entities", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var entities = await client.LongTerm.SearchEntitiesAsync(
        S(body, "query"),
        I(body, "limit", 10));
    return Results.Ok(entities);
});

app.MapPost("/search_preferences", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var cat = S(body, "category");
    var prefs = await client.LongTerm.SearchPreferencesAsync(
        S(body, "query"),
        string.IsNullOrEmpty(cat) ? null : cat,
        I(body, "limit", 10));
    return Results.Ok(prefs);
});

app.MapPost("/get_entity_by_name", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var entity = await client.LongTerm.GetEntityByNameAsync(S(body, "name"));
    if (entity == null) return Results.Json(null as object, statusCode: 200);
    return Results.Ok(entity);
});

app.MapPost("/get_related_entities", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var relType = S(body, "relationship_type");
    var entities = await client.LongTerm.GetRelatedEntitiesAsync(
        S(body, "entity_id"),
        string.IsNullOrEmpty(relType) ? null : relType,
        I(body, "depth", 1));
    return Results.Ok(entities);
});

app.MapPost("/add_relationship", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var rel = await client.LongTerm.AddRelationshipAsync(
        S(body, "source_id"),
        S(body, "target_id"),
        S(body, "relationship_type"),
        M(body, "properties"));
    return Results.Ok(rel);
});

app.MapPost("/merge_duplicate_entities", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var cn = S(body, "canonical_name");
    var entity = await client.LongTerm.MergeDuplicateEntitiesAsync(
        S(body, "source_id"),
        S(body, "target_id"),
        string.IsNullOrEmpty(cn) ? null : cn);
    return Results.Ok(entity);
});

// --- Reasoning ---

app.MapPost("/start_trace", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var trace = await client.Reasoning.StartTraceAsync(
        S(body, "session_id"),
        S(body, "task"));
    return Results.Ok(trace);
});

app.MapPost("/add_step", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var thought = S(body, "thought");
    var action = S(body, "action");
    var observation = S(body, "observation");
    var step = await client.Reasoning.AddStepAsync(
        S(body, "trace_id"),
        string.IsNullOrEmpty(thought) ? null : thought,
        string.IsNullOrEmpty(action) ? null : action,
        string.IsNullOrEmpty(observation) ? null : observation);
    return Results.Ok(step);
});

app.MapPost("/record_tool_call", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var args = M(body, "arguments") ?? new Dictionary<string, object?>();
    var statusStr = S(body, "status");
    var status = string.IsNullOrEmpty(statusStr)
        ? ToolCallStatus.Success
        : Enum.Parse<ToolCallStatus>(statusStr, ignoreCase: true);
    int? durationMs = body.ContainsKey("duration_ms") ? I(body, "duration_ms", 0) : null;
    var errStr = S(body, "error");
    var tc = await client.Reasoning.RecordToolCallAsync(
        S(body, "step_id"),
        S(body, "tool_name"),
        args,
        V(body, "result"),
        status,
        durationMs,
        string.IsNullOrEmpty(errStr) ? null : errStr);
    return Results.Ok(tc);
});

app.MapPost("/complete_trace", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var outcome = S(body, "outcome");
    var trace = await client.Reasoning.CompleteTraceAsync(
        S(body, "trace_id"),
        string.IsNullOrEmpty(outcome) ? null : outcome,
        B(body, "success"));
    return Results.Ok(trace);
});

app.MapPost("/get_trace_with_steps", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var trace = await client.Reasoning.GetTraceWithStepsAsync(S(body, "trace_id"));
    if (trace == null) return Results.Json(null as object, statusCode: 200);
    return Results.Ok(trace);
});

app.MapPost("/list_traces", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var sid = S(body, "session_id");
    var traces = await client.Reasoning.ListTracesAsync(
        string.IsNullOrEmpty(sid) ? null : sid,
        I(body, "limit", 100));
    return Results.Ok(traces);
});

app.MapPost("/get_tool_stats", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var toolName = S(body, "tool_name");
    var stats = await client.Reasoning.GetToolStatsAsync(
        string.IsNullOrEmpty(toolName) ? null : toolName);
    return Results.Ok(stats);
});

app.MapPost("/get_similar_traces", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var successOnly = B(body, "success_only") ?? true;
    var traces = await client.Reasoning.GetSimilarTracesAsync(
        S(body, "task"),
        I(body, "limit", 5),
        successOnly);
    return Results.Ok(traces);
});

Console.WriteLine($"C# conformance server running on http://localhost:{port}");
Console.WriteLine($"Upstream: {endpoint}");
app.Run();
return 0;
