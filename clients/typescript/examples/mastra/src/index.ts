/**
 * Mastra example — wraps a MemoryClient in Neo4jMastraMemory and exercises
 * the duck-typed Mastra memory provider interface.
 *
 * In a real Mastra app you'd hand this object to `new Agent({ memory })`.
 * Here we drive it directly so you can see the contract without depending
 * on `@mastra/core` at install time.
 */

import { MemoryClient } from "@neo4j-labs/agent-memory";
import { Neo4jMastraMemory } from "@neo4j-labs/agent-memory/integrations/mastra";

async function main() {
  const memory = new Neo4jMastraMemory(new MemoryClient());

  const thread = await memory.createThread({
    resourceId: "mastra-demo-user",
    title: "Vacation planning session",
    metadata: { source: "mastra-example" },
  });
  console.log(`Created thread ${thread.id} for ${thread.resourceId}`);

  await memory.saveMessage({
    threadId: thread.id,
    role: "user",
    content: "I'm planning a 7-day trip to Lisbon.",
  });
  await memory.saveMessage({
    threadId: thread.id,
    role: "assistant",
    content: "Great choice — what are your interests? Food, history, day trips?",
  });
  await memory.saveMessage({
    threadId: thread.id,
    role: "user",
    content: "Food and history.",
  });

  const messages = await memory.getMessages(thread.id);
  console.log(`Recovered ${messages.length} messages:`);
  for (const m of messages) {
    console.log(`  [${m.role}] ${m.content.slice(0, 60)}`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
