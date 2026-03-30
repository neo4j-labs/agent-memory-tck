// Sage — Knowledge Validation Agent (C# / Semantic Kernel)
//
// Sage reads the shared knowledge graph, detects contradictions between
// facts from different agents, and produces confidence scores.
// Works without an LLM key for programmatic validation; optionally uses
// Semantic Kernel for natural-language conflict summaries.
//
// Endpoints:
//   GET  /health    — Health check
//   POST /validate  — Detect contradictions in facts about an entity
//   POST /audit     — Audit knowledge graph integrity

using Neo4j.AgentMemory;
using Neo4j.AgentMemory.Models;
using Sage.Services;

var memoryEndpoint = Environment.GetEnvironmentVariable("MEMORY_ENDPOINT") ?? "http://localhost:3001";
var port = Environment.GetEnvironmentVariable("PORT") ?? "8005";

var client = new MemoryClient(new MemoryClientOptions { Endpoint = memoryEndpoint });
await client.ConnectAsync();

var conflictDetector = new ConflictDetector(client);
var knowledgeAuditor = new KnowledgeAuditor(client);

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls($"http://0.0.0.0:{port}");
var app = builder.Build();

// --- Health ---
app.MapGet("/health", () => Results.Ok(new
{
    agent = "Sage",
    framework = "Semantic Kernel",
    language = "C#",
    status = "healthy"
}));

// --- Validate ---
app.MapPost("/validate", async (HttpRequest req) =>
{
    using var doc = await System.Text.Json.JsonDocument.ParseAsync(req.Body);
    var entityName = doc.RootElement.GetProperty("entity_name").GetString() ?? "";

    var result = await conflictDetector.ValidateEntityAsync(entityName);
    return Results.Ok(result);
});

// --- Audit ---
app.MapPost("/audit", async (HttpRequest req) =>
{
    string? query = null;
    if (req.ContentLength > 0)
    {
        using var doc = await System.Text.Json.JsonDocument.ParseAsync(req.Body);
        if (doc.RootElement.TryGetProperty("query", out var q))
            query = q.GetString();
    }

    var result = await knowledgeAuditor.AuditAsync(query);
    return Results.Ok(result);
});

Console.WriteLine($"Sage (C#/Semantic Kernel) running on http://localhost:{port}");
Console.WriteLine($"Memory endpoint: {memoryEndpoint}");
app.Run();
