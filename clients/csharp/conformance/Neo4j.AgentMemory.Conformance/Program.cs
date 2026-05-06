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

var apiKey = Environment.GetEnvironmentVariable("MEMORY_API_KEY");
var client = new MemoryClient(new MemoryClientOptions { Endpoint = endpoint, ApiKey = apiKey });
try { await client.ConnectAsync(); }
catch (Exception ex) { Console.Error.WriteLine($"warn: connect failed: {ex.Message}"); }

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
var app = builder.Build();

var jsonOptions = new JsonSerializerOptions
{
    PropertyNamingPolicy = null,
    DefaultIgnoreCondition = System.Text.Json.Serialization.JsonIgnoreCondition.WhenWritingNull
};

// Global error handler so unhandled exceptions return JSON errors instead of empty 500 responses
app.Use(async (context, next) =>
{
    try
    {
        await next(context);
    }
    catch (Exception ex)
    {
        context.Response.StatusCode = 500;
        context.Response.ContentType = "application/json";
        var error = JsonSerializer.Serialize(new { error = ex.Message });
        await context.Response.WriteAsync(error);
    }
});

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

app.MapPost("/setup", () => Results.Ok(new { ok = true, protocol_version = "0.2.0" }));

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

// --- Volume 5 / Platinum (hosted-native) ---

app.MapPost("/create_conversation", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var conv = await client.ShortTerm.CreateConversationAsync(S(body, "user_id"), M(body, "metadata"));
    return Results.Ok(conv);
});

app.MapPost("/list_conversations", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    int? limit = body.ContainsKey("limit") ? I(body, "limit", 0) : null;
    var convs = await client.ShortTerm.ListConversationsAsync(limit);
    return Results.Ok(convs);
});

app.MapPost("/get_conversation_metadata", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var conv = await client.ShortTerm.GetConversationMetadataAsync(S(body, "conversation_id"));
    return Results.Ok(conv);
});

app.MapPost("/delete_conversation", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    await client.ShortTerm.DeleteConversationAsync(S(body, "conversation_id"));
    return Results.NoContent();
});

app.MapPost("/get_context", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var ctx = await client.ShortTerm.GetContextAsync(S(body, "conversation_id"));
    return Results.Ok(ctx);
});

app.MapPost("/bulk_add_messages", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var raw = body.ContainsKey("messages") ? body["messages"] : default;
    var messages = new List<BulkMessageInput>();
    if (raw.ValueKind == JsonValueKind.Array)
    {
        foreach (var item in raw.EnumerateArray())
        {
            messages.Add(new BulkMessageInput
            {
                Role = item.TryGetProperty("role", out var r) ? r.GetString() ?? "user" : "user",
                Content = item.TryGetProperty("content", out var c) ? c.GetString() ?? "" : "",
                Metadata = item.TryGetProperty("metadata", out var m) && m.ValueKind == JsonValueKind.Object
                    ? JsonSerializer.Deserialize<Dictionary<string, object?>>(m.GetRawText()) : null,
            });
        }
    }
    var msgs = await client.ShortTerm.BulkAddMessagesAsync(S(body, "conversation_id"), messages);
    return Results.Ok(msgs);
});

app.MapPost("/get_observations", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    int? limit = body.ContainsKey("limit") ? I(body, "limit", 0) : null;
    var obs = await client.ShortTerm.GetObservationsAsync(S(body, "conversation_id"), limit);
    return Results.Ok(obs);
});

app.MapPost("/get_reflections", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var refl = await client.ShortTerm.GetReflectionsAsync(S(body, "conversation_id"));
    return Results.Ok(refl);
});

app.MapPost("/list_entities", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var t = S(body, "type");
    int? limit = body.ContainsKey("limit") ? I(body, "limit", 0) : null;
    var ents = await client.LongTerm.ListEntitiesAsync(string.IsNullOrEmpty(t) ? null : t, limit);
    return Results.Ok(ents);
});

app.MapPost("/get_entity", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var ent = await client.LongTerm.GetEntityAsync(S(body, "entity_id"));
    return Results.Ok(ent);
});

app.MapPost("/update_entity", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var name = S(body, "name");
    var desc = S(body, "description");
    var ent = await client.LongTerm.UpdateEntityAsync(
        S(body, "entity_id"),
        string.IsNullOrEmpty(name) ? null : name,
        string.IsNullOrEmpty(desc) ? null : desc);
    return Results.Ok(ent);
});

app.MapPost("/delete_entity", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    await client.LongTerm.DeleteEntityAsync(S(body, "entity_id"));
    return Results.NoContent();
});

app.MapPost("/set_entity_feedback", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var fb = await client.LongTerm.SetEntityFeedbackAsync(
        S(body, "entity_id"),
        D(body, "user_score", 0),
        B(body, "confirmed") ?? false);
    return Results.Ok(fb);
});

app.MapPost("/get_entity_history", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var hist = await client.LongTerm.GetEntityHistoryAsync(S(body, "entity_id"));
    return Results.Ok(hist);
});

app.MapPost("/merge_entities", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var res = await client.LongTerm.MergeEntitiesAsync(S(body, "source_id"), S(body, "target_id"));
    return Results.Ok(res);
});

app.MapPost("/get_entity_graph", async () =>
{
    var graph = await client.LongTerm.GetEntityGraphAsync();
    return Results.Ok(graph);
});

app.MapPost("/record_step", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var resStr = S(body, "result");
    var step = await client.Reasoning.RecordStepAsync(
        S(body, "conversation_id"),
        S(body, "reasoning"),
        S(body, "action_taken"),
        string.IsNullOrEmpty(resStr) ? null : resStr);
    return Results.Ok(step);
});

app.MapPost("/list_steps", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var steps = await client.Reasoning.ListStepsAsync(S(body, "conversation_id"));
    return Results.Ok(steps);
});

app.MapPost("/explain_step", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var ex = await client.Reasoning.ExplainStepAsync(S(body, "step_id"));
    return Results.Ok(ex);
});

app.MapPost("/get_trace_by_conversation", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var t = await client.Reasoning.GetTraceByConversationAsync(S(body, "conversation_id"));
    return Results.Ok(t);
});

app.MapPost("/get_entity_provenance", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var p = await client.Reasoning.GetEntityProvenanceAsync(S(body, "entity_id"));
    return Results.Ok(p);
});

app.MapPost("/cypher_query", async (HttpRequest req) =>
{
    var body = await ReadBody(req);
    var result = await client.Query.CypherAsync(S(body, "cypher"), M(body, "params"));
    return Results.Ok(result);
});

Console.WriteLine($"C# conformance server running on http://localhost:{port}");
Console.WriteLine($"Upstream: {endpoint}");
app.Run();
return 0;
