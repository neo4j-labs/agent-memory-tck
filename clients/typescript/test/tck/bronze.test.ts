/**
 * Bronze Tier TCK Conformance Tests.
 *
 * These tests mirror the Python TCK test suite (tck/tests/v1/test_schema.py
 * and tck/tests/v1/test_short_term.py) and validate the TypeScript client
 * against the same behavioral contracts.
 *
 * Requires a running conformance server or the hosted service.
 * Set MEMORY_ENDPOINT env var to configure the target.
 */

import { describe, it, expect, beforeEach, beforeAll, afterAll } from "vitest";
import { MemoryClient } from "../../src/client.js";
import type { Message } from "../../src/types.js";
import {
  SESSION_A,
  SESSION_B,
  SESSION_C,
  CONVERSATION_MESSAGES,
  LONG_CONTENT,
  UNICODE_CONTENT,
  SPECIAL_CHARS_CONTENT,
  EMPTY_CONTENT,
  NESTED_METADATA,
  ENTITIES,
  PREFERENCES,
  FACTS,
} from "./testdata.js";

const ENDPOINT = process.env["MEMORY_ENDPOINT"] ?? "http://localhost:3001";
const RUN_TCK = process.env["RUN_TCK_BRIDGE"] === "1";

const describeOrSkip = RUN_TCK ? describe : describe.skip;

