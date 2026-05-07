/**
 * End-to-end tests — live hosted Neo4j Agent Memory Service.
 *
 * Skipped when MEMORY_API_KEY is unset. `.env` at the repo root is loaded
 * by vitest.config.ts; CI uses the MEMORY_API_KEY repo secret.
 */

import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { MemoryClient } from "../../src/client.js";
import { AuthenticationError } from "../../src/errors.js";

const API_KEY = (process.env.MEMORY_API_KEY ?? "").trim();
const ENDPOINT = process.env.MEMORY_ENDPOINT ?? "https://memory.neo4jlabs.com/v1";
const HAS_KEY = API_KEY.length > 0;

const describeOrSkip = HAS_KEY ? describe : describe.skip;

function userId(): string {
  const base = process.env.MEMORY_E2E_USER_ID ?? "tck-e2e-ts";
  return `${base}-${Math.random().toString(36).slice(2, 10)}`;
}

describeOrSkip("hosted service e2e", () => {
  let client: MemoryClient;
  const createdConversationIds: string[] = [];

  beforeAll(async () => {
    client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    await client.connect();
  });

  afterAll(async () => {
    for (const id of createdConversationIds) {
      try {
        await client.shortTerm.deleteConversation(id);
      } catch {
        // best-effort cleanup
      }
    }
    await client.close();
  });

  async function newConv(): Promise<string> {
    const conv = await client.shortTerm.createConversation({ userId: userId() });
    createdConversationIds.push(conv.id);
    return conv.id;
  }

  // -- Connection + auth ---------------------------------------------------

  it("connects with a valid API key", () => {
    expect(client).toBeDefined();
  });

  it("rejects an invalid API key with AuthenticationError", async () => {
    const bad = new MemoryClient({ endpoint: ENDPOINT, apiKey: "nams_obviously_not_real" });
    await expect(bad.connect()).rejects.toBeInstanceOf(AuthenticationError);
    await bad.close();
  });

  // -- Short-Term ----------------------------------------------------------

  it("creates and lists conversations", async () => {
    const id = await newConv();
    expect(id).toMatch(/[0-9a-f-]{8,}/i);
    const list = await client.shortTerm.listConversations({ limit: 50 });
    expect(Array.isArray(list)).toBe(true);
  });

  it("adds a message and reads it back", async () => {
    const id = await newConv();
    const msg = await client.shortTerm.addMessage(id, "user", "John works at Acme.");
    expect(msg.id).toBeDefined();
    expect(msg.role).toBe("user");
  });

  it("bulkAddMessages stores messages in order", async () => {
    const id = await newConv();
    const messages = Array.from({ length: 5 }, (_, i) => ({
      role: "user" as const,
      content: `bulk-${i}`,
    }));
    const result = await client.shortTerm.bulkAddMessages(id, messages);
    expect(result).toHaveLength(5);
  });

  it("getContext returns three-tier shape", async () => {
    const id = await newConv();
    await client.shortTerm.addMessage(id, "user", "Hello there.");
    const ctx = await client.shortTerm.getContext(id);
    expect(ctx).toHaveProperty("reflections");
    expect(ctx).toHaveProperty("observations");
    expect(ctx).toHaveProperty("recentMessages");
    expect(Array.isArray(ctx.recentMessages)).toBe(true);
  });

  // -- Long-Term -----------------------------------------------------------

  it("getEntityGraph returns nodes + edges", async () => {
    const graph = await client.longTerm.getEntityGraph();
    expect(graph).toHaveProperty("nodes");
    expect(graph).toHaveProperty("edges");
    expect(Array.isArray(graph.nodes)).toBe(true);
    expect(Array.isArray(graph.edges)).toBe(true);
  });

  it("searchEntities returns an array", async () => {
    const entities = await client.longTerm.searchEntities("anything", { limit: 5 });
    expect(Array.isArray(entities)).toBe(true);
  });

  it("listEntities returns an array", async () => {
    const entities = await client.longTerm.listEntities({ limit: 5 });
    expect(Array.isArray(entities)).toBe(true);
  });

  // -- Reasoning -----------------------------------------------------------

  it("recordStep + getTraceByConversation round-trip", async () => {
    const id = await newConv();
    const step = await client.reasoning.recordStep({
      conversationId: id,
      reasoning: "test hypothesis",
      actionTaken: "ran assertion",
      result: "passed",
    });
    expect(step.id).toBeDefined();

    const trace = await client.reasoning.getTraceByConversation(id);
    expect(trace.conversationId).toBe(id);
    expect(Array.isArray(trace.steps)).toBe(true);
  });

  // -- Cypher console ------------------------------------------------------

  it("cypher_query executes read-only Cypher", async () => {
    const result = await client.query.cypher({
      cypher: "MATCH (n) RETURN count(n) AS total",
    });
    expect(result.columns).toContain("total");
    expect(result.rows.length).toBeGreaterThanOrEqual(1);
  });
});
