# @neo4j-labs/agent-memory

[![neo4j-labs](https://img.shields.io/badge/Neo4j_Labs-Experimental-orange?logo=neo4j)](https://neo4j.com/labs/)
[![TCK Bronze](https://img.shields.io/badge/agent--memory--tck-Bronze-F97316?logo=neo4j)](https://github.com/neo4j-labs/agent-memory-tck)

TypeScript client for [neo4j-agent-memory](https://github.com/neo4j-labs/agent-memory) — a shared memory layer for AI agents backed by Neo4j.

> **Neo4j Labs Project** — This is an experimental project under active development. APIs may change without notice.

## Installation

```bash
npm install @neo4j-labs/agent-memory
```

## Quick Start

```typescript
import { MemoryClient } from "@neo4j-labs/agent-memory";

const client = new MemoryClient({
  endpoint: "https://nams.neo4jsandbox.com",
  apiKey: process.env.MEMORY_API_KEY,
});
await client.connect();

// Short-term memory: conversation history
const msg = await client.shortTerm.addMessage("session-1", "user", "Hello!");
const conversation = await client.shortTerm.getConversation("session-1");

// Long-term memory: knowledge graph
const entity = await client.longTerm.addEntity("Alice Johnson", "PERSON", {
  description: "Software engineer at Acme Corp",
});
const results = await client.longTerm.searchEntities("Alice");

// Reasoning memory: trace agent decisions
const trace = await client.reasoning.startTrace("session-1", "Research task");
const step = await client.reasoning.addStep(trace.id, {
  thought: "I need to search for Alice's employer",
  action: "search_entities",
});
await client.reasoning.recordToolCall(step.id, "search_entities", { query: "Alice" });
await client.reasoning.completeTrace(trace.id, {
  outcome: "Found Alice at Acme Corp",
  success: true,
});

await client.close();
```

## Vercel AI SDK Integration

```typescript
import { streamText } from "ai";
import { MemoryClient } from "@neo4j-labs/agent-memory";
import { agentMemoryMiddleware } from "@neo4j-labs/agent-memory/middleware/vercel-ai";

const client = new MemoryClient({ endpoint: "..." });
await client.connect();

const result = await streamText({
  model: yourModel,
  experimental_middleware: agentMemoryMiddleware(client, {
    sessionId: "user-session-123",
  }),
  messages: [{ role: "user", content: "Tell me about Alice" }],
});
```

## MCP Tools

```typescript
import { createMemoryTools, handleMemoryToolCall } from "@neo4j-labs/agent-memory/mcp";

const tools = createMemoryTools();
// Register with your MCP server...

const result = await handleMemoryToolCall(client, "memory.addEntity", {
  name: "Alice",
  entityType: "PERSON",
});
```

## API Reference

### MemoryClient

| Property | Description |
|----------|-------------|
| `shortTerm` | Short-term (conversational) memory operations |
| `longTerm` | Long-term (entity/preference/fact) memory operations |
| `reasoning` | Reasoning (trace/step/tool call) memory operations |

### Short-Term Memory

| Method | Description |
|--------|-------------|
| `addMessage(sessionId, role, content, options?)` | Add a message to a session |
| `getConversation(sessionId, options?)` | Retrieve conversation with messages |
| `searchMessages(query, options?)` | Semantic search across messages |
| `listSessions(options?)` | List all sessions |
| `deleteMessage(messageId)` | Delete a specific message |
| `clearSession(sessionId)` | Clear all messages in a session |

### Long-Term Memory

| Method | Description |
|--------|-------------|
| `addEntity(name, entityType, options?)` | Create an entity |
| `addPreference(category, preference, options?)` | Store a preference |
| `addFact(subject, predicate, obj)` | Store a fact triple |
| `searchEntities(query, options?)` | Search entities |
| `searchPreferences(query, options?)` | Search preferences |
| `getEntityByName(name)` | Lookup entity by name |
| `getRelatedEntities(entityId, options?)` | Traverse relationships |

### Reasoning Memory

| Method | Description |
|--------|-------------|
| `startTrace(sessionId, task)` | Start a reasoning trace |
| `addStep(traceId, options?)` | Add a step to a trace |
| `recordToolCall(stepId, toolName, args, options?)` | Record a tool call |
| `completeTrace(traceId, options?)` | Complete a trace |
| `getTraceWithSteps(traceId)` | Get full trace with steps |
| `listTraces(options?)` | List traces |
| `getToolStats(toolName?)` | Get tool usage statistics |

## TCK Compliance

This client is tested against the [neo4j-agent-memory TCK](https://github.com/neo4j-labs/agent-memory-tck).

```bash
# Run Bronze-tier conformance tests
npm run tck:bronze

# Run full TCK via Python bridge
MEMORY_ENDPOINT=http://localhost:3001 npm run conformance:server &
pytest -m bronze --bridge-url http://localhost:3001
```

## License

Apache 2.0 — See [LICENSE](../../LICENSE).
