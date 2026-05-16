/**
 * AWS Strands + neo4j-agent-memory — a memory-augmented agent.
 *
 * Demonstrates the three integration surfaces:
 *  1. Neo4jSessionStorage  — agent state persists to NAMS automatically
 *  2. Neo4jConversationManager — three-tier context injected before each turn
 *  3. registerReasoningHooks — reasoning steps + tool calls captured in the graph
 *
 * Wired via the single `connectMemoryToAgent` factory.
 */

import { Agent, FunctionTool } from "@strands-agents/sdk";
import { OpenAIModel } from "@strands-agents/sdk/models/openai";
import { z } from "zod";
import { MemoryClient } from "@neo4j-labs/agent-memory";
import { connectMemoryToAgent } from "@neo4j-labs/agent-memory/integrations/strands";

async function main() {
  // 1. Set up the memory client and a fresh conversation.
  const memory = new MemoryClient();
  const conv = await memory.shortTerm.createConversation({
    userId: process.env.DEMO_USER_ID ?? "strands-demo-user",
    metadata: { source: "strands-example" },
  });
  process.stdout.write(`Created conversation ${conv.id}\n`);

  // 2. Spread the memory integration into the Agent config.
  const { sessionManager, conversationManager } = await connectMemoryToAgent(memory, {
    conversationId: conv.id,
  });

  // 3. Build the agent. A toy tool is included so the reasoning trace has
  //    something interesting to record.
  const lookupTool = new FunctionTool({
    name: "lookup_fact",
    description: "Look up a fact about a topic.",
    schema: z.object({ topic: z.string() }),
    handler: async ({ topic }) => `Fact about ${topic}: graphs model relationships natively.`,
  });

  const agent = new Agent({
    systemPrompt: "You are a helpful assistant who explains things concisely.",
    model: new OpenAIModel({ modelId: "gpt-4o-mini" }),
    tools: [lookupTool],
    sessionManager,
    conversationManager,
  });

  // 4. Drive a three-turn dialogue.
  const turns = [
    "Tell me about graph databases.",
    "Use the lookup_fact tool to find one more thing about Neo4j.",
    "Summarize what we've discussed so far.",
  ];
  for (const userMessage of turns) {
    process.stdout.write(`\n[user] ${userMessage}\n[assistant] `);
    const result = await agent.invoke(userMessage);
    process.stdout.write(`${result.text ?? ""}\n`);
  }

  process.stdout.write(
    `\nConversation persisted as ${conv.id}.\n` +
      `View reasoning trace: client.reasoning.getTraceByConversation("${conv.id}").\n` +
      `Re-run with DEMO_USER_ID=${conv.userId} to see context recall.\n`,
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
