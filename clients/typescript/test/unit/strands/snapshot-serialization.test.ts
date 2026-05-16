/**
 * Pure-logic tests for Snapshot serialization. We construct synthetic
 * Snapshot objects, feed them through Neo4jSessionStorage with a mocked
 * MemoryClient that records every call, and assert the message extraction
 * + opaque blob behaviour. No HTTP involved.
 */

import { describe, it, expect, vi } from "vitest";
import type { Snapshot } from "@strands-agents/sdk";
import { Neo4jSessionStorage } from "../../../src/integrations/strands.js";

type RecordedCall = { method: string; params: Record<string, unknown> };

interface StoredState {
  latestId?: string;
  history?: string[];
  blobs?: Record<string, unknown>;
  manifest?: unknown;
}

function makeFakeClient(opts: {
  /** Pre-seed Strands state, as the integration would have written it. */
  initialState?: StoredState;
  initialMessages?: Array<{ id: string; role: string; content: string }>;
} = {}) {
  const calls: RecordedCall[] = [];
  let metadata: Record<string, unknown> =
    opts.initialState !== undefined
      ? { strands_state: JSON.stringify(opts.initialState) }
      : {};
  const messages = (opts.initialMessages ?? []).slice();

  const transport = {
    async request(method: string, params: Record<string, unknown>) {
      calls.push({ method, params });
      if (method === "update_conversation_metadata") {
        metadata = (params.metadata as Record<string, unknown>) ?? {};
        return undefined;
      }
      return undefined;
    },
  };

  const fake = {
    transport,
    shortTerm: {
      async getConversation(_id: string) {
        return { id: _id, messages: messages.map((m) => ({ ...m })) };
      },
      async getConversationMetadata(_id: string) {
        return { id: _id, metadata };
      },
      async addMessage(convId: string, role: string, content: string) {
        const id = `m${messages.length + 1}`;
        const m = { id, role, content };
        messages.push(m);
        calls.push({ method: "addMessage", params: { convId, role, content } });
        return m;
      },
      async deleteConversation(convId: string) {
        calls.push({ method: "deleteConversation", params: { convId } });
        return undefined;
      },
    },
  };
  function getState(): StoredState {
    const raw = metadata.strands_state;
    if (typeof raw !== "string") return {};
    try {
      return JSON.parse(raw) as StoredState;
    } catch {
      return {};
    }
  }

  return {
    client: fake as unknown as ConstructorParameters<typeof Neo4jSessionStorage>[0],
    calls,
    getMetadata: () => metadata,
    getState,
    getMessages: () => messages,
  };
}

function snapshotWith(opts: {
  messages?: Array<{ role: string; content: Array<{ text?: string; type?: string }> }>;
  data?: Record<string, unknown>;
  appData?: Record<string, unknown>;
}): Snapshot {
  return {
    scope: "agent",
    schemaVersion: "1.0",
    createdAt: new Date().toISOString(),
    data: {
      ...(opts.data ?? {}),
      messages: opts.messages ?? [],
    } as unknown as Snapshot["data"],
    appData: (opts.appData ?? {}) as Snapshot["appData"],
  };
}

const LOCATION = { sessionId: "conv-1", scope: "agent" as const, scopeId: "agent-1" };

describe("Neo4jSessionStorage — message extraction", () => {
  it("extracts text-block messages and persists each via addMessage", async () => {
    const { client, calls } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const snap = snapshotWith({
      messages: [
        { role: "user", content: [{ text: "hello" }] },
        { role: "assistant", content: [{ text: "hi there" }] },
      ],
    });

    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: true,
      snapshot: snap,
    });

    const addCalls = calls.filter((c) => c.method === "addMessage");
    expect(addCalls).toHaveLength(2);
    expect(addCalls[0]!.params).toMatchObject({ role: "user", content: "hello" });
    expect(addCalls[1]!.params).toMatchObject({ role: "assistant", content: "hi there" });
  });

  it("dedupes against existing conversation messages", async () => {
    const { client, calls } = makeFakeClient({
      initialMessages: [{ id: "m0", role: "user", content: "hello" }],
    });
    const storage = new Neo4jSessionStorage(client);
    const snap = snapshotWith({
      messages: [
        { role: "user", content: [{ text: "hello" }] },
        { role: "assistant", content: [{ text: "new" }] },
      ],
    });

    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: true,
      snapshot: snap,
    });

    const addCalls = calls.filter((c) => c.method === "addMessage");
    expect(addCalls).toHaveLength(1);
    expect(addCalls[0]!.params).toMatchObject({ role: "assistant", content: "new" });
  });

  it("handles snapshots with zero messages", async () => {
    const { client, calls } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: true,
      snapshot: snapshotWith({ messages: [] }),
    });
    expect(calls.filter((c) => c.method === "addMessage")).toHaveLength(0);
  });

  it("handles malformed snapshots (missing messages field) without throwing", async () => {
    const { client } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const malformed = {
      scope: "agent",
      schemaVersion: "1.0",
      createdAt: "x",
      data: {} as Snapshot["data"],
      appData: {} as Snapshot["appData"],
    } as Snapshot;
    await expect(
      storage.saveSnapshot({
        location: LOCATION,
        snapshotId: "s1",
        isLatest: true,
        snapshot: malformed,
      }),
    ).resolves.toBeUndefined();
  });

  it("preserves non-message snapshot state in metadata blob", async () => {
    const { client, getState } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);

    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: true,
      snapshot: snapshotWith({
        messages: [{ role: "user", content: [{ text: "hi" }] }],
        data: { agentState: { foo: 1 } },
        appData: { userCounter: 42 },
      }),
    });

    const state = getState();
    const blobs = state.blobs as Record<string, Snapshot>;
    expect(blobs["s1"]).toBeDefined();
    expect(blobs["s1"]!.appData).toMatchObject({ userCounter: 42 });
    expect(blobs["s1"]!.data).toMatchObject({ agentState: { foo: 1 } });
    // messages field stripped from the persisted blob
    expect((blobs["s1"]!.data as Record<string, unknown>).messages).toBeUndefined();
  });

  it("tracks isLatest separately from the history list", async () => {
    const { client, getState } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);

    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: false,
      snapshot: snapshotWith({ messages: [] }),
    });
    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s2",
      isLatest: true,
      snapshot: snapshotWith({ messages: [] }),
    });

    const state = getState();
    expect(state.latestId).toBe("s2");
    expect(state.history).toEqual(["s1", "s2"]);
  });
});

