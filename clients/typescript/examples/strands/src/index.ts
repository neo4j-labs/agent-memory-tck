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
import type { ContentBlock } from "@strands-agents/sdk";
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
  type AgentConfig = NonNullable<ConstructorParameters<typeof Agent>[0]>;
  // The local example and the file-linked client each resolve their own
  // `@strands-agents/sdk` instance during validation, so TypeScript treats
  // the nominal classes as distinct because they carry private fields.
  const memoryAgentConfig = {
    sessionManager: sessionManager as unknown as AgentConfig["sessionManager"],
    conversationManager: conversationManager as unknown as AgentConfig["conversationManager"],
  };

  // 3. Build the agent. A toy tool is included so the reasoning trace has
  //    something interesting to record.
  const lookupTool = new FunctionTool({
    name: "lookup_fact",
    description: "Look up a fact about a topic.",
    inputSchema: {
      type: "object",
      properties: {
        topic: { type: "string", description: "Topic to look up" },
      },
      required: ["topic"],
    },
    callback: async (input: unknown) => {
      const topic = (input as { topic?: string })?.topic ?? "unknown";
      return `Fact about ${topic}: graphs model relationships natively.`;
    },
  });

  const agent = new Agent({
    systemPrompt: "You are a helpful assistant who explains things concisely.",
    model: new OpenAIModel({ modelId: "gpt-4o-mini" }),
    tools: [lookupTool],
    ...memoryAgentConfig,
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
    const text = flattenText(result.lastMessage.content) || "(no text response)";
    process.stdout.write(`${text}\n`);
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

function flattenText(blocks: ContentBlock[]): string {
  return blocks
    .map((block) => ("text" in block && typeof block.text === "string" ? block.text : ""))
    .filter((chunk) => chunk.length > 0)
    .join("");
}
