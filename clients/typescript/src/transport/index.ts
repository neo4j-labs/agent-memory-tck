/**
 * Transport abstraction layer.
 *
 * The Transport interface decouples the memory client from the underlying
 * communication mechanism. This enables the same client API to work with:
 *   - Cypherlite Cloud HTTP API (primary)
 *   - Direct Neo4j connection (for local development)
 *   - MCP protocol
 */

export interface Transport {
  /** Send a request to the backend and return the parsed response. */
  request<T>(method: string, params: Record<string, unknown>): Promise<T>;

  /** Establish the connection. */
  connect(): Promise<void>;

  /** Close the connection and release resources. */
  close(): Promise<void>;
}

export { HttpTransport } from "./http.js";
