/**
 * LangChain JS example — uses Neo4jChatMessageHistory and
 * Neo4jEntityRetriever directly so you can see the duck-typed shape
 * without depending on any specific @langchain/* version.
 *
 * In a real LangChain JS app you'd pass these to `RunnableWithMessageHistory`
 * and a retriever chain respectively — the duck-typing here means they fit
 * those slots regardless of the LangChain JS minor version.
 */

import { MemoryClient } from "@neo4j-labs/agent-memory";
import {
  Neo4jChatMessageHistory,
  Neo4jEntityRetriever,
} from "@neo4j-labs/agent-memory/integrations/langchain";

async function main() {
  const memory = new MemoryClient();
  const conv = await memory.shortTerm.createConversation({ userId: "langchain-demo" });

  const history = new Neo4jChatMessageHistory(memory, conv.id);

  console.log("Persisting messages via Neo4jChatMessageHistory ...");
  await history.addUserMessage("Tell me about graph databases.");
  await history.addAIMessage(
    "Graph databases store data as nodes and relationships, making path queries fast.",
  );
  await history.addUserMessage("How does that compare to relational?");

  const messages = await history.getMessages();
  console.log(`Retrieved ${messages.length} messages from history.`);
  for (const m of messages) {
    console.log(`  [${m.type}] ${m.content.slice(0, 60)}`);
  }

  console.log("\nLooking up entities via Neo4jEntityRetriever ...");
  await memory.longTerm.addEntity("Neo4j", "concept", {
    description: "Graph database; mentioned in this demo.",
  });
  const retriever = new Neo4jEntityRetriever(memory, { limit: 3 });
  const docs = await retriever.invoke("graph database");
  console.log(`Found ${docs.length} entity-shaped documents.`);
  for (const d of docs) {
    console.log(`  ${d.metadata.name}: ${d.pageContent.slice(0, 60)}`);
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
