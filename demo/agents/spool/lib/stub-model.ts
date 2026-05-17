/**
 * Stub Strands model — returns canned responses with deterministic tool
 * calls. Used when no LLM key is configured so the demo's chat surface
 * works for click-through visitors.
 */

import type { Message } from "@strands-agents/sdk";

interface CannedTurn {
  /** Substring(s) that match the user input to select this canned turn. */
  match: (input: string) => boolean;
  /** Assistant text to stream back. */
  text: string;
  /** Optional canned tool calls to emit before the text. */
  toolCalls?: Array<{ name: string; input: Record<string, unknown>; result: string }>;
}

const CANNED: CannedTurn[] = [
  {
    match: (s) => /(hello|hi|hey)/i.test(s),
    text:
      "Hi! I'm a stub agent running in spool's demo-without-keys mode. I can chat about " +
      "graph databases, memory, or Neo4j Agent Memory Service. Try asking about graphs " +
      "or run with MEMORY_API_KEY + OPENAI_API_KEY for the real agent.",
  },
  {
    match: (s) => /(graph|neo4j)/i.test(s),
    text:
      "Graph databases store data as nodes (things) and relationships (how they're " +
      "connected). Neo4j is the canonical example. The Neo4j Agent Memory Service is " +
      "built on top of Neo4j and gives agents three kinds of memory: short-term " +
      "(conversations), long-term (entities, preferences), and reasoning (steps, traces).",
    toolCalls: [
      {
        name: "lookup_fact",
        input: { topic: "Neo4j" },
        result: "Neo4j supports Cypher, a declarative graph query language.",
      },
    ],
  },
  {
    match: (s) => /(memory|nams|hosted)/i.test(s),
    text:
      "Neo4j Agent Memory Service (NAMS) is the hosted memory backend at " +
      "memory.neo4jlabs.com. It exposes a REST API plus an MCP endpoint. " +
      "This spool demo uses NAMS as the durable store for everything you see in the " +
      "side panels.",
    toolCalls: [
      {
        name: "search_entities",
        input: { query: "memory service" },
        result: "Found 1 entity: NAMS (concept)",
      },
    ],
  },
  {
    match: () => true,
    text:
      "I'm a stub agent — I have canned responses for hello/graph/memory questions. " +
      "Configure OPENAI_API_KEY and MEMORY_API_KEY to flip to the live agent.",
  },
];

export interface StubInvocationResult {
  text: string;
  toolCalls: Array<{ name: string; input: Record<string, unknown>; result: string }>;
}

export function lastUserText(messages: Message[]): string {
  for (let i = messages.length - 1; i >= 0; i--) {
    const m = messages[i]!;
    if (m.role !== "user") continue;
    const content = (m as unknown as { content: Array<{ text?: string }> }).content ?? [];
    return content.map((b) => b.text ?? "").join(" ").trim();
  }
  return "";
}

export function pickCanned(userInput: string): StubInvocationResult {
  const turn = CANNED.find((t) => t.match(userInput)) ?? CANNED[CANNED.length - 1]!;
  return {
    text: turn.text,
    toolCalls: turn.toolCalls ?? [],
  };
}

/**
 * Stub model entry point — given the last user message text, return a
 * canned response. The API route uses this when no OPENAI_API_KEY is set.
 */
export async function runStubAgent(userInput: string): Promise<StubInvocationResult> {
  // Microscopic delay so the streaming UI has something to render.
  await new Promise((r) => setTimeout(r, 80));
  return pickCanned(userInput);
}
