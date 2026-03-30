/**
 * Bronze Tier TCK Conformance Tests.
 *
 * These tests mirror the Python TCK test suite (tck/tests/v1/test_short_term.py)
 * and validate the TypeScript client against the same behavioral contracts.
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
  NESTED_METADATA,
} from "./testdata.js";

const ENDPOINT = process.env["MEMORY_ENDPOINT"] ?? "http://localhost:3001";

describe("Bronze Tier", () => {
  let client: MemoryClient;

  beforeAll(async () => {
    client = new MemoryClient({ endpoint: ENDPOINT });
    await client.connect();
  });

  afterAll(async () => {
    await client.close();
  });

  beforeEach(async () => {
    // Clear all data between tests for isolation
    await client.shortTerm.clearSession(SESSION_A);
    await client.shortTerm.clearSession(SESSION_B);
    await client.shortTerm.clearSession(SESSION_C);
  });

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

    it("SPEC-2.1.12: preserves nested metadata", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Nested", { metadata: NESTED_METADATA });
      expect(msg.metadata["source"]).toBe("test");
      expect(msg.metadata["count"]).toBe(42);
      expect(msg.metadata["active"]).toBe(true);
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
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha 1");
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha 2");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta 1");

      const convA = await client.shortTerm.getConversation(SESSION_A);
      const convB = await client.shortTerm.getConversation(SESSION_B);

      expect(convA.messages).toHaveLength(2);
      expect(convB.messages).toHaveLength(1);
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
  });

  describe("SearchMessages", () => {
    it("SPEC-2.3.1: finds relevant messages", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "I love programming in Python");
      await client.shortTerm.addMessage(SESSION_A, "user", "The weather is sunny today");

      const results = await client.shortTerm.searchMessages("Python programming", {
        limit: 10,
        threshold: 0.0,
      });
      expect(results.length).toBeGreaterThan(0);
      expect(results.some((r: Message) => r.content.includes("Python"))).toBe(true);
    });

    it("SPEC-2.3.3: respects limit", async () => {
      for (let i = 0; i < 5; i++) {
        await client.shortTerm.addMessage(SESSION_A, "user", `Test message ${i}`);
      }
      const results = await client.shortTerm.searchMessages("Test message", {
        limit: 2,
        threshold: 0.0,
      });
      expect(results.length).toBeLessThanOrEqual(2);
    });

    it("SPEC-2.3.6: empty database returns empty list", async () => {
      const results = await client.shortTerm.searchMessages("anything", {
        limit: 10,
        threshold: 0.0,
      });
      expect(results).toEqual([]);
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

    it("SPEC-2.5.9: second delete returns false", async () => {
      const msg = await client.shortTerm.addMessage(SESSION_A, "user", "Once");
      expect(await client.shortTerm.deleteMessage(msg.id)).toBe(true);
      expect(await client.shortTerm.deleteMessage(msg.id)).toBe(false);
    });
  });

  describe("ClearSession", () => {
    it("SPEC-2.6.1: removes all messages", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "One");
      await client.shortTerm.addMessage(SESSION_A, "user", "Two");
      await client.shortTerm.clearSession(SESSION_A);

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(0);
    });

    it("SPEC-2.6.2: preserves other sessions", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Alpha");
      await client.shortTerm.addMessage(SESSION_B, "user", "Beta");
      await client.shortTerm.clearSession(SESSION_A);

      const convB = await client.shortTerm.getConversation(SESSION_B);
      expect(convB.messages).toHaveLength(1);
      expect(convB.messages[0]!.content).toBe("Beta");
    });

    it("SPEC-2.6.4: accepts new messages after clear", async () => {
      await client.shortTerm.addMessage(SESSION_A, "user", "Before");
      await client.shortTerm.clearSession(SESSION_A);
      await client.shortTerm.addMessage(SESSION_A, "user", "After");

      const conv = await client.shortTerm.getConversation(SESSION_A);
      expect(conv.messages).toHaveLength(1);
      expect(conv.messages[0]!.content).toBe("After");
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
  });
});
