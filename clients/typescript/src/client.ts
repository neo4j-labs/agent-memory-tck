/**
 * MemoryClient — root entry point for all memory operations.
 *
 * Zero-config form (Node, Bun, Deno):
 *
 *     const client = new MemoryClient();
 *
 * defaults the endpoint to https://memory.neo4jlabs.com/v1 and reads
 * MEMORY_API_KEY from the environment.
 *
 * Edge runtimes (Cloudflare Workers, Vercel Edge) read env from the request
 * handler scope, not module init, so pass apiKey explicitly:
 *
 *     const client = new MemoryClient({ apiKey: env.MEMORY_API_KEY });
 *
 * The first request triggers the auth probe automatically; calling
 * `connect()` upfront is supported but optional.
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

const DEFAULT_ENDPOINT = "https://memory.neo4jlabs.com/v1";

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

  constructor(options?: MemoryClientOptions);
  constructor(transport: Transport);
  constructor(optionsOrTransport: MemoryClientOptions | Transport = {}) {
    if (isTransport(optionsOrTransport)) {
      this.transport = optionsOrTransport;
    } else {
      this.transport = new LazyConnectTransport(createTransport(optionsOrTransport));
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
  // Auto: REST if the endpoint path contains /vN (the canonical hosted root).
  return /\/v\d+\b/.test(endpoint) ? "rest" : "bridge";
}

/**
 * Resolve the API key from explicit option or MEMORY_API_KEY env var.
 *
 * Explicit `undefined` falls through to env. Explicit empty string does NOT
 * — passing `apiKey: ""` is treated as "I am intentionally unauthenticated."
 */
function resolveApiKey(option: string | undefined): string | undefined {
  if (option !== undefined) return option;
  if (typeof process === "undefined" || !process.env) return undefined;
  return process.env.MEMORY_API_KEY;
}

function createTransport(options: MemoryClientOptions): Transport {
  const endpoint = options.endpoint;
  const apiKey = resolveApiKey(options.apiKey);

  const choice = pickTransport(endpoint ?? DEFAULT_ENDPOINT, options.transport);
  if (choice === "rest") {
    return new RestTransport({
      endpoint: endpoint ?? DEFAULT_ENDPOINT,
      apiKey,
      tokenProvider: options.tokenProvider,
      timeout: options.timeout,
      headers: options.headers,
      logger: options.logger,
    });
  }
  if (!endpoint) {
    throw new ValidationError("endpoint must be provided for bridge transport.");
  }
  return new BridgeTransport({
    endpoint,
    apiKey,
    timeout: options.timeout,
    headers: options.headers,
    logger: options.logger,
  });
}

/**
 * Wraps a Transport so requests are issued without an upfront connectivity
 * probe — the first real request becomes the de facto health check. An
 * explicit `connect()` still triggers the inner connect (and is idempotent
 * across concurrent callers), letting apps that prefer fail-fast at startup
 * opt in.
 */
class LazyConnectTransport implements Transport {
  private connectPromise: Promise<void> | null = null;

  constructor(public readonly inner: Transport) {}

  async request<T>(method: string, params: Record<string, unknown>): Promise<T> {
    return this.inner.request<T>(method, params);
  }

  async connect(): Promise<void> {
    if (!this.connectPromise) {
      this.connectPromise = this.inner.connect().catch((err) => {
        this.connectPromise = null;
        throw err;
      });
    }
    return this.connectPromise;
  }

  async close(): Promise<void> {
    return this.inner.close();
  }
}
