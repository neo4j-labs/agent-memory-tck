/**
 * Edge-runtime sanity tests — verifies the package can be constructed and
 * issue a request in an environment where `process` is undefined.
 *
 * A full Cloudflare Workers harness lives outside this unit suite; this
 * file is the cheap canary that catches the most common edge regression
 * (an accidental unguarded `process.env` read in the construction path).
 */

import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { MemoryClient } from "../../src/client.js";
import { RestTransport } from "../../src/transport/rest.js";

describe("Edge runtime: process is undefined", () => {
  let savedProcess: unknown;

  beforeEach(() => {
    savedProcess = (globalThis as { process?: unknown }).process;
    // Simulate edge: `process` does not exist at all.
    delete (globalThis as { process?: unknown }).process;
  });

  afterEach(() => {
    (globalThis as { process?: unknown }).process = savedProcess;
  });

  it("constructs without throwing when process is undefined", () => {
    expect(() => new MemoryClient({ apiKey: "k" })).not.toThrow();
  });

  it("zero-arg construction works without process", () => {
    expect(() => new MemoryClient()).not.toThrow();
  });

  it("explicit apiKey is used (no env lookup attempted)", () => {
    const c = new MemoryClient({ apiKey: "explicit-key" });
    const inner = (c as unknown as { transport: { inner: { apiKey?: string } } }).transport.inner;
    expect(inner.apiKey).toBe("explicit-key");
  });
});

describe("Edge runtime: User-Agent without process", () => {
  let savedProcess: unknown;

  beforeEach(() => {
    savedProcess = (globalThis as { process?: unknown }).process;
    delete (globalThis as { process?: unknown }).process;
  });

  afterEach(() => {
    (globalThis as { process?: unknown }).process = savedProcess;
  });

  it("defaultUserAgent works without process", async () => {
    const { defaultUserAgent } = await import("../../src/observability.js");
    const ua = defaultUserAgent();
    expect(ua).toMatch(/@neo4j-labs\/agent-memory\/\d+\.\d+\.\d+/);
  });

  it("does not add a User-Agent header when the runtime forbids it", async () => {
    const transport = new RestTransport({ endpoint: "https://memory.test/v1", apiKey: "k" });
    const headers = await (
      transport as unknown as {
        buildHeaders: (includeContentType?: boolean) => Promise<Record<string, string>>;
      }
    ).buildHeaders();
    expect(Object.keys(headers).some((key) => key.toLowerCase() === "user-agent")).toBe(false);
  });

  it("drops caller-supplied User-Agent headers when the runtime forbids them", async () => {
    const transport = new RestTransport({
      endpoint: "https://memory.test/v1",
      apiKey: "k",
      headers: { "User-Agent": "wrapper/1.0" },
    });
    const headers = await (
      transport as unknown as {
        buildHeaders: (includeContentType?: boolean) => Promise<Record<string, string>>;
      }
    ).buildHeaders();
    expect(Object.keys(headers).some((key) => key.toLowerCase() === "user-agent")).toBe(false);
  });
});
