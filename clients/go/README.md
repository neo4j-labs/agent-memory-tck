# agent-memory-go

[![neo4j-labs](https://img.shields.io/badge/Neo4j_Labs-Experimental-orange?logo=neo4j)](https://neo4j.com/labs/)
[![TCK Bronze](https://img.shields.io/badge/agent--memory--tck-Bronze-F97316?logo=neo4j)](https://github.com/neo4j-labs/agent-memory-tck)

Go client for [neo4j-agent-memory](https://github.com/neo4j-labs/agent-memory) — a shared memory layer for AI agents backed by Neo4j.

> **Neo4j Labs Project** — This is an experimental project under active development. APIs may change without notice.

## Installation

```bash
go get github.com/neo4j-labs/agent-memory-tck/clients/go/memory
```

## Quick Start

```go
package main

import (
    "context"
    "fmt"
    "log"

    "github.com/neo4j-labs/agent-memory-tck/clients/go/memory"
)

func main() {
    ctx := context.Background()

    client, err := memory.New(
        memory.WithEndpoint("https://nams.neo4jsandbox.com"),
        memory.WithAPIKey("your-api-key"),
    )
    if err != nil {
        log.Fatal(err)
    }
    defer client.Close(ctx)

    if err := client.Connect(ctx); err != nil {
        log.Fatal(err)
    }

    // Short-term memory
    msg, _ := client.ShortTerm.AddMessage(ctx, "session-1", memory.RoleUser, "Hello!")
    fmt.Println("Message:", msg.Content)

    conv, _ := client.ShortTerm.GetConversation(ctx, "session-1")
    fmt.Println("Messages:", len(conv.Messages))

    // Long-term memory
    entity, _ := client.LongTerm.AddEntity(ctx, "Alice", "PERSON",
        memory.WithDescription("Software engineer"),
    )
    fmt.Println("Entity:", entity.Name)

    // Reasoning memory
    trace, _ := client.Reasoning.StartTrace(ctx, "session-1", "Research task")
    step, _ := client.Reasoning.AddStep(ctx, trace.ID,
        memory.WithThought("Search for Alice"),
        memory.WithAction("search_entities"),
    )
    client.Reasoning.RecordToolCall(ctx, step.ID, "search_entities",
        map[string]interface{}{"query": "Alice"},
        memory.WithDurationMs(150),
    )
    client.Reasoning.CompleteTrace(ctx, trace.ID,
        memory.WithOutcome("Found Alice"),
        memory.WithSuccess(true),
    )
}
```

## MCP Handler

Expose memory operations as MCP endpoints on any Go HTTP server:

```go
client, _ := memory.New(memory.WithEndpoint("..."))
http.Handle("/mcp/", client.MCPHandler())
http.ListenAndServe(":8080", nil)
```

## API Reference

### Client

| Method | Description |
|--------|-------------|
| `New(opts...)` | Create a new client |
| `Connect(ctx)` | Validate connection |
| `Close(ctx)` | Release resources |

### ShortTerm

| Method | Description |
|--------|-------------|
| `AddMessage(ctx, sessionID, role, content, opts...)` | Add message |
| `GetConversation(ctx, sessionID, opts...)` | Get conversation |
| `SearchMessages(ctx, query, opts...)` | Search messages |
| `ListSessions(ctx, opts...)` | List sessions |
| `DeleteMessage(ctx, messageID)` | Delete message |
| `ClearSession(ctx, sessionID)` | Clear session |

### LongTerm

| Method | Description |
|--------|-------------|
| `AddEntity(ctx, name, entityType, opts...)` | Create entity |
| `AddPreference(ctx, category, preference, opts...)` | Store preference |
| `AddFact(ctx, subject, predicate, obj)` | Store fact |
| `SearchEntities(ctx, query, limit)` | Search entities |
| `GetEntityByName(ctx, name)` | Lookup by name |
| `GetRelatedEntities(ctx, entityID, opts...)` | Traverse relationships |

### Reasoning

| Method | Description |
|--------|-------------|
| `StartTrace(ctx, sessionID, task)` | Start trace |
| `AddStep(ctx, traceID, opts...)` | Add step |
| `RecordToolCall(ctx, stepID, toolName, args, opts...)` | Record tool call |
| `CompleteTrace(ctx, traceID, opts...)` | Complete trace |
| `GetTraceWithSteps(ctx, traceID)` | Get full trace |
| `ListTraces(ctx, opts...)` | List traces |
| `GetToolStats(ctx, toolName)` | Tool statistics |

## TCK Compliance

```bash
# Run conformance server
MEMORY_ENDPOINT=http://localhost:3001 go run ./conformance

# Run Python TCK against it
pytest -m bronze --bridge-url http://localhost:3001
```

## License

Apache 2.0 — See [LICENSE](../../LICENSE).
