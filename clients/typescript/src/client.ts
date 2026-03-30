/**
 * MemoryClient — root entry point for all memory operations.
 *
 * Provides access to short-term, long-term, and reasoning memory
 * through a unified client with configurable transport.
 */

import { ValidationError } from "./errors.js";
import { LongTermMemory } from "./long-term/index.js";
import { ReasoningMemory } from "./reasoning/index.js";
import { ShortTermMemory } from "./short-term/index.js";
import type { Transport } from "./transport/index.js";
import { HttpTransport } from "./transport/http.js";
import type { MemoryClientOptions } from "./types.js";

export class MemoryClient {
  /** Short-term (conversational) memory operations. */
  readonly shortTerm: ShortTermMemory;

  /** Long-term (entity/preference/fact) memory operations. */
  readonly longTerm: LongTermMemory;

  /** Reasoning (trace/step/tool call) memory operations. */
  readonly reasoning: ReasoningMemory;

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
  }

  /** Establish the connection to the backend. */
  async connect(): Promise<void> {
    await this.transport.connect();
  }

  /** Close the connection and release resources. */
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

function createTransport(options: MemoryClientOptions): Transport {
  if (options.endpoint) {
    return new HttpTransport({
      endpoint: options.endpoint,
      apiKey: options.apiKey,
      timeout: options.timeout,
    });
  }

  if (options.neo4jUri) {
    // Direct Neo4j connection would be loaded dynamically to avoid
    // requiring neo4j-driver as a hard dependency.
    throw new ValidationError(
      "Direct Neo4j connection is not yet implemented. " +
        "Use the endpoint option to connect to the hosted service.",
    );
  }

  throw new ValidationError(
    "Either endpoint or neo4jUri must be provided in MemoryClientOptions.",
  );
}
