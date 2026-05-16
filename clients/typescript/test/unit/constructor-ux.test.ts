/**
 * Unit tests — zero-config construction, env-fallback API key, lazy connect.
 */

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MemoryClient } from "../../src/client.js";
import { RestTransport } from "../../src/transport/rest.js";

function getInternalTransport(client: MemoryClient): unknown {
  const t = (client as unknown as { transport: unknown }).transport;
  if (t && typeof t === "object" && "inner" in t) {
    return (t as { inner: unknown }).inner;
  }
  return t;
}

function getEndpoint(client: MemoryClient): string {
  const inner = getInternalTransport(client) as { endpoint?: string };
  return inner.endpoint ?? "";
}

function getApiKey(client: MemoryClient): string | undefined {
  const inner = getInternalTransport(client) as { apiKey?: string };
  return inner.apiKey;
}

describe("MemoryClient zero-config", () => {
  const originalKey = process.env.MEMORY_API_KEY;

  beforeEach(() => {
    delete process.env.MEMORY_API_KEY;
  });

  afterEach(() => {
    if (originalKey === undefined) {
      delete process.env.MEMORY_API_KEY;
    } else {
      process.env.MEMORY_API_KEY = originalKey;
    }
  });

  it("defaults endpoint to the hosted service when omitted", () => {
    const c = new MemoryClient({ apiKey: "k" });
    expect(getEndpoint(c)).toBe("https://memory.neo4jlabs.com/v1");
    expect(getInternalTransport(c)).toBeInstanceOf(RestTransport);
  });

  it("reads MEMORY_API_KEY from env when apiKey is omitted", () => {
    process.env.MEMORY_API_KEY = "env-key";
    const c = new MemoryClient();
    expect(getApiKey(c)).toBe("env-key");
  });

  it("explicit apiKey wins over env var", () => {
    process.env.MEMORY_API_KEY = "env-key";
    const c = new MemoryClient({ apiKey: "explicit-key" });
    expect(getApiKey(c)).toBe("explicit-key");
  });

  it("explicit empty apiKey is preserved (does NOT fall back to env)", () => {
    process.env.MEMORY_API_KEY = "env-key";
    const c = new MemoryClient({ apiKey: "" });
    expect(getApiKey(c)).toBe("");
  });

  it("zero-arg construction works without any env var", () => {
    expect(() => new MemoryClient()).not.toThrow();
  });
});

describe("MemoryClient lazy connect", () => {
  it("requests are issued without an implicit connect probe", async () => {
    let connectCalls = 0;
    const fakeInner = {
      request: vi.fn(async () => ({})),
      connect: vi.fn(async () => {
        connectCalls++;
      }),
      close: vi.fn(async () => {}),
    };

    const c = new MemoryClient();
    const lazyWrapper = (c as unknown as { transport: { inner: unknown } }).transport;
    (lazyWrapper as { inner: unknown }).inner = fakeInner;

    await Promise.all([
      c.shortTerm.listConversations({ limit: 1 }).catch(() => null),
      c.shortTerm.listConversations({ limit: 1 }).catch(() => null),
      c.shortTerm.listConversations({ limit: 1 }).catch(() => null),
    ]);

    expect(connectCalls).toBe(0);
  });

  it("explicit connect() is idempotent across concurrent callers", async () => {
    let connectCalls = 0;
    const fakeInner = {
      request: vi.fn(async () => ({})),
      connect: vi.fn(async () => {
        connectCalls++;
      }),
      close: vi.fn(async () => {}),
    };

    const c = new MemoryClient();
    const lazyWrapper = (c as unknown as { transport: { inner: unknown } }).transport;
    (lazyWrapper as { inner: unknown }).inner = fakeInner;

    await Promise.all([c.connect(), c.connect(), c.connect()]);

    expect(connectCalls).toBe(1);
  });

  it("failed connect clears the cached promise so a retry can re-attempt", async () => {
    let calls = 0;
    const fakeInner = {
      request: vi.fn(async () => ({})),
      connect: vi.fn(async () => {
        calls++;
        if (calls === 1) throw new Error("transient");
      }),
      close: vi.fn(async () => {}),
    };

    const c = new MemoryClient();
    const lazyWrapper = (c as unknown as { transport: { inner: unknown } }).transport;
    (lazyWrapper as { inner: unknown }).inner = fakeInner;

    await expect(c.connect()).rejects.toThrow("transient");
    await expect(c.connect()).resolves.toBeUndefined();
    expect(calls).toBe(2);
  });

  it("user-supplied Transport is NOT wrapped in lazy connect", () => {
    const fake = {
      request: async () => ({}),
      connect: async () => {},
      close: async () => {},
    };
    const c = new MemoryClient(fake);
    expect(getInternalTransport(c)).toBe(fake);
  });
});
