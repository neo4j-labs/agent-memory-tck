/**
 * Vercel AI SDK + neo4j-agent-memory — a memory-augmented multi-turn chat.
 *
 * The middleware injects three-tier context (reflections + observations +
 * recent messages) into every model call and persists both sides of the
 * conversation. Run it twice in a row with the same userId to see the
 * second run remember the first.
 */

import { generateText, experimental_wrapLanguageModel as wrapModel } from "ai";
import { openai } from "@ai-sdk/openai";
import { MemoryClient } from "@neo4j-labs/agent-memory";
import { agentMemoryMiddleware } from "@neo4j-labs/agent-memory/middleware/vercel-ai";

async function main() {
  const memory = new MemoryClient();

  // One conversation per user. Reuse the same id across runs to see the
  // assistant carry context from prior sessions.
  const conv = await memory.shortTerm.createConversation({
    userId: process.env.DEMO_USER_ID ?? "demo-user",
    metadata: { source: "vercel-ai-example" },
  });

  const model = wrapModel({
    model: openai("gpt-4o-mini"),
    middleware: agentMemoryMiddleware(memory, { conversationId: conv.id }),
  });

  const turns = [
    "Hi! I'm building a recommendation engine for board games. I love euro-style games.",
    "What kind of dataset would you use?",
    "Given what I just told you about my preferences, suggest a starter project.",
  ];

  for (const userMessage of turns) {
    process.stdout.write(`\n[user] ${userMessage}\n[assistant] `);
    const { text } = await generateText({
      model,
      messages: [{ role: "user", content: userMessage }],
    });
    process.stdout.write(`${text}\n`);
  }

  process.stdout.write(
    `\nConversation persisted as ${conv.id}. ` +
      `Re-run with DEMO_USER_ID=${conv.id} to see context recall.\n`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