describe("Neo4jSessionStorage — load + list + delete", () => {
  it("loadSnapshot returns the stashed blob + current messages merged in", async () => {
    const initial: StoredState = {
      latestId: "s1",
      history: ["s1"],
      blobs: {
        s1: {
          scope: "agent",
          schemaVersion: "1.0",
          createdAt: "x",
          data: { agentState: { foo: 1 } },
          appData: { stored: true },
        },
      },
    };
    const { client } = makeFakeClient({
      initialState: initial,
      initialMessages: [{ id: "m1", role: "user", content: "hi" }],
    });
    const storage = new Neo4jSessionStorage(client);
    const snap = await storage.loadSnapshot({ location: LOCATION });
    expect(snap).not.toBeNull();
    expect(snap!.data).toMatchObject({
      agentState: { foo: 1 },
    });
    const messages = (snap!.data as { messages?: unknown[] }).messages;
    expect(Array.isArray(messages)).toBe(true);
    expect(messages).toHaveLength(1);
  });

  it("loadSnapshot returns null when no snapshot exists", async () => {
    const { client } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const snap = await storage.loadSnapshot({ location: LOCATION });
    expect(snap).toBeNull();
  });

  it("listSnapshotIds respects limit + startAfter", async () => {
    const { client } = makeFakeClient({
      initialState: {
        history: ["a", "b", "c", "d", "e"],
        blobs: {},
      },
    });
    const storage = new Neo4jSessionStorage(client);
    expect(await storage.listSnapshotIds({ location: LOCATION })).toEqual([
      "a", "b", "c", "d", "e",
    ]);
    expect(await storage.listSnapshotIds({ location: LOCATION, limit: 2 })).toEqual(["a", "b"]);
    expect(
      await storage.listSnapshotIds({ location: LOCATION, startAfter: "b" }),
    ).toEqual(["c", "d", "e"]);
    expect(
      await storage.listSnapshotIds({ location: LOCATION, startAfter: "b", limit: 2 }),
    ).toEqual(["c", "d"]);
  });

  it("deleteSession calls deleteConversation", async () => {
    const { client, calls } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    await storage.deleteSession({ sessionId: "conv-1" });
    expect(calls.find((c) => c.method === "deleteConversation")).toBeTruthy();
  });
});

describe("Neo4jSessionStorage — manifest", () => {
  it("manifest round-trip", async () => {
    const { client } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const manifest = { schemaVersion: "1.0", updatedAt: "2026-05-16T00:00:00Z" };
    await storage.saveManifest({ location: LOCATION, manifest });
    const loaded = await storage.loadManifest({ location: LOCATION });
    expect(loaded).toEqual(manifest);
  });

  it("loadManifest returns a default when none stored", async () => {
    const { client } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const loaded = await storage.loadManifest({ location: LOCATION });
    expect(loaded.schemaVersion).toBe("1.0");
    expect(typeof loaded.updatedAt).toBe("string");
  });
});

describe("Neo4jSessionStorage — unicode + long content", () => {
  it("preserves unicode through extraction + persistence", async () => {
    const { client, calls } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const content = "你好 🚀 émoji ñ ç ø";
    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: true,
      snapshot: snapshotWith({
        messages: [{ role: "user", content: [{ text: content }] }],
      }),
    });
    const addCalls = calls.filter((c) => c.method === "addMessage");
    expect(addCalls[0]!.params).toMatchObject({ content });
  });

  it("preserves a thousand messages without loss", async () => {
    const { client, calls } = makeFakeClient();
    const storage = new Neo4jSessionStorage(client);
    const big = Array.from({ length: 1000 }, (_, i) => ({
      role: i % 2 === 0 ? "user" : "assistant",
      content: [{ text: `msg-${i}` }],
    }));
    await storage.saveSnapshot({
      location: LOCATION,
      snapshotId: "s1",
      isLatest: true,
      snapshot: snapshotWith({ messages: big }),
    });
    const addCalls = calls.filter((c) => c.method === "addMessage");
    expect(addCalls).toHaveLength(1000);
  });
});

// Silence unused warning on vi when unused in this file.
void vi;
