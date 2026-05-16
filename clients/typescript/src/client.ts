/**
 * MemoryClient — root entry point for all memory operations.
 *
 * Picks a transport automatically based on the endpoint shape:
 *   - Endpoints containing `/v1` → RestTransport (hosted service)
 *   - Otherwise → BridgeTransport (TCK conformance servers, local reference)
 *
 * Override with `transport: "rest" | "bridge"` in MemoryClientOptions, or
 * pass a custom Transport instance directly.
 */

import { AuthClient } from "./auth/index.js";
import { ValidationError } from "./errors.js";
import { LongTermMemory } from "./long-term/index.js";
import { QueryConsole } from "./query/index.js";
import { ReasoningMemory } from "./reasoning/index.js";
import { ShortTermMemory } from "./short-term/index.js";
import { BridgeTransport } from "./transport/bridge.js";
import type { Transport } from "./transport/index.js";
import { RestTransport } from "./transport/rest.js";
import type { MemoryClientOptions } from "./types.js";

export class MemoryClient {
  /** Short-term (conversational) memory operations. */
  readonly shortTerm: ShortTermMemory;

  /** Long-term (entity / preference / fact / graph) memory operations. */
  readonly longTerm: LongTermMemory;

  /** Reasoning (trace / step / tool call / provenance) memory operations. */
  readonly reasoning: ReasoningMemory;

  /** Read-only Cypher query console (hosted service only). */
  readonly query: QueryConsole;

  /** API-key & OAuth management (hosted service only). */
  readonly auth: AuthClient;

  private readonly transport: Transport;

  constructor(options: MemoryClientOptions);
  constructor(transport: Transport);
  constructor(optionsOrTransport: MemoryClientOptions | Transport) {
    if (isTransport(optionsOrTransport)) {
      this.transport = optionsOrTransport;
    } else {
      this.transport = createTransport(optionsOrTransport);
    }

    this.shortTerm = new ShortTermMemory(this.transport);
    this.longTerm = new LongTermMemory(this.transport);
    this.reasoning = new ReasoningMemory(this.transport);
    this.query = new QueryConsole(this.transport);
    this.auth = new AuthClient(this.transport);
  }

  async connect(): Promise<void> {
    await this.transport.connect();
  }

  async close(): Promise<void> {
    await this.transport.close();
  }
}

function isTransport(obj: unknown): obj is Transport {
  return (
    typeof obj === "object" &&
    obj !== null &&
    "request" in obj &&
    typeof (obj as Transport).request === "function"
  );
}

function pickTransport(endpoint: string, mode: MemoryClientOptions["transport"]): "bridge" | "rest" {
  if (mode === "bridge" || mode === "rest") return mode;
  // Auto: REST if the endpoint path contains /v1 (the canonical hosted root).
  return /\/v\d+\b/.test(endpoint) ? "rest" : "bridge";
}

function createTransport(options: MemoryClientOptions): Transport {
  if (!options.endpoint) {
    if (options.neo4jUri) {
      throw new ValidationError(
        "Direct Neo4j connection is not yet implemented. Use endpoint with the hosted service.",
      );
    }
    throw new ValidationError("Either endpoint or neo4jUri must be provided.");
  }

  const choice = pickTransport(options.endpoint, options.transport);
  if (choice === "rest") {
    return new RestTransport({
      endpoint: options.endpoint,
      apiKey: options.apiKey,
      tokenProvider: options.tokenProvider,
      timeout: options.timeout,
      headers: options.headers,
    });
  }
  return new BridgeTransport({
    endpoint: options.endpoint,
    apiKey: options.apiKey,
    timeout: options.timeout,
    headers: options.headers,
  });
}
