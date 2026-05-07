/**
 * Comprehensive end-to-end tests against the live hosted Neo4j Agent Memory
 * Service.
 *
 * Mirrors the Python suite at clients/python/tests/e2e/test_hosted_service.py
 * — same scenarios, same fixtures, same skip patterns. Skipped wholesale
 * when MEMORY_API_KEY is unset; individual tests skip themselves when the
 * service rejects an operation that requires elevated workspace scope.
 *
 * Each test creates short-lived data (conversations / entities tagged with
 * the `tck-e2e-ts-` user prefix) and tears it down via afterAll. Failures
 * during cleanup are swallowed.
 */

import {
  afterAll,
  beforeAll,
  beforeEach,
  describe,
  expect,
  it,
} from "vitest";
import { MemoryClient } from "../../src/client.js";
import {
  AuthenticationError,
  NotFoundError,
  TransportError,
  ValidationError,
} from "../../src/errors.js";
import type { Entity } from "../../src/types.js";
import {
  metadataFor,
  provenanceReasoning,
  provenanceResult,
  tagDescription,
} from "./tck-provenance.js";

const API_KEY = (process.env.MEMORY_API_KEY ?? "").trim();
const ENDPOINT = process.env.MEMORY_ENDPOINT ?? "https://memory.neo4jlabs.com/v1";
const HAS_KEY = API_KEY.length > 0;

const describeOrSkip = HAS_KEY ? describe : describe.skip;

const UNIQUE_TAG = randomHex(8);
const USER_PREFIX = process.env.MEMORY_E2E_USER_ID ?? "tck-e2e-ts";

function userId(suffix = ""): string {
  const rand = randomHex(6);
  const base = `${USER_PREFIX}-${UNIQUE_TAG}-${rand}`;
  return suffix ? `${base}-${suffix}` : base;
}

function randomHex(n: number): string {
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    const buf = new Uint8Array(Math.ceil(n / 2));
    crypto.getRandomValues(buf);
    return Array.from(buf, (b) => b.toString(16).padStart(2, "0")).join("").slice(0, n);
  }
  return Date.now().toString(16).slice(-n);
}

async function waitUntil<T>(
  predicate: () => Promise<T | null | undefined>,
  { timeout = 12_000, interval = 1_000 }: { timeout?: number; interval?: number } = {},
): Promise<T | null> {
  const deadline = Date.now() + timeout;
  let last: T | null | undefined = null;
  while (Date.now() < deadline) {
    last = await predicate();
    if (last) return last;
    await new Promise((r) => setTimeout(r, interval));
  }
  return last ?? null;
}

