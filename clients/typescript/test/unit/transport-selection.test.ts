/**
 * Unit tests — auto-selection of BridgeTransport vs RestTransport.
 */

import { describe, it, expect } from "vitest";
import { MemoryClient } from "../../src/client.js";
import { BridgeTransport, RestTransport } from "../../src/transport/index.js";

function getInternalTransport(client: MemoryClient): unknown {
  return (client as unknown as { transport: unknown }).transport;
}

describe("transport auto-selection", () => {
  it("selects RestTransport for /v1 endpoints", () => {
    const c = new MemoryClient({
      endpoint: "https://memory.neo4jlabs.com/v1",
      apiKey: "k",
    });
    expect(getInternalTransport(c)).toBeInstanceOf(RestTransport);
  });

  it("selects RestTransport for /v2 endpoints", () => {
    const c = new MemoryClient({
      endpoint: "https://memory.neo4jlabs.com/v2",
      apiKey: "k",
    });
    expect(getInternalTransport(c)).toBeInstanceOf(RestTransport);
  });

  it("selects BridgeTransport for localhost:3001", () => {
    const c = new MemoryClient({ endpoint: "http://localhost:3001" });
    expect(getInternalTransport(c)).toBeInstanceOf(BridgeTransport);
  });

  it("explicit transport: 'bridge' overrides auto-detection", () => {
    const c = new MemoryClient({
      endpoint: "https://memory.neo4jlabs.com/v1",
      transport: "bridge",
    });
    expect(getInternalTransport(c)).toBeInstanceOf(BridgeTransport);
  });

  it("explicit transport: 'rest' overrides auto-detection", () => {
    const c = new MemoryClient({
      endpoint: "http://localhost:3001",
      transport: "rest",
      apiKey: "k",
    });
    expect(getInternalTransport(c)).toBeInstanceOf(RestTransport);
  });

  it("accepts a custom Transport instance", () => {
    const fake = {
      request: async () => ({}),
      connect: async () => {},
      close: async () => {},
    };
    const c = new MemoryClient(fake);
    expect(getInternalTransport(c)).toBe(fake);
  });
});
