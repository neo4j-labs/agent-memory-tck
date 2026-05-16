# Mastra example

Wraps a `MemoryClient` in `Neo4jMastraMemory` and exercises the duck-typed
Mastra memory provider interface (`createThread`, `saveMessage`, `getMessages`,
`deleteThread`) without depending on `@mastra/core` at install time.

## Run it

```bash
cp .env.example .env       # set MEMORY_API_KEY
npm install
npm start
```

The script creates a thread, persists a three-turn conversation, then
reads it back.

## Using inside a real Mastra app

```ts
import { Agent } from "@mastra/core/agent";
import { MemoryClient } from "@neo4j-labs/agent-memory";
import { Neo4jMastraMemory } from "@neo4j-labs/agent-memory/integrations/mastra";

const memory = new Neo4jMastraMemory(new MemoryClient());

const agent = new Agent({
  name: "scout",
  instructions: "You help users plan trips.",
  memory,
});
```

## See also

- [How-to: Mastra](../../../../docs/how-to/mastra.adoc)
- [Mastra docs](https://mastra.ai)