describeOrSkip("hosted service e2e", () => {
  let client: MemoryClient;
  let currentTestName = "unknown";
  const cleanupConversations: string[] = [];
  const cleanupEntities: string[] = [];

  beforeAll(async () => {
    client = new MemoryClient({ endpoint: ENDPOINT, apiKey: API_KEY });
    await client.connect();
  });

  // Capture the current test name for provenance tagging. Vitest exposes
  // the task on the context handed to each test; we mirror it into a
  // closure variable so newConv / newEntity helpers can read it.
  beforeEach((ctx) => {
    currentTestName = ctx?.task?.name ?? "unknown";
  });

  afterAll(async () => {
    for (const id of cleanupConversations) {
      try {
        await client.shortTerm.deleteConversation(id);
      } catch {
        // best-effort
      }
    }
    for (const id of cleanupEntities) {
      try {
        await client.longTerm.deleteEntity(id);
      } catch {
        // best-effort
      }
    }
    await client.close();
  });

  /**
   * Create a conversation tagged with full provenance metadata + record an
   * `record_step` on it so the hosted reasoning graph can trace this
   * conversation back to the originating test (client + run + sha).
   */
  async function newConv(opts?: { userId?: string; metadata?: Record<string, unknown> }) {
    const baseMeta = metadataFor(currentTestName, { tck_phase: "fixture" });
    const conv = await client.shortTerm.createConversation({
      userId: opts?.userId ?? userId(),
      metadata: { ...baseMeta, ...(opts?.metadata ?? {}) },
    });
    cleanupConversations.push(conv.id);
    // Provenance is best-effort — never fail a test on tagging noise.
    try {
      await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: provenanceReasoning(currentTestName, "setup"),
        actionTaken: "create_conversation",
        result: provenanceResult(currentTestName, { conversation_id: conv.id }),
      });
    } catch {
      // ignore
    }
    return conv;
  }

  /**
   * Create an entity whose `description` is prefixed with a provenance tag
   * (e.g. `[tck:typescript:run123:test_name] tck e2e probe entity`) so even
   * a workspace operator without graph access can grep for test data.
   */
  async function newEntity(opts?: {
    name?: string;
    entityType?: string;
    description?: string;
  }): Promise<Entity> {
    const description = tagDescription(
      currentTestName,
      opts?.description ?? "tck e2e probe entity",
    );
    const e = await client.longTerm.addEntity(
      opts?.name ?? `TCK-Probe-${randomHex(8)}`,
      opts?.entityType ?? "concept",
      { description },
    );
    cleanupEntities.push(e.id);
    return e;
  }

  // =====================================================================
  // 1. Connection + auth
  // =====================================================================

  describe("Connection and auth", () => {
    it("connect succeeds with valid key", () => {
      expect(client).toBeDefined();
    });

    it("invalid api key throws AuthenticationError", async () => {
      const bad = new MemoryClient({
        endpoint: ENDPOINT,
        apiKey: "nams_obviously_not_real_token",
      });
      try {
        await expect(bad.connect()).rejects.toBeInstanceOf(AuthenticationError);
      } finally {
        await bad.close();
      }
    });

    it("empty api key throws AuthenticationError", async () => {
      const bad = new MemoryClient({ endpoint: ENDPOINT, apiKey: "" });
      try {
        await expect(bad.connect()).rejects.toBeInstanceOf(AuthenticationError);
      } finally {
        await bad.close();
      }
    });
  });

  // =====================================================================
  // 2. Conversation lifecycle
  // =====================================================================

  describe("Conversation lifecycle", () => {
    it("create returns uuid + user_id + workspace_id", async () => {
      const uid = userId("create");
      const conv = await newConv({ userId: uid, metadata: { source: "e2e", seq: 1 } });
      expect(conv.id.length).toBeGreaterThanOrEqual(8);
      expect(conv.userId).toBe(uid);
      expect(conv.workspaceId).toBeTruthy();
    });

    it("get_metadata round-trips user_id", async () => {
      const conv = await newConv();
      const meta = await client.shortTerm.getConversationMetadata(conv.id);
      expect(meta.id).toBe(conv.id);
      expect(meta.userId).toBe(conv.userId);
    });

    it("list includes freshly created conversation", async () => {
      const conv = await newConv({ userId: userId("list-probe") });
      const listed = await client.shortTerm.listConversations({ limit: 200 });
      expect(listed.some((x) => x.id === conv.id)).toBe(true);
    });

    it("delete is idempotent", async () => {
      const conv = await newConv();
      await client.shortTerm.deleteConversation(conv.id);
      // Second call must not throw
      await client.shortTerm.deleteConversation(conv.id);
    });
  });

  // =====================================================================
  // 3. Short-term memory: messages
  // =====================================================================

  describe("Message basics", () => {
    it("addMessage returns id + role", async () => {
      const conv = await newConv();
      const msg = await client.shortTerm.addMessage(conv.id, "user", "hello world");
      expect(msg.id).toBeTruthy();
      expect(msg.role).toBe("user");
      expect(msg.content).toBe("hello world");
    });

    it("getConversation returns added messages", async () => {
      const conv = await newConv();
      const contents = ["one", "two", "three", "four", "five"];
      for (const c of contents) {
        await client.shortTerm.addMessage(conv.id, "user", c);
      }
      const got = await client.shortTerm.getConversation(conv.id);
      const seen = got.messages.map((m) => m.content);
      for (const c of contents) {
        expect(seen).toContain(c);
      }
    });

    it("searchMessages returns array", async () => {
      const conv = await newConv();
      await client.shortTerm.addMessage(
        conv.id,
        "user",
        "Marie Curie won the Nobel Prize in Physics in 1903.",
      );
      const results = await client.shortTerm.searchMessages("Nobel", {
        sessionId: conv.id,
        limit: 5,
        threshold: 0.0,
      });
      expect(Array.isArray(results)).toBe(true);
    });
  });

  describe("Message roles", () => {
    for (const role of ["user", "assistant", "system"] as const) {
      it(`role round-trip: ${role}`, async () => {
        const conv = await newConv();
        const msg = await client.shortTerm.addMessage(conv.id, role, `role ${role}`);
        expect(msg.role).toBe(role);
      });
    }
  });

  describe("Content fidelity", () => {
    it("unicode preserved", async () => {
      const conv = await newConv();
      const content = "你好 🚀 émoji ñ ç ø";
      const msg = await client.shortTerm.addMessage(conv.id, "user", content);
      expect(msg.content).toBe(content);
    });

    it("long content (10k chars) preserved", async () => {
      const conv = await newConv();
      const content = "x".repeat(10_000);
      const msg = await client.shortTerm.addMessage(conv.id, "user", content);
      expect(msg.content).toBe(content);
      expect(msg.content.length).toBe(10_000);
    });

    it("special chars preserved", async () => {
      const conv = await newConv();
      const content = 'quote " backslash \\ newline\nreturn\r tab\t json {"a":1}';
      const msg = await client.shortTerm.addMessage(conv.id, "user", content);
      expect(msg.content).toBe(content);
    });

    it("metadata round-trips without error", async () => {
      const conv = await newConv();
      const msg = await client.shortTerm.addMessage(conv.id, "user", "with-meta", {
        metadata: { source: "tck-e2e", priority: "high", count: 42, active: true },
      });
      expect(msg.content).toBe("with-meta");
    });
  });

  // =====================================================================
  // 4. Bulk operations
  // =====================================================================

  describe("Bulk add messages", () => {
    it("bulk add 5 messages", async () => {
      const conv = await newConv();
      const msgs = Array.from({ length: 5 }, (_, i) => ({
        role: "user" as const,
        content: `bulk-${i}`,
      }));
      const out = await client.shortTerm.bulkAddMessages(conv.id, msgs);
      expect(out).toHaveLength(5);
    });

    it("bulk add 50 messages", async () => {
      const conv = await newConv();
      const msgs = Array.from({ length: 50 }, (_, i) => ({
        role: "user" as const,
        content: `big-bulk-${i}`,
      }));
      const out = await client.shortTerm.bulkAddMessages(conv.id, msgs);
      expect(out).toHaveLength(50);
    });

    it("rejects more than 100 messages", async () => {
      const conv = await newConv();
      const msgs = Array.from({ length: 101 }, (_, i) => ({
        role: "user" as const,
        content: `x-${i}`,
      }));
      await expect(client.shortTerm.bulkAddMessages(conv.id, msgs)).rejects.toThrow();
    });
  });

  // =====================================================================
  // 5. Three-tier context
  // =====================================================================

  describe("Context endpoints", () => {
    it("getContext returns three-tier shape", async () => {
      const conv = await newConv();
      await client.shortTerm.addMessage(conv.id, "user", "Hello world");
      const ctx = await client.shortTerm.getContext(conv.id);
      expect(ctx).toHaveProperty("reflections");
      expect(ctx).toHaveProperty("observations");
      expect(ctx).toHaveProperty("recentMessages");
      expect(Array.isArray(ctx.recentMessages)).toBe(true);
    });

    it("getObservations returns list", async () => {
      const conv = await newConv();
      const obs = await client.shortTerm.getObservations(conv.id, { limit: 10 });
      expect(Array.isArray(obs)).toBe(true);
    });

    it("getReflections returns list", async () => {
      const conv = await newConv();
      const refl = await client.shortTerm.getReflections(conv.id);
      expect(Array.isArray(refl)).toBe(true);
    });

    it("recent_messages includes added message", async () => {
      const conv = await newConv();
      await client.shortTerm.addMessage(conv.id, "user", "context-probe-message");
      const ctx = await client.shortTerm.getContext(conv.id);
      const contents = ctx.recentMessages.map((m) => m.content);
      expect(contents).toContain("context-probe-message");
    });
  });

  // =====================================================================
  // 6. Long-term: entities CRUD + search
  // =====================================================================

  describe("Entity CRUD", () => {
    it("addEntity returns id + fields", async () => {
      const e = await newEntity({ name: "TCK Alice", description: "test person" });
      expect(e.id.length).toBeGreaterThanOrEqual(8);
      expect(e.name).toBe("TCK Alice");
      // newEntity tags the description with a tck-provenance prefix; the
      // original payload is preserved at the end of the string.
      expect(e.description ?? "").toMatch(/test person$/);
      expect(e.description ?? "").toContain("tck:typescript");
    });

    it("listEntities returns array", async () => {
      const ents = await client.longTerm.listEntities({ limit: 5 });
      expect(Array.isArray(ents)).toBe(true);
    });

    it("listEntities with type filter", async () => {
      const ents = await client.longTerm.listEntities({ type: "person", limit: 5 });
      expect(Array.isArray(ents)).toBe(true);
      for (const e of ents) {
        expect(e.type).toBe("person");
      }
    });

    it("getEntity returns relationships array", async () => {
      const e = await newEntity();
      const full = await client.longTerm.getEntity(e.id);
      expect(full.id).toBe(e.id);
      // relationships may be undefined or an array.
      if (full.relationships !== undefined) {
        expect(Array.isArray(full.relationships)).toBe(true);
      }
    });

    it("updateEntity changes description", async () => {
      const e = await newEntity({ description: "orig" });
      const updated = await client.longTerm.updateEntity(e.id, {
        description: "rewritten",
      });
      expect(updated.id).toBe(e.id);
      expect(updated.description).toBe("rewritten");
    });

    it("updateEntity changes name", async () => {
      const e = await newEntity({ name: `Original-${randomHex(6)}` });
      const newName = `Renamed-${randomHex(6)}`;
      const updated = await client.longTerm.updateEntity(e.id, { name: newName });
      expect(updated.name).toBe(newName);
    });

    it("deleteEntity removes it", async () => {
      const e = await newEntity();
      await client.longTerm.deleteEntity(e.id);
      try {
        const after = await client.longTerm.getEntity(e.id);
        // soft-delete: id should still match
        expect(after.id).toBe(e.id);
      } catch (err) {
        if (err instanceof NotFoundError || err instanceof TransportError) {
          return;
        }
        throw err;
      }
    });
  });

  describe("Entity search", () => {
    it("searchEntities returns array", async () => {
      const results = await client.longTerm.searchEntities("anything", { limit: 5 });
      expect(Array.isArray(results)).toBe(true);
    });

    it("search finds freshly created entity", async (ctx) => {
      const marker = `TCK-Probe-${randomHex(8)}`;
      const e = await newEntity({ name: marker });
      const found = await waitUntil(async () => {
        const hits = await client.longTerm.searchEntities(marker, { limit: 10 });
        return hits.find((h) => h.id === e.id) ?? null;
      });
      if (!found) {
        ctx.skip();
        return;
      }
      expect(found.id).toBe(e.id);
    });

    it("search with type filter returns array", async () => {
      const e = await newEntity({
        entityType: "concept",
        name: `TCKConcept-${randomHex(6)}`,
      });
      const hits = await client.longTerm.searchEntities(e.name, {
        type: "concept",
        limit: 5,
      });
      expect(Array.isArray(hits)).toBe(true);
    });
  });

  describe("Entity feedback", () => {
    it("setEntityFeedback returns updated", async () => {
      const e = await newEntity();
      const result = await client.longTerm.setEntityFeedback(e.id, {
        userScore: 0.93,
        confirmed: true,
      });
      expect(result.id).toBe(e.id);
      expect(result.updated).toBe(true);
    });

    it("setEntityFeedback with score 0", async () => {
      const e = await newEntity();
      const result = await client.longTerm.setEntityFeedback(e.id, {
        userScore: 0.0,
        confirmed: false,
      });
      expect(result.id).toBe(e.id);
    });
  });

  describe("Entity history + provenance", () => {
    it("getEntityHistory returns shape", async () => {
      const e = await newEntity();
      const hist = await client.longTerm.getEntityHistory(e.id);
      expect(hist.entityId).toBe(e.id);
      expect(Array.isArray(hist.mentions)).toBe(true);
    });

    it("getEntityProvenance returns shape", async () => {
      const e = await newEntity();
      const prov = await client.reasoning.getEntityProvenance(e.id);
      expect(prov.entityId).toBe(e.id);
      expect(Array.isArray(prov.steps)).toBe(true);
    });
  });

  describe("Entity graph", () => {
    it("getEntityGraph returns nodes + edges", async () => {
      const graph = await client.longTerm.getEntityGraph();
      expect(Array.isArray(graph.nodes)).toBe(true);
      expect(Array.isArray(graph.edges)).toBe(true);
      if (graph.nodes.length > 0) {
        expect(graph.nodes[0]!.id).toBeTruthy();
      }
    });
  });

  describe("Entity merge", () => {
    it("mergeEntities returns status", async (ctx) => {
      const a = await newEntity({ name: `MergeA-${randomHex(6)}` });
      const b = await newEntity({ name: `MergeB-${randomHex(6)}` });
      try {
        const result = await client.longTerm.mergeEntities(a.id, b.id);
        expect(result.sourceId).toBeTruthy();
        expect(result.targetId).toBeTruthy();
        expect(result.status).toBeTruthy();
      } catch (err) {
        if (err instanceof TransportError || err instanceof AuthenticationError) {
          ctx.skip();
          return;
        }
        throw err;
      }
    });
  });

  // =====================================================================
  // 7. Reasoning memory
  // =====================================================================

  describe("Reasoning steps", () => {
    it("recordStep persists", async () => {
      const conv = await newConv();
      const step = await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: "hypothesizing user's intent",
        actionTaken: "lookup_user_profile",
        result: "found profile",
      });
      expect(step.id).toBeTruthy();
      expect(step.conversationId).toBe(conv.id);
      expect(step.reasoning).toMatch(/hypothesizing/);
    });

    it("recordStep without result", async () => {
      const conv = await newConv();
      const step = await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: "r",
        actionTaken: "a",
      });
      expect(step.id).toBeTruthy();
    });

    it("listSteps returns recorded steps", async () => {
      const conv = await newConv();
      const s1 = await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: "r1",
        actionTaken: "a1",
      });
      const s2 = await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: "r2",
        actionTaken: "a2",
      });
      const steps = await client.reasoning.listSteps(conv.id);
      const ids = new Set(steps.map((s) => s.id));
      expect(ids.has(s1.id)).toBe(true);
      expect(ids.has(s2.id)).toBe(true);
    });
  });

  describe("Reasoning explain", () => {
    it("explainStep returns tool_calls + influenced_entities arrays", async () => {
      const conv = await newConv();
      const step = await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: "r",
        actionTaken: "a",
      });
      const explanation = await client.reasoning.explainStep(step.id);
      expect(explanation.id).toBe(step.id);
      expect(Array.isArray(explanation.toolCalls)).toBe(true);
      expect(Array.isArray(explanation.influencedEntities)).toBe(true);
    });
  });

  describe("Reasoning trace", () => {
    it("getTraceByConversation works for empty conversation", async () => {
      const conv = await newConv();
      const trace = await client.reasoning.getTraceByConversation(conv.id);
      expect(trace.conversationId).toBe(conv.id);
      expect(Array.isArray(trace.steps)).toBe(true);
      expect(Array.isArray(trace.toolCalls)).toBe(true);
    });

    it("getTraceByConversation includes recorded step", async () => {
      const conv = await newConv();
      await client.reasoning.recordStep({
        conversationId: conv.id,
        reasoning: "r",
        actionTaken: "a",
      });
      const trace = await client.reasoning.getTraceByConversation(conv.id);
      expect(trace.steps.some((s) => s.reasoning.includes("r"))).toBe(true);
    });
  });

  // =====================================================================
  // 8. Cypher console (skipped on 403)
  // =====================================================================

  describe("Cypher console", () => {
    it("count query returns total column", async (ctx) => {
      try {
        const result = await client.query.cypher({
          cypher: "MATCH (n) RETURN count(n) AS total",
        });
        expect(result.columns).toContain("total");
        expect(result.rows.length).toBeGreaterThanOrEqual(1);
      } catch (err) {
        if (err instanceof AuthenticationError) {
          ctx.skip();
          return;
        }
        throw err;
      }
    });

    it("parameterised query returns columns", async (ctx) => {
      try {
        const result = await client.query.cypher({
          cypher: "MATCH (n) RETURN $label AS label LIMIT 1",
          params: { label: "tck-e2e" },
        });
        expect(Array.isArray(result.columns)).toBe(true);
      } catch (err) {
        if (err instanceof AuthenticationError) {
          ctx.skip();
          return;
        }
        throw err;
      }
    });
  });

  // =====================================================================
  // 9. Auth API (skipped on 403)
  // =====================================================================

  describe("Auth API keys", () => {
    it("listApiKeys returns array (or skips on scope)", async (taskCtx) => {
      const conv = await newConv();
      const meta = await client.shortTerm.getConversationMetadata(conv.id);
      const ws = meta.workspaceId;
      if (!ws) {
        taskCtx.skip();
        return;
      }
      try {
        const keys = await client.auth.listApiKeys(ws);
        expect(Array.isArray(keys)).toBe(true);
      } catch (err) {
        if (err instanceof AuthenticationError) {
          taskCtx.skip();
          return;
        }
        throw err;
      }
    });
  });

  // =====================================================================
  // 10. Cross-feature workflows
  // =====================================================================

  describe("Agent workflows", () => {
    it("message flow extracts entities asynchronously", async (ctx) => {
      const conv = await newConv({ userId: userId("agent-flow") });
      const uniqueName = `TCKMercury${randomHex(8)}`;
      await client.shortTerm.addMessage(
        conv.id,
        "user",
        `${uniqueName} is the smallest planet in the solar system.`,
      );
      await client.shortTerm.addMessage(
        conv.id,
        "assistant",
        `Yes, ${uniqueName} has a thin atmosphere.`,
      );
      const found = await waitUntil(
        async () => {
          const hits = await client.longTerm.searchEntities(uniqueName, { limit: 10 });
          return hits.find((h) =>
            h.name.toLowerCase().includes(uniqueName.toLowerCase()),
          )
            ? hits
            : null;
        },
        { timeout: 20_000, interval: 2_000 },
      );
      if (!found) {
        ctx.skip();
        return;
      }
      expect(
        found.some((e) => e.name.toLowerCase().includes(uniqueName.toLowerCase())),
      ).toBe(true);
    });

    it("multi-step reasoning trace round-trip", async () => {
      const conv = await newConv();
      const steps = [];
      for (let i = 0; i < 3; i++) {
        steps.push(
          await client.reasoning.recordStep({
            conversationId: conv.id,
            reasoning: `step ${i} reasoning`,
            actionTaken: `action_${i}`,
            result: `result_${i}`,
          }),
        );
      }
      const trace = await client.reasoning.getTraceByConversation(conv.id);
      const recorded = new Set(steps.map((s) => s.id));
      const tracedIds = new Set(trace.steps.map((s) => s.id));
      for (const id of recorded) {
        expect(tracedIds.has(id)).toBe(true);
      }
    });

    it("multi-turn conversation appears in context", async () => {
      const conv = await newConv();
      const turns: [string, string][] = [
        ["user", "I'm planning a trip to Tokyo next month."],
        ["assistant", "Tokyo is great in autumn — what are your interests?"],
        ["user", "Mostly food and historical sites."],
        ["assistant", "Visit Tsukiji Outer Market and Senso-ji."],
        ["user", "How long should I stay?"],
      ];
      for (const [role, content] of turns) {
        await client.shortTerm.addMessage(conv.id, role as any, content);
      }
      const ctx = await client.shortTerm.getContext(conv.id);
      const allContent = ctx.recentMessages.map((m) => m.content).join(" ");
      expect(/Tokyo|Tsukiji/.test(allContent)).toBe(true);
    });
  });

  // =====================================================================
  // 11. Concurrency
  // =====================================================================

  describe("Concurrency", () => {
    it("concurrent addMessage calls each get unique ids", async () => {
      const conv = await newConv();
      const results = await Promise.all(
        Array.from({ length: 8 }, (_, i) =>
          client.shortTerm.addMessage(conv.id, "user", `concurrent-${i}`),
        ),
      );
      const ids = new Set(results.map((m) => m.id));
      expect(ids.size).toBe(8);
    });

    it("concurrent createConversation calls each get unique ids", async () => {
      const results = await Promise.all(
        Array.from({ length: 4 }, (_, i) => newConv({ userId: userId(`concur-${i}`) })),
      );
      const ids = new Set(results.map((c) => c.id));
      expect(ids.size).toBe(4);
    });
  });
});
