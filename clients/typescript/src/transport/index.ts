/**
 * Transport abstraction layer.
 *
 * RestTransport speaks the hosted REST API at https://memory.neo4jlabs.com/v1
 * and is the transport every application should use. BridgeTransport speaks
 * the TCK bridge protocol and is exposed via the `./testing` subpath for
 * conformance testing only.
 */

export interface Transport {
  /** Send a request to the backend and return the parsed response. */
  request<T>(method: string, params: Record<string, unknown>): Promise<T>;

  /** Establish the connection. */
  connect(): Promise<void>;

  /** Close the connection and release resources. */
  close(): Promise<void>;
}

export { RestTransport } from "./rest.js";
export type { RestTransportOptions, TokenProvider } from "./rest.js";
