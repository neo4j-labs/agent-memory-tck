/**
 * Cypher query console — read-only access to the underlying graph.
 *
 * Hosted service only: write operations are rejected with HTTP 400.
 */

import type { Transport } from "../transport/index.js";
import type { CypherResult } from "../types.js";

interface WireCypherResult {
  columns?: string[];
  rows?: unknown[][];
  stats?: Record<string, unknown>;
}

export class QueryConsole {
  constructor(private readonly transport: Transport) {}

  /**
   * Execute a read-only Cypher query.
   *
   * @example
   * const r = await client.query.cypher({
   *   cypher: "MATCH (e:Entity) RETURN e.name AS name LIMIT $n",
   *   params: { n: 10 },
   * });
   */
  async cypher(input: { cypher: string; params?: Record<string, unknown> }): Promise<CypherResult> {
    const wire = await this.transport.request<WireCypherResult>("cypher_query", {
      cypher: input.cypher,
      params: input.params ?? {},
    });
    return {
      columns: wire.columns ?? [],
      rows: wire.rows ?? [],
      stats: wire.stats,
    };
  }
}
