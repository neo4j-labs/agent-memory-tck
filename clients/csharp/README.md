# Neo4j.AgentMemory — C# Client

[![.NET 8](https://img.shields.io/badge/.NET-8.0+-512bd4.svg?logo=dotnet)](https://dotnet.microsoft.com/)
[![License](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](../../LICENSE)

C# client for [neo4j-agent-memory](https://github.com/neo4j-labs/agent-memory) — short-term, long-term, and reasoning memory for AI agents backed by Neo4j.

## Quick Start

```csharp
using Neo4j.AgentMemory;
using Neo4j.AgentMemory.Models;

await using var client = new MemoryClient(new MemoryClientOptions
{
    Endpoint = "https://memory.cypherlite.cloud"
});
await client.ConnectAsync();

// Short-term memory
var msg = await client.ShortTerm.AddMessageAsync("session-1", MessageRole.User, "Hello!");
var conv = await client.ShortTerm.GetConversationAsync("session-1");

// Long-term memory
var entity = await client.LongTerm.AddEntityAsync("Alice", "PERSON", description: "Engineer");
var fact = await client.LongTerm.AddFactAsync("Alice", "WORKS_AT", "Acme Corp");

// Reasoning memory
var trace = await client.Reasoning.StartTraceAsync("session-1", "Research task");
var step = await client.Reasoning.AddStepAsync(trace.Id, thought: "Analyzing data", action: "search");
await client.Reasoning.CompleteTraceAsync(trace.Id, outcome: "Found results", success: true);
```

## Architecture

The client mirrors the TypeScript and Go implementations:

```
MemoryClient
├── ShortTerm   — AddMessage, GetConversation, SearchMessages, ListSessions, DeleteMessage, ClearSession
├── LongTerm    — AddEntity, AddPreference, AddFact, SearchEntities, SearchPreferences, GetEntityByName, GetRelatedEntities, AddRelationship, MergeDuplicateEntities
└── Reasoning   — StartTrace, AddStep, RecordToolCall, CompleteTrace, GetTraceWithSteps, ListTraces, GetToolStats, GetSimilarTraces
```

All methods are `async Task<T>` with optional `CancellationToken`. The client implements `IAsyncDisposable` for deterministic cleanup.

## Building

```bash
dotnet build Neo4j.AgentMemory.sln
dotnet test tests/Neo4j.AgentMemory.Tests/
```

## Conformance Testing

The conformance server enables the Python TCK to validate this C# implementation via the HTTP bridge protocol:

```bash
# Terminal 1: Start the conformance server (proxies to upstream memory service)
MEMORY_ENDPOINT=http://localhost:7474 dotnet run --project conformance/Neo4j.AgentMemory.Conformance

# Terminal 2: Run the TCK against it
cd ../..
uv run pytest -m bronze --bridge-url http://localhost:3001 -v
```

## Project Structure

```
clients/csharp/
├── src/Neo4j.AgentMemory/           # Core library
│   ├── MemoryClient.cs              # Root client composing sub-clients
│   ├── Transport/                   # ITransport + HttpTransport
│   ├── ShortTerm/                   # Short-term memory operations
│   ├── LongTerm/                    # Long-term memory operations
│   ├── Reasoning/                   # Reasoning memory operations
│   ├── Models/                      # Data models (Message, Entity, etc.)
│   └── Errors/                      # Exception hierarchy
├── conformance/                     # TCK bridge conformance server
├── tests/                           # xUnit tests
└── Neo4j.AgentMemory.sln
```