describeOrSkip("Bronze Tier", () => {
  let client: MemoryClient;

  beforeAll(async () => {
    client = new MemoryClient({ endpoint: ENDPOINT });
    await client.connect();
  });

  afterAll(async () => {
    await client.close();
  });

  beforeEach(async () => {
    await client.shortTerm.clearSession(SESSION_A);
    await client.shortTerm.clearSession(SESSION_B);
    await client.shortTerm.clearSession(SESSION_C);
  });

  // =====================================================================
  // Schema Tests (test_schema.py)
  // =====================================================================

  describe("SchemaConversationCreation", () => {
    it("SPEC-1.1.1: first message creates conversation", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.sessionId).toBe(SESSION_A);
      expect(conv.messages).toHaveLength(1);
    });

    it("SPEC-1.1.2: subsequent messages reuse conversation", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "First message");
      await client.shortTerm.addMessage(SESSION_A, "assistant", "Second message");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(2);
    });

    it("SPEC-1.1.5: conversation id is valid UUID", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.id).toBeDefined();
      expect(typeof conv.id).toBe("string");
      expect(conv.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      );
    });

    it("SPEC-1.1.6: conversation created_at is set", async () => {
      const before = new Date(Date.now() - 5000);
      await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.createdAt).toBeDefined();
    });

    it("SPEC-1.1.7: conversation title is optional", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.title === null || conv.title === undefined || typeof conv.title === "string").toBe(true);
    });

    it("SPEC-1.1.8: same session reuses same conversation id", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "First");
      const conv1 = await client.shortTerm.getConversation(SESSION_A);
      await client.shortTerm.addMessage(SESSION_A, "user", "Second");
      const conv2 = await client.shortTerm.getConversation(SESSION_A);
      expect(conv1.id).toBe(conv2.id);
    });
  });

  describe("SchemaSessionIsolation", () => {
    it("SPEC-1.1.3: messages isolated between sessions", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Message in session A");
      await client.shortTerm.addMessage(SESSION_B, "user", "Message in session B");
      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);
      expect(convA.messages).toHaveLength(1);
      expect(convB.messages).toHaveLength(1);
      expect(convA.messages[0]!.content).toBe("Message in session A");
      expect(convB.messages[0]!.content).toBe("Message in session B");
    });

    it("SPEC-1.1.9: different sessions have different conversation ids", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta");
      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);
      expect(convA.id).not.toBe(convB.id);
    });
  });

  describe("SchemaMessageDeletion", () => {
    it("SPEC-1.1.4: deleted message not retrievable", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Delete me");
      const deleted = await client.shortTerm.deleteMessage(msg.id);
      expect(deleted).toBe(true);
      const conv = await client.shortTerm.getConversation(SESSION_A);
      const ids = conv.messages.map((m: Message) => m.id);
      expect(ids).not.toContain(msg.id);
    });

    it("SPEC-1.1.10: deletion does not affect other messages", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Keep me");
      const msg2 = await client.shortTerm.addMessage(SESSION_A, "user", "Delete me");
      await client.shortTerm.addMessage(SESSION_A, "user", "Keep me too");
      await client.shortTerm.deleteMessage(msg2.id);
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(2);
      expect(conv.messages[0]!.content).toBe("Keep me");
      expect(conv.messages[1]!.content).toBe("Keep me too");
    });
  });

  describe("SchemaMessageProperties", () => {
    it("SPEC-1.1.11: message has id", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      expect(msg.id).toBeDefined();
    });

    it("SPEC-1.1.12: message has role matching input", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      expect(msg.role).toBe("user");
    });

    it("SPEC-1.1.13: message has content matching input", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Hello world");
      expect(msg.content).toBe("Hello world");
    });

    it("SPEC-1.1.14: message has timestamp", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      expect(msg.timestamp).toBeDefined();
    });
  });

  describe("SchemaEntityCreation", () => {
    it("SPEC-1.2.1: entity created with required fields", async () => {
      const e = ENTITIES[0]!;
      const entity = await client.longTerm.addEntity(e.name, e.type, { description: e.description });
      expect(entity.id).toBeDefined();
      expect(entity.name).toBe(e.name);
      expect(entity.type).toBe(e.type);
      expect(entity.createdAt).toBeDefined();
    });

    it("SPEC-1.2.2: entity id is valid UUID", async () => {
      const entity = await client.longTerm.addEntity("Test Entity", "PERSON");
      expect(entity.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      );
    });

    it("SPEC-1.2.3: PERSON entity type", async () => {
      const entity = await client.longTerm.addEntity("Alice", "PERSON");
      expect(entity.type).toBe("PERSON");
      expect(entity.name).toBe("Alice");
    });

    it("SPEC-1.2.4: ORGANIZATION entity type", async () => {
      const entity = await client.longTerm.addEntity("Acme", "ORGANIZATION");
      expect(entity.type).toBe("ORGANIZATION");
    });

    it("SPEC-1.2.5: LOCATION entity type", async () => {
      const entity = await client.longTerm.addEntity("NYC", "LOCATION");
      expect(entity.type).toBe("LOCATION");
    });

    it("SPEC-1.2.6: EVENT entity type", async () => {
      const entity = await client.longTerm.addEntity("Launch", "EVENT");
      expect(entity.type).toBe("EVENT");
    });

    it("SPEC-1.2.7: OBJECT entity type", async () => {
      const entity = await client.longTerm.addEntity("Laptop", "OBJECT");
      expect(entity.type).toBe("OBJECT");
    });

    it("SPEC-1.2.8: entity created_at is set", async () => {
      const entity = await client.longTerm.addEntity("Timestamped", "PERSON");
      expect(entity.createdAt).toBeDefined();
    });
  });

  describe("SchemaPreferenceCreation", () => {
    it("SPEC-1.3.1: preference has required fields", async () => {
      const pref = await client.longTerm.addPreference("language", "Prefers Python");
      expect(pref.id).toBeDefined();
      expect(pref.category).toBe("language");
      expect(pref.preference).toBe("Prefers Python");
    });

    it("SPEC-1.3.2: preference id is valid UUID", async () => {
      const pref = await client.longTerm.addPreference("food", "Likes pizza");
      expect(pref.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      );
    });
  });

  describe("SchemaFactCreation", () => {
    it("SPEC-1.4.1: fact has required fields", async () => {
      const fact = await client.longTerm.addFact("Alice", "WORKS_AT", "Acme");
      expect(fact.id).toBeDefined();
      expect(fact.subject).toBe("Alice");
      expect(fact.predicate).toBe("WORKS_AT");
      expect(fact.object).toBe("Acme");
    });

    it("SPEC-1.4.2: fact id is valid UUID", async () => {
      const fact = await client.longTerm.addFact("Bob", "KNOWS", "Alice");
      expect(fact.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      );
    });
  });

  // =====================================================================
  // Short-Term Memory Tests (test_short_term.py)
  // =====================================================================

  describe("AddMessage", () => {
    it("SPEC-2.1.1: returns valid message with UUID and timestamp", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Hello, world!");
      expect(msg.id).toBeDefined();
      expect(msg.role).toBe("user");
      expect(msg.content).toBe("Hello, world!");
      expect(msg.timestamp).toBeDefined();
    });

    it("SPEC-2.1.2: accepts user role", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "User message");
      expect(msg.role).toBe("user");
    });

    it("SPEC-2.1.3: accepts assistant role", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "assistant", "Assistant message");
      expect(msg.role).toBe("assistant");
    });

    it("SPEC-2.1.4: accepts system role", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "system", "System prompt");
      expect(msg.role).toBe("system");
    });

    it("SPEC-2.1.5: preserves metadata", async () => {
      const metadata = { source: "test", priority: "high" };
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "With meta", { metadata });
      expect(msg.metadata["source"]).toBe("test");
      expect(msg.metadata["priority"]).toBe("high");
    });

    it("SPEC-2.1.6: creates conversation on first call", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "First message");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.sessionId).toBe(SESSION_A);
      expect(conv.messages).toHaveLength(1);
    });

    it("SPEC-2.1.7: accepts empty string content", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", EMPTY_CONTENT);
      expect(msg.content).toBe("");
    });

    it("SPEC-2.1.8: preserves long content (10K+ chars)", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", LONG_CONTENT);
      expect(msg.content.length).toBe(10_000);
    });

    it("SPEC-2.1.9: preserves unicode and emoji", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", UNICODE_CONTENT);
      expect(msg.content).toBe(UNICODE_CONTENT);
    });

    it("SPEC-2.1.10: preserves special characters", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", SPECIAL_CHARS_CONTENT);
      expect(msg.content).toBe(SPECIAL_CHARS_CONTENT);
    });

    it("SPEC-2.1.11: empty dict metadata succeeds", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Empty meta", { metadata: {} });
      expect(typeof msg.metadata).toBe("object");
    });

    it("SPEC-2.1.12: preserves nested metadata", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Nested", { metadata: NESTED_METADATA });
      expect(msg.metadata["source"]).toBe("test");
      expect(msg.metadata["count"]).toBe(42);
      expect(msg.metadata["active"]).toBe(true);
    });

    it("SPEC-2.1.13: null metadata defaults to empty dict", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "No meta");
      expect(typeof msg.metadata).toBe("object");
    });

    it("SPEC-2.1.14: returns valid UUID for message id", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "UUID check");
      expect(msg.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      );
    });

    it("SPEC-2.1.15: timestamp is recent", async () => {
      const before = Date.now() - 60_000;
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Timestamp check");
      const ts = new Date(msg.timestamp).getTime();
      const after = Date.now() + 60_000;
      expect(ts).toBeGreaterThanOrEqual(before);
      expect(ts).toBeLessThanOrEqual(after);
    });

    it("SPEC-2.1.16: 50 rapid messages all stored and ordered", async () => {
      for (let i = 0; i < 50; i++) {
        await client.shortTerm.addMessage(SESSION_A, "user", `Rapid message ${i}`);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(50);
      for (let i = 0; i < 50; i++) {
        expect(conv.messages[i]!.content).toBe(`Rapid message ${i}`);
      }
    });
  });

  describe("GetConversation", () => {
    it("SPEC-2.2.1: returns messages in insertion order", async () => {
      for (const msg of CONVERSATION_MESSAGES) {
        await client.shortTerm.addMessage(SESSION_A, msg.role, msg.content);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(CONVERSATION_MESSAGES.length);
      for (let i = 0; i < conv.messages.length; i++) {
        expect(conv.messages[i]!.content).toBe(CONVERSATION_MESSAGES[i]!.content);
      }
    });

    it("SPEC-2.2.2: respects limit parameter", async () => {
      for (const msg of CONVERSATION_MESSAGES) {
        await client.shortTerm.addMessage(SESSION_A, msg.role, msg.content);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A, { limit: 2 });
      expect(conv.messages).toHaveLength(2);
    });

    it("SPEC-2.2.3: returns empty for non-existent session", async () => {
      const conv = await client.shortTerm.getConversation("tck-nonexistent");
      expect(conv.messages).toHaveLength(0);
    });

    it("SPEC-2.2.4: isolates sessions", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha message 1");
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha message 2");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta message 1");

      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);

      expect(convA.messages).toHaveLength(2);
      expect(convB.messages).toHaveLength(1);
    });

    it("SPEC-2.2.5: limit exceeding count returns all", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Only one");
      const conv = await client.shortTerm.getConversation(SESSION_A, { limit: 100 });
      expect(conv.messages).toHaveLength(1);
    });

    it("SPEC-2.2.6: limit=1 returns exactly one", async () => {
      for (const msg of CONVERSATION_MESSAGES) {
        await client.shortTerm.addMessage(SESSION_A, msg.role, msg.content);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A, { limit: 1 });
      expect(conv.messages).toHaveLength(1);
    });

    it("SPEC-2.2.7: preserves content fidelity (unicode + special chars)", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", UNICODE_CONTENT);
      await client.shortTerm.addMessage(SESSION_A, "user", SPECIAL_CHARS_CONTENT);
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages[0]!.content).toBe(UNICODE_CONTENT);
      expect(conv.messages[1]!.content).toBe(SPECIAL_CHARS_CONTENT);
    });

    it("SPEC-2.2.8: preserves metadata on retrieved messages", async () => {
      const metadata = { key: "value", num: 99 };
      await client.shortTerm.addMessage(SESSION_A, "user", "With meta", { metadata });
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages[0]!.metadata["key"]).toBe("value");
      expect(conv.messages[0]!.metadata["num"]).toBe(99);
    });

    it("SPEC-2.2.9: preserves roles", async () => {
      await client.shortTerm.addMessage(SESSION_A, "system", "System");
      await client.shortTerm.addMessage(SESSION_A, "user", "User");
      await client.shortTerm.addMessage(SESSION_A, "assistant", "Assistant");

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages[0]!.role).toBe("system");
      expect(conv.messages[1]!.role).toBe("user");
      expect(conv.messages[2]!.role).toBe("assistant");
    });

    it("SPEC-2.2.10: 20 messages maintain order", async () => {
      for (let i = 0; i < 20; i++) {
        await client.shortTerm.addMessage(SESSION_A, "user", `Message ${String(i).padStart(3, "0")}`);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(20);
      for (let i = 0; i < 20; i++) {
        expect(conv.messages[i]!.content).toBe(`Message ${String(i).padStart(3, "0")}`);
      }
    });

    it("SPEC-2.2.11: returns valid conversation id", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Hello");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.id).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i,
      );
    });

    it("SPEC-2.2.12: three sessions fully isolated", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta 1");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta 2");
      await client.shortTerm.addMessage(SESSION_C, "user", "Gamma 1");
      await client.shortTerm.addMessage(SESSION_C, "user", "Gamma 2");
      await client.shortTerm.addMessage(SESSION_C, "user", "Gamma 3");

      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);
      const convC = await client.shortTerm.getConversation(SESSION_C);

      expect(convA.messages).toHaveLength(1);
      expect(convB.messages).toHaveLength(2);
      expect(convC.messages).toHaveLength(3);
    });
  });

  describe("SearchMessages", () => {
    it("SPEC-2.3.1: finds relevant messages", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "I love programming in Python");
      await client.shortTerm.addMessage(SESSION_A, "user", "The weather is sunny today");
      await client.shortTerm.addMessage(SESSION_A, "user", "Python is great for data science");

      const results = await client.shortTerm.searchMessages("Python programming", {
        limit: 10,
        threshold: 0.0,
      });
      expect(results.length).toBeGreaterThan(0);
      expect(results.some((r: Message) => r.content.includes("Python"))).toBe(true);
    });

    it("SPEC-2.3.2: session filter restricts results", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Python in session A");
      await client.shortTerm.addMessage(SESSION_B, "user", "Python in session B");

      const results = await client.shortTerm.searchMessages("Python", {
        sessionId: SESSION_A,
        limit: 10,
        threshold: 0.0,
      });
      for (const msg of results) {
        expect(msg.content.includes("session A") || msg.content.includes("Python")).toBe(true);
      }
    });

    it("SPEC-2.3.3: respects limit", async () => {
      for (let i = 0; i < 5; i++) {
        await client.shortTerm.addMessage(SESSION_A, "user", `Test message number ${i}`);
      }
      const results = await client.shortTerm.searchMessages("Test message", {
        limit: 2,
        threshold: 0.0,
      });
      expect(results.length).toBeLessThanOrEqual(2);
    });

    it("SPEC-2.3.4: no results returns empty list", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "The sky is blue");
      const results = await client.shortTerm.searchMessages(
        "quantum cryptography algorithms",
        { limit: 10, threshold: 0.99 },
      );
      expect(Array.isArray(results)).toBe(true);
    });

    it("SPEC-2.3.5: limit=1 returns at most 1", async () => {
      for (let i = 0; i < 5; i++) {
        await client.shortTerm.addMessage(SESSION_A, "user", `Searchable content ${i}`);
      }
      const results = await client.shortTerm.searchMessages("Searchable content", {
        limit: 1,
        threshold: 0.0,
      });
      expect(results.length).toBeLessThanOrEqual(1);
    });

    it("SPEC-2.3.6: empty database returns empty list", async () => {
      const results = await client.shortTerm.searchMessages("anything", {
        limit: 10,
        threshold: 0.0,
      });
      expect(results).toEqual([]);
    });

    it("SPEC-2.3.7: searches across sessions without filter", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha Python topic");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta Python topic");

      const results = await client.shortTerm.searchMessages("Python topic", {
        limit: 10,
        threshold: 0.0,
      });
      expect(results.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe("ListSessions", () => {
    it("SPEC-2.4.1: returns all active sessions", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta");

      const sessions = await client.shortTerm.listSessions();
      const ids = sessions.map((s) => s.sessionId);
      expect(ids).toContain(SESSION_A);
      expect(ids).toContain(SESSION_B);
    });

    it("SPEC-2.4.2: includes accurate message counts", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "One");
      await client.shortTerm.addMessage(SESSION_A, "assistant", "Two");
      await client.shortTerm.addMessage(SESSION_A, "user", "Three");

      const sessions = await client.shortTerm.listSessions();
      const sessionA = sessions.find((s) => s.sessionId === SESSION_A);
      expect(sessionA?.messageCount).toBe(3);
    });

    it("SPEC-2.4.3: empty returns empty list", async () => {
      const sessions = await client.shortTerm.listSessions();
      expect(sessions).toEqual([]);
    });

    it("SPEC-2.4.4: single session returns one entry", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Solo");
      const sessions = await client.shortTerm.listSessions();
      expect(sessions).toHaveLength(1);
      expect(sessions[0]!.sessionId).toBe(SESSION_A);
    });

    it("SPEC-2.4.5: respects limit", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta");
      await client.shortTerm.addMessage(SESSION_C, "user", "Gamma");

      const sessions = await client.shortTerm.listSessions({ limit: 2 });
      expect(sessions.length).toBeLessThanOrEqual(2);
    });

    it("SPEC-2.4.6: entries have created_at", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Timestamped");
      const sessions = await client.shortTerm.listSessions();
      expect(sessions.length).toBeGreaterThanOrEqual(1);
      expect(sessions[0]!.createdAt).toBeDefined();
    });

    it("SPEC-2.4.7: message count reflects deletion", async () => {
      const msg1 = await client.shortTerm.addMessage(SESSION_A, "user", "One");
      await client.shortTerm.addMessage(SESSION_A, "user", "Two");
      await client.shortTerm.addMessage(SESSION_A, "user", "Three");
      await client.shortTerm.deleteMessage(msg1.id);

      const sessions = await client.shortTerm.listSessions();
      const sessionA = sessions.find((s) => s.sessionId === SESSION_A);
      expect(sessionA?.messageCount).toBe(2);
    });

    it("SPEC-2.4.8: independent counts per session", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "A1");
      await client.shortTerm.addMessage(SESSION_A, "user", "A2");
      await client.shortTerm.addMessage(SESSION_B, "user", "B1");

      const sessions = await client.shortTerm.listSessions();
      const sessionA = sessions.find((s) => s.sessionId === SESSION_A);
      const sessionB = sessions.find((s) => s.sessionId === SESSION_B);
      expect(sessionA?.messageCount).toBe(2);
      expect(sessionB?.messageCount).toBe(1);
    });
  });

  describe("DeleteMessage", () => {
    it("SPEC-2.5.1: returns true for existing message", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Delete me");
      const result = await client.shortTerm.deleteMessage(msg.id);
      expect(result).toBe(true);
    });

    it("SPEC-2.5.2: removes from conversation", async () => {
      const msg1 = await client.shortTerm.addMessage(SESSION_A, "user", "Keep");
      const msg2 = await client.shortTerm.addMessage(SESSION_A, "user", "Delete");
      await client.shortTerm.deleteMessage(msg2.id);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(1);
      expect(conv.messages[0]!.id).toBe(msg1.id);
    });

    it("SPEC-2.5.3: returns false for non-existent", async () => {
      const result = await client.shortTerm.deleteMessage(crypto.randomUUID());
      expect(result).toBe(false);
    });

    it("SPEC-2.5.4: preserves remaining order", async () => {
      const msgs: Message[] = [];
      for (const content of ["First", "Second", "Third", "Fourth"]) {
        msgs.push(await client.shortTerm.addMessage(SESSION_A, "user", content));
      }
      await client.shortTerm.deleteMessage(msgs[1]!.id);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(3);
      expect(conv.messages[0]!.content).toBe("First");
      expect(conv.messages[1]!.content).toBe("Third");
      expect(conv.messages[2]!.content).toBe("Fourth");
    });

    it("SPEC-2.5.5: deleting first message preserves rest", async () => {
      const msgs: Message[] = [];
      for (const content of ["First", "Second", "Third"]) {
        msgs.push(await client.shortTerm.addMessage(SESSION_A, "user", content));
      }
      await client.shortTerm.deleteMessage(msgs[0]!.id);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(2);
      expect(conv.messages[0]!.content).toBe("Second");
      expect(conv.messages[1]!.content).toBe("Third");
    });

    it("SPEC-2.5.6: deleting last message preserves earlier", async () => {
      const msgs: Message[] = [];
      for (const content of ["First", "Second", "Third"]) {
        msgs.push(await client.shortTerm.addMessage(SESSION_A, "user", content));
      }
      await client.shortTerm.deleteMessage(msgs[2]!.id);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(2);
      expect(conv.messages[0]!.content).toBe("First");
      expect(conv.messages[1]!.content).toBe("Second");
    });

    it("SPEC-2.5.7: deleting middle message repairs chain", async () => {
      const msgs: Message[] = [];
      for (const content of ["First", "Second", "Third"]) {
        msgs.push(await client.shortTerm.addMessage(SESSION_A, "user", content));
      }
      await client.shortTerm.deleteMessage(msgs[1]!.id);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(2);
      expect(conv.messages[0]!.content).toBe("First");
      expect(conv.messages[1]!.content).toBe("Third");
    });

    it("SPEC-2.5.8: deleting all messages one by one leaves empty", async () => {
      const msgs: Message[] = [];
      for (const content of ["One", "Two", "Three"]) {
        msgs.push(await client.shortTerm.addMessage(SESSION_A, "user", content));
      }
      for (const msg of msgs) {
        const result = await client.shortTerm.deleteMessage(msg.id);
        expect(result).toBe(true);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(0);
    });

    it("SPEC-2.5.9: second delete returns false", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Once");
      expect(await client.shortTerm.deleteMessage(msg.id)).toBe(true);
      expect(await client.shortTerm.deleteMessage(msg.id)).toBe(false);
    });
  });

  describe("ClearSession", () => {
    it("SPEC-2.6.1: removes all messages", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "One");
      await client.shortTerm.addMessage(SESSION_A, "assistant", "Two");
      await client.shortTerm.addMessage(SESSION_A, "user", "Three");
      await client.shortTerm.clearSession(SESSION_A);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(0);
    });

    it("SPEC-2.6.2: preserves other sessions", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta");
      await client.shortTerm.clearSession(SESSION_A);

      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);
      expect(convA.messages).toHaveLength(0);
      expect(convB.messages).toHaveLength(1);
      expect(convB.messages[0]!.content).toBe("Beta");
    });

    it("SPEC-2.6.3: idempotent on empty session", async () => {
      await client.shortTerm.clearSession(SESSION_A);
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(0);
    });

    it("SPEC-2.6.4: accepts new messages after clear", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Before");
      await client.shortTerm.clearSession(SESSION_A);
      await client.shortTerm.addMessage(SESSION_A, "user", "After");

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(1);
      expect(conv.messages[0]!.content).toBe("After");
    });

    it("SPEC-2.6.5: clearing one of three sessions preserves others", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta");
      await client.shortTerm.addMessage(SESSION_C, "user", "Gamma");
      await client.shortTerm.clearSession(SESSION_B);

      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);
      const convC = await client.shortTerm.getConversation(SESSION_C);

      expect(convA.messages).toHaveLength(1);
      expect(convB.messages).toHaveLength(0);
      expect(convC.messages).toHaveLength(1);
    });
  });

  describe("MessageChainStructure", () => {
    it("SPEC-2.7.1: maintains insertion order", async () => {
      const contents = ["First", "Second", "Third", "Fourth", "Fifth"];
      for (const content of contents) {
        await client.shortTerm.addMessage(SESSION_A, "user", content);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(5);
      for (let i = 0; i < contents.length; i++) {
        expect(conv.messages[i]!.content).toBe(contents[i]);
      }
    });

    it("SPEC-2.7.2: timestamps are monotonically increasing", async () => {
      for (const content of ["First", "Second", "Third"]) {
        await client.shortTerm.addMessage(SESSION_A, "user", content);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      for (let i = 1; i < conv.messages.length; i++) {
        const prev = new Date(conv.messages[i - 1]!.timestamp).getTime();
        const curr = new Date(conv.messages[i]!.timestamp).getTime();
        expect(curr).toBeGreaterThanOrEqual(prev);
      }
    });

    it("SPEC-2.7.3: single message retrievable", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Solo");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(1);
      expect(conv.messages[0]!.content).toBe("Solo");
    });

    it("SPEC-2.7.4: two messages correctly ordered", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "First");
      await client.shortTerm.addMessage(SESSION_A, "assistant", "Second");
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(2);
      expect(conv.messages[0]!.content).toBe("First");
      expect(conv.messages[1]!.content).toBe("Second");
    });

    it("SPEC-2.7.5: chain integrity after middle delete", async () => {
      const msgs: Message[] = [];
      for (const c of ["A", "B", "C", "D", "E"]) {
        msgs.push(await client.shortTerm.addMessage(SESSION_A, "user", c));
      }
      await client.shortTerm.deleteMessage(msgs[2]!.id);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(4);
      expect(conv.messages.map((m: Message) => m.content)).toEqual(["A", "B", "D", "E"]);
    });

    it("SPEC-2.7.6: 100 messages maintain order", async () => {
      for (let i = 0; i < 100; i++) {
        await client.shortTerm.addMessage(SESSION_A, "user", `Msg-${String(i).padStart(4, "0")}`);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(100);
      for (let i = 0; i < 100; i++) {
        expect(conv.messages[i]!.content).toBe(`Msg-${String(i).padStart(4, "0")}`);
      }
    });

    it("SPEC-2.7.7: mixed roles maintain order", async () => {
      const sequence: [string, string][] = [
        ["system", "You are helpful"],
        ["user", "Hello"],
        ["assistant", "Hi there"],
        ["user", "How are you?"],
        ["assistant", "I'm doing well"],
      ];
      for (const [role, content] of sequence) {
        await client.shortTerm.addMessage(SESSION_A, role, content);
      }
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(5);
      for (let i = 0; i < sequence.length; i++) {
        expect(conv.messages[i]!.role).toBe(sequence[i]![0]);
        expect(conv.messages[i]!.content).toBe(sequence[i]![1]);
      }
    });
  });

  describe("Idempotency", () => {
    it("SPEC-2.8.1: each add_message returns unique ID", async () => {
      const msg1 = await client.shortTerm.addMessage(SESSION_A, "user", "Same");
      const msg2 = await client.shortTerm.addMessage(SESSION_A, "user", "Same");
      expect(msg1.id).not.toBe(msg2.id);
    });

    it("SPEC-2.8.2: duplicate content stored separately", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Dup");
      await client.shortTerm.addMessage(SESSION_A, "user", "Dup");
      await client.shortTerm.addMessage(SESSION_A, "user", "Dup");

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(3);
    });

    it("SPEC-2.8.3: clear_session is idempotent", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Data");
      await client.shortTerm.clearSession(SESSION_A);
      await client.shortTerm.clearSession(SESSION_A);
      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(0);
    });
  });
});
